package com.prism.music.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame judge on made-up frames: the edge cases real storyboards rarely show cleanly. */
class FrameJudgeTest {
    private fun frame(f: (x: Int, y: Int) -> Int) = Grid(IntArray(Grid.W * Grid.H) { f(it % Grid.W, it / Grid.W).coerceIn(0, 255) })

    /** Frame [i] of a video that moves everywhere, brightness around [base]. */
    private fun moving(i: Int, base: Int = 100, swing: Int = 60) = frame { x, y -> base + ((x * 7 + y * 13 + i * 29) % swing) - swing / 2 }

    private fun video(n: Int, f: (Int) -> Grid) = List(n, f)

    private val interval = 2_000L
    private val length = 240_000L

    @Test fun allBlackIsStillAndHasNoBars() {
        val black = video(120) { frame { _, _ -> 0 } }
        assertEquals(Bars.NONE, FrameJudge.bars(black))
        assertEquals(FrameJudge.Verdict.Still, FrameJudge.judge(black, interval, length, null, null))
    }

    @Test fun twoStillShotsWithOneCutAreStill() {
        val a = frame { x, y -> 50 + x * 3 + y }
        val b = frame { x, y -> 200 - x * 2 - y * 3 }
        val frames = video(120) { if (it < 60) a else b }
        assertEquals(FrameJudge.Verdict.Still, FrameJudge.judge(frames, interval, length, null, null))
    }

    @Test fun aDarkButMovingVideoIsNotStill() {
        // Brightness 8 to 37 (a night scene), changing by about 14 a cell between frames.
        val frames = video(120) { i -> frame { x, y -> 8 + (x * 7 + y * 13 + i * 11) % 30 } }
        assertTrue(FrameJudge.judge(frames, interval, length, null, null) is FrameJudge.Verdict.Loop)
    }

    @Test fun aStillMiddleInsideAMovingFrameIsStill() {
        // A cover in the middle, an ornament turning around it.
        val frames = video(120) { i -> frame { x, y -> if (x in 5..26 && y in 3..14) 90 + x else 60 + (x * 11 + y * 5 + i * 23) % 120 } }
        assertTrue(FrameJudge.stillShare(frames, Bars.NONE) >= 0.95)
        assertEquals(FrameJudge.Verdict.Still, FrameJudge.judge(frames, interval, length, null, null))
    }

    @Test fun barsAreFoundOnEverySide() {
        val frames = video(60) { i -> frame { x, y -> if (x < 4 || x >= 28 || y < 2 || y >= 17) 3 else moving(i)[x, y] } }
        assertEquals(Bars(left = 4f / 32, top = 2f / 18, right = 4f / 32, bottom = 1f / 18), FrameJudge.bars(frames))
    }

    @Test fun fadesToBlackDoNotMakeBars() {
        // Every third frame black: the lit frames have no bars.
        val frames = video(60) { i -> if (i % 3 == 0) frame { _, _ -> 0 } else moving(i) }
        assertEquals(Bars.NONE, FrameJudge.bars(frames))
    }

    @Test fun aMostlyDarkPictureIsNotHalfBars() {
        // Only a narrow lit strip in the middle: that's a dark video, not bars wider than the picture.
        val frames = video(60) { i -> frame { x, y -> if (x in 14..17) moving(i)[x, y] else 2 } }
        assertEquals(0f, FrameJudge.bars(frames).left)
        assertEquals(0f, FrameJudge.bars(frames).right)
    }

    @Test fun stillAndNearTheEndsWindowsGetNoScore() {
        // Moving only from 60 s to 120 s.
        val frames = video(120) { i -> if (i in 30..60) moving(i) else frame { x, y -> 80 + x + y } }
        val ws = FrameJudge.windows(frames, interval, length, Bars.NONE)
        assertTrue(ws.isNotEmpty())
        assertTrue(ws.all { it.startMs >= 52_000 && it.endMs <= 128_000 })
        assertTrue(ws.all { it.startMs >= FrameJudge.HEAD_MS && it.endMs <= length - FrameJudge.TAIL_MS })
    }

