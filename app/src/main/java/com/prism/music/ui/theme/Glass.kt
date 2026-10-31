package com.prism.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.GlassKind
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/** The backdrop that glass surfaces in the current subtree refract. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

internal enum class GlassPath { LIQUID, FROSTED, SOLID }

/**
 * How [GlassSurface] draws: Liquid Glass and Frosted need something behind to sample ([haze]); Liquid Glass also needs
 * Android 12's blur, without which it fell back to a thin tint and Clear glass showed everything behind it sharp, so
 * below it Liquid Glass is drawn frosted (1.7.9.2).
 */
internal fun glassPath(sdk: Int, liquid: Boolean, frosted: Boolean, haze: Boolean): GlassPath = when {
    !haze -> GlassPath.SOLID
    liquid && sdk >= 31 -> GlassPath.LIQUID
    liquid || frosted -> GlassPath.FROSTED
    else -> GlassPath.SOLID
}

/**
 * A surface that renders as refractive Liquid Glass when enabled in
 * Appearance settings, or as a soft translucent card otherwise.
 *
 * @param onMedia true for glass sitting over artwork/video (uses lighter, clearer optics)
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    tint: Color = Color.Unspecified,
    onMedia: Boolean = false,
    elevation: Dp = 10.dp,
    /**
     * The surface is part of a page (inside the NavHost, which is the haze source itself). Haze can't draw glass
     * that samples its own source, so the search box came out with no background at all with Liquid or Frosted
     * Glass on (issue #5): in a page it's frosted with the page's own fill instead.
     */
    inPage: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalAppSettings.current
    val haze = LocalHazeState.current.takeUnless { inPage }
    val dark = LocalIsDark.current
    if (inPage && com.prism.music.ui.components.LocalFrosted.current) {
        Box(
            modifier
                .shadow(elevation * 0.5f, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(if (tint.isSpecified) tint else if (onMedia) Color.Black.copy(alpha = 0.35f) else com.prism.music.ui.components.frostFill(1.4f))
                .border(
                    0.6.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark || onMedia) 0.18f else 0.6f), Color.White.copy(alpha = 0.03f))),
                    shape,
                ),
            content = content,
        )
    } else if (glassPath(android.os.Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass, haze != null) == GlassPath.LIQUID) {
        val clear = onMedia || settings.glassKind == GlassKind.CLEAR
        val style = remember(shape, tint, clear, dark) {
            val baseStyle = if (clear) GlassStyle.clear else GlassStyle.regular
            baseStyle.then {
                shape(shape)
                if (tint.isSpecified) tint(tint)
                else if (clear) tint(if (dark || onMedia) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.10f))
            }
        }
        Box(
            modifier
                .shadow(elevation * 0.6f, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .hazeGlass(input = HazeInput.Backdrop(haze), style = style)
                .clip(shape),
            content = content,
        )
    } else if (glassPath(android.os.Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass, haze != null) == GlassPath.FROSTED) {
        val scheme = MaterialTheme.colorScheme
        // Below Android 12 there's no blur (1.7.9's check: Clear glass showed Home's tiles sharp through the tab bar
        // and mini player, as if drawn over them), so the wash carries it alone and is made thick enough to.
        val noBlur = android.os.Build.VERSION.SDK_INT < 31
        val wash = when {
            tint.isSpecified -> if (noBlur) tint.copy(alpha = tint.alpha.coerceAtLeast(0.88f)) else tint
            onMedia -> Color.Black.copy(alpha = if (noBlur) 0.55f else 0.28f)
            else -> scheme.surfaceContainerHigh.copy(alpha = if (noBlur) (if (dark) 0.9f else 0.92f) else if (dark) 0.55f else 0.62f)
        }
        val style = remember(wash) {
            HazeBlurStyle {
                blurRadius(28.dp)
                noiseFactor(0.12f)
                colorEffects(listOf(HazeColorEffect.tint(wash)))
            }
        }
        Box(
            modifier
                .shadow(elevation * 0.6f, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(shape)
                .hazeBlur(input = HazeInput.Backdrop(haze), style = style)
                .border(
                    0.6.dp,
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark || onMedia) 0.18f else 0.55f), Color.White.copy(alpha = 0.03f))
                    ),
                    shape,
                ),
            content = content,
        )
    } else {
        val scheme = MaterialTheme.colorScheme
        // Opaque: with no glass, rows scrolling under the mini player read through even a 3% see-through fill.
        val fill = if (onMedia) Color.Black.copy(alpha = 0.35f) else scheme.surfaceContainerHigh
        Box(
            modifier
                .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(shape)
                .background(if (tint.isSpecified) tint else fill)
                .border(
                    0.6.dp,
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark || onMedia) 0.16f else 0.6f), Color.White.copy(alpha = 0.02f))
                    ),
                    shape,
                ),
            content = content,
        )
    }
}
