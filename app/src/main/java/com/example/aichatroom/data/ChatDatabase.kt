package com.example.aichatroom.data

import androidx.room.*
import com.example.aichatroom.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "messages")
data class MessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val speaker: String, val text: String, val error: Boolean, val timestamp: Long) {
    fun domain() = Message(id, Speaker.valueOf(speaker), text, error, timestamp)
}
@Dao
interface ChatDao {
    @Query("SELECT * FROM messages ORDER BY id ASC") fun observe(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages ORDER BY id ASC") suspend fun all(): List<MessageEntity>
    @Insert suspend fun insert(message: MessageEntity)
    @Query("DELETE FROM messages") suspend fun clear()
}
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() { abstract fun chatDao(): ChatDao }
class ChatRepository(private val dao: ChatDao) : MessageStore {
    val messages = dao.observe().map { list -> list.map { it.domain() } }
    override suspend fun history() = dao.all().map { it.domain() }
    override suspend fun append(message: Message) {
        dao.insert(MessageEntity(speaker = message.speaker.name, text = message.text,
            error = message.error, timestamp = message.timestamp))
    }
    suspend fun clear() = dao.clear()
}