    @Test fun someMovementScoresBetterThanFranticOrNone() {
        val calm = video(120) { moving(it, swing = 20) }
        val ideal = video(120) { moving(it, swing = 80) }
        val frantic = video(120) { i -> frame { x, y -> if ((x + y + i) % 2 == 0) 255 else 0 } }
        fun top(v: List<Grid>) = FrameJudge.windows(v, interval, length, Bars.NONE).maxOf { it.score }
        assertTrue(top(ideal) > top(calm))
        assertTrue(top(ideal) > top(frantic))
    }

    @Test fun aBlackScreenScoresNothing() {
        // Frames 60 to 70 nearly black, still moving a little (so they are scored, not left out as still).
        val frames = video(120) { i -> if (i in 60..70) moving(i, base = 6, swing = 12) else moving(i) }
        val ws = FrameJudge.windows(frames, interval, length, Bars.NONE)
        val dark = ws.filter { it.startMs in 122_000..130_000 }
        assertTrue(dark.isNotEmpty())
        assertTrue(dark.all { it.score < ws.maxOf { w -> w.score } * 0.2 })
    }

    @Test fun aPeakInTheFirstSecondsIsSkipped() {
        val frames = video(120) { moving(it) }
        val withPeak = FrameJudge.judge(frames, interval, length, peakMs = 3_000, chorusMs = null)
        val without = FrameJudge.judge(frames, interval, length, peakMs = null, chorusMs = null)
        assertEquals(without, withPeak)
        val l = without as FrameJudge.Verdict.Loop
        assertTrue("${l.startMs}", l.startMs in length / 3..length * 2 / 3)
    }

    @Test fun aPeakLaterOnPicksTheWindowThere() {
        val l = FrameJudge.judge(video(120) { moving(it) }, interval, length, peakMs = 170_000, chorusMs = 60_000) as FrameJudge.Verdict.Loop
        assertTrue("${l.startMs}", l.startMs in 166_000..178_000)
    }

    @Test fun thePeakWinsOverTheChorusAndTheChorusOverTheMiddle() {
        val frames = video(120) { moving(it) }
        val byChorus = FrameJudge.judge(frames, interval, length, peakMs = null, chorusMs = 40_000) as FrameJudge.Verdict.Loop
        assertTrue("${byChorus.startMs}", byChorus.startMs in 30_000..50_000)
    }

    @Test fun aPeakOverABlackScreenGivesWay() {
        val frames = video(120) { i -> if (i in 80..92) frame { _, _ -> 2 } else moving(i) }
        val l = FrameJudge.judge(frames, interval, length, peakMs = 165_000, chorusMs = null) as FrameJudge.Verdict.Loop
        assertTrue("${l.startMs}", l.startMs !in 156_000..186_000)
    }

    @Test fun runnersUpAreBestFirstAndApart() {
        val ws = listOf(
            FrameJudge.Window(20_000, 28_000, 0.9), FrameJudge.Window(22_000, 30_000, 0.95), FrameJudge.Window(60_000, 68_000, 0.5),
            FrameJudge.Window(70_000, 78_000, 0.8), FrameJudge.Window(100_000, 108_000, 0.7), FrameJudge.Window(140_000, 148_000, 0.6),
            FrameJudge.Window(180_000, 188_000, 0.65), FrameJudge.Window(200_000, 208_000, 0.0),
        )
        val chosen = ws[1]
        assertEquals(listOf(70_000L, 100_000L, 180_000L, 140_000L), FrameJudge.runnersUp(ws, chosen))
    }

    @Test fun equalScoresChooseTheEarlierWindow() {
        val ws = listOf(FrameJudge.Window(90_000, 98_000, 0.5), FrameJudge.Window(80_000, 88_000, 0.5))
        assertEquals(80_000L, FrameJudge.choose(ws, length, null, null)!!.startMs)
        assertNull(FrameJudge.choose(emptyList(), length, null, null))
    }

