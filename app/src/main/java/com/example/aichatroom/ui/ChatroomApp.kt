package com.example.aichatroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichatroom.domain.Message
import com.example.aichatroom.domain.Mode
import com.example.aichatroom.domain.Speaker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatroomApp(vm: ChatViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (settingsOpen) "Settings" else "AI Chatroom", fontWeight = FontWeight.Bold)
                        if (!settingsOpen) Text(
                            "Two minds. One conversation.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (settingsOpen) IconButton(onClick = { settingsOpen = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                    }
                },
                actions = {
                    if (!settingsOpen) {
                        IconButton(
                            onClick = { confirmClear = true },
                            enabled = state.messages.isNotEmpty() || state.busy
                        ) { Icon(Icons.Default.DeleteOutline, "Clear chat") }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.notice?.let { notice ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, null, Modifier.size(16.dp))
                        Text(notice, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (settingsOpen) SettingsScreen(state, vm) else ChatScreen(state, vm) { settingsOpen = true }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Start a fresh conversation?") },
        text = { Text("This deletes the local chat and stops any reply in progress. Your API keys and preferences are kept.") },
        confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("Clear chat") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ChatScreen(state: ChatState, vm: ChatViewModel, onSettings: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val preferences = state.preferences
    LaunchedEffect(state.messages.size, state.thinking) {
        val count = state.messages.size + if (state.thinking != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize()) {
        RoomControls(state, vm, onSettings)
        if (state.messages.isEmpty() && !state.busy) {
            WelcomePanel(onPrompt = { draft = "Explain Kotlin coroutines, then challenge each other's explanation." })
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.messages, key = { it.id }) { MessageBubble(it, onSettings) }
                if (state.thinking != null) item(key = "thinking") {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(state.thinking)
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("${state.thinking.label} is thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.busy && state.thinking != null) TextButton(onClick = vm::stop, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Stop replies") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message the room…") },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp)
            )
            FilledIconButton(
                onClick = { if (vm.send(draft)) draft = "" },
                enabled = draft.isNotBlank() && !state.busy && (preferences.nvidiaEnabled || preferences.geminiEnabled),
                modifier = Modifier.size(52.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send message") }
        }
    }
}

@Composable
private fun RoomControls(state: ChatState, vm: ChatViewModel, onSettings: () -> Unit) {
    val preferences = state.preferences
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Conversation style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(if (preferences.mode == Mode.FRIENDLY) "Light banter and quick perspectives" else "Detailed answers and careful reasoning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = preferences.mode == Mode.FRIENDLY, enabled = !state.busy, onClick = { vm.updatePreferences(preferences.copy(mode = Mode.FRIENDLY)) }, label = { Text("Friendly") })
            FilterChip(selected = preferences.mode == Mode.EXPERT, enabled = !state.busy, onClick = { vm.updatePreferences(preferences.copy(mode = Mode.EXPERT)) }, label = { Text("Expert") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParticipantChip(Speaker.NVIDIA, preferences.nvidiaEnabled, !state.busy) { vm.updatePreferences(preferences.copy(nvidiaEnabled = !preferences.nvidiaEnabled)) }
            ParticipantChip(Speaker.GEMINI, preferences.geminiEnabled, !state.busy) { vm.updatePreferences(preferences.copy(geminiEnabled = !preferences.geminiEnabled)) }
        }
        if ((preferences.nvidiaEnabled && !state.nvidiaKeySaved) || (preferences.geminiEnabled && !state.geminiKeySaved)) {
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Connect a provider to start chatting →") }
        }
    }
}

@Composable
private fun WelcomePanel(onPrompt: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Avatar(Speaker.NVIDIA); Avatar(Speaker.GEMINI) }
        Spacer(Modifier.height(22.dp))
        Text("Welcome to the room", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Ask one question and get two distinct perspectives that can build on, challenge, and sharpen each other.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        OutlinedButton(onClick = onPrompt) { Text("Try a technical discussion") }
        Spacer(Modifier.height(10.dp))
        Text("Tap a participant above to mute or unmute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ParticipantChip(speaker: Speaker, active: Boolean, enabled: Boolean, click: () -> Unit) {
    FilterChip(selected = active, enabled = enabled, onClick = click, leadingIcon = { Box(Modifier.size(9.dp).background(speakerAccent(speaker), CircleShape)) }, label = { Text("${speaker.label} · ${if (active) "on" else "muted"}") })
}

@Composable
private fun Avatar(speaker: Speaker) {
    Box(Modifier.size(38.dp).background(speakerAccent(speaker), CircleShape), contentAlignment = Alignment.Center) {
        Text(when (speaker) { Speaker.NVIDIA -> "N"; Speaker.CHATGPT -> "C"; Speaker.GEMINI -> "G"; Speaker.USER -> "U" }, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageBubble(message: Message, onSettings: () -> Unit) {
    val user = message.speaker == Speaker.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (!user) { Avatar(message.speaker); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.widthIn(max = 520.dp).weight(1f, fill = false), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 5.dp)) {
                Text(message.speaker.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("· ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), color = when { message.error -> MaterialTheme.colorScheme.errorContainer; user -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant }) {
                Column(Modifier.padding(14.dp)) {
                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    if (message.error) TextButton(onClick = onSettings) { Text("Open Settings") }
                }
            }
        }
        if (user) { Spacer(Modifier.width(8.dp)); Avatar(Speaker.USER) }
    }
}

@Composable
private fun SettingsScreen(state: ChatState, vm: ChatViewModel) {
    var nvidiaKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var nvidiaModel by remember(state.preferences.nvidiaModel) { mutableStateOf(state.preferences.nvidiaModel) }
    var fallbackModel by remember(state.preferences.nvidiaFallbackModel) { mutableStateOf(state.preferences.nvidiaFallbackModel) }
    var geminiModel by remember(state.preferences.geminiModel) { mutableStateOf(state.preferences.geminiModel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Make the room yours", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Connect your providers, tune response style, and keep control of your conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f)) { Text("Quick replies", style = MaterialTheme.typography.titleMedium); Text("Shorter answers with less thinking time.", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = state.preferences.quickReplies, enabled = !state.busy, onCheckedChange = { vm.updatePreferences(state.preferences.copy(quickReplies = it)) })
            }
        }
        KeyCard("NVIDIA", state.nvidiaKeySaved, nvidiaKey, { nvidiaKey = it }, !state.busy) { vm.removeKey(Speaker.NVIDIA) }
        OutlinedTextField(nvidiaModel, { nvidiaModel = it }, label = { Text("NVIDIA model ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fallbackModel, { fallbackModel = it }, label = { Text("NVIDIA fallback model ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("On NVIDIA 404/410, retry once with this fallback model. Choose an active hosted model if the default is unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyCard("Gemini", state.geminiKeySaved, geminiKey, { geminiKey = it }, !state.busy) { vm.removeKey(Speaker.GEMINI) }
        OutlinedTextField(geminiModel, { geminiModel = it }, label = { Text("Gemini model ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveKeys(nvidiaKey, geminiKey, nvidiaModel, geminiModel, fallbackModel); nvidiaKey = ""; geminiKey = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
        Text("Leave a key field blank to keep its saved value. Keys are encrypted on this device. Full chat history is sent to enabled providers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyCard(name: String, saved: Boolean, value: String, change: (String) -> Unit, enabled: Boolean, remove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$name API key", style = MaterialTheme.typography.titleMedium)
            Text(if (saved) "Key saved securely" else "Not connected yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value, change, enabled = enabled, modifier = Modifier.fillMaxWidth(), placeholder = { Text(if (saved) "Paste a replacement key" else "Paste your API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            if (saved) TextButton(onClick = remove, enabled = enabled) { Text("Remove $name key") }
        }
    }
}
