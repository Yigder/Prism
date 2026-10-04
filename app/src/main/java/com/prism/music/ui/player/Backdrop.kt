package com.prism.music.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

val PlayerFallbackBackdrop = Color(0xFF121212)

/** The cover reduced to a tiny, smoothly interpolated colour field. */
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
fun ArtworkMeshBackdrop(mesh: ArtworkMesh?, modifier: Modifier = Modifier, seam: Dp = 0.dp) {
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
    Canvas(
        modifier
            .fillMaxSize()
            .background(PlayerFallbackBackdrop)
            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
    ) {
        val seamY = seam.toPx().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, 1f) }
        incoming?.let { drawMesh(it, seamY, fade.value) }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.30f))))
    }
}

private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val img = mesh.image
    val w = size.width.roundToInt()
    if (seamY > 0.5f) drawImage(img, IntOffset.Zero, IntSize(img.width, 1), IntOffset.Zero, IntSize(w, seamY.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Low)
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(0, seamY.roundToInt()), IntSize(w, (size.height - seamY).roundToInt()), alpha = alpha, filterQuality = FilterQuality.Low)
}

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

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
        ColorUtils.HSLToColor(hsl)
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

/** A small, pre-blurred copy of the cover for the lyrics and queue views. */
@Composable
fun rememberFullArtworkBlur(url: String?): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf(url?.let(blurCache::get)) }
    LaunchedEffect(url) {
        if (url == null || image != null) return@LaunchedEffect
        val bmp = runCatching { decodeSmall(context, url, 128) }.getOrNull() ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) { boxBlur(bmp, 3) }.asImageBitmap()
        blurCache[url] = blurred
        image = blurred
    }
    return image
}

@Composable
fun FullArtworkBlurBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier) {
    val a by animateFloatAsState(if (image != null) 1f else 0f, tween(320), label = "blurImg")
    Box(modifier.fillMaxSize().background(PlayerFallbackBackdrop)) {
        image?.let {
            val painter = remember(it) { BitmapPainter(it) }
            Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a; scaleX = 1.15f; scaleY = 1.15f })
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.34f), 0.55f to Color.Black.copy(alpha = 0.48f), 1f to Color.Black.copy(alpha = 0.64f)))
        }
    }
}

private fun boxBlur(src: Bitmap, passes: Int): Bitmap {
    val w = src.width; val h = src.height
    if (w < 2 || h < 2) return src
    var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    var t = IntArray(a.size)
    val radius = (minOf(w, h) / 12).coerceAtLeast(2)
    fun pass(s: IntArray, d: IntArray, horizontal: Boolean) {
        val major = if (horizontal) w else h
        val minor = if (horizontal) h else w
        val win = radius * 2 + 1
        for (fixed in 0 until minor) {
            fun px(at: Int): Int { val p = at.coerceIn(0, major - 1); return if (horizontal) s[fixed * w + p] else s[p * w + fixed] }
            var rr = 0; var gg = 0; var bb = 0
            for (o in -radius..radius) { val c = px(o); rr += c shr 16 and 0xFF; gg += c shr 8 and 0xFF; bb += c and 0xFF }
            for (m in 0 until major) {
                d[if (horizontal) fixed * w + m else m * w + fixed] = argb(rr / win, gg / win, bb / win)
                val out = px(m - radius); val inn = px(m + radius + 1)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gg += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
    repeat(passes) { pass(a, t, true); pass(t, a, false) }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}
