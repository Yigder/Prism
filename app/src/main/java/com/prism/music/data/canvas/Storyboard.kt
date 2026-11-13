package com.prism.music.data.canvas

/**
 * A video's preview frames ("storyboards"): small pictures of the whole video, one every 1, 2 or 5 seconds, sent with
 * the player answer (plan-canvas §1.2, §3.3). Canvas judges a whole video from them without downloading or decoding any
 * of the video itself: is it a still picture, where are its black bars, which seconds move.
 *
 * The spec is "base url|level 0|level 1|…", each level "width#height#count#columns#rows#interval ms#name#sigh"; the last
 * level has the largest frames (160x90). A sheet holds columns x rows frames.
 */
class Storyboard(
    private val base: String,
    private val level: Int,
    private val name: String,
    private val sigh: String,
    val frameW: Int,
    val frameH: Int,
    val count: Int,
    val cols: Int,
    val rows: Int,
    val intervalMs: Long,
) {
    val perSheet: Int get() = cols * rows
    val sheets: Int get() = (count + perSheet - 1) / perSheet

    /** The [n]th sheet's address (the sigh's "$" must be escaped, or YouTube answers 404). */
    fun sheetUrl(n: Int): String =
        base.replace("\$L", level.toString()).replace("\$N", name.replace("\$M", n.toString())) + "&sigh=" + sigh.replace("$", "%24")

    /**
     * Cuts the frames out of sheet number [n] (decoded to grey values) and adds each, reduced to a [Grid], to [out];
     * stops at [count]. A sheet smaller than it should be (the last one often is) gives what it holds.
     */
    fun cut(sheet: Sheet, n: Int, out: MutableList<Grid>) {
        for (i in 0 until perSheet) {
            if (n * perSheet + i >= count) return
            val x0 = (i % cols) * frameW
            val y0 = (i / cols) * frameH
            if (x0 + frameW > sheet.width || y0 + frameH > sheet.height) return
            out += Grid.of(sheet, x0, y0, frameW, frameH)
        }
    }

    companion object {
        /** The largest level of [spec]; null when it can't be read. [lengthMs] stands in for a missing interval. */
        fun parse(spec: String?, lengthMs: Long = 0): Storyboard? {
            val parts = spec?.split('|') ?: return null
            if (parts.size < 2) return null
            val f = parts.last().split('#')
            if (f.size < 8) return null
            val w = f[0].toIntOrNull() ?: return null
            val h = f[1].toIntOrNull() ?: return null
            val count = f[2].toIntOrNull() ?: return null
            val cols = f[3].toIntOrNull() ?: return null
            val rows = f[4].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || count <= 0 || cols <= 0 || rows <= 0) return null
            // Level 0's interval is 0 (its frames are spread over the video); the larger levels say theirs.
            val interval = f[5].toLongOrNull()?.takeIf { it > 0 } ?: (if (lengthMs > 0) lengthMs / count else return null)
            return Storyboard(parts[0], parts.size - 2, f[6], f[7], w, h, count, cols, rows, interval)
        }
    }
}

/** A decoded sheet as grey values (0..255), row by row. */
class Sheet(val width: Int, val height: Int, val luma: IntArray)

/** Turns a sheet's bytes (a JPEG or WebP) into grey values. Behind an interface so tests need no Android. */
fun interface SheetDecoder {
    fun decode(bytes: ByteArray): Sheet?

    companion object {
        /** Grey value of an ARGB pixel, by the eye's weights (green counts most). */
        fun luma(argb: Int): Int = ((argb shr 16 and 255) * 3 + (argb shr 8 and 255) * 6 + (argb and 255)) / 10
    }
}

/** The real decoder: Android's own. */
object BitmapSheetDecoder : SheetDecoder {
    override fun decode(bytes: ByteArray): Sheet? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Sheet(bmp.width, bmp.height, IntArray(px.size) { SheetDecoder.luma(px[it]) })
        } finally {
            bmp.recycle()
        }
    }
}

/** One preview frame reduced to [W] x [H] grey values (0..255), row by row: all the judging needs. */
class Grid(val v: IntArray) {
    init { require(v.size == W * H) }

    operator fun get(x: Int, y: Int): Int = v[y * W + x]

    companion object {
        const val W = 32
        const val H = 18

        /** The average of each cell of the [w] x [h] frame at ([x0], [y0]) in [sheet]. */
        fun of(sheet: Sheet, x0: Int, y0: Int, w: Int, h: Int): Grid {
            val out = IntArray(W * H)
            for (cy in 0 until H) for (cx in 0 until W) {
                val ya = y0 + cy * h / H
                val yb = (y0 + (cy + 1) * h / H).coerceAtLeast(ya + 1)
                val xa = x0 + cx * w / W
                val xb = (x0 + (cx + 1) * w / W).coerceAtLeast(xa + 1)
                var sum = 0; var n = 0
                for (y in ya until yb) for (x in xa until xb) { sum += sheet.luma[y * sheet.width + x]; n++ }
                out[cy * W + cx] = if (n > 0) sum / n else 0
            }
            return Grid(out)
        }

        /** A grid as text (base64 of its bytes), for saved fixtures and diagnostics. */
        fun encode(g: Grid): String = java.util.Base64.getEncoder().encodeToString(ByteArray(W * H) { g.v[it].coerceIn(0, 255).toByte() })

        fun decode(s: String): Grid = java.util.Base64.getDecoder().decode(s.trim()).let { b -> Grid(IntArray(W * H) { b[it].toInt() and 255 }) }
    }
}
