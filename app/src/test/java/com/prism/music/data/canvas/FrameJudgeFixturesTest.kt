package com.prism.music.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame judge on real storyboards of videos from the by-eye round (.claude/canvas-study/eye/), saved by
 * [StoryboardFixturesLiveTest] as canvas/<name>.txt: a header line (video id, interval, length, the "most replayed"
 * peak or -1), then one 32x18 grid a frame.
 */
class FrameJudgeFixturesTest {
    class Fixture(val id: String, val intervalMs: Long, val lengthMs: Long, val peakMs: Long?, val frames: List<Grid>)

    private fun load(name: String): Fixture {
        val lines = javaClass.getResourceAsStream("/canvas/$name.txt")!!.bufferedReader().readLines()
        val head = lines.first().removePrefix("#").trim().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        return Fixture(
            head.getValue("id"), head.getValue("interval").toLong(), head.getValue("length").toLong(),
            head.getValue("peak").toLong().takeIf { it >= 0 }, lines.drop(1).filter { it.isNotBlank() }.map(Grid::decode),
        )
    }

    private fun judge(f: Fixture, chorusMs: Long? = null) = FrameJudge.judge(f.frames, f.intervalMs, f.lengthMs, f.peakMs, chorusMs)

    private fun loop(name: String): FrameJudge.Verdict.Loop = judge(load(name)) as FrameJudge.Verdict.Loop

    @Test fun aStillAudioUploadIsStill() {
        // #2 "Shararat - Audio": the film's poster under the song.
        assertEquals(FrameJudge.Verdict.Still, judge(load("audio-02")))
    }

    @Test fun aStillOfficialVisualizerIsStill() {
        // #32 "E85 [Official Visualizer]": passes the title words, the frames turn it away.
        assertEquals(FrameJudge.Verdict.Still, judge(load("visualizer-32")))
    }

    @Test fun aStillCoverInsideATurningOrnamentIsStill() {
        // #62 and #87, label "BGM" uploads: the whole frame changes (the ornament), the middle does not.
        for (name in listOf("bgm-62", "bgm-87")) {
            val f = load(name)
            assertEquals(name, FrameJudge.Verdict.Still, judge(f))
            assertTrue(name, FrameJudge.stillShare(f.frames, FrameJudge.bars(f.frames)) >= 0.95)
        }
    }

    @Test fun movingVideosAreNotStill() {
        for (name in MOVING) {
            val f = load(name)
            assertTrue(name, FrameJudge.stillShare(f.frames, FrameJudge.bars(f.frames)) < 0.3)
            assertTrue(name, judge(f) is FrameJudge.Verdict.Loop)
        }
    }

    @Test fun aFourThreeVideoInsideSixteenNineHasSideBars() {
        // #41 "Every Breath You Take": 4:3 in a 16:9 frame, an eighth of the width each side.
        assertEquals(Bars(left = 0.125f, right = 0.125f), FrameJudge.bars(load("sidebars-41").frames))
    }

    @Test fun aWideFilmHasTopAndBottomBars() {
        // "Vaaroon Forever" (coverage.csv, India chart: two black rows of nine).
        assertEquals(Bars(top = 2f / 18, bottom = 2f / 18), FrameJudge.bars(load("topbars-vaaroon").frames))
    }

    @Test fun aNormalVideoHasNoBars() {
        assertEquals(Bars.NONE, FrameJudge.bars(load("moving-35").frames))
    }

    @Test fun theLoopSitsAtTheMostReplayedPeak() {
        // #35 "Dai Dai": the peak is at 163.9 s.
        val f = load("moving-35")
        val l = loop("moving-35")
        assertTrue("${l.startMs}", l.startMs in f.peakMs!! - 4_000..f.peakMs!! + 8_000)
        assertEquals(160_000L, l.startMs)
        assertEquals(FrameJudge.WINDOW_MS, l.endMs - l.startMs)
        assertTrue(l.otherParts.size in 3..4)
        assertTrue((listOf(l.startMs) + l.otherParts).let { s -> s.all { a -> s.all { b -> a == b || kotlin.math.abs(a - b) >= FrameJudge.APART_MS } } })
    }

    @Test fun aPeakInTheIntroIsSkipped() {
        // #41's curve peaks at 6.9 s (the intro everyone replays): the loop comes from the middle third instead.
        val f = load("sidebars-41")
        assertTrue(f.peakMs!! < FrameJudge.HEAD_MS)
        val l = loop("sidebars-41")
        assertTrue("${l.startMs}", l.startMs in f.lengthMs / 3..f.lengthMs * 2 / 3)
        assertEquals(l, FrameJudge.judge(f.frames, f.intervalMs, f.lengthMs, null, null))
    }

    @Test fun noLoopTouchesTheEnds() {
        for (name in MOVING) {
            val f = load(name)
            val l = loop(name)
            assertTrue(name, l.startMs >= FrameJudge.HEAD_MS && l.endMs <= f.lengthMs - FrameJudge.TAIL_MS)
            assertTrue(name, l.otherParts.all { it >= FrameJudge.HEAD_MS && it + FrameJudge.WINDOW_MS <= f.lengthMs - FrameJudge.TAIL_MS })
        }
    }

    @Test fun theSameFramesGiveTheSameLoopEveryRun() {
        for (name in listOf("moving-35", "dark-79", "topbars-vaaroon")) {
            val a = judge(load(name))
            repeat(3) { assertEquals(name, a, judge(load(name))) }
        }
    }

    @Test fun theChorusDecidesWhenThereIsNoPeak() {
        val f = load("moving-35")
        val l = FrameJudge.judge(f.frames, f.intervalMs, f.lengthMs, null, 60_000L) as FrameJudge.Verdict.Loop
        assertTrue("${l.startMs}", l.startMs in 50_000L..70_000L)
    }

    @Test fun theDarkVideoIsReallyDark() {
        // #79 "Blinding Lights", mostly night: it must pass on movement, not on brightness.
        val f = load("dark-79")
        assertTrue(f.frames.map { it.v.average() }.average() < 40)
    }

    @Test fun everySavedFixtureIsUsed() {
        assertEquals(StoryboardFixturesLiveTest.VIDEOS.map { it.first }.toSet(), (MOVING + listOf("audio-02", "visualizer-32", "bgm-62", "bgm-87")).toSet())
    }

    private companion object {
        val MOVING = listOf("moving-35", "dark-79", "sidebars-41", "topbars-vaaroon")
    }
}
