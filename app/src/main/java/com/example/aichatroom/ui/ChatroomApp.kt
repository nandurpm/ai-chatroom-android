package com.example.aichatroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichatroom.domain.*
import com.example.aichatroom.network.PendingGithubWrite
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/** Chat and settings share a single state owner so navigation cannot start duplicate turns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatroomApp(vm: ChatViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<PendingGithubWrite?>(null) }
    BackHandler(settings) { settings = false }
    Scaffold(topBar = { TopAppBar(title = { Text(if (settings) "Settings" else "AI Chatroom") },
        navigationIcon = { if (settings) TextButton(onClick = { settings = false }) { Text("Back") } },
        actions = {
            if (!settings) { TextButton(onClick = { clear = true }) { Text("Clear") }; TextButton(onClick = { settings = true }) { Text("Settings") } }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.notice?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp)) }
            if (settings) SettingsScreen(state, vm) else {
                val list = rememberLazyListState()
                var input by rememberSaveable { mutableStateOf("") }
                var voiceMode by rememberSaveable { mutableStateOf(false) }
                var voicePreview by rememberSaveable { mutableStateOf(false) }
                var feedbackBaseline by remember { mutableIntStateOf(-1) }
                val haptics = LocalHapticFeedback.current
                val view = LocalView.current
                LaunchedEffect(state.messages.size) {
                    if (feedbackBaseline == -1) feedbackBaseline = state.messages.size
                    else if (state.messages.size > feedbackBaseline) {
                        state.messages.lastOrNull()?.takeIf { it.speaker.id != AgentProfile.USER.id }?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            view.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN)
                        }
                        feedbackBaseline = state.messages.size
                    }
                    if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.messages.isEmpty()) item { Text("Your room, your agents. Add providers in Settings, then start a conversation.", style = MaterialTheme.typography.titleMedium) }
                    items(state.messages, key = { it.id }) { message ->
                        AnimatedVisibility(visible = true, enter = fadeIn(tween(260)) + slideInVertically(tween(300, easing = FastOutSlowInEasing), initialOffsetY = { it / 3 })) { MessageBubble(message) }
                        state.proposals.find { it.messageId == message.id }?.let { proposal ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("GitHub write proposal · ${proposal.repository}", fontWeight = FontWeight.Bold)
                                    Text("${proposal.action.operation}: ${proposal.action.path.ifBlank { proposal.action.title }}")
                                    Row { TextButton(onClick = { confirmation = proposal }, enabled = !state.busy) { Text("Review write") }
                                        TextButton(onClick = { vm.rejectWrite(proposal.messageId) }) { Text("Reject") } }
                                }
                            }
                        }
                    }
                }
                // Sequential calls still show each participant's own queued/responding state.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.agents.filter { it.id in state.statuses }.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Avatar(a)
                            if (state.statuses[a.id] == AgentStatus.RESPONDING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("${a.displayName}: ${state.statuses[a.id]?.name?.lowercase()}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (voiceMode || voicePreview) VoiceMessageComposer(voiceMode, { voiceMode = false; voicePreview = true }, { voiceMode = false; voicePreview = false }, { voicePreview = false; voiceMode = true })
                else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); voiceMode = true }) { Icon(Icons.Default.Mic, "Record voice message") }
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message the room") }, maxLines = 5)
                    if (state.busy) TextButton(onClick = vm::stop) { Text("Stop") }
                    else TextButton(onClick = { if (vm.send(input)) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); view.playSoundEffect(android.view.SoundEffectConstants.CLICK); input = "" } }, enabled = state.ready && input.isNotBlank()) { Text("Send") }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear chat?") }, text = { Text("This deletes local messages and pending proposals.") },
        confirmButton = { TextButton(onClick = { vm.clear(); clear = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
    confirmation?.let { proposal ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Confirm GitHub write") },
            text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("Agent: ${proposal.agentName}\nRepository: ${proposal.repository}\nTarget: default branch\nAction: ${proposal.action.operation}\nPath: ${proposal.action.path}\nTitle: ${proposal.action.title}")
                HorizontalDivider(Modifier.padding(vertical = 10.dp)); Text(proposal.action.body)
            } },
            confirmButton = { TextButton(onClick = { vm.confirmWrite(proposal.messageId); confirmation = null }, enabled = !state.busy && state.proposals.any { it == proposal }) { Text("Confirm this write") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}

/** Custom avatar colors never control body text contrast. */
@Composable
private fun Avatar(a: AgentProfile) {
    Box(Modifier.size(32.dp).background(a.color, CircleShape), contentAlignment = Alignment.Center) {
        Text(a.avatarLetter, color = avatarTextColor(a.color), fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun MessageBubble(message: Message) {
    val human = message.speaker.id == AgentProfile.USER.id
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (human) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(message.speaker); Text(message.speaker.displayName, style = MaterialTheme.typography.labelLarge)
        }
        Surface(Modifier.padding(top = 6.dp).widthIn(max = 680.dp), shape = MaterialTheme.shapes.medium,
            color = if (message.error) MaterialTheme.colorScheme.errorContainer else if (human) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(14.dp)) { if (message.error) Text(message.text) else MarkdownContent(message.text) }
        }
    }
}

