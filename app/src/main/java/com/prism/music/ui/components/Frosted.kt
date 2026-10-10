package com.prism.music.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.boxBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Colours taken from a picture, for the pages that dress themselves in their artwork (artist,
 * album and playlist pages, like Apple Music's).
 */
@Immutable
data class ArtPalette(
    /** The liveliest colour: buttons and links. */
    val vibrant: Color,
    /** What the picture is mostly made of. */
    val dominant: Color,
) {
    private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }
    private fun Color.withLightness(l: Float, satScale: Float = 1f): Color {
        val h = hsl(this); h[1] = (h[1] * satScale).coerceIn(0f, 1f); h[2] = l
        return Color(ColorUtils.HSLToColor(h))
    }

    /** The accent that reads well on the page: light on dark pages, deep on light ones. */
    fun accent(dark: Boolean): Color {
        val h = hsl(vibrant)
        // Near-greys make poor accents; lean on the dominant colour's hue then.
        val base = if (h[1] < 0.18f) dominant else vibrant
        return base.withLightness(if (dark) 0.74f else 0.40f, 1.1f)
    }

    /** The page colour far below the picture, where the frost settles. */
    fun settle(dark: Boolean): Color = dominant.withLightness(if (dark) 0.09f else 0.93f, if (dark) 0.85f else 0.6f)
}

private val paletteCache = object : LinkedHashMap<String, ArtPalette>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtPalette>) = size > 48
}

/** A small, heavily blurred copy of a picture (blurred in software, so it works on every Android version). */
@Immutable
class FrostImage(val image: ImageBitmap)

private val frostCache = object : LinkedHashMap<String, FrostImage>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, FrostImage>) = size > 12
}

private suspend fun decodeSmall(context: android.content.Context, url: String, px: Int): Bitmap? {
    val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(px).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
}

@Composable
fun rememberArtPalette(url: String?): ArtPalette? {
    val context = LocalContext.current
    val palette by produceState(url?.let { synchronized(paletteCache) { paletteCache[it] } }, url) {
        val u = url ?: return@produceState
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = decodeSmall(context, u, 160) ?: return@runCatching null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val dominant = (p.dominantSwatch ?: p.mutedSwatch ?: p.vibrantSwatch)?.rgb?.let { Color(it) } ?: return@runCatching null
                val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb?.let { Color(it) } ?: dominant
                ArtPalette(vibrant, dominant).also { synchronized(paletteCache) { paletteCache[u] = it } }
            }.getOrNull()
        }
    }
    return palette
}

@Composable
fun rememberFrost(url: String?): FrostImage? {
    val context = LocalContext.current
    val frost by produceState(url?.let { synchronized(frostCache) { frostCache[it] } }, url) {
        val u = url ?: return@produceState
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = decodeSmall(context, u, 96) ?: return@runCatching null
                FrostImage(boxBlur(bmp, 3, (minOf(bmp.width, bmp.height) / 7).coerceAtLeast(3)).asImageBitmap())
                    .also { synchronized(frostCache) { frostCache[u] = it } }
            }.getOrNull()
        }
    }
    return frost
}

/** A fine grain over the frost, so large blurred areas read as frosted glass rather than flat colour. */
private val grain: ImageBitmap by lazy {
    val n = 96
    val rnd = Random(7)
    val px = IntArray(n * n) { if (rnd.nextBoolean()) 0x22FFFFFF else 0x22000000 }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) })

/**
 * A frosted page backdrop, after Apple Music's artist pages: [frost] (the hero picture, blurred)
 * sits exactly under the hero, which dissolves into it, and its foot then flows down the page,
 * settling into a tint of the picture. [heroScroll] is how far the hero has scrolled (px); the
 * picture inside the hero drifts at [parallax] of the scroll speed, and the backdrop follows it.
 */
