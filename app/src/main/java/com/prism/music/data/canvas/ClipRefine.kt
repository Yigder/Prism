package com.prism.music.data.canvas

import kotlin.math.abs

/**
 * The second look at a Canvas loop, on its real frames (1.8.6, plan-canvas §3.4 and §11a R4). The finder chose the
 * seconds from preview pictures two seconds apart; here the kept file's own frames, read small, say three things:
 * - **where the loop ends**: on one of the video's own cuts, 5 to 9 s in, so the restart looks like its editing;
 * - **whether it flashes**: more than three flashes in any second over a quarter of the picture is never shown;
 * - **where the people are**: the crop follows the faces found (Android's own face finder), so heads aren't cut off.
 *
 * Everything here is plain arithmetic on small grids ([ClipDecode] reads them from the file), so it is tested without
 * a phone.
 */
object ClipRefine {
    const val GRID_W = 32
    const val GRID_H = 18

    /** One frame read small: its time in the video and its brightness on a [GRID_W] x [GRID_H] grid (0..255 each). */
    class Frame(val timeMs: Long, val grid: ByteArray)

    /** A face found in a frame: its middle across the whole frame (0..1), its size (eye distance over the frame's width), how sure. */
    data class Face(val timeMs: Long, val x: Float, val size: Float, val confidence: Float)

    /** What the second look says. [cropX] null: no faces to go by, the finder's guess stays. */
    data class Refined(val endMs: Long, val cropX: Float?, val flashing: Boolean, val cutsMs: List<Long>, val faces: Int)

    /** A cut: this much of the brightness changes from one frame to the next (of 255), and far more than around it. */
    private const val CUT = 22f
    /** A cell counts toward a flash when it changes by a tenth of the range; a flash needs a quarter of the picture. */
    private const val FLASH_STEP = 26
    private const val FLASH_AREA = 0.25f
    /** More than three flashes (a change and its opposite) in a second: seven changes of direction or more. */
    private const val FLASH_CHANGES = 7
    /** The upright band a tall phone shows of a wide picture, as a share of its width (for placing the crop). */
    const val BAND = 0.3f
    /** The crop follows the faces when this share of them (by size and sureness) fits in one band. */
    private const val AGREE = 0.8

    /** How much two frames differ: the mean change of a cell, 0..255. */
    fun change(a: ByteArray, b: ByteArray): Float {
        var sum = 0
        for (i in a.indices) sum += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
        return sum.toFloat() / a.size
    }

    /** The times of the first frame after each cut. */
    fun cuts(frames: List<Frame>): List<Long> {
        if (frames.size < 3) return emptyList()
        val d = FloatArray(frames.size)
        for (i in 1 until frames.size) d[i] = change(frames[i - 1].grid, frames[i].grid)
        val out = ArrayList<Long>()
        for (i in 1 until frames.size) {
            val before = if (i > 1) d[i - 1] else 0f
            val after = if (i + 1 < frames.size) d[i + 1] else 0f
            // A cut stands alone: movement and dissolves change several frames in a row by about as much.
            if (d[i] >= CUT && d[i] >= 3f * maxOf(before, after, 2f)) out += frames[i].timeMs
        }
        return out
    }

    /** True when the frames flash more than three times in any one second over a quarter of the picture or more. */
    fun flashing(frames: List<Frame>): Boolean {
        // Each large change of brightness, and which way it went.
        val times = ArrayList<Long>(); val up = ArrayList<Boolean>()
        for (i in 1 until frames.size) {
            val a = frames[i - 1].grid; val b = frames[i].grid
            var cells = 0; var sum = 0
            for (k in a.indices) {
                val step = (b[k].toInt() and 0xff) - (a[k].toInt() and 0xff)
                if (abs(step) >= FLASH_STEP) { cells++; sum += step }
            }
            if (cells >= a.size * FLASH_AREA && sum != 0) { times += frames[i].timeMs; up += sum > 0 }
        }
        // Changes of direction inside any one second.
        for (i in times.indices) {
            var changes = 1; var last = up[i]
            var j = i + 1
            while (j < times.size && times[j] - times[i] <= 1000) {
                if (up[j] != last) { changes++; last = up[j] }
                j++
            }
            if (changes >= FLASH_CHANGES) return true
        }
        return false
    }

    /**
     * Where the loop ends: on the last cut that leaves it 5 to 9 s long; with no such cut, 8 s in or where the frames
     * end, whichever comes first (never under what there is).
     */
    fun end(startMs: Long, cutsMs: List<Long>, framesEndMs: Long): Long {
        val cut = cutsMs.lastOrNull { it - startMs in ClipRepository.MIN_LOOP_MS..ClipRepository.MAX_LOOP_MS && it <= framesEndMs }
        return cut ?: minOf(startMs + 8_000L, framesEndMs).coerceAtLeast(startMs + 1)
    }

    /**
     * Where the crop sits (0 the picture's left edge, 1 its right: [VideoClip.cropX]) so that the faces are in an upright
     * band. Faces seen in one frame only are not trusted (the finder calls a lamp a face now and then): null then.
     */
    fun cropX(faces: List<Face>, bars: Bars): Float? {
        if (faces.map { it.timeMs }.distinct().size < 3) return null
        val across = (1f - bars.left - bars.right).takeIf { it > 0.1f } ?: 1f
        val left = if (across == 1f) 0f else bars.left
        // The middle by weight: large, sure faces count most, and one face off to the side doesn't pull it.
        val sorted = faces.map { ((it.x - left) / across).coerceIn(0f, 1f) to (it.size * it.confidence).coerceAtLeast(0.0001f) }.sortedBy { it.first }
        val half = sorted.sumOf { it.second.toDouble() } / 2
        var run = 0.0
        var middle = sorted.last().first
        for ((x, w) in sorted) { run += w; if (run >= half) { middle = x; break } }
        // One place for the whole loop only when the faces agree on it: a loop of several scenes with people here and
        // there (or a wall the finder took for a face) keeps the finder's guess, which a wrong move would only spoil.
        val near = sorted.filter { abs(it.first - middle) <= BAND / 2f }.sumOf { it.second.toDouble() }
        if (near < AGREE * 2 * half) return null
        return ((middle - BAND / 2f) / (1f - BAND)).coerceIn(0f, 1f)
    }

    /** The whole second look at a loop that starts at [startMs] (the frames' own clock). */
    fun judge(frames: List<Frame>, faces: List<Face>, startMs: Long, bars: Bars): Refined {
        val from = frames.filter { it.timeMs >= startMs }
        val step = if (from.size > 1) (from.last().timeMs - from.first().timeMs) / (from.size - 1) else 33L
        val framesEnd = (from.lastOrNull()?.timeMs ?: startMs) + step
        val cuts = cuts(from)
        // The longest calm loop: if the stretch up to the last cut flashes (a strobe late in it), an earlier cut is the end.
        var end = end(startMs, cuts, framesEnd)
        while (flashing(from.filter { it.timeMs < end })) {
            end = cuts.lastOrNull { it < end && it - startMs >= ClipRepository.MIN_LOOP_MS } ?: break
        }
        val shown = from.filter { it.timeMs < end }
        return Refined(end, cropX(faces.filter { it.timeMs in startMs until end }, bars), flashing(shown), cuts, faces.size)
    }
}

/** Reads a kept loop's frames and judges them ([ClipRefine]); null when this phone can't read the file. */
fun interface ClipRefiner {
    suspend fun refine(file: java.io.File, loop: VideoClip): ClipRefine.Refined?
}
