package com.rowlandrestorations.klinelogger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors with a meaning beyond Material's roles: gauge states and link status. */
@Immutable
data class StatusColors(
    val ok: Color = Ok,
    val warning: Color = Warning,
    val critical: Color = Critical,
    val onStatus: Color = OnStatus,
    val track: Color = Navy700,
    val faint: Color = InkFaint,
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

// Dark-first and always dark: the app is read on the water, often in direct sun, where a
// dark background with very bright figures holds contrast better than a light page.
private val GaugeColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentDim,
    onPrimaryContainer = Ink,
    secondary = InkMuted,
    onSecondary = Navy950,
    secondaryContainer = Navy700,
    onSecondaryContainer = Ink,
    tertiary = Ok,
    onTertiary = OnStatus,
    background = Navy900,
    onBackground = Ink,
    surface = Navy900,
    onSurface = Ink,
    surfaceVariant = Navy800,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy850,
    surfaceContainer = Navy850,
    surfaceContainerHigh = Navy800,
    surfaceContainerHighest = Navy700,
    surfaceBright = Navy700,
    surfaceDim = Navy950,
    outline = Navy600,
    outlineVariant = Navy700,
    error = Critical,
    onError = OnStatus,
    errorContainer = Color(0xFF4A1416),
    onErrorContainer = Ink,
    inverseSurface = Ink,
    inverseOnSurface = Navy900,
    inversePrimary = AccentDim,
    scrim = Color.Black,
)

@Composable
fun KlineLoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GaugeColorScheme, typography = Typography) {
        CompositionLocalProvider(LocalStatusColors provides StatusColors(), content = content)
    }
}

object GaugeTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
