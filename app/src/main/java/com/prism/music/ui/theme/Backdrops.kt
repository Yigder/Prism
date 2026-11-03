package com.prism.music.ui.theme

import com.prism.music.R
import com.prism.music.tr
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
import androidx.compose.foundation.layout.padding
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
enum class BackdropScreen(val label: String) { HOME(tr(R.string.home)), SEARCH(tr(R.string.search)), LIBRARY(tr(R.string.library)), REPLAY(tr(R.string.replay_2)), SETTINGS(tr(R.string.settings)) }

/**
 * The player's own background (1.7.4), kept in the same map as the screens' (so Looks keep its photo too), but not a
 * screen: "Same on every screen" leaves it alone. Missing, the player's "A background" shows Home's.
 */
const val PLAYER_BACKDROP = "PLAYER"

/** The screens' backgrounds alone, without the player's. */
val Map<String, String>.screensOnly: Map<String, String> get() = filterKeys { it != PLAYER_BACKDROP }

/** How a ready-made background is drawn. */
enum class BackdropKind {
    /** Default, Now playing and Your photo: drawn specially. */
    SPECIAL,
    /** A base color with three slow-drifting light fields. */
    FIELDS,
    /** A quiet top-to-bottom blend of two colors. */
    BLEND,
    /** The theme's accent, softly, over the theme's background (follows the accent color). */
    TINT,
    /** Simple line patterns in the accent color over the theme's background. */
    DOTS, PAPER, PINSTRIPE, WAVES, RIPPLES, DIAMONDS, ZIGZAG, HALFTONE, CROSSES, SCALES, CONFETTI,
    /** Detailed illustrations in their own palettes ([Design]). */
    CONSTELLATION, CONTOURS, MOSAIC, BOKEH, SILK, HORIZON,
    /** The seasonal scenes (1.8.0, [SeasonalDesigns]): Thanksgiving's harvest and Christmas. */
    HARVEST, HOLIDAY,
}

/** Which row of the picker a background sits in. */
enum class BackdropGroup(val label: String) { YOURS(tr(R.string.yours)), COLOR(tr(R.string.color_2)), SIMPLE(tr(R.string.simple)), DESIGNS(tr(R.string.designs)) }

/**
 * Ready-made backgrounds. Each has a dark and a light version (base color first, then its light
 * fields), so the same pick looks at home in either theme: deep and glowing in dark mode, airy
 * pastels in light mode. Pattern designs take their ink from the theme's accent instead.
 * Names are stored in settings, so keep them stable.
 */
