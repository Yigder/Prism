package com.prism.music.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.boxBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

val PlayerFallbackBackdrop = Color(0xFF060609)

/** The cover reduced to a tiny, smoothly interpolated color field. */
@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

private const val GRID = 6
private const val TEX = 32
private const val VIBRANCE = 1.12f
private const val FLOOR = 0.045f

private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > 64
}
private val blurCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 8
}

private suspend fun decodeSmall(context: android.content.Context, url: String, px: Int): Bitmap? {
    val req = ImageRequest.Builder(context).data(hiRes(url, 544)).size(px).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
}

@Composable
fun rememberArtworkMesh(url: String?): ArtworkMesh? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<ArtworkMesh?>(null) }
    var mesh by remember(url) { mutableStateOf(url?.let(meshCache::get) ?: held.value) }
    LaunchedEffect(mesh) { held.value = mesh }
    LaunchedEffect(url) {
        if (url == null || meshCache[url] != null) return@LaunchedEffect
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1500)
            val bmp = runCatching { decodeSmall(context, url, 120) }.getOrNull()
            if (bmp != null) {
                val built = withContext(Dispatchers.Default) { meshOf(bmp, url.hashCode()) }
                if (built != null) {
                    meshCache[url] = built
                    mesh = built
                }
                return@LaunchedEffect
            }
        }
    }
    return mesh
}

/**
 * The player's backdrop: the mesh hung from [seam] (where the artwork's bottom
 * edge sits) and stretched over everything below, its first row held above.
 */
@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    seam: Dp = 0.dp,
    /**
     * Settings → Player → Background (1.7.4): [speed] 0 holds it still (as before), up to 1 it drifts and breathes
     * slowly; [intensity] its colors' strength (1 as before); [blur] how soft (1 as before).
     */
    speed: Float = 0f,
    intensity: Float = 1f,
    blur: Float = 1f,
    /**
     * The system's blur over the mesh (Android 12+), redone every frame it's drawn. Off on a TV (1.8.0): the mesh is a
     * smoothly scaled texture already, and a TV's GPU spends its frames on focus moves instead.
     */
    liveBlur: Boolean = true,
) {
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(mesh) {
        val next = mesh ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect
        if (current == null) { shown = next; return@LaunchedEffect }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }
    // The drift moves the drawn layer only (scaled up a little so its edges never show): what's drawn and blurred
    // inside it stays as it is, so it costs the GPU a transform a frame, not a redraw.
    val still = speed <= 0.01f || com.prism.music.ui.theme.LocalUi.current.reduceMotion
    val drift = if (still) null else rememberInfiniteTransition(label = "meshDrift").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween((48_000 / speed.coerceIn(0.1f, 1f)).roundToInt(), easing = LinearEasing)),
        label = "drift",
    )
    val tone = remember(intensity) {
        if (kotlin.math.abs(intensity - 1f) < 0.01f) null
        else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(intensity) })
    }
    Canvas(
        modifier
            .fillMaxSize()
            .background(PlayerFallbackBackdrop)
            .graphicsLayer {
                val t = drift?.value ?: return@graphicsLayer
                val s = 1.12f + 0.04f * kotlin.math.sin(t * 2)
                scaleX = s; scaleY = s
                translationX = size.width * 0.04f * kotlin.math.cos(t)
                translationY = size.height * 0.03f * kotlin.math.sin(t)
            }
            .then(if (liveBlur && Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp * blur.coerceIn(0.3f, 2f)) else Modifier),
    ) {
        val seamY = seam.toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, 1f, tone) }
        incoming?.let { drawMesh(it, seamY, fade.value, tone) }
        // Aura × Spectrum (1.7): the cover's colors glow, deepening under the controls so the white type reads.
        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.08f), 0.55f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.42f)))
    }
}

/**
 * [ArtworkMeshBackdrop] as it rests on a TV (no drift, no live blur, no seam): the same fill, mesh and deepening,
 * for the TV to make once into a picture (1.8.0).
 */
internal fun DrawScope.drawStillMesh(mesh: ArtworkMesh) {
    drawRect(PlayerFallbackBackdrop)
    drawMesh(mesh, 0f, 1f)
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.08f), 0.55f to Color.Black.copy(alpha = 0.22f), 1f to Color.Black.copy(alpha = 0.42f)))
}

private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float, tone: androidx.compose.ui.graphics.ColorFilter? = null) {
    if (alpha <= 0.001f) return
    val img = mesh.image
    val w = size.width.roundToInt()
    if (seamY > 0.5f) drawImage(img, IntOffset.Zero, IntSize(img.width, 1), IntOffset.Zero, IntSize(w, seamY.roundToInt()), alpha = alpha, colorFilter = tone, filterQuality = FilterQuality.Low)
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(0, seamY.roundToInt()), IntSize(w, (size.height - seamY).roundToInt()), alpha = alpha, colorFilter = tone, filterQuality = FilterQuality.Low)
}

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/**
 * The player's type is always white, so a pale cover (A Night at the Opera, most white sleeves) can't give a pale
 * backdrop: each cell's lightness comes down until its luminance is under [CEILING], hue and saturation kept.
 */
internal fun ceiled(hsl: FloatArray): Int {
    var c = ColorUtils.HSLToColor(hsl)
    if (ColorUtils.calculateLuminance(c) <= CEILING) return c
    var lo = 0f; var hi = hsl[2]
    repeat(12) {
        val mid = (lo + hi) / 2
        if (ColorUtils.calculateLuminance(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], mid))) > CEILING) hi = mid else lo = mid
    }
    c = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lo))
    return c
}