@Composable
fun FrostedBackdrop(
    frost: FrostImage?,
    palette: ArtPalette?,
    heroHeight: Dp,
    heroScroll: () -> Float,
    modifier: Modifier = Modifier,
    parallax: Float = 0.5f,
) {
    val dark = LocalIsDark.current
    val settings = LocalAppSettings.current
    val pureBlack = settings.pureBlack
    val bg = MaterialTheme.colorScheme.background
    // Turned off in Appearance: the page's own background, as before.
    if (!settings.frostedPages) { Box(modifier.fillMaxSize().background(bg)); return }
    val shown by animateFloatAsState(if (frost != null) 1f else 0f, tween(500), label = "frost")
    val settle = palette?.settle(dark) ?: bg
    val grainBrush = remember { ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val heroPx = heroHeight.toPx()
        val scroll = heroScroll().coerceIn(0f, heroPx)
        val heroBottom = heroPx - scroll
        val imgTop = -scroll * (1f - parallax)
        drawRect(settle)
        val img = frost?.image
        if (img != null && shown > 0f) {
            // Centre-cropped to the hero's shape, as the photo above it is.
            val boxAspect = w / heroPx
            val imgAspect = img.width.toFloat() / img.height
            val (sw, sh) = if (imgAspect > boxAspect) (img.height * boxAspect) to img.height.toFloat() else img.width.toFloat() to (img.width / boxAspect)
            val sx = ((img.width - sw) / 2).roundToInt()
            val sy = ((img.height - sh) / 2).roundToInt()
            val srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1))
            if (heroBottom > 0f) clipRect(bottom = heroBottom) {
                drawImage(img, IntOffset(sx, sy), srcSize, IntOffset(0, imgTop.roundToInt()), IntSize(w.roundToInt(), heroPx.roundToInt()), alpha = shown, colorFilter = saturate, filterQuality = FilterQuality.Low)
            }
            // The row of the picture at the hero's foot, drawn tall: its colours run on down the page.
            val frac = ((heroBottom - imgTop) / heroPx).coerceIn(0f, 1f)
            val row = (sy + frac * srcSize.height).roundToInt().coerceIn(sy, sy + srcSize.height - 1)
            val below = (size.height - heroBottom.coerceAtLeast(0f)).roundToInt()
            if (below > 0) drawImage(
                img, IntOffset(sx, (row - 1).coerceAtLeast(0)), IntSize(srcSize.width, 1),
                IntOffset(0, heroBottom.coerceAtLeast(0f).roundToInt()), IntSize(w.roundToInt(), below),
                alpha = shown, colorFilter = saturate, filterQuality = FilterQuality.Low,
            )
            // …and settle into the page's tint further down.
            val from = heroBottom.coerceAtLeast(0f)
            drawRect(
                Brush.verticalGradient(0f to settle.copy(alpha = 0f), 1f to settle, startY = from, endY = from + size.height * 0.9f),
                topLeft = Offset(0f, from),
            )
        }
        // Keeps text readable: darker (or lighter) toward the bottom of the screen.
        val wash = if (dark) Color.Black else Color.White
        val k = if (dark && pureBlack) 1.25f else 1f
        val (a0, a1, a2) = if (dark) Triple(0.12f, 0.30f, 0.55f) else Triple(0.30f, 0.52f, 0.70f)
        drawRect(Brush.verticalGradient(0f to wash.copy(alpha = a0 * k), 0.45f to wash.copy(alpha = (a1 * k).coerceAtMost(0.9f)), 1f to wash.copy(alpha = (a2 * k).coerceAtMost(0.92f))))
        drawRect(grainBrush, alpha = if (dark) 0.5f else 0.35f)
    }
}

/**
 * The frost behind the app's own pages (Home, Search, Library, Replay, Settings): the same
 * frosted glass as the artist and album pages, made from the cover of what's playing (or what
 * played last), so the whole app shares their look. It stays put while the page scrolls.
 */
@Composable
fun AmbientFrost(modifier: Modifier = Modifier) {
    val c = com.prism.music.ui.theme.LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    val recent by c.library.recent.collectAsState()
    val url = current?.thumbnail ?: recent.firstOrNull()?.thumbnail
    val frost = rememberFrost(url)
    val palette = rememberArtPalette(url)
    FrostedBackdrop(frost, palette, AmbientFrostHeight, { 0f }, modifier, parallax = 0f)
}

/** How much of the top of a page the ambient frost's picture covers before it runs down the page. */
val AmbientFrostHeight = 360.dp

/** True on a page that sits on frosted glass: its panels, chips and buttons go see-through too. */
val LocalFrosted = compositionLocalOf { false }

