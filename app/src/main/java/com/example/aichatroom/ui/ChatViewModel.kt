package com.example.aichatroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichatroom.data.*
import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatState(val messages: List<Message> = emptyList(), val preferences: Preferences = Preferences(),
    val busy: Boolean = false, val thinking: Speaker? = null, val notice: String? = null,
    val openAiKeySaved: Boolean = false, val geminiKeySaved: Boolean = false)
class ChatViewModel(private val repository: ChatRepository, private val settings: SettingsStore,
    private val factory: ProviderFactory) : ViewModel() {
    private val state = MutableStateFlow(ChatState(preferences = settings.read(),
        openAiKeySaved = settings.hasKey(Speaker.CHATGPT), geminiKeySaved = settings.hasKey(Speaker.GEMINI)))
    val ui: StateFlow<ChatState> = combine(repository.messages.catch {
        state.update { it.copy(notice = "Chat history could not be loaded.") }; emit(emptyList())
    }, state) { messages, current -> current.copy(messages = messages) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    private var turn: Job? = null
    private val engine = TurnEngine(repository)

    fun send(text: String): Boolean {
        val clean = text.trim()
        if (state.value.busy || clearing || clean.isEmpty()) return false
        val preferences = state.value.preferences
        if (!preferences.chatGptEnabled && !preferences.geminiEnabled) {
            state.update { it.copy(notice = "Unmute at least one AI before sending.") }; return false
        }
        state.update { it.copy(busy = true, notice = null) }
        turn = viewModelScope.launch {
            try {
                repository.append(Message(speaker = Speaker.USER, text = clean))
                engine.run(factory.create(preferences) { speaker ->
                    try { settings.readKey(speaker) } catch (_: Exception) {
                        throw UserFacingException("Unable to unlock saved API key. Replace it in Settings.")
                    }
                }, preferences.mode, { who -> state.update { it.copy(thinking = who) } }, ApiErrors::describe)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { state.update { it.copy(notice = "Could not save this turn. Please try again.") }
            } finally { state.update { it.copy(busy = false, thinking = null) } }
        }
        return true
    }
    // Cancels and joins the HTTP/DB work BEFORE clearing: no late reply can reappear.
    fun clear() {
        if (clearing) return
        clearing = true
        val active = turn
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                active?.cancelAndJoin()
                state.update { it.copy(busy = true) }
                repository.clear()
                state.update { it.copy(notice = null) }
            } catch (_: Exception) { state.update { it.copy(notice = "Could not clear the chat.") }
            } finally { clearing = false; state.update { it.copy(busy = false, thinking = null) } }
        }
    }
    private var clearing = false
    fun updatePreferences(value: Preferences) {
        if (state.value.busy || clearing) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { settings.save(value) }; state.update { it.copy(preferences = value) }
            } catch (_: Exception) { state.update { it.copy(notice = "Could not save settings.") }
            } finally { state.update { it.copy(busy = false) } }
        }
    }
    // Blank input means keep existing key; use explicit Remove to delete it.
    fun saveKeys(openAi: String, gemini: String, openModel: String, geminiModel: String) {
        if (state.value.busy || clearing) return
        val modelPattern = Regex("[A-Za-z0-9._-]+")
        if (!modelPattern.matches(openModel.trim()) || !modelPattern.matches(geminiModel.trim())) {
            state.update { it.copy(notice = "Enter model IDs using letters, numbers, dots, underscores or hyphens.") }; return
        }
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val updated = state.value.preferences.copy(openAiModel = openModel.trim(), geminiModel = geminiModel.trim())
                withContext(Dispatchers.IO) {
                    if (openAi.isNotBlank()) settings.saveKey(Speaker.CHATGPT, openAi)
                    if (gemini.isNotBlank()) settings.saveKey(Speaker.GEMINI, gemini)
                    settings.save(updated)
                }
                state.update { it.copy(preferences = updated, notice = "Settings saved. Send a message to test your keys.") }
            } catch (_: Exception) { state.update { it.copy(notice = "Could not save all settings. Please retry.") }
            } finally { refreshKeys(); state.update { it.copy(busy = false) } }
        }
    }
    fun removeKey(speaker: Speaker) {
        if (state.value.busy || clearing) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { settings.saveKey(speaker, "") }
            } catch (_: Exception) { state.update { it.copy(notice = "Could not remove key.") }
            } finally { refreshKeys(); state.update { it.copy(busy = false) } }
        }
    }
    private fun refreshKeys() { state.update { it.copy(openAiKeySaved = settings.hasKey(Speaker.CHATGPT),
        geminiKeySaved = settings.hasKey(Speaker.GEMINI)) } }
}
