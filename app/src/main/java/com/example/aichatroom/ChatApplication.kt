package com.example.aichatroom

import android.app.Application
import androidx.room.Room
import com.example.aichatroom.data.*
import com.example.aichatroom.network.ProviderFactory

class ChatApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ChatDatabase::class.java, "chat.db").build() }
    val repository by lazy { ChatRepository(database.chatDao()) }
    val settings by lazy { SettingsStore(this) }
    val providers by lazy { ProviderFactory() }
}