enum class Backdrop(
    val label: String,
    val kind: BackdropKind,
    val group: BackdropGroup,
    val dark: List<Long> = emptyList(),
    val light: List<Long> = emptyList(),
    /** Only offered in this season's month (a seasonal Look's own background); null for always. */
    val season: com.prism.music.data.looks.Season? = null,
) {
    /** Ambient: frost from what's playing (Aura × Spectrum's own look), or the plain page with frosted pages off. */
    DEFAULT(tr(R.string.ambient), BackdropKind.SPECIAL, BackdropGroup.YOURS),
    ARTWORK(tr(R.string.widget_now_playing), BackdropKind.SPECIAL, BackdropGroup.YOURS),
    PHOTO(tr(R.string.your_photo), BackdropKind.SPECIAL, BackdropGroup.YOURS),

    /** The prism's split light as three slow fields: red, green and violet (1.7). */
    SPECTRUM(tr(R.string.spectrum), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0B0910, 0xFFFF4D5E, 0xFF3DDC97, 0xFF7C5CFF), listOf(0xFFF8F5F9, 0xFFFF9AA6, 0xFF8FE5C0, 0xFFBBA9FF)),
    AURORA(tr(R.string.aurora), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF07122A, 0xFF2EE59D, 0xFF7C5CFF, 0xFF00C2FF), listOf(0xFFEEF6F5, 0xFF7FE6BE, 0xFFB3A3FF, 0xFF8ADDFF)),
    PRISM(tr(R.string.app_name), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0E0E12, 0xFFFF7A9C, 0xFFFFD36B, 0xFF6BE3FF), listOf(0xFFFBF8F4, 0xFFFFA9BE, 0xFFFFDE94, 0xFF9AE8FF)),
    SUNSET(tr(R.string.sunset), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF2A0E1A, 0xFFFF6B3D, 0xFFFF3B7F, 0xFFFFC145), listOf(0xFFFFF4EE, 0xFFFFA57D, 0xFFFF8FB3, 0xFFFFD685)),
    OCEAN(tr(R.string.ocean), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF031827, 0xFF0077B6, 0xFF00B4D8, 0xFF48CAE4), listOf(0xFFEEF7FC, 0xFF6DBDEA, 0xFF7ADBEE, 0xFFAAE7F3)),
    FOREST(tr(R.string.forest), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF07150E, 0xFF2D6A4F, 0xFF52B788, 0xFFB7E4C7), listOf(0xFFF2F7F1, 0xFF8EC3A4, 0xFFA8D9B9, 0xFFDDF0DA)),
    NEON(tr(R.string.neon), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF0A0014, 0xFFFF00E5, 0xFF00F0FF, 0xFF7B2FFF), listOf(0xFFF8F3FF, 0xFFFF8CF2, 0xFF7FF3FF, 0xFFB394FF)),
    ROSE(tr(R.string.ros), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF1C0B12, 0xFFF4A3B4, 0xFFE76F8A, 0xFFFFD6A5), listOf(0xFFFFF5F6, 0xFFF8BECB, 0xFFF0A3B6, 0xFFFFE1C0)),
    MIDNIGHT(tr(R.string.midnight), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF04040A, 0xFF1B1F3B, 0xFF3A2E6E, 0xFF0F3460), listOf(0xFFEFF1F8, 0xFFB4BDE6, 0xFFC2B5EC, 0xFFA8C2E0)),
    EMBER(tr(R.string.ember), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF140604, 0xFFB5361B, 0xFFFF7B00, 0xFF5A0F0F), listOf(0xFFFFF5EF, 0xFFEE9A80, 0xFFFFBC7A, 0xFFE3A0A0)),
    GRAPHITE(tr(R.string.graphite), BackdropKind.FIELDS, BackdropGroup.COLOR,
        listOf(0xFF111113, 0xFF2C2C31, 0xFF44444C, 0xFF1C1C20), listOf(0xFFF5F5F7, 0xFFDDDDE3, 0xFFD2D2D9, 0xFFE8E8EC)),

    TINT(tr(R.string.soft_tint), BackdropKind.TINT, BackdropGroup.SIMPLE),
    DUSK(tr(R.string.dusk), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF221338, 0xFF0B1B2E), listOf(0xFFFCE8F1, 0xFFE2ECFF)),
    SAND(tr(R.string.sand), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF221D18, 0xFF141210), listOf(0xFFF7F0E6, 0xFFEDE3D3)),
    MIST(tr(R.string.mist), BackdropKind.BLEND, BackdropGroup.SIMPLE,
        listOf(0xFF0F1E22, 0xFF0D1218), listOf(0xFFEAF5F3, 0xFFE6ECF4)),
    DOTS(tr(R.string.dots), BackdropKind.DOTS, BackdropGroup.SIMPLE),
    PAPER(tr(R.string.paper), BackdropKind.PAPER, BackdropGroup.SIMPLE),
    PINSTRIPE(tr(R.string.pinstripe), BackdropKind.PINSTRIPE, BackdropGroup.SIMPLE),
    WAVES(tr(R.string.waves), BackdropKind.WAVES, BackdropGroup.SIMPLE),
    RIPPLES(tr(R.string.ripples), BackdropKind.RIPPLES, BackdropGroup.SIMPLE),
    DIAMONDS(tr(R.string.diamonds), BackdropKind.DIAMONDS, BackdropGroup.SIMPLE),
    ZIGZAG(tr(R.string.zigzag), BackdropKind.ZIGZAG, BackdropGroup.SIMPLE),
    HALFTONE(tr(R.string.halftone), BackdropKind.HALFTONE, BackdropGroup.SIMPLE),
    CROSSES(tr(R.string.crosses), BackdropKind.CROSSES, BackdropGroup.SIMPLE),
    SCALES(tr(R.string.scales), BackdropKind.SCALES, BackdropGroup.SIMPLE),
    CONFETTI(tr(R.string.confetti), BackdropKind.CONFETTI, BackdropGroup.SIMPLE),

    CONSTELLATION(tr(R.string.constellation), BackdropKind.CONSTELLATION, BackdropGroup.DESIGNS,
        listOf(0xFF070B1A, 0xFFA9C6FF, 0xFF6B4CFF, 0xFF00C2D1), listOf(0xFFF3F5FC, 0xFF3D4F8A, 0xFFB8A8FF, 0xFF8EE3EA)),
    CONTOURS(tr(R.string.contours), BackdropKind.CONTOURS, BackdropGroup.DESIGNS,
        listOf(0xFF0B1414, 0xFF3FD0A0, 0xFF3F8FB0, 0xFFE0B354), listOf(0xFFF4F8F3, 0xFF2E8B6E, 0xFF4F8FA8, 0xFFD9A441)),
    MOSAIC(tr(R.string.mosaic), BackdropKind.MOSAIC, BackdropGroup.DESIGNS,
        listOf(0xFF120F1E, 0xFF5B3FD9, 0xFFE0457B, 0xFF1FA2C9), listOf(0xFFFAF7F2, 0xFFC4B5FF, 0xFFFFB3C7, 0xFFA8E0F0)),
    BOKEH(tr(R.string.bokeh), BackdropKind.BOKEH, BackdropGroup.DESIGNS,
        listOf(0xFF0E0A14, 0xFFFFB347, 0xFFFF5E8A, 0xFF7A6BFF), listOf(0xFFFFF8F1, 0xFFFFB866, 0xFFFF8FB0, 0xFF9C90FF)),
    SILK(tr(R.string.silk), BackdropKind.SILK, BackdropGroup.DESIGNS,
        listOf(0xFF0A0E18, 0xFF9C84FF, 0xFF27C4E8, 0xFFFF6B9D), listOf(0xFFF6F4FB, 0xFF7C5CFF, 0xFF1AA6CC, 0xFFE8558A)),
    HORIZON(tr(R.string.horizon), BackdropKind.HORIZON, BackdropGroup.DESIGNS,
        listOf(0xFF0D0420, 0xFFFFB03D, 0xFFFF2E88, 0xFFB08CFF), listOf(0xFFFFF3EA, 0xFFFFB27A, 0xFFFF86B8, 0xFF9B7BFF)),
    // Seasonal (1.8.0): base, then the glows' colors; the scenes keep their own inks ([SeasonalDesigns]).
    HARVEST(tr(R.string.harvest), BackdropKind.HARVEST, BackdropGroup.DESIGNS,
        listOf(0xFF1A0F09, 0xFFE8892B, 0xFFB8432A, 0xFFE0B04A), listOf(0xFFFBF3E7, 0xFFF0A65A, 0xFFE07F62, 0xFFEBC77A),
        season = com.prism.music.data.looks.Season.HARVEST),
    HOLIDAY(tr(R.string.holiday), BackdropKind.HOLIDAY, BackdropGroup.DESIGNS,
        listOf(0xFF08150F, 0xFFD7263D, 0xFF2E8B57, 0xFFF2C14E), listOf(0xFFF5F8F6, 0xFFE8707C, 0xFF6DB88C, 0xFFEBC66C),
        season = com.prism.music.data.looks.Season.HOLIDAY),
    ;

    fun colors(dark: Boolean): List<Long> = if (dark) this.dark else light

    /** Whether the pickers offer it on [date]: always, or a seasonal one in its month. */
    fun offeredOn(date: java.time.LocalDate): Boolean = season?.contains(date) ?: true

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
    val frosted = ambientFrostShown(s, LocalIsDark.current)
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
        // The background reaches under the navigation rail (wide windows); the page itself starts beside it.
        Box(Modifier.fillMaxSize().padding(start = LocalChromeStart.current)) {
            // Over any background (the ambient frost or a picked one), the page's panels and buttons are glass.
            CompositionLocalProvider(LocalFrosted provides (frosted || style != Backdrop.DEFAULT), LocalPageBackdrop provides page, LocalChromeStart provides 0.dp) { content() }
        }
    }
}