/** The current page's backdrop, so a [ScrollEdge] can draw it again under the top bar. */
@Immutable
class PageBackdrop(val height: Dp, val draw: @Composable (Modifier) -> Unit)

val LocalPageBackdrop = compositionLocalOf<PageBackdrop?> { null }

/**
 * The fill of a card or panel: frosted glass (with its light top edge) on a frosted page,
 * [fallback] (the theme's container colour by default) elsewhere.
 */
@Composable
fun Modifier.pane(shape: Shape, fallback: Color = Color.Unspecified): Modifier {
    if (LocalFrosted.current) {
        val dark = LocalIsDark.current
        return clip(shape).background(frostFill())
            .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.7f), Color.White.copy(alpha = 0.02f))), shape)
    }
    return clip(shape).background(if (fallback != Color.Unspecified) fallback else MaterialTheme.colorScheme.surfaceContainerHigh)
}

/**
 * The fill of a feature card (a session code, the account, a call to action): frosted glass on a
 * frosted page, the accent's gradient elsewhere. Text on it uses [featureInk].
 */
@Composable
fun Modifier.featurePane(shape: Shape): Modifier {
    if (LocalFrosted.current) return pane(shape)
    val scheme = MaterialTheme.colorScheme
    return clip(shape).background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
}

/** Text and icons on a [featurePane]. */
@Composable
fun featureInk(): Color = if (LocalFrosted.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer

/** A quiet fill for small controls (chips, round buttons): frosted on a frosted page, [fallback] elsewhere. */
@Composable
fun quietFill(fallback: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Color =
    if (LocalFrosted.current) frostFill(1.4f) else fallback

/**
 * A scroll edge under the status bar (and a [bar] below it) for pages on the app's own
 * backdrop: content melts away under it rather than meeting a hard line. Draws nothing when
 * the page has no backdrop to redraw.
 */
@Composable
fun TopScrollEdge(visible: () -> Float, bar: Dp = 0.dp) {
    val backdrop = LocalPageBackdrop.current ?: return
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    ScrollEdge(visible, top + bar + 34.dp, backdrop.height) { m -> backdrop.draw(m) }
}

/** Fades the bottom of whatever it's on to nothing, from [start] (0–1 down the height) to the foot. */
fun Modifier.dissolveBottom(start: Float = 0.5f): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

/** The see-through fill of frosted panels and buttons on a frosted page. */
@Composable
fun frostFill(strength: Float = 1f): Color =
    if (LocalIsDark.current) Color.White.copy(alpha = 0.10f * strength) else Color.White.copy(alpha = 0.55f * strength)

/** A pane of frosted glass for content on a frosted page: see-through, with a light top edge. */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDark.current
    Column(
        modifier.clip(shape).background(frostFill())
            .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.7f), Color.White.copy(alpha = 0.02f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/**
 * The scroll edge of a frosted page, as iOS draws it: the page's own [backdrop] drawn again over
 * the top [height] of the screen, fading out downward, so content melts away under the bar rather
 * than meeting a hard edge. [pageHeight] is the page's full height, so the copy lines up exactly
 * with the backdrop behind; [visible] fades the edge in once the hero has scrolled away.
 */
@Composable
fun ScrollEdge(visible: () -> Float, height: Dp, pageHeight: Dp, backdrop: @Composable (Modifier) -> Unit) {
    if (pageHeight <= 0.dp) return
    Box(
        Modifier.fillMaxWidth().height(height).graphicsLayer { alpha = visible() }.clipToBounds().dissolveBottom(0.62f),
        contentAlignment = Alignment.TopStart,
    ) {
        backdrop(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(pageHeight))
    }
}

/**
 * The app's theme with the page's accent swapped for the artwork's ([palette]), so its buttons,
 * links and playing marks wear the picture's colour.
 */
@Composable
fun ArtworkAccent(palette: ArtPalette?, content: @Composable () -> Unit) {
    if (palette == null) { content(); return }
    val dark = LocalIsDark.current
    val scheme = MaterialTheme.colorScheme
    val accent = palette.accent(dark)
    val on = if (accent.luminance() > 0.5f) Color.Black else Color.White
    MaterialTheme(colorScheme = scheme.copy(primary = accent, onPrimary = on, surfaceTint = accent), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

private fun Color.luminance(): Float = ColorUtils.calculateLuminance(toArgb()).toFloat()