/** White over this luminance (before the backdrop's own darkening) reads at about 4.5:1 or better. */
internal const val CEILING = 0.18

/** The blurred cover gets at least a third black on top, which takes 0.40 down to about 0.16. */
internal const val BLUR_CEILING = 0.40

/** Darkens [bmp]'s pixels brighter than [ceiling] (relative luminance) to it, keeping their hue; in place. */
internal fun capLuminance(bmp: Bitmap, ceiling: Double): Bitmap {
    val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width; val h = out.height
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val p = px[i]
        val lum = ColorUtils.calculateLuminance(p)
        if (lum <= ceiling) continue
        // Luminance goes roughly with the 2.2th power of the channels.
        val k = Math.pow(ceiling / lum, 1 / 2.2).toFloat()
        fun ch(sh: Int) = (((p shr sh) and 0xFF) * k).roundToInt().coerceIn(0, 255)
        px[i] = (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private fun meshOf(src: Bitmap, seed: Int): ArtworkMesh? {
    val w = src.width
    val h = src.height
    if (w < 1 || h < 1) return null
    val cols = GRID.coerceAtMost(w)
    val rows = GRID.coerceAtMost(h)
    val n = rows * cols
    val r = LongArray(n); val g = LongArray(n); val b = LongArray(n); val cnt = IntArray(n)
    val line = IntArray(w)
    for (y in 0 until h) {
        src.getPixels(line, 0, w, 0, y, w, 1)
        // Flipped vertically: row 0 is the artwork's bottom edge.
        val base = ((h - 1 - y) * rows / h) * cols
        for (x in 0 until w) {
            val cell = base + x * cols / w
            val p = line[x]
            r[cell] += (p shr 16) and 0xFF; g[cell] += (p shr 8) and 0xFF; b[cell] += p and 0xFF
            cnt[cell]++
        }
    }
    val grid = IntArray(n) { i ->
        val c = cnt[i].coerceAtLeast(1)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb((r[i] / c).toInt(), (g[i] / c).toInt(), (b[i] / c).toInt()), hsl)
        hsl[1] = (hsl[1] * VIBRANCE).coerceAtMost(1f)
        hsl[2] = hsl[2].coerceAtLeast(FLOOR)
        ceiled(hsl)
    }
    // Shift (and maybe mirror) every row below the seam row so the backdrop never
    // reads as a reflection of the cover, while keeping each cell's neighbours.
    val rnd = Random(seed)
    val mirror = rnd.nextBoolean()
    val shift = rnd.nextInt(cols)
    val arranged = grid.copyOf()
    for (row in 1 until rows) for (x in 0 until cols) {
        val s = if (mirror) cols - 1 - x else x
        arranged[row * cols + x] = grid[row * cols + (s + shift) % cols]
    }
    val tex = IntArray(TEX * TEX)
    fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    fun lerp(a: Int, c: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) + ((((c shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t)).roundToInt().coerceIn(0, 255)
        return argb(ch(16), ch(8), ch(0))
    }
    for (ty in 0 until TEX) {
        val fy = (ty + 0.5f) / TEX * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1); val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smooth(fy - y0)
        for (tx in 0 until TEX) {
            val fx = (tx + 0.5f) / TEX * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1); val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smooth(fx - x0)
            tex[ty * TEX + tx] = lerp(lerp(arranged[y0 * cols + x0], arranged[y0 * cols + x1], wx), lerp(arranged[y1 * cols + x0], arranged[y1 * cols + x1], wx), wy)
        }
    }
    return ArtworkMesh(Bitmap.createBitmap(tex, TEX, TEX, Bitmap.Config.ARGB_8888).asImageBitmap())
}

/**
 * A small, pre-blurred copy of the cover for the lyrics and queue views; [strength] (Settings → Player → Background,
 * 1.7.4) makes it softer or clearer than 1, as before.
 */
@Composable
fun rememberFullArtworkBlur(url: String?, strength: Float = 1f): ImageBitmap? {
    val context = LocalContext.current
    val step = (strength.coerceIn(0.4f, 1.8f) * 10).roundToInt()
    val key = url?.let { if (step == 10) it else "$it@$step" }
    var image by remember(key) { mutableStateOf(key?.let(blurCache::get)) }
    LaunchedEffect(key) {
        if (url == null || key == null || image != null) return@LaunchedEffect
        val bmp = runCatching { decodeSmall(context, url, 128) }.getOrNull() ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) {
            capLuminance(boxBlur(bmp, 3, ((minOf(bmp.width, bmp.height) / 12f) * step / 10f).roundToInt().coerceAtLeast(1)), BLUR_CEILING)
        }.asImageBitmap()
        blurCache[key] = blurred
        image = blurred
    }
    return image
}

@Composable
fun FullArtworkBlurBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier, scrim: Float = 1f) {
    val a by animateFloatAsState(if (image != null) 1f else 0f, tween(320), label = "blurImg")
    Box(modifier.fillMaxSize().background(PlayerFallbackBackdrop)) {
        image?.let {
            val painter = remember(it) { BitmapPainter(it) }
            Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a; scaleX = 1.15f; scaleY = 1.15f })
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.34f * scrim), 0.55f to Color.Black.copy(alpha = 0.48f * scrim), 1f to Color.Black.copy(alpha = (0.64f * scrim).coerceAtMost(0.9f)),
            ))
        }
    }
}

