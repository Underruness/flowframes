package com.flowframes.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.R

/*
 * Capri's palette, taken from the character this edition is named after: a black silhouette, a neon
 * green eye mask, electric blue ringed eyes, a white zigzag mouth, on a hot pink / crimson field
 * sparkling with white dust, with violet rim light on the edges.
 */
/** Hot pink / crimson: the background and everything the eye should catch first. */
val CapriHot = Color(0xFFFF0A54)

/** Deeper crimson: the shadow side of [CapriHot], for shards and gradients. */
val CapriDeep = Color(0xFFB70E42)

/** Neon green: the mask, the live state, everything that is "on". */
val CapriGreen = Color(0xFF2BE85C)

/** Dark green, for the fill behind [CapriGreen]. */
val CapriGreenDeep = Color(0xFF0C3D1D)

/** Electric blue: the eyes, and the information the app reports. */
val CapriBlue = Color(0xFF1A9DFF)

/** Lighter blue, for the highlight inside the eyes. */
val CapriBlueLight = Color(0xFF65D7FF)

/** Violet rim light. */
val CapriPurple = Color(0xFF8755FF)

/** The black the whole character is drawn in. */
val CapriBlack = Color(0xFF08060C)

/** Panels and cards, a hair above the background. */
val CapriPanel = Color(0xFF150D1B)

/** Raised panels and controls, with a violet tint. */
val CapriPanelHigh = Color(0xFF241528)

/** Off white: text and sparkles. */
val CapriWhite = Color(0xFFFFF4FA)

/** Muted text. */
val CapriDim = Color(0xFFD3B7C7)

/** Success / finished. */
val CapriMint = CapriGreen

/** Warning. */
val CapriWarn = Color(0xFFFFC24B)

/** Failure and cancellation. */
val CapriDanger = Color(0xFFFF3B6B)

/** Ink used on the bright surfaces (masks, call to action). */
val CapriInk = Color(0xFF07050A)

/** Kept for the historical names a few call sites still use. */
val CapriNight = CapriBlack
val CapriSurface = CapriPanel
val CapriMagenta = CapriHot

/**
 * Chakra Petch (OFL, bundled in res/font): squared, slightly condensed and techy — the closest a
 * free face gets to the display lettering of the reference art. Italic bold is used for anything
 * that acts as a label or a shout.
 */
val CapriFont = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
    Font(R.font.chakra_petch_bold_italic, FontWeight.Bold, FontStyle.Italic)
)

/** The shouting face: bold italic only. */
val CapriDisplay = FontFamily(
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
    Font(R.font.chakra_petch_bold_italic, FontWeight.Bold, FontStyle.Italic)
)

private val CapriDark = darkColorScheme(
    primary = CapriGreen,
    onPrimary = CapriInk,
    primaryContainer = CapriGreenDeep,
    onPrimaryContainer = Color(0xFFB8FFCB),
    secondary = CapriHot,
    onSecondary = Color(0xFFFFF0F5),
    secondaryContainer = Color(0xFF4A0A22),
    onSecondaryContainer = Color(0xFFFFD3E2),
    tertiary = CapriBlue,
    onTertiary = Color(0xFF04121F),
    tertiaryContainer = Color(0xFF0A2E4A),
    onTertiaryContainer = CapriBlueLight,
    background = CapriBlack,
    onBackground = CapriWhite,
    surface = CapriPanel,
    onSurface = CapriWhite,
    surfaceVariant = CapriPanelHigh,
    onSurfaceVariant = CapriDim,
    surfaceContainer = CapriPanel,
    surfaceContainerHigh = CapriPanelHigh,
    surfaceContainerLow = Color(0xFF100A15),
    outline = Color(0xFF7A2E5C),
    outlineVariant = Color(0xFF3A1B2C),
    error = CapriDanger,
    onError = Color(0xFF2A0410),
    scrim = Color(0xCC050308)
)

/** Sharp, cut corners: nothing here is a soft Material You pill. */
private val CapriShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val base = Typography()

private fun TextStyle.display(size: Int) = copy(
    fontFamily = CapriDisplay,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.5.sp,
    fontSize = size.sp
)

private fun TextStyle.body() = copy(fontFamily = CapriFont)

private val CapriTypography = Typography(
    displayLarge = base.displayLarge.display(56),
    displayMedium = base.displayMedium.display(44),
    displaySmall = base.displaySmall.display(36),
    headlineLarge = base.headlineLarge.display(30),
    headlineMedium = base.headlineMedium.display(26),
    headlineSmall = base.headlineSmall.display(22),
    titleLarge = base.titleLarge.copy(fontFamily = CapriDisplay, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    titleMedium = base.titleMedium.copy(fontFamily = CapriFont, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = CapriFont, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.body(),
    bodyMedium = base.bodyMedium.body(),
    bodySmall = base.bodySmall.body(),
    labelLarge = base.labelLarge.copy(fontFamily = CapriFont, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    labelMedium = base.labelMedium.copy(fontFamily = CapriFont, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = base.labelSmall.copy(fontFamily = CapriFont, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
)

/**
 * Capri's theme: always the character's own colours, never the wallpaper's. The carmine field, the
 * neon green mask and the electric blue eyes are the identity, so Material You's dynamic palette is
 * deliberately not used here (the historical FlowFrames edition keeps it).
 */
@Composable
fun CapriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CapriDark,
        shapes = CapriShapes,
        typography = CapriTypography,
        content = content
    )
}
