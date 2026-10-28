package com.flowframes.android.ui

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
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
 * Material You accents on OLED black.
 *
 * The wallpaper still decides the primary / secondary / tertiary colours on Android 12+, but the
 * neutrals are pinned: background, surfaces and every compartment are pure black, so the phone's
 * OLED panel draws nothing where there is nothing, and the accents carry the colour. The app is
 * dark whatever the system setting says; a light palette on black would be unreadable.
 */
@Composable
fun CapriTheme(content: @Composable () -> Unit) {
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        CapriDark
    }
    val scheme = base.copy(
        background = Color.Black,
        onBackground = Color(0xFFF4F1FF),
        surface = Color.Black,
        onSurface = Color(0xFFF4F1FF),
        surfaceVariant = Color(0xFF0B0B0B),
        onSurfaceVariant = Color(0xFFB9B3C9),
        outline = Color(0xFF3A3346),
        outlineVariant = Color(0xFF232030)
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = CapriShapes,
        typography = CapriTypography,
        content = content
    )
}
