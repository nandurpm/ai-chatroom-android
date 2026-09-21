package com.example.aichatroom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape

private val Ink = Color(0xFF17202E)
private val MutedInk = Color(0xFF5F6B7A)
private val Canvas = Color(0xFFF7F8FC)
private val Lavender = Color(0xFF5C5BD6)
private val LavenderSoft = Color(0xFFE9E8FF)
private val Teal = Color(0xFF087D70)
private val TealSoft = Color(0xFFD8F3EC)
private val Night = Color(0xFF0E121A)
private val NightSurface = Color(0xFF171D28)
private val NightElevated = Color(0xFF202938)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = bodyLarge.lineHeight * 1.12f)
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ChatroomTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFBDBBFF),
        onPrimary = Color(0xFF29276B),
        primaryContainer = Color(0xFF3E3C87),
        onPrimaryContainer = Color(0xFFE9E8FF),
        secondary = Color(0xFF7CDEC8),
        onSecondary = Color(0xFF00382F),
        secondaryContainer = Color(0xFF175348),
        onSecondaryContainer = Color(0xFFB5F1E2),
        background = Night,
        onBackground = Color(0xFFE5E8F0),
        surface = Night,
        onSurface = Color(0xFFE5E8F0),
        surfaceVariant = NightElevated,
        onSurfaceVariant = Color(0xFFC2C8D4),
        outline = Color(0xFF4D586A),
        errorContainer = Color(0xFF5B252A)
    ) else lightColorScheme(
        primary = Lavender,
        onPrimary = Color.White,
        primaryContainer = LavenderSoft,
        onPrimaryContainer = Color(0xFF25235E),
        secondary = Teal,
        onSecondary = Color.White,
        secondaryContainer = TealSoft,
        onSecondaryContainer = Color(0xFF003C32),
        background = Canvas,
        onBackground = Ink,
        surface = Canvas,
        onSurface = Ink,
        surfaceVariant = Color(0xFFEEF0F6),
        onSurfaceVariant = MutedInk,
        outline = Color(0xFFD0D5E0),
        errorContainer = Color(0xFFFFDAD9)
    )

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

fun speakerAccent(speaker: com.example.aichatroom.domain.Speaker): Color = when (speaker) {
    com.example.aichatroom.domain.Speaker.NVIDIA -> Color(0xFF0A8F78)
    com.example.aichatroom.domain.Speaker.CHATGPT -> Color(0xFF0A8F78)
    com.example.aichatroom.domain.Speaker.GEMINI -> Color(0xFF5577E8)
    com.example.aichatroom.domain.Speaker.USER -> Color(0xFF7568DE)
}