/**
 * Whether the Default background is the frost from what's playing. Pure black in dark mode means black pages (Reddit,
 * Pixel 9 Pro: "AMOLED mode stays grey"), so the frost steps aside for it.
 */
fun ambientFrostShown(s: com.prism.music.data.prefs.AppSettings, dark: Boolean): Boolean = s.frostedPages && !(dark && s.pureBlack)

/** One background, at any size (the settings previews use it too). */
@Composable
fun BackdropLayer(style: Backdrop, photo: String?, dim: Float, motion: Boolean, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val dark = LocalIsDark.current
    // Pure black (AMOLED) in dark mode: every background sits on true black, its own colors kept but quieter, so most of
    // the screen's pixels stay off whichever background is picked (it used to change only Ambient and the plain page).
    val black = dark && LocalAppSettings.current.pureBlack
    val palette = style.colors(dark).map { Color(it) }.let { if (black && it.isNotEmpty()) listOf(Color.Black) + it.drop(1) else it }
    Box(modifier) {
        when (style) {
            // With frosted pages on, the default is the frost from what's playing (the previews show it so).
            Backdrop.DEFAULT -> if (ambientFrostShown(LocalAppSettings.current, dark)) AmbientFrost(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(bg))
            Backdrop.ARTWORK -> {
                val song by LocalContainer.current.player.currentSong.collectAsState()
                Box(Modifier.fillMaxSize().background(bg))
                AsyncImage(
                    hiRes(song?.thumbnail, 300), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = if (black) 0.5f else 0.85f },
                )
            }
            Backdrop.PHOTO -> {
                Box(Modifier.fillMaxSize().background(bg))
                if (photo != null) AsyncImage(File(photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            else -> when (style.kind) {
                BackdropKind.FIELDS -> Fields(palette, dark, motion, if (black) 0.45f else 1f)
                BackdropKind.BLEND -> style.colors(dark).map { Color(it) }.map { if (black) androidx.compose.ui.graphics.lerp(Color.Black, it, 0.45f) else it }.let { (top, bottom) ->
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
                }
                else -> if (style.group == BackdropGroup.DESIGNS) Design(style.kind, palette, dark, motion)
                    else Pattern(style.kind, dark, motion, if (black) 0.5f else 1f)
            }
        }
        // Keeps text readable: a wash of the theme's background, stronger toward the bottom. The light
        // palettes are pale already, so they take a lighter wash; the patterns are quiet and take less still.
        val base = if (dark) dim else dim * 0.85f
        val wash = if (style.group == BackdropGroup.SIMPLE) base * 0.5f else base
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = wash * 0.7f), bg.copy(alpha = wash), bg.copy(alpha = (wash + 0.25f).coerceAtMost(0.95f))))
            ),
        )
    }
}

