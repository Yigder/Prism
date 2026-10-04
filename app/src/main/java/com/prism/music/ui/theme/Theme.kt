package com.prism.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.prism.music.AppContainer
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.ThemeMode

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }
val LocalIsDark = staticCompositionLocalOf { true }

val PrismViolet = Color(0xFF7C5CFF)

val AccentSwatches = listOf(
    0xFF7C5CFF, 0xFFFF3B5C, 0xFFFF6B35, 0xFFFFB627, 0xFF2EC27E, 0xFF00B4D8,
    0xFF3A86FF, 0xFFE056FD, 0xFFFF5D8F, 0xFF9BE15D, 0xFFB08968, 0xFFE0E0E0,
).map { Color(it) }

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Builds a full Material 3 scheme from a single seed colour (tonal approximation). */
fun schemeFromSeed(seed: Color, dark: Boolean, pureBlack: Boolean): ColorScheme {
    val out = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), out)
    val h = out[0]
    val s = out[1].coerceIn(0.30f, 0.92f)
    val n = 0.10f
    return if (dark) {
        val bg = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.055f)
        darkColorScheme(
            primary = hsl(h, s, 0.76f),
            onPrimary = hsl(h, s, 0.14f),
            primaryContainer = hsl(h, s * 0.75f, 0.30f),
            onPrimaryContainer = hsl(h, s, 0.92f),
            inversePrimary = hsl(h, s, 0.42f),
            secondary = hsl(h, s * 0.35f, 0.76f),
            onSecondary = hsl(h, s * 0.35f, 0.16f),
            secondaryContainer = hsl(h, s * 0.30f, 0.26f),
            onSecondaryContainer = hsl(h, s * 0.35f, 0.92f),
            tertiary = hsl(h + 45f, s * 0.6f, 0.78f),
            onTertiary = hsl(h + 45f, s * 0.6f, 0.16f),
            tertiaryContainer = hsl(h + 45f, s * 0.5f, 0.28f),
            onTertiaryContainer = hsl(h + 45f, s * 0.6f, 0.92f),
            background = bg,
            onBackground = hsl(h, n, 0.93f),
            surface = bg,
            onSurface = hsl(h, n, 0.93f),
            surfaceVariant = hsl(h, n * 1.5f, 0.20f),
            onSurfaceVariant = hsl(h, n * 1.2f, 0.74f),
            surfaceTint = hsl(h, s, 0.76f),
            inverseSurface = hsl(h, n, 0.92f),
            inverseOnSurface = hsl(h, n, 0.12f),
            outline = hsl(h, n, 0.45f),
            outlineVariant = hsl(h, n, 0.26f),
            surfaceBright = hsl(h, n * 1.4f, if (pureBlack) 0.16f else 0.20f),
            surfaceDim = bg,
            surfaceContainerLowest = if (pureBlack) Color.Black else hsl(h, n * 1.4f, 0.04f),
            surfaceContainerLow = hsl(h, n * 1.4f, if (pureBlack) 0.05f else 0.085f),
            surfaceContainer = hsl(h, n * 1.4f, if (pureBlack) 0.07f else 0.105f),
            surfaceContainerHigh = hsl(h, n * 1.4f, if (pureBlack) 0.10f else 0.135f),
            surfaceContainerHighest = hsl(h, n * 1.4f, if (pureBlack) 0.13f else 0.165f),
        )
    } else {
        val bg = hsl(h, 0.30f, 0.985f)
        lightColorScheme(
            primary = hsl(h, s, 0.42f),
            onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.90f),
            onPrimaryContainer = hsl(h, s, 0.14f),
            inversePrimary = hsl(h, s, 0.78f),
            secondary = hsl(h, s * 0.30f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.40f, 0.90f),
            onSecondaryContainer = hsl(h, s * 0.35f, 0.14f),
            tertiary = hsl(h + 45f, s * 0.6f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(h + 45f, s * 0.6f, 0.90f),
            onTertiaryContainer = hsl(h + 45f, s * 0.6f, 0.14f),
            background = bg,
            onBackground = hsl(h, n, 0.10f),
            surface = bg,
            onSurface = hsl(h, n, 0.10f),
            surfaceVariant = hsl(h, 0.18f, 0.91f),
            onSurfaceVariant = hsl(h, n, 0.34f),
            surfaceTint = hsl(h, s, 0.42f),
            inverseSurface = hsl(h, n, 0.18f),
            inverseOnSurface = hsl(h, n, 0.95f),
            outline = hsl(h, n, 0.52f),
            outlineVariant = hsl(h, n, 0.82f),
            surfaceBright = bg,
            surfaceDim = hsl(h, 0.15f, 0.88f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, 0.30f, 0.965f),
            surfaceContainer = hsl(h, 0.28f, 0.95f),
            surfaceContainerHigh = hsl(h, 0.25f, 0.93f),
            surfaceContainerHighest = hsl(h, 0.22f, 0.905f),
        )
    }
}

private val base = Typography()
val PrismTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.03).em),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.03).em),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

@Composable
fun isAppDark(settings: AppSettings = LocalAppSettings.current): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PrismTheme(settings: AppSettings, artworkColor: Color?, content: @Composable () -> Unit) {
    val dark = isAppDark(settings)
    val context = LocalContext.current
    val seedTarget = when (settings.colorSource) {
        ColorSource.ARTWORK -> artworkColor ?: Color(settings.accent)
        ColorSource.CUSTOM -> Color(settings.accent)
        ColorSource.DYNAMIC -> Color(settings.accent)
    }
    val seed by animateColorAsState(seedTarget, tween(900), label = "seed")
    val scheme = if (settings.colorSource == ColorSource.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        val d = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (dark && settings.pureBlack) d.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black) else d
    } else remember(seed, dark, settings.pureBlack) { schemeFromSeed(seed, dark, settings.pureBlack) }

    CompositionLocalProvider(LocalAppSettings provides settings, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = PrismTypography) {
            // Text and icons outside a Surface read LocalContentColor, which
            // otherwise defaults to black — unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
