package com.flowframes.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF8B7CFF)
val AccentDim = Color(0xFF2A2550)
val Mint = Color(0xFF62D6C0)
val Warn = Color(0xFFFFB960)
val Danger = Color(0xFFFF6B6B)

private val FlowColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF14121F),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Mint,
    onSecondary = Color(0xFF07231D),
    background = Color(0xFF0E0D14),
    onBackground = Color(0xFFEDEBFF),
    surface = Color(0xFF141223),
    onSurface = Color(0xFFEDEBFF),
    surfaceVariant = Color(0xFF232135),
    onSurfaceVariant = Color(0xFFBDB8D6),
    outline = Color(0xFF3A3653),
    error = Danger,
    onError = Color(0xFF2A0A0A)
)

@Composable
fun FlowFramesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlowColors,
        typography = Typography(),
        content = content
    )
}