/**
 * A moving background's clock: 0 to 1 over [periodMs], over and over ([rest] when it doesn't move). It's read where
 * the background is drawn, so each frame only redraws it (reading it while composing rebuilt the whole background every
 * frame). Under the full player, which hides it, it holds still, and carries on from that same place when the page
 * shows again.
 */
@Composable
internal fun drift(motion: Boolean, periodMs: Int, rest: Float): androidx.compose.runtime.State<Float> {
    val t = remember { androidx.compose.runtime.mutableFloatStateOf(if (motion) 0f else rest) }
    // How long it has moved for (ms), kept across holds.
    val moved = remember { doubleArrayOf(0.0) }
    val hidden = com.prism.music.ui.components.LocalHiddenByPlayer.current()
    androidx.compose.runtime.LaunchedEffect(motion, periodMs, hidden) {
        if (!motion) { t.floatValue = rest; return@LaunchedEffect }
        if (hidden) return@LaunchedEffect
        // As Compose's own animations: with Android's animations turned off they sit at their end.
        val scale = coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
        if (scale == 0f) { t.floatValue = 1f; return@LaunchedEffect }
        var last = -1L
        while (true) androidx.compose.animation.core.withInfiniteAnimationFrameNanos { now ->
            if (last >= 0) moved[0] += (now - last) / 1_000_000.0 / scale
            last = now
            t.floatValue = ((moved[0] % periodMs) / periodMs).toFloat()
        }
    }
    return t
}

