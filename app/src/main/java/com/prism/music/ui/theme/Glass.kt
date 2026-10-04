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
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalAppSettings.current
    val haze = LocalHazeState.current
    val dark = LocalIsDark.current
    if (settings.liquidGlass && haze != null) {
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
    } else if (settings.frostedGlass && haze != null) {
        val scheme = MaterialTheme.colorScheme
        val wash = when {
            tint.isSpecified -> tint
            onMedia -> Color.Black.copy(alpha = 0.28f)
            else -> scheme.surfaceContainerHigh.copy(alpha = if (dark) 0.55f else 0.62f)
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
        val fill = if (onMedia) Color.Black.copy(alpha = 0.35f) else scheme.surfaceContainerHigh.copy(alpha = 0.97f)
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
