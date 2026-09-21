package com.example.aichatroom.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aichatroom.domain.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.combine

/** JSON stores nonsecret provider options; ARGB is portable across Compose versions. */
@Entity(tableName = "agents")
data class AgentEntity(@PrimaryKey val id: String, val displayName: String, val colorArgb: Int,
    val avatarLetter: String, val providerJson: String, val enabled: Boolean, val archived: Boolean) {
    fun domain() = AgentProfile(id, displayName, Color(colorArgb), avatarLetter,
        Gson().fromJson(providerJson, ProviderConfig::class.java), enabled, archived)
    companion object {
        fun from(a: AgentProfile) = AgentEntity(a.id, a.displayName, a.color.toArgb(), a.avatarLetter,
            Gson().toJson(a.providerConfig), a.enabled, a.archived)
    }
}
/** An agent's stable ID survives renames, recoloring and removal from the active room. */
@Entity(tableName = "messages")
data class MessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String, val text: String, val error: Boolean, val timestamp: Long) {
    fun domain(agents: List<AgentProfile>) = Message(id,
        agents.find { it.id == agentId } ?: AgentProfile.valueOf(agentId), text, error, timestamp)
}
@Dao
interface ChatDao {
    @Query("SELECT * FROM messages ORDER BY id ASC") fun observe(): kotlinx.coroutines.flow.Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages ORDER BY id ASC") suspend fun all(): List<MessageEntity>
    @Insert suspend fun insert(message: MessageEntity)
    @Query("DELETE FROM messages") suspend fun clear()
    @Query("SELECT * FROM agents ORDER BY rowid") fun observeAgents(): kotlinx.coroutines.flow.Flow<List<AgentEntity>>
    @Query("SELECT * FROM agents ORDER BY rowid") suspend fun agents(): List<AgentEntity>
    @Upsert suspend fun saveAgent(agent: AgentEntity)
}
/** Never use destructive migration: v1 speaker strings become identical stable IDs. */
@Database(entities = [MessageEntity::class, AgentEntity::class], version = 2, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agents (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, colorArgb INTEGER NOT NULL, avatarLetter TEXT NOT NULL, providerJson TEXT NOT NULL, enabled INTEGER NOT NULL, archived INTEGER NOT NULL)")
                for (profile in listOf(AgentProfile.USER, AgentProfile.NVIDIA, AgentProfile.GEMINI, AgentProfile.CHATGPT)) {
                    val a = AgentEntity.from(profile)
                    db.execSQL("INSERT OR IGNORE INTO agents VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(a.id, a.displayName, a.colorArgb, a.avatarLetter, a.providerJson, if (a.enabled) 1 else 0, if (a.archived) 1 else 0))
                }
                // A table rebuild also works on older Android SQLite versions without RENAME COLUMN.
                db.execSQL("CREATE TABLE messages_v2 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, agentId TEXT NOT NULL, text TEXT NOT NULL, error INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("INSERT INTO messages_v2 SELECT id, speaker, text, error, timestamp FROM messages")
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_v2 RENAME TO messages")
            }
        }
    }
}
/** Resolves identities at read time so all history immediately reflects edits to an agent. */
class ChatRepository(private val dao: ChatDao) : MessageStore {
    val agents = dao.observeAgents()
    val messages = combine(dao.observe(), agents) { messages, rows ->
        val profiles = rows.map { it.domain() }; messages.map { it.domain(profiles) }
    }
    suspend fun initialize(p: Preferences, importLegacy: Boolean) {
        if (dao.agents().isEmpty() || importLegacy) {
            listOf(AgentProfile.USER, AgentProfile.NVIDIA.copy(enabled = p.nvidiaEnabled,
                providerConfig = AgentProfile.NVIDIA.providerConfig.copy(modelId = p.nvidiaModel, fallbackModel = p.nvidiaFallbackModel)),
                AgentProfile.GEMINI.copy(enabled = p.geminiEnabled,
                    providerConfig = AgentProfile.GEMINI.providerConfig.copy(modelId = p.geminiModel)), AgentProfile.CHATGPT)
                .forEach { dao.saveAgent(AgentEntity.from(it)) }
        }
    }
    suspend fun saveAgent(a: AgentProfile) {
        require(a.id != AgentProfile.USER.id) { "The human participant cannot be edited." }
        require(a.displayName.isNotBlank() && a.displayName.length <= 60 && a.avatarLetter.codePointCount(0, a.avatarLetter.length) in 1..2)
        a.providerConfig.validate()
        dao.saveAgent(AgentEntity.from(a))
    }
    override suspend fun history(): List<Message> {
        val profiles = dao.agents().map { it.domain() }; return dao.all().map { it.domain(profiles) }
    }
    override suspend fun append(message: Message) {
        dao.insert(MessageEntity(agentId = message.speaker.id, text = message.text, error = message.error, timestamp = message.timestamp))
    }
    suspend fun clear() = dao.clear()
}