@Composable
private fun Fields(colors: List<Color>, dark: Boolean, motion: Boolean, quiet: Float = 1f) {
    val clock = drift(motion, 28_000, 0.15f)
    // Pastels need a touch less strength to stay airy; the dark palettes glow at full ([quiet] lowers it on pure black).
    val strength = (if (dark) 0.7f else 0.62f) * quiet
    Canvas(Modifier.fillMaxSize().background(colors[0])) {
        val t = clock.value
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.6f
        fun field(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        field(colors[1], size.width * (0.15f + 0.25f * sin(a)), size.height * (0.12f + 0.08f * cos(a)))
        field(colors[2], size.width * (0.9f + 0.12f * cos(a * 2)), size.height * (0.45f + 0.12f * sin(a)))
        field(colors[3], size.width * (0.35f + 0.2f * cos(a)), size.height * (0.92f + 0.06f * sin(a * 2)))
    }
}

/**
 * The simple designs: the theme's background, a soft glow of the accent from the top, and (except
 * Soft tint) a fine pattern in the accent that fades out down the page. Inks are stronger in light
 * mode, where pale lines on a pale page would otherwise vanish.
 */
@Composable
private fun Pattern(kind: BackdropKind, dark: Boolean, motion: Boolean, glow: Float = 1f) {
    val scheme = MaterialTheme.colorScheme
    val bg = scheme.background
    val accent = scheme.primary
    val second = scheme.tertiary
    val t = drift(motion && (kind == BackdropKind.WAVES || kind == BackdropKind.RIPPLES), 24_000, 0.2f)
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize().background(bg)) { drawPattern(kind, bg, accent, second, dark, t.value, density, glow) }
}

