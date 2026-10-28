package com.flowframes.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Capri's own colours, used when the phone has no Material You palette (before Android 12) and for
// the status colours of the queue.
val CapriYellow = Color(0xFFF3E24B)
val CapriCyan = Color(0xFF4BE1F5)
val CapriMagenta = Color(0xFFF54B94)
val CapriNight = Color(0xFF0B0A14)
val CapriSurface = Color(0xFF14121F)
val CapriMint = Color(0xFF63E6BE)
val CapriWarn = Color(0xFFFFB960)
val CapriDanger = Color(0xFFFF6B81)

/** Ink used on the bright gradient surfaces (logo, call to action). */
val CapriInk = Color(0xFF120F1E)

private val CapriDark = darkColorScheme(
    primary = CapriYellow,
    onPrimary = Color(0xFF1B1806),
    primaryContainer = Color(0xFF3A3405),
    onPrimaryContainer = Color(0xFFFFF6C2),
    secondary = CapriCyan,
    onSecondary = Color(0xFF04232B),
    secondaryContainer = Color(0xFF0B3A45),
    onSecondaryContainer = Color(0xFFCCF5FF),
    tertiary = CapriMagenta,
    onTertiary = Color(0xFF34081D),
    background = CapriNight,
    onBackground = Color(0xFFF2EFFA),
    surface = CapriSurface,
    onSurface = Color(0xFFF2EFFA),
    surfaceVariant = Color(0xFF262237),
    onSurfaceVariant = Color(0xFFC8C2DE),
    outline = Color(0xFF3E3854),
    error = CapriDanger,
    onError = Color(0xFF33060E)
)

private val CapriLight = lightColorScheme(
    primary = Color(0xFF6A5C00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFF2A8),
    onPrimaryContainer = Color(0xFF211C00),
    secondary = Color(0xFF00677A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB6EBF7),
    onSecondaryContainer = Color(0xFF001F27),
    tertiary = Color(0xFFA1205E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1725),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1725),
    surfaceVariant = Color(0xFFE6E0F0),
    onSurfaceVariant = Color(0xFF47415A),
    outline = Color(0xFF78718E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

/** Soft, generous corners: the Material You look. */
private val CapriShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val CapriTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    )
}

/**
 * Material You when the phone has it: on Android 12+ the palette comes from the wallpaper
 * (dynamic colour), and Capri's own yellow / cyan / magenta take over before that.
 */
@Composable
fun CapriTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) CapriDark else CapriLight
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = CapriShapes,
        typography = CapriTypography,
        content = content
    )
}
