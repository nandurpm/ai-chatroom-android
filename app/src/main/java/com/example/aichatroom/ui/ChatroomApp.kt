package com.example.aichatroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aichatroom.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatroomApp(vm: ChatViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text(if (settingsOpen) "Settings" else "AI Chatroom", fontWeight = FontWeight.Bold)
            if (!settingsOpen) Text("One room. Different perspectives.", style = MaterialTheme.typography.labelMedium)
        } }, navigationIcon = {
            if (settingsOpen) IconButton(onClick = { settingsOpen = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
            }
        }, actions = {
            if (!settingsOpen) {
                IconButton(onClick = { confirmClear = true }, enabled = state.messages.isNotEmpty() || state.busy) {
                    Icon(Icons.Default.DeleteOutline, "Clear chat")
                }
                IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "Settings") }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.notice?.let { notice ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(notice, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (settingsOpen) SettingsScreen(state, vm)
            else ChatScreen(state, vm, onSettings = { settingsOpen = true })
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Start a fresh conversation?") },
        text = { Text("This deletes the local chat and stops any reply in progress. Your API keys and preferences are kept.") },
        confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("Clear chat") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable private fun ChatScreen(state: ChatState, vm: ChatViewModel, onSettings: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val preferences = state.preferences
    LaunchedEffect(state.messages.size, state.thinking) {
        val count = state.messages.size + if (state.thinking != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = preferences.mode == Mode.FRIENDLY, enabled = !state.busy,
                onClick = { vm.updatePreferences(preferences.copy(mode = Mode.FRIENDLY)) }, label = { Text("Friendly Roast") })
            FilterChip(selected = preferences.mode == Mode.EXPERT, enabled = !state.busy,
                onClick = { vm.updatePreferences(preferences.copy(mode = Mode.EXPERT)) }, label = { Text("Expert Mode") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParticipantChip(Speaker.CHATGPT, preferences.chatGptEnabled, !state.busy) {
                vm.updatePreferences(preferences.copy(chatGptEnabled = !preferences.chatGptEnabled))
            }
            ParticipantChip(Speaker.GEMINI, preferences.geminiEnabled, !state.busy) {
                vm.updatePreferences(preferences.copy(geminiEnabled = !preferences.geminiEnabled))
            }
        }
        if ((preferences.chatGptEnabled && !state.openAiKeySaved) || (preferences.geminiEnabled && !state.geminiKeySaved)) {
            TextButton(onClick = onSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Add API keys in Settings →")
            }
        }
        if (state.messages.isEmpty() && !state.busy) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Avatar(Speaker.CHATGPT); Avatar(Speaker.GEMINI) }
                Spacer(Modifier.height(20.dp))
                Text("Meet your group chat.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Ask a question. Get two perspectives. Let the conversation unfold.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { draft = "Explain Kotlin coroutines, then challenge each other's explanation." }) {
                    Text("Try a technical discussion")
                }
                Text("Tap a participant above to mute or unmute.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.messages, key = { it.id }) { MessageBubble(it, onSettings) }
                if (state.thinking != null) item(key = "thinking") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(state.thinking)
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("${state.thinking.label} is thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message the room…") }, maxLines = 5, shape = RoundedCornerShape(24.dp))
            FilledIconButton(onClick = { if (vm.send(draft)) draft = "" },
                enabled = draft.isNotBlank() && !state.busy && (preferences.chatGptEnabled || preferences.geminiEnabled),
                modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send message") }
        }
    }
}
@Composable private fun ParticipantChip(speaker: Speaker, active: Boolean, enabled: Boolean, click: () -> Unit) {
    FilterChip(selected = active, enabled = enabled, onClick = click,
        leadingIcon = { Box(Modifier.size(9.dp).background(speakerColor(speaker), CircleShape)) },
        label = { Text("${speaker.label} · ${if (active) "on" else "muted"}") })
}
private fun speakerColor(speaker: Speaker): Color = when (speaker) {
    Speaker.CHATGPT -> Color(0xFF087D59); Speaker.GEMINI -> Color(0xFF315FC6); Speaker.USER -> Color(0xFF6750A4)
}
@Composable private fun Avatar(speaker: Speaker) {
    Box(Modifier.size(36.dp).background(speakerColor(speaker), CircleShape), contentAlignment = Alignment.Center) {
        Text(when (speaker) { Speaker.CHATGPT -> "C"; Speaker.GEMINI -> "G"; Speaker.USER -> "U" },
            color = Color.White, fontWeight = FontWeight.Bold)
    }
}
@Composable private fun MessageBubble(message: Message, onSettings: () -> Unit) {
    val user = message.speaker == Speaker.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (!user) { Avatar(message.speaker); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.widthIn(max = 520.dp).weight(1f, fill = false),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Text(message.speaker.label + " · " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 5.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = when {
                message.error -> MaterialTheme.colorScheme.errorContainer
                user -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }) {
                Column(Modifier.padding(14.dp)) {
                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    if (message.error) TextButton(onClick = onSettings) { Text("Open Settings") }
                }
            }
        }
        if (user) { Spacer(Modifier.width(8.dp)); Avatar(Speaker.USER) }
    }
}

@Composable private fun SettingsScreen(state: ChatState, vm: ChatViewModel) {
    // Deliberately remember, NOT rememberSaveable: keys must never enter saved-state bundles.
    var openKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var openModel by remember(state.preferences.openAiModel) { mutableStateOf(state.preferences.openAiModel) }
    var geminiModel by remember(state.preferences.geminiModel) { mutableStateOf(state.preferences.geminiModel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your keys. Your conversation.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Keys are encrypted on this device. Messages are sent to each enabled provider with the full chat history. API usage may incur charges.")
        KeyCard("OpenAI", state.openAiKeySaved, openKey, { openKey = it }, !state.busy,
            { vm.removeKey(Speaker.CHATGPT) })
        OutlinedTextField(openModel, { openModel = it }, label = { Text("OpenAI model ID") },
            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        KeyCard("Gemini", state.geminiKeySaved, geminiKey, { geminiKey = it }, !state.busy,
            { vm.removeKey(Speaker.GEMINI) })
        OutlinedTextField(geminiModel, { geminiModel = it }, label = { Text("Gemini model ID") },
            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            vm.saveKeys(openKey, geminiKey, openModel, geminiModel)
            openKey = ""; geminiKey = ""
        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
        Text("Leave a key field blank to keep its saved value. Use Remove to delete a key. Model IDs can be changed if availability changes.",
            style = MaterialTheme.typography.bodySmall)
        Text("Chat history stays on this device until you clear it or uninstall. This app is an independent API client.",
            style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun KeyCard(name: String, saved: Boolean, value: String, change: (String) -> Unit,
    enabled: Boolean, remove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$name API key", style = MaterialTheme.typography.titleMedium)
            Text(if (saved) "Key saved securely" else "No key saved", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, change, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (saved) "Paste a replacement key" else "Paste your API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            if (saved) TextButton(onClick = remove, enabled = enabled) { Text("Remove $name key") }
        }
    }
}
