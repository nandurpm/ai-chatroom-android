package com.example.aichatroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichatroom.data.*
import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A status belongs to an agent ID, so queued, responding and completed agents can be shown independently. */
enum class AgentStatus { QUEUED, RESPONDING, DONE }
data class ChatState(val messages: List<Message> = emptyList(), val agents: List<AgentProfile> = emptyList(),
    val preferences: Preferences = Preferences(), val busy: Boolean = false, val ready: Boolean = false,
    val statuses: Map<String, AgentStatus> = emptyMap(), val notice: String? = null,
    val githubRepo: String = "", val proposals: List<PendingGithubWrite> = emptyList())

/** Owns one cancellable turn. Profile edits and GitHub connection changes wait until it finishes. */
class ChatViewModel(private val repository: ChatRepository, private val settings: SettingsStore,
    private val factory: ProviderFactory) : ViewModel() {
    private val state = MutableStateFlow(ChatState(preferences = settings.read(), githubRepo = settings.githubRepo()))
    val ui = combine(repository.messages, repository.agents, state) { messages, agents, current ->
        current.copy(messages = messages, agents = agents.map { it.domain() })
    }.catch { emit(state.value.copy(notice = "Unable to load the chat database.")) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    private var turn: Job? = null
    private var clearing = false
    private val engine = TurnEngine(repository)
    private val confirmations = WriteConfirmations()
    private val github = GithubCapability { settings.readSecret("github_pat") }
    init {
        viewModelScope.launch {
            try {
                repository.initialize(settings.read(), !settings.agentsImported())
                withContext(Dispatchers.IO) { settings.markAgentsImported() }
                state.update { it.copy(ready = true) }
            } catch (_: Exception) { state.update { it.copy(notice = "Could not initialize agents. Reopen the app.") } }
        }
    }
    private fun available() = state.value.ready && !state.value.busy && !clearing
    fun send(text: String): Boolean {
        if (!available() || text.isBlank()) return false
        val agents = ui.value.agents.filter { it.enabled && !it.archived && it.id != AgentProfile.USER.id }
        if (agents.isEmpty()) { notice("Enable at least one agent in Settings."); return false }
        val preferences = state.value.preferences
        val connectedRepo = state.value.githubRepo
        state.update { it.copy(busy = true, notice = null, statuses = agents.associate { a -> a.id to AgentStatus.QUEUED }) }
        turn = viewModelScope.launch {
            try {
                val startId = repository.history().lastOrNull()?.id ?: 0
                repository.append(Message(speaker = AgentProfile.USER, text = text.trim()))
                val participants = factory.create(agents, preferences) { a ->
                    try { settings.readKey(a) } catch (_: Exception) { throw UserFacingException("Could not unlock the key. Replace it in Settings.") }
                }.map { if (connectedRepo.isBlank()) it else GithubParticipant(it, github, connectedRepo) }
                engine.run(participants, preferences.mode, { active ->
                    state.update { current -> current.copy(statuses = current.statuses.mapValues { (id, status) ->
                        when { id == active?.id -> AgentStatus.RESPONDING
                            status == AgentStatus.RESPONDING -> AgentStatus.DONE
                            else -> status }
                    }) }
                }, ApiErrors::describe)
                // Only fresh AI messages from this turn can propose writes. History cannot replay approvals.
                val proposals = repository.history().filter { it.id > startId && it.speaker.id != AgentProfile.USER.id && !it.error }
                    .mapNotNull { m -> GithubProtocol.parse(m.text)?.takeIf { it.writes && connectedRepo.isNotBlank() }
                        ?.let { PendingGithubWrite(m.id, m.speaker.displayName, connectedRepo, it) } }
                proposals.forEach(confirmations::offer)
                state.update { it.copy(proposals = it.proposals + proposals) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { notice(if (e is IllegalArgumentException) e.message ?: "Invalid settings." else ApiErrors.describe(e))
            } finally { state.update { it.copy(busy = false, statuses = emptyMap()) } }
        }
        return true
    }
    fun stop() { if (!clearing) turn?.cancel() }
    /** Join cancellation before clearing; a late HTTP reply must not repopulate deleted history. */
    fun clear() {
        if (clearing || (state.value.busy && state.value.statuses.isEmpty())) return
        clearing = true
        viewModelScope.launch {
            try { turn?.cancelAndJoin(); repository.clear(); confirmations.clear(); state.update { it.copy(proposals = emptyList()) }
            } catch (_: Exception) { notice("Could not clear chat.")
            } finally { clearing = false; state.update { it.copy(busy = false, statuses = emptyMap()) } }
        }
    }
    fun updatePreferences(value: Preferences) = edit {
        withContext(Dispatchers.IO) { settings.save(value) }; state.update { it.copy(preferences = value) }
    }
    /** Changing an endpoint clears the old key unless the user explicitly supplies a replacement. */
    fun saveAgent(agent: AgentProfile, key: String = "", removeKey: Boolean = false) = edit {
        agent.providerConfig.validate()
        val previous = ui.value.agents.find { it.id == agent.id }
        withContext(Dispatchers.IO) {
            if (removeKey || (previous != null && previous.providerConfig.baseUrl != agent.providerConfig.baseUrl)) settings.saveKey(agent, "")
            if (key.isNotBlank()) settings.saveKey(agent, key)
        }
        repository.saveAgent(agent)
        notice("Agent saved.")
    }
    fun removeAgent(agent: AgentProfile) = edit {
        repository.saveAgent(agent.copy(enabled = false, archived = true))
        withContext(Dispatchers.IO) { settings.saveKey(agent, "") }
    }
    fun connectGithub(repositoryName: String, key: String) = edit {
        val repo = repositoryName.trim()
        // Verify the draft secret before replacing a working saved connection.
        val draft = GithubCapability { if (key.isBlank()) settings.readSecret("github_pat") else key.trim() }
        val login = draft.verify(repo)
        withContext(Dispatchers.IO) {
            if (key.isNotBlank()) settings.saveSecret("github_pat", key)
            settings.saveGithubRepo(repo)
        }
        confirmations.clear(); state.update { it.copy(githubRepo = repo, proposals = emptyList(), notice = "GitHub connected as $login.") }
    }
    fun disconnectGithub() = edit {
        withContext(Dispatchers.IO) { settings.saveSecret("github_pat", ""); settings.saveGithubRepo("") }
        confirmations.clear(); state.update { it.copy(githubRepo = "", proposals = emptyList()) }
    }
    fun rejectWrite(id: Long) {
        confirmations.reject(id); state.update { it.copy(proposals = it.proposals.filterNot { p -> p.messageId == id }) }
    }
    /** Called only by the explicit confirmation dialog, never by model text or a provider callback. */
    fun confirmWrite(id: Long) = edit {
        state.update { it.copy(proposals = it.proposals.filterNot { p -> p.messageId == id }) }
        try {
            val result = github.confirm(id, state.value.githubRepo, confirmations)
            repository.append(Message(speaker = AgentProfile.USER, text = "GitHub write confirmed and completed:\n$result"))
            notice("GitHub action completed.")
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            // Never auto-retry writes: a timeout can mean GitHub accepted the action but the response was lost.
            notice("GitHub write did not return success. Check the repository before proposing it again; it may have completed.")
        }
    }
    private fun notice(message: String) { state.update { it.copy(notice = message) } }
    private fun edit(block: suspend () -> Unit) {
        if (!available()) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e
            } catch (e: Exception) { notice(if (e is IllegalArgumentException) e.message ?: "Invalid input." else ApiErrors.describe(e))
            } finally { state.update { it.copy(busy = false) } }
        }
    }
}
