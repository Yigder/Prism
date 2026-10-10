package com.prism.music.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.prism.music.ui.components.AmbientFrost
import com.prism.music.ui.components.LocalFrosted
import com.prism.music.ui.components.LocalPageBackdrop
import com.prism.music.ui.components.PageBackdrop
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.hiRes
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The screens whose background can be changed. */
enum class BackdropScreen(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library"), REPLAY("Replay"), SETTINGS("Settings") }

/** Ready-made backgrounds: a base colour with three slow-drifting light fields, or a picture. */
enum class Backdrop(val label: String, val colors: List<Long>) {
    DEFAULT("Default", emptyList()),
    AURORA("Aurora", listOf(0xFF07122A, 0xFF2EE59D, 0xFF7C5CFF, 0xFF00C2FF)),
    PRISM("Prism", listOf(0xFF0E0E12, 0xFFFF7A9C, 0xFFFFD36B, 0xFF6BE3FF)),
    SUNSET("Sunset", listOf(0xFF2A0E1A, 0xFFFF6B3D, 0xFFFF3B7F, 0xFFFFC145)),
    OCEAN("Ocean", listOf(0xFF031827, 0xFF0077B6, 0xFF00B4D8, 0xFF48CAE4)),
    FOREST("Forest", listOf(0xFF07150E, 0xFF2D6A4F, 0xFF52B788, 0xFFB7E4C7)),
    NEON("Neon", listOf(0xFF0A0014, 0xFFFF00E5, 0xFF00F0FF, 0xFF7B2FFF)),
    ROSE("Rosé", listOf(0xFF1C0B12, 0xFFF4A3B4, 0xFFE76F8A, 0xFFFFD6A5)),
    MIDNIGHT("Midnight", listOf(0xFF04040A, 0xFF1B1F3B, 0xFF3A2E6E, 0xFF0F3460)),
    EMBER("Ember", listOf(0xFF140604, 0xFFB5361B, 0xFFFF7B00, 0xFF5A0F0F)),
    GRAPHITE("Graphite", listOf(0xFF111113, 0xFF2C2C31, 0xFF44444C, 0xFF1C1C20)),
    ARTWORK("Now playing", emptyList()),
    PHOTO("Your photo", emptyList()),
    ;

    companion object {
        /** Stored as "NAME" or "PHOTO|/path/to/image". */
        fun parse(v: String?): Pair<Backdrop, String?> {
            if (v.isNullOrBlank()) return DEFAULT to null
            val name = v.substringBefore('|')
            val style = entries.firstOrNull { it.name == name } ?: DEFAULT
            return style to v.substringAfter('|', "").ifBlank { null }
        }
    }
}

/**
 * Draws [screen]'s chosen background behind [content]. With frosted pages on, the default is the
 * frost made from what's playing (as on artist and album pages), and the page's panels, chips and
 * buttons turn to frosted glass over it, whichever background it has.
 */
@Composable
fun ScreenBackdrop(screen: BackdropScreen, content: @Composable BoxScope.() -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.flow.collectAsState()
    val (style, photo) = Backdrop.parse(s.backdrops[screen.name])
    val motion = s.backdropMotion && !s.reduceMotion
    val frosted = s.frostedPages
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0.dp) }
    val draw: @Composable (Modifier) -> Unit = { m ->
        when {
            style != Backdrop.DEFAULT -> BackdropLayer(style, photo, s.backdropDim, motion, m)
            frosted -> AmbientFrost(m)
            else -> Box(m.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
    val page = remember(style, photo, frosted, height, s.backdropDim, motion) { PageBackdrop(height, draw) }
    Box(Modifier.fillMaxSize().onSizeChanged { height = with(density) { it.height.toDp() } }) {
        if (style != Backdrop.DEFAULT || frosted) draw(Modifier.fillMaxSize())
        CompositionLocalProvider(LocalFrosted provides frosted, LocalPageBackdrop provides page) { content() }
    }
}

/** One background, at any size (the settings previews use it too). */
@Composable
fun BackdropLayer(style: Backdrop, photo: String?, dim: Float, motion: Boolean, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.red + bg.green + bg.blue < 1.5f
    Box(modifier) {
        when (style) {
            // With frosted pages on, the default is the frost from what's playing (the previews show it so).
            Backdrop.DEFAULT -> if (LocalAppSettings.current.frostedPages) AmbientFrost(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(bg))
            Backdrop.ARTWORK -> {
                val song by LocalContainer.current.player.currentSong.collectAsState()
                Box(Modifier.fillMaxSize().background(bg))
                AsyncImage(
                    hiRes(song?.thumbnail, 300), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.85f },
                )
            }
            Backdrop.PHOTO -> {
                Box(Modifier.fillMaxSize().background(bg))
                if (photo != null) AsyncImage(File(photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            else -> Fields(style.colors.map { Color(it) }, motion)
        }
        // Keeps text readable: a wash of the theme's background, stronger toward the bottom.
        val wash = if (dark) dim else (dim + 0.3f).coerceAtMost(0.9f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = wash * 0.7f), bg.copy(alpha = wash), bg.copy(alpha = (wash + 0.25f).coerceAtMost(0.95f))))
            ),
        )
    }
}

@Composable
private fun Fields(colors: List<Color>, motion: Boolean) {
    val t = if (motion) {
        rememberInfiniteTransition(label = "backdrop").animateFloat(0f, 1f, infiniteRepeatable(tween(28_000, easing = LinearEasing)), label = "t").value
    } else 0.15f
    Canvas(Modifier.fillMaxSize().background(colors[0])) {
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.6f
        fun field(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.7f), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        field(colors[1], size.width * (0.15f + 0.25f * sin(a)), size.height * (0.12f + 0.08f * cos(a)))
        field(colors[2], size.width * (0.9f + 0.12f * cos(a * 2)), size.height * (0.45f + 0.12f * sin(a)))
        field(colors[3], size.width * (0.35f + 0.2f * cos(a)), size.height * (0.92f + 0.06f * sin(a * 2)))
    }
}
