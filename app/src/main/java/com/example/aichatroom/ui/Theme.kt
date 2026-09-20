package com.example.aichatroom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable fun ChatroomTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFA9BBFF), secondary = Color(0xFF56DCAD),
        background = Color(0xFF10141C), surface = Color(0xFF10141C),
        surfaceVariant = Color(0xFF232B3A), primaryContainer = Color(0xFF303D6A))
    else lightColorScheme(primary = Color(0xFF3E56A5), secondary = Color(0xFF087D59),
        background = Color(0xFFF6F7FC), surface = Color(0xFFF6F7FC),
        surfaceVariant = Color(0xFFE8ECF5), primaryContainer = Color(0xFFDEE5FF))
    MaterialTheme(colorScheme = colors, content = content)
}
