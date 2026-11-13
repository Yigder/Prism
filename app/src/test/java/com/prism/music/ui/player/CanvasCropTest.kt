package com.prism.music.ui.player

import com.prism.music.data.canvas.Bars
import com.prism.music.data.canvas.ClipRepository
import com.prism.music.data.canvas.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 Canvas: where a loop sits in the view (bars cut off, the crop placed), and the loop inside its kept file. */
class CanvasCropTest {
    private val wide = 16f / 9f

    /** Where a point of the frame (as shares of its width and height) lands in the view. */
    private fun at(t: FloatArray, viewW: Float, viewH: Float, fx: Float, fy: Float) = (fx * viewW * t[0] + t[2]) to (fy * viewH * t[1] + t[3])

    @Test fun anAnimatedCoverIsCentredAsBefore() {
        // A square clip in a tall view: full height, the middle of its width.
        val t = cropTransform(1080f, 2340f, 1f, Bars.NONE, 0.5f)
        val (cx, cy) = at(t, 1080f, 2340f, 0.5f, 0.5f)
        assertEquals(540f, cx, 0.5f); assertEquals(1170f, cy, 0.5f)
        assertEquals(0f, at(t, 1080f, 2340f, 0f, 0f).second, 0.5f)
        assertEquals(2340f, at(t, 1080f, 2340f, 0f, 1f).second, 0.5f)
    }

    @Test fun aWideVideoKeepsItsShapeInATallView() {
        val t = cropTransform(1080f, 2340f, wide, Bars.NONE, 0.5f)
        val frameW = 1080f * t[0]; val frameH = 2340f * t[1]
        assertEquals(wide, frameW / frameH, 0.001f)
        assertEquals(2340f, frameH, 0.5f)
    }

    @Test fun theCropFollowsTheSubjectAcross() {
        val left = cropTransform(1080f, 2340f, wide, Bars.NONE, 0f)
        val right = cropTransform(1080f, 2340f, wide, Bars.NONE, 1f)
        assertEquals("left edge of the picture at the view's left", 0f, at(left, 1080f, 2340f, 0f, 0.5f).first, 0.5f)
        assertEquals("right edge of the picture at the view's right", 1080f, at(right, 1080f, 2340f, 1f, 0.5f).first, 0.5f)
    }

    @Test fun barsAreCutOffOnEverySide() {
        // A 4:3 picture inside 16:9 (side bars an eighth each), shown square on the cover.
        val side = Bars(left = 0.125f, right = 0.125f)
        val t = cropTransform(900f, 900f, wide, side, 0.5f)
        assertTrue("left bar is off screen", at(t, 900f, 900f, 0.125f, 0.5f).first <= 0.5f)
        assertTrue("right bar is off screen", at(t, 900f, 900f, 0.875f, 0.5f).first >= 899.5f)
        // A film with bars top and bottom, full screen: the picture's own top is the view's top.
        val film = Bars(top = 0.12f, bottom = 0.12f)
        val f = cropTransform(1080f, 2340f, wide, film, 0.5f)
        assertEquals(0f, at(f, 1080f, 2340f, 0.5f, 0.12f).second, 0.5f)
        assertEquals(2340f, at(f, 1080f, 2340f, 0.5f, 0.88f).second, 0.5f)
        assertEquals("shape kept", wide, (1080f * f[0]) / (2340f * f[1]), 0.001f)
    }

    @Test fun barsThatWouldEatThePictureAreIgnored() {
        val t = cropTransform(1080f, 2340f, wide, Bars(left = 0.5f, right = 0.45f), 0.5f)
        assertEquals(cropTransform(1080f, 2340f, wide, Bars.NONE, 0.5f).toList(), t.toList())
    }

    // The loop inside its kept file (ClipRepository.loopIn): the file holds whole fragments, the loop starts on a key frame.
    private val want = VideoClip("v", 66_000, 74_000, 1080)

    @Test fun theLoopStartsOnTheNearestKeyFrame() {
        val loop = ClipRepository.loopIn(want, fileStartMs = 57_891, fileEndMs = 75_075, keyFramesMs = listOf(57_891, 64_898, 68_443))
        assertEquals(64_898, loop.startMs)
        assertEquals(72_898, loop.endMs)
        assertEquals(57_891, loop.fileStartMs)
    }

    @Test fun aKeyFrameTooNearTheEndIsNotAStart() {
        // 71 s is nearest, but leaves under five seconds: the one before it is taken.
        val loop = ClipRepository.loopIn(want.copy(startMs = 71_500), 57_891, 75_075, listOf(57_891, 64_898, 71_000))
        assertEquals(64_898, loop.startMs)
        assertTrue(loop.endMs <= 75_075)
    }

    @Test fun theLoopIsFiveToNineSecondsAndEndsInsideTheFile() {
        val long = ClipRepository.loopIn(want.copy(endMs = 96_000), 57_891, 75_075, listOf(57_891, 64_898))
        assertEquals(9_000, long.lengthMs)
        val short = ClipRepository.loopIn(want.copy(endMs = 67_000), 57_891, 75_075, listOf(57_891, 64_898))
        assertEquals(5_000, short.lengthMs)
        val tail = ClipRepository.loopIn(want, 57_891, 70_500, listOf(57_891, 64_898))
        assertEquals(70_500, tail.endMs)
    }

    @Test fun aLoopCutToItsRealFramesKeepsItsEnd() {
        val refined = VideoClip("v", 64_898, 70_311, 1080, refined = true)
        val same = ClipRepository.loopIn(refined, 57_891, 75_075, listOf(57_891, 64_898))
        assertEquals(70_311, same.endMs); assertTrue(same.refined)
        // The file's key frames moved (another height): the cut no longer holds, so it is a guess again.
        val moved = ClipRepository.loopIn(refined, 57_891, 75_075, listOf(57_891, 63_000))
        assertFalse(moved.refined)
    }

    @Test fun anotherPartOfTheSameFileIsAnotherClipToThePlayer() {
        val f = java.io.File("/data/x/abc.720.136.57891-75075.mp4")
        val a = ClipRepository.fileUrl(f, want); val b = ClipRepository.fileUrl(f, want.copy(startMs = 57_891))
        assertTrue(a.startsWith("file://")); assertFalse(a == b)
    }
}
