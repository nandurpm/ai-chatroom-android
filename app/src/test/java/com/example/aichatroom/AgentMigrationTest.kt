package com.example.aichatroom

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aichatroom.data.*
import com.example.aichatroom.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens a real v1 SQLite file through Room v2, which checks the migrated schema itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentMigrationTest {
    @Test fun allHistoricalSpeakersAndRowsSurviveMigration() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration-test")
        context.openOrCreateDatabase("migration-test", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, speaker TEXT NOT NULL, text TEXT NOT NULL, error INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
            listOf("USER", "NVIDIA", "GEMINI", "CHATGPT").forEachIndexed { i, speaker ->
                old.execSQL("INSERT INTO messages VALUES (?, ?, ?, 0, 123)", arrayOf(i + 1, speaker, "history-$speaker"))
            }
            old.version = 1
        }
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, "migration-test")
            .addMigrations(ChatDatabase.MIGRATION_1_2).build()
        try {
            val dao = db.chatDao()
            assertEquals(listOf("USER", "NVIDIA", "GEMINI", "CHATGPT"), dao.all().map { it.agentId })
            assertEquals(4, dao.agents().size)
            val repository = ChatRepository(dao)
            val renamed = AgentProfile.NVIDIA.copy(displayName = "Renamed", enabled = false, archived = true)
            repository.saveAgent(renamed)
            assertEquals("Renamed", repository.history()[1].speaker.displayName)
            assertEquals("history-CHATGPT", repository.history().last().text)
            assertEquals("ChatGPT", repository.history().last().speaker.displayName)
        } finally { db.close(); context.deleteDatabase("migration-test") }
    }
    @Test fun userIsFixedAndNewAgentsRoundTripConfig() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        try {
            val repository = ChatRepository(db.chatDao())
            repository.initialize(Preferences(), false)
            val a = AgentProfile.NVIDIA.copy(id = "custom", displayName = "Custom", providerConfig = ProviderConfig(modelId = "other"))
            repository.saveAgent(a)
            assertEquals(a, db.chatDao().agents().first { it.id == "custom" }.domain())
            try { repository.saveAgent(AgentProfile.USER); fail("Fixed user") } catch (_: IllegalArgumentException) {}
        } finally { db.close() }
    }
}