    @Test fun theCropFollowsTheSubject() {
        // Detail and movement on the left third, a flat grey right.
        val left = video(5) { i -> frame { x, y -> if (x < 10) (x * 37 + y * 11 + i * 53) % 200 else 90 } }
        assertTrue(FrameJudge.cropX(left, Bars.NONE) < 0.2f)
        val right = video(5) { i -> frame { x, y -> if (x > 22) (x * 37 + y * 11 + i * 53) % 200 else 90 } }
        assertTrue(FrameJudge.cropX(right, Bars.NONE) > 0.8f)
    }

    @Test fun theCropStaysCentredWhenNoSideLeads() {
        assertEquals(0.5f, FrameJudge.cropX(video(5) { moving(it) }, Bars.NONE))
        assertEquals(0.5f, FrameJudge.cropX(video(5) { frame { _, _ -> 90 } }, Bars.NONE))
        assertEquals(0.5f, FrameJudge.cropX(emptyList(), Bars.NONE))
    }

    @Test fun theCropIsMeasuredInsideTheBars() {
        // Side bars, the subject at the picture's left edge (just inside the left bar).
        val bars = Bars(left = 0.125f, right = 0.125f)
        val frames = video(5) { i -> frame { x, y -> if (x < 4 || x >= 28) 0 else if (x < 11) (x * 37 + y * 11 + i * 53) % 200 else 90 } }
        assertEquals(0f, FrameJudge.cropX(frames, bars))
    }

    @Test fun noFramesMeansTheMiddle() {
        assertEquals(FrameJudge.Verdict.Loop(116_000, 124_000, 0.5f, Bars.NONE, emptyList()), FrameJudge.noFrames(240_000))
        assertEquals(FrameJudge.Verdict.Loop(0, 5_000, 0.5f, Bars.NONE, emptyList()), FrameJudge.noFrames(5_000))
    }

    @Test fun storyboardSpecsAreRead() {
        val spec = "https://i.ytimg.com/sb/abc/storyboard3_L\$L/\$N.jpg?sqp=q|48#27#100#10#10#0#default#rs\$x|80#45#120#10#10#2000#M\$M#rs\$y|160#90#120#5#5#2000#M\$M#rs\$z"
        val b = Storyboard.parse(spec)!!
        assertEquals(160, b.frameW); assertEquals(90, b.frameH); assertEquals(120, b.count); assertEquals(5, b.sheets)
        assertEquals("https://i.ytimg.com/sb/abc/storyboard3_L2/M0.jpg?sqp=q&sigh=rs%24z", b.sheetUrl(0))
        // A level without an interval spreads its frames over the video.
        assertEquals(2_400L, Storyboard.parse("u|48#27#100#10#10#0#default#rs\$x", lengthMs = 240_000)!!.intervalMs)
        assertNull(Storyboard.parse("u|48#27#100#10#10#0#default#rs\$x"))
        assertNull(Storyboard.parse(null))
        assertNull(Storyboard.parse("garbage"))
        assertNull(Storyboard.parse("u|1#2#3"))
    }

    @Test fun sheetsAreCutIntoGrids() {
        // A 2x2 sheet of 64x36 frames, each frame one grey value; the last sheet holds three frames.
        val w = 128; val h = 72
        val luma = IntArray(w * h) { p -> val x = p % w; val y = p / w; if (x < 64) (if (y < 36) 10 else 30) else (if (y < 36) 20 else 40) }
        val b = Storyboard.parse("u|64#36#7#2#2#1000#M\$M#s")!!
        val out = ArrayList<Grid>()
        b.cut(Sheet(w, h, luma), 0, out)
        b.cut(Sheet(w, h, luma), 1, out)
        assertEquals(listOf(10, 20, 30, 40, 10, 20, 30), out.map { it[5, 5] })
        assertTrue(out.all { g -> g.v.toSet().size == 1 })
    }

    @Test fun gridsSurviveText() {
        val g = moving(3)
        assertTrue(g.v.contentEquals(Grid.decode(Grid.encode(g)).v))
    }
}