@Composable
private fun VoiceMessageComposer(recording: Boolean, onStop: () -> Unit, onDiscard: () -> Unit, onStart: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voiceWaveform")
    val phase by transition.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "wavePhase")
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onDiscard) { Icon(Icons.Default.Close, "Discard voice message") }
            Column(Modifier.weight(1f)) {
                Text(if (recording) "Recording voice message" else "Voice message preview", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { VoiceWaveform(phase, recording); Text(if (recording) "Listening…" else "Ready to send", style = MaterialTheme.typography.bodySmall) }
            }
            FilledIconButton(onClick = if (recording) onStop else onStart, modifier = Modifier.size(44.dp)) { Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, if (recording) "Stop recording" else "Record again") }
        }
    }
}

@Composable
private fun VoiceWaveform(phase: Float, active: Boolean) {
    val waveformColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.width(112.dp).height(30.dp)) {
        val bars = 20
        val gap = size.width / bars
        repeat(bars) { index ->
            val wave = abs(sin((phase + index * 0.55f).toDouble())).toFloat()
            val height = size.height * (0.22f + wave * if (active) 0.7f else 0.38f)
            drawLine(waveformColor, androidx.compose.ui.geometry.Offset(index * gap + gap / 2, (size.height - height) / 2), androidx.compose.ui.geometry.Offset(index * gap + gap / 2, (size.height + height) / 2), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Agent selection, presets and credential editing are runtime data, including duplicate provider instances. */
@Composable
private fun SettingsScreen(state: ChatState, vm: ChatViewModel) {
    var editing by remember { mutableStateOf<AgentProfile?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Conversation", style = MaterialTheme.typography.titleLarge)
        Row { Mode.entries.forEach { mode -> FilterChip(state.preferences.mode == mode,
            { vm.updatePreferences(state.preferences.copy(mode = mode)) }, label = { Text(mode.name.lowercase()) }, enabled = !state.busy) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Quick replies", Modifier.weight(1f)); Switch(state.preferences.quickReplies,
            { vm.updatePreferences(state.preferences.copy(quickReplies = it)) }, enabled = !state.busy) }
        Text("Agents", style = MaterialTheme.typography.titleLarge)
        state.agents.filter { it.id != AgentProfile.USER.id && !it.archived }.forEach { a ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(a)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(a.displayName); Text(a.providerConfig.modelId, style = MaterialTheme.typography.bodySmall) }
                    Switch(a.enabled, { vm.saveAgent(a.copy(enabled = it)) }, enabled = !state.busy)
                    TextButton(onClick = { editing = a }, enabled = !state.busy) { Text("Edit") }
                }
            }
        }
        Button(onClick = { editing = AgentProfile(UUID.randomUUID().toString(), "New agent", Color(0xFF6750A4), "A", ProviderPresets.all.first().config) }, enabled = !state.busy) { Text("Add agent") }
        HorizontalDivider()
        Text("GitHub", style = MaterialTheme.typography.titleLarge)
        Text("Connect one repository using a fine-grained PAT. Repository reads may be shared with the enabled AI providers. Every proposed write requires your separate review and confirmation.")
        var repo by remember(state.githubRepo) { mutableStateOf(state.githubRepo) }
        // Secrets intentionally use remember, never rememberSaveable or Room.
        var pat by remember { mutableStateOf("") }
        OutlinedTextField(repo, { repo = it }, label = { Text("owner/repository") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pat, { pat = it }, label = { Text("GitHub PAT (blank keeps saved token)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row {
            Button(onClick = { vm.connectGithub(repo, pat); pat = "" }, enabled = !state.busy) { Text("Verify & connect") }
            TextButton(onClick = vm::disconnectGithub, enabled = !state.busy && state.githubRepo.isNotBlank()) { Text("Disconnect") }
        }
        if (state.githubRepo.isNotBlank()) Text("Connected repository: ${state.githubRepo}")
    }
    editing?.let { agent -> AgentEditor(agent, state.busy, onDismiss = { editing = null },
        onSave = { a, key, remove -> vm.saveAgent(a, key, remove); editing = null },
        onRemove = { vm.removeAgent(agent); editing = null }) }
}

/** All provider fields are editable; applying a preset never copies credentials from another agent. */
@Composable
private fun AgentEditor(original: AgentProfile, busy: Boolean, onDismiss: () -> Unit,
    onSave: (AgentProfile, String, Boolean) -> Unit, onRemove: () -> Unit) {
    var draft by remember(original.id) { mutableStateOf(original) }
    var key by remember { mutableStateOf("") }
    var removeKey by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf("%06X".format(original.color.toArgb() and 0xFFFFFF)) }
    var error by remember { mutableStateOf<String?>(null) }
    var terms by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf(original.providerConfig.maxTokens?.toString().orEmpty()) }
    var context by remember { mutableStateOf(original.providerConfig.contextTokens.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agent & provider") }, text = {
        Column(Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Provider preset")
            ProviderPresets.all.forEach { preset -> TextButton(onClick = {
                draft = draft.copy(providerConfig = preset.config); terms = preset.terms; key = ""
                tokens = preset.config.maxTokens?.toString().orEmpty(); context = preset.config.contextTokens.toString()
            }) { Text(preset.name) } }
            if (terms.isNotBlank()) Text(terms, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.displayName, { draft = draft.copy(displayName = it) }, label = { Text("Display name") })
            OutlinedTextField(draft.avatarLetter, { draft = draft.copy(avatarLetter = it) }, label = { Text("Avatar letter / emoji") })
            OutlinedTextField(color, { color = it.removePrefix("#") }, label = { Text("Avatar color RRGGBB") })
            Avatar(draft.copy(color = color.toLongOrNull(16)?.let { Color((it or 0xFF000000).toInt()) } ?: draft.color))
            OutlinedTextField(draft.providerConfig.baseUrl, { draft = draft.copy(providerConfig = draft.providerConfig.copy(baseUrl = it)) }, label = { Text("API base URL (include /v1 if needed)") })
            OutlinedTextField(draft.providerConfig.modelId, { draft = draft.copy(providerConfig = draft.providerConfig.copy(modelId = it)) }, label = { Text("Model ID") })
            Text("Request shape")
            RequestShape.entries.forEach { shape -> FilterChip(draft.providerConfig.shape == shape,
                { draft = draft.copy(providerConfig = draft.providerConfig.copy(shape = shape)) }, label = { Text(shape.name) }) }
            Text("Authentication")
            AuthStyle.entries.forEach { auth -> FilterChip(draft.providerConfig.authStyle == auth,
                { draft = draft.copy(providerConfig = draft.providerConfig.copy(authStyle = auth)) }, label = { Text(auth.name) }) }
            if (draft.providerConfig.authStyle == AuthStyle.CUSTOM) OutlinedTextField(draft.providerConfig.customHeader,
                { draft = draft.copy(providerConfig = draft.providerConfig.copy(customHeader = it)) }, label = { Text("Custom header name") })
            OutlinedTextField(key, { key = it }, label = { Text("API key (blank keeps saved key)") }, visualTransformation = PasswordVisualTransformation())
            Text("Changing the base URL clears the old key. Enter a replacement for the new endpoint.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Remove saved key", Modifier.weight(1f)); Checkbox(removeKey, { removeKey = it }) }
            OutlinedTextField(draft.providerConfig.fallbackModel, { draft = draft.copy(providerConfig = draft.providerConfig.copy(fallbackModel = it)) }, label = { Text("Fallback model (optional, compatible APIs)") })
            OutlinedTextField(tokens, { tokens = it }, label = { Text("Max output tokens (blank = mode default)") })
            OutlinedTextField(context, { context = it }, label = { Text("Context token budget") })
            Text("Compatible API thinking override")
            listOf(null, false, true).forEach { value -> FilterChip(draft.providerConfig.thinking == value,
                { draft = draft.copy(providerConfig = draft.providerConfig.copy(thinking = value)) }, label = { Text(value?.toString() ?: "Provider default") }) }
            OutlinedTextField(draft.providerConfig.thinkingLevel.orEmpty(), { draft = draft.copy(providerConfig = draft.providerConfig.copy(thinkingLevel = it.ifBlank { null })) }, label = { Text("Gemini thinking level (optional)") })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow keyless local HTTP", Modifier.weight(1f)); Switch(draft.providerConfig.allowLocalHttp,
                { draft = draft.copy(providerConfig = draft.providerConfig.copy(allowLocalHttp = it)) }) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onRemove, enabled = !busy) { Text("Remove agent (keep history)") }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            require(color.matches(Regex("[0-9a-fA-F]{6}"))) { "Enter a six-digit color." }
            require(draft.displayName.isNotBlank() && draft.displayName.length <= 60) { "Name must have 1–60 characters." }
            require(draft.avatarLetter.codePointCount(0, draft.avatarLetter.length) in 1..2) { "Use one letter or emoji." }
            require(tokens.isBlank() || tokens.toIntOrNull() != null) { "Output tokens must be a number." }
            val config = draft.providerConfig.copy(maxTokens = tokens.toIntOrNull(),
                contextTokens = context.toIntOrNull() ?: throw IllegalArgumentException("Context tokens must be a number."))
            config.validate()
            onSave(draft.copy(providerConfig = config, color = Color((color.toLong(16) or 0xFF000000).toInt())), key, removeKey)
        } catch (e: IllegalArgumentException) { error = e.message }
    }, enabled = !busy) { Text("Save agent") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
