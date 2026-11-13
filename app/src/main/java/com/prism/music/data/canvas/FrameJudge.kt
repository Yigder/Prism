package com.prism.music.data.canvas

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Judges a whole video from its preview frames ([Grid]s, one every [Storyboard.intervalMs]): plan-canvas §3.3 and
 * §3.4, with what the by-eye round found (.claude/canvas-study/eye/verdicts.md). Pure functions: the same frames always
 * give the same answer.
 */
object FrameJudge {
    /** A loop's length as first guessed; the refine pass (4.3) moves its ends onto real cuts, 5 to 9 s apart. */
    const val WINDOW_MS = 8_000L
    /** Logos, title cards and the intro everyone replays: no loop starts this early. */
    const val HEAD_MS = 12_000L
    /** Credits and end cards: no loop ends this late. */
    const val TAIL_MS = 15_000L
    /** "Pick another part": runners-up at least this far from the chosen part and from each other. */
    const val APART_MS = 15_000L
    const val RUNNERS_UP = 4

    /** A cell this dark or darker is black (JPEG noise keeps a bar's cells a little above 0). */
    internal const val BLACK = 24
    /** A row or column is a bar when it is black in at least this share of the frames that show anything. */
    internal const val BAR_SHARE = 0.9
    /** A frame "shows anything" when one of its cells is at least this bright. */
    internal const val LIT = 48
    /** Average change per cell between two frames below which nothing happened (JPEG noise is about 1). */
    internal const val STEP_MOVE = 3.0
    /** A video is a still when at least this share of its steps show no change in the middle of the frame. */
    internal const val STILL_SHARE = 0.6
    /** A window whose steps are this still has no score. */
    internal const val WINDOW_STILL_SHARE = 0.75
    /** Movement (average change per cell per step) that scores best; above it the picture gets busier. */
    internal const val IDEAL_MOVE = 18.0
    /** Movement this high or more is frantic: half the score. */
    internal const val FRANTIC_MOVE = 60.0
    /** Brightness (0..255) below which a window is a black screen, and from which it counts fully. */
    internal const val DARK = 16.0
    internal const val BRIGHT = 64.0
    /** The share of a 16:9 picture's width a full-height upright crop keeps (9:16 inside 16:9, rounded up). */
    internal const val CROP_SHARE = 0.32
    /** The subject's side must hold this much more detail and movement than the centre to move the crop off it. */
    internal const val CROP_LEAD = 1.15

    /** A scored stretch of the video. */
    data class Window(val startMs: Long, val endMs: Long, val score: Double)

    /** What the frames say: a still video, or the chosen loop with its framing and runners-up. */
    sealed interface Verdict {
        data object Still : Verdict
        data class Loop(val startMs: Long, val endMs: Long, val cropX: Float, val bars: Bars, val otherParts: List<Long>) : Verdict
    }

    /**
     * Everything at once: bars, the still check, window scores, the chosen window ([peakMs] of the video's "most
     * replayed" curve first, then the song's [chorusMs], then the middle third) and where the crop sits.
     */
    fun judge(frames: List<Grid>, intervalMs: Long, lengthMs: Long, peakMs: Long?, chorusMs: Long?): Verdict {
        val length = maxOf(lengthMs, frames.size * intervalMs)
        val bars = bars(frames)
        if (isStill(frames, bars)) return Verdict.Still
        val windows = windows(frames, intervalMs, length, bars)
        val chosen = choose(windows, length, peakMs, chorusMs) ?: return Verdict.Still
        val first = (chosen.startMs / intervalMs).toInt()
        val last = ((chosen.endMs + intervalMs - 1) / intervalMs).toInt().coerceAtMost(frames.size - 1)
        val crop = cropX(frames.subList(first, last + 1), bars)
        return Verdict.Loop(chosen.startMs, chosen.endMs, crop, bars, runnersUp(windows, chosen))
    }

    /** The middle of a video with no preview frames (plan §3.4's fallback): centred, no bars, no runners-up. */
    fun noFrames(lengthMs: Long): Verdict.Loop {
        val window = minOf(WINDOW_MS, lengthMs)
        val start = ((lengthMs - window) / 2).coerceAtLeast(0)
        return Verdict.Loop(start, start + window, 0.5f, Bars.NONE, emptyList())
    }

    // ---------------------------------------------------------------- Bars