/** One simple design, drawn plainly (tests render it to an image). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPattern(
    kind: BackdropKind, bg: Color, accent: Color, second: Color, dark: Boolean, t: Float, density: Float, glow: Float = 1f,
) {
        drawRect(bg)
        val w = size.width
        val h = size.height
        // The glow every simple design shares: the accent, faintly, from the top.
        drawRect(Brush.verticalGradient(
            0f to accent.copy(alpha = (if (dark) 0.22f else 0.16f) * glow), 0.55f to accent.copy(alpha = (if (dark) 0.06f else 0.04f) * glow), 1f to Color.Transparent,
        ))
        if (kind == BackdropKind.TINT) {
            drawCircle(
                Brush.radialGradient(listOf(second.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent), Offset(w, h * 0.85f), w * 0.9f),
                w * 0.9f, Offset(w, h * 0.85f),
            )
            return
        }
        val ink = accent.copy(alpha = if (dark) 0.20f else 0.26f)
        // Patterns fade out toward the bottom, where lists sit thickest.
        val fade = { y: Float -> (1f - (y / h) * 0.85f).coerceIn(0.15f, 1f) }
        val stroke = 1f * density
        when (kind) {
            BackdropKind.DOTS -> {
                val step = 18f * density
                val r = 1.4f * density
                var y = step / 2
                while (y < h) {
                    var x = step / 2
                    while (x < w) { drawCircle(ink.copy(alpha = ink.alpha * fade(y)), r, Offset(x, y)); x += step }
                    y += step
                }
            }
            BackdropKind.PAPER -> {
                val step = 24f * density
                var x = step
                while (x < w) { drawLine(Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.15f))), Offset(x, 0f), Offset(x, h), stroke * 0.8f); x += step }
                var y = step
                while (y < h) { drawLine(ink.copy(alpha = ink.alpha * fade(y)), Offset(0f, y), Offset(w, y), stroke * 0.8f); y += step }
            }
            BackdropKind.PINSTRIPE -> {
                val step = 14f * density
                var x = -h
                while (x < w) {
                    drawLine(Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.15f))), Offset(x, h), Offset(x + h, 0f), stroke)
                    x += step
                }
            }
            BackdropKind.WAVES -> {
                // Three soft bands rolling along the foot of the page, plus thin echoes at the top.
                val phase = t * 2 * PI.toFloat()
                listOf(Triple(accent, 0.70f, 0f), Triple(second, 0.78f, 1.7f), Triple(accent, 0.86f, 3.4f)).forEachIndexed { i, (color, at, shift) ->
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, h)
                        var x = 0f
                        while (x <= w + 4f) {
                            lineTo(x, h * at + sin(x / w * 2 * PI.toFloat() * 1.2f + phase + shift) * h * 0.03f)
                            x += 4f
                        }
                        lineTo(w, h)
                        close()
                    }
                    drawPath(path, color.copy(alpha = (if (dark) 0.14f else 0.12f) + i * 0.03f))
                }
                for (k in 0 until 4) {
                    val path = androidx.compose.ui.graphics.Path()
                    var x = 0f
                    val y0 = h * (0.08f + k * 0.035f)
                    path.moveTo(0f, y0)
                    while (x <= w + 4f) { path.lineTo(x, y0 + sin(x / w * 2 * PI.toFloat() + phase + k * 0.6f) * h * 0.012f); x += 4f }
                    drawPath(path, ink.copy(alpha = ink.alpha * (1f - k * 0.2f)), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                }
            }
            BackdropKind.RIPPLES -> {
                // Rings spreading from the top corner; with motion on they slowly travel outward.
                val origin = Offset(w * 0.92f, h * 0.06f)
                val step = 26f * density
                val maxR = size.maxDimension * 1.2f
                var r = step * t
                while (r < maxR) {
                    if (r > 0f) drawCircle(ink.copy(alpha = ink.alpha * (1f - r / maxR)), r, origin, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    r += step
                }
            }
            BackdropKind.DIAMONDS -> {
                // A fine lattice of diagonals both ways.
                val step = 22f * density
                val brush = Brush.verticalGradient(listOf(ink, ink.copy(alpha = ink.alpha * 0.12f)))
                var x = -h
                while (x < w + h) {
                    drawLine(brush, Offset(x, h), Offset(x + h, 0f), stroke * 0.8f)
                    drawLine(brush, Offset(x, 0f), Offset(x + h, h), stroke * 0.8f)
                    x += step
                }
            }
            BackdropKind.ZIGZAG -> {
                val rowStep = 24f * density
                val run = 9f * density
                val amp = 4.5f * density
                var y = rowStep / 2
                while (y < h) {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, y) }
                    var x = 0f
                    var up = true
                    while (x < w + run) { x += run; path.lineTo(x, y + if (up) -amp else amp); up = !up }
                    drawPath(path, ink.copy(alpha = ink.alpha * fade(y)), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    y += rowStep
                }
            }
            BackdropKind.HALFTONE -> {
                // Dots that swell toward the top corner, like a print's halftone.
                // Kept to the corner and fine, so the title and first rows stay clear.
                val step = 11f * density
                val origin = Offset(w, 0f)
                val reach = size.minDimension * 0.95f
                var y = step / 2
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < w) {
                        val k = (1f - (Offset(x, y) - origin).getDistance() / reach).coerceIn(0f, 1f)
                        if (k > 0.06f) drawCircle(ink.copy(alpha = ink.alpha * 0.9f), step * 0.36f * k, Offset(x, y))
                        x += step
                    }
                    y += step * 0.87f; row++
                }
            }
            BackdropKind.CROSSES -> {
                val step = 30f * density
                val arm = 3.5f * density
                var y = step / 2
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) step / 2 else step
                    val c = ink.copy(alpha = ink.alpha * fade(y))
                    while (x < w) {
                        drawLine(c, Offset(x - arm, y), Offset(x + arm, y), stroke * 1.2f)
                        drawLine(c, Offset(x, y - arm), Offset(x, y + arm), stroke * 1.2f)
                        x += step
                    }
                    y += step / 2; row++
                }
            }
            BackdropKind.SCALES -> {
                // Overlapping arcs in offset rows, like fish scales or roof tiles.
                val rad = 13f * density
                var y = 0f
                var row = 0
                while (y < h + rad) {
                    var x = if (row % 2 == 0) 0f else rad
                    val c = ink.copy(alpha = ink.alpha * fade(y))
                    while (x < w + rad) {
                        drawArc(c, 0f, 180f, false, Offset(x - rad, y - rad), androidx.compose.ui.geometry.Size(rad * 2, rad * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        x += rad * 2
                    }
                    y += rad * 0.9f; row++
                }
            }
            BackdropKind.CONFETTI -> {
                // Short dashes scattered at all angles, in the accent and its partner color (same scatter every time).
                val rnd = kotlin.random.Random(42)
                val len = 7f * density
                repeat((w * h / (34f * 34f * density * density)).toInt().coerceAtMost(900)) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val a = rnd.nextFloat() * PI.toFloat()
                    val col = (if (rnd.nextBoolean()) accent else second).copy(alpha = ink.alpha * 1.15f * fade(y))
                    drawLine(col, Offset(x - cos(a) * len / 2, y - sin(a) * len / 2), Offset(x + cos(a) * len / 2, y + sin(a) * len / 2),
                        stroke * 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            else -> {}
        }
}