    /** The inside of the picture, in grid cells: x in [x0, x1), y in [y0, y1). */
    internal class Area(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    internal fun area(bars: Bars) = Area(
        (bars.left * Grid.W).roundToInt(), (bars.top * Grid.H).roundToInt(),
        Grid.W - (bars.right * Grid.W).roundToInt(), Grid.H - (bars.bottom * Grid.H).roundToInt(),
    )

    /**
     * Black bars baked into the picture, on all four sides (11 in 100 sampled videos are 4:3 inside 16:9, so side bars
     * are as common as a film's top and bottom ones): the rows and columns from each edge that are black in nearly
     * every frame that shows anything. Fades to black don't count either way. A side that would leave less than half
     * the picture is a dark video, not bars.
     */
    fun bars(frames: List<Grid>): Bars {
        val lit = frames.filter { f -> f.v.any { it >= LIT } }
        if (lit.size < 3) return Bars.NONE
        fun blackCol(f: Grid, x: Int) = (0 until Grid.H).count { f[x, it] <= BLACK } >= Grid.H * 9 / 10
        fun blackRow(f: Grid, y: Int) = (0 until Grid.W).count { f[it, y] <= BLACK } >= Grid.W * 9 / 10
        val need = lit.size * BAR_SHARE
        val col = BooleanArray(Grid.W) { x -> lit.count { blackCol(it, x) } >= need }
        val row = BooleanArray(Grid.H) { y -> lit.count { blackRow(it, y) } >= need }
        var left = 0; while (left < Grid.W && col[left]) left++
        var right = 0; while (right < Grid.W - left && col[Grid.W - 1 - right]) right++
        var top = 0; while (top < Grid.H && row[top]) top++
        var bottom = 0; while (bottom < Grid.H - top && row[Grid.H - 1 - bottom]) bottom++
        if (left + right > Grid.W / 2) { left = 0; right = 0 }
        if (top + bottom > Grid.H / 2) { top = 0; bottom = 0 }
        return Bars(left.toFloat() / Grid.W, top.toFloat() / Grid.H, right.toFloat() / Grid.W, bottom.toFloat() / Grid.H)
    }

    // ---------------------------------------------------------------- Still

    /** The middle of the picture inside the bars: its centre 60% across and down. */
    internal fun middle(a: Area): Area {
        val dx = (a.w * 0.2).roundToInt(); val dy = (a.h * 0.2).roundToInt()
        return Area(a.x0 + dx, a.y0 + dy, (a.x1 - dx).coerceAtLeast(a.x0 + dx + 1), (a.y1 - dy).coerceAtLeast(a.y0 + dy + 1))
    }

    /** The average change per cell inside [a] from frame [p] to frame [q]. */
    internal fun diff(p: Grid, q: Grid, a: Area): Double {
        var sum = 0L
        for (y in a.y0 until a.y1) for (x in a.x0 until a.x1) sum += abs(p[x, y] - q[x, y])
        return sum.toDouble() / (a.w * a.h)
    }

    /** The share of steps that show no change in the middle of the picture. */
    fun stillShare(frames: List<Grid>, bars: Bars): Double {
        if (frames.size < 2) return 1.0
        val m = middle(area(bars))
        return (1 until frames.size).count { diff(frames[it - 1], frames[it], m) < STEP_MOVE }.toDouble() / (frames.size - 1)
    }

    /**
     * A still upload: most steps show no change in the middle of the picture. The middle, not the whole frame: label
     * "BGM" uploads turn an ornament slowly around a still cover, and that passed a whole-frame measure.
     */
    fun isStill(frames: List<Grid>, bars: Bars): Boolean = stillShare(frames, bars) >= STILL_SHARE

    // ---------------------------------------------------------------- Windows

    /**
     * Every window of about [WINDOW_MS], one per frame, with a score from its movement inside the bars (some, not
     * frantic), its brightness (not a black screen) and its distance from the video's first [HEAD_MS] and last
     * [TAIL_MS]. A still window gets no score (it is left out).
     */
    fun windows(frames: List<Grid>, intervalMs: Long, lengthMs: Long, bars: Bars): List<Window> {
        if (frames.size < 2 || intervalMs <= 0) return emptyList()
        val a = area(bars)
        val m = middle(a)
        val steps = (WINDOW_MS.toDouble() / intervalMs).roundToInt().coerceIn(1, frames.size - 1)
        val move = DoubleArray(frames.size - 1) { diff(frames[it], frames[it + 1], a) }
        val moveMiddle = DoubleArray(frames.size - 1) { diff(frames[it], frames[it + 1], m) }
        val light = DoubleArray(frames.size) { i -> mean(frames[i], a) }
        // A short video can't keep clear of both ends: then the ends only cost.
        val roomy = lengthMs >= HEAD_MS + TAIL_MS + 3 * WINDOW_MS
        val out = ArrayList<Window>()
        for (i in 0..frames.size - 1 - steps) {
            val start = i * intervalMs
            val end = minOf(start + WINDOW_MS, lengthMs)
            val nearEnds = start < HEAD_MS || end > lengthMs - TAIL_MS
            if (nearEnds && roomy) continue
            val still = (i until i + steps).count { moveMiddle[it] < STEP_MOVE }
            if (still >= steps * WINDOW_STILL_SHARE) continue
            val motion = (i until i + steps).sumOf { move[it] } / steps
            val bright = (i..i + steps).sumOf { light[it] } / (steps + 1)
            val dark = (i..i + steps).count { light[it] < DARK }.toDouble() / (steps + 1)
            val moveScore = if (motion <= IDEAL_MOVE) motion / IDEAL_MOVE
            else 1.0 - 0.5 * ((motion - IDEAL_MOVE) / (FRANTIC_MOVE - IDEAL_MOVE)).coerceAtMost(1.0)
            val brightScore = ((bright - DARK) / (BRIGHT - DARK)).coerceIn(0.0, 1.0)
            val score = moveScore * brightScore * (1.0 - dark) * (if (nearEnds) 0.5 else 1.0)
            out += Window(start, end, score)
        }
        return out
    }

    private fun mean(g: Grid, a: Area): Double {
        var sum = 0L
        for (y in a.y0 until a.y1) for (x in a.x0 until a.x1) sum += g[x, y]
        return sum.toDouble() / (a.w * a.h)
    }

    // ---------------------------------------------------------------- Which window

    /** Within this of the "most replayed" peak (before / after it), a window counts as near. */
    private const val PEAK_BEFORE_MS = 4_000L
    private const val PEAK_AFTER_MS = 8_000L
    /** Within this of the chorus, either way. */
    private const val CHORUS_NEAR_MS = 10_000L
    /** A window near the peak or the chorus must score at least this share of the video's best to be taken. */
    private const val GOOD_ENOUGH = 0.3

    /** Best first; equal scores: the earlier one (so the answer never depends on anything but the frames). */
    private val best = compareByDescending<Window> { it.score }.thenBy { it.startMs }

    /**
     * The loop's window: the best-scoring one near the video's most-replayed [peakMs] (a peak in the first [HEAD_MS]
     * is the intro everyone replays: ignored), else the best near the song's [chorusMs], else the best in the middle
     * third, else the best anywhere. Null when no window scores.
     */
    fun choose(windows: List<Window>, lengthMs: Long, peakMs: Long?, chorusMs: Long?): Window? {
        if (windows.isEmpty()) return null
        val top = windows.minWith(best).score
        fun pick(near: (Window) -> Boolean) = windows.filter { near(it) && it.score > 0 && it.score >= top * GOOD_ENOUGH }.minWithOrNull(best)
        peakMs?.takeIf { it >= HEAD_MS }?.let { p -> pick { it.startMs in (p - PEAK_BEFORE_MS)..(p + PEAK_AFTER_MS) }?.let { return it } }
        chorusMs?.let { c -> pick { abs(it.startMs - c) <= CHORUS_NEAR_MS }?.let { return it } }
        return pick { it.startMs >= lengthMs / 3 && it.startMs <= lengthMs * 2 / 3 } ?: windows.minWith(best)
    }

    /** Up to [RUNNERS_UP] other good parts' starts, best first, each [APART_MS] from the chosen one and each other. */
    fun runnersUp(windows: List<Window>, chosen: Window): List<Long> {
        val taken = mutableListOf(chosen.startMs)
        val out = ArrayList<Long>()
        for (w in windows.filter { it.score > 0 }.sortedWith(best)) {
            if (out.size >= RUNNERS_UP) break
            if (taken.any { abs(it - w.startMs) < APART_MS }) continue
            taken += w.startMs; out += w.startMs
        }
        return out
    }

    // ---------------------------------------------------------------- Crop

    /**
     * Where an upright crop sits across the picture inside the bars, from the loop's own frames: the band about a
     * third of the width wide (what a full-height upright crop of a 16:9 picture keeps) holding the most detail and
     * movement. As a share of the room the crop can move in: 0 at the left edge, 0.5 centred, 1 at the right edge.
     * 0.5 when no side clearly leads (the refine pass sets the real crop on real frames).
     */
    fun cropX(frames: List<Grid>, bars: Bars): Float {
        if (frames.isEmpty()) return 0.5f
        val a = area(bars)
        if (a.w < 4) return 0.5f
        val weight = DoubleArray(a.w)
        for ((i, f) in frames.withIndex()) {
            for (y in a.y0 until a.y1) for (x in a.x0 until a.x1) {
                val k = x - a.x0
                if (x + 1 < a.x1) {
                    val d = abs(f[x + 1, y] - f[x, y]).toDouble()
                    weight[k] += d / 2; weight[k + 1] += d / 2
                }
                if (i > 0) weight[k] += abs(f[x, y] - frames[i - 1][x, y]).toDouble()
            }
        }
        val band = (a.w * CROP_SHARE).roundToInt().coerceIn(1, a.w)
        val room = a.w - band
        if (room <= 0) return 0.5f
        fun sum(s: Int) = (s until s + band).sumOf { weight[it] }
        val centre = room / 2
        val centreSum = sum(centre)
        // Ties go to the band nearer the centre.
        val bestStart = (0..room).minWith(compareByDescending<Int> { sum(it) }.thenBy { abs(it * 2 - room) })
        if (sum(bestStart) <= centreSum * CROP_LEAD) return 0.5f
        return bestStart.toFloat() / room
    }
}
