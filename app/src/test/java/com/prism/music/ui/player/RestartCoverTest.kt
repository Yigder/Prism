package com.prism.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 Canvas: the kept first frame over a video loop's restart, in the order things happen on the phone. */
class RestartCoverTest {
    @Test fun theFirstFrameDrawnIsKeptOnce() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        assertTrue("the loop's first frame", r.frameDrawn())
        assertFalse("the frames after it", r.frameDrawn())
        assertFalse(r.shown)
    }

    /**
     * Owner, 2026-11-13: the picture used to fade in over the loop's last quarter second, the first scene ghosting over
     * the last one. Nothing shows while the loop still plays: the picture goes up when it goes round, as a cut.
     */
    @Test fun nothingShowsBeforeTheLoopGoesRound() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        r.frameDrawn()
        // The whole loop plays, to its last frame.
        repeat(200) { r.frameDrawn(); assertFalse(r.shown) }
        r.wentRound()
        assertTrue(r.shown)
    }

    @Test fun nothingShowsUntilAFrameIsKept() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        r.wentRound()
        assertFalse(r.shown)
    }

    @Test fun itShowsAsTheLoopGoesRoundAndGoesWithTheRealFirstFrame() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        r.frameDrawn()
        r.wentRound(); assertTrue(r.shown)
        // A late frame of the old round, drawn after the loop went back: the picture stays (this is the standstill it hides).
        r.frameDrawn(); assertTrue(r.shown)
        r.firstFrameOut(); assertTrue("out of the decoder, not on screen yet", r.shown)
        assertFalse("kept already, so not asked for again", r.frameDrawn())
        assertFalse(r.shown)
    }

    @Test fun aClipPickedUpMidLoopKeepsItsFirstFrameNextTimeRound() {
        val r = RestartCover().apply { reset(atStart = false) }
        assertFalse(r.frameDrawn())
        r.wentRound(); assertFalse("no picture yet: this one restart is bare", r.shown)
        r.firstFrameOut()
        assertTrue(r.frameDrawn())
        r.wentRound(); assertTrue(r.shown)
    }

    @Test fun aFirstFrameThatNeverComesLetsGo() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        r.frameDrawn(); r.wentRound()
        r.giveUp()
        assertFalse(r.shown)
        // The decoder's first frame of a start that isn't a restart (a new surface) changes nothing.
        r.firstFrameOut(); assertFalse(r.frameDrawn())
    }

    @Test fun aNewClipStartsOver() {
        val r = RestartCover().apply { reset(atStart = true); firstFrameOut() }
        r.frameDrawn(); r.wentRound()
        r.reset(atStart = true)
        assertFalse(r.shown)
        assertFalse("a frame of the clip before, drawn late, is not this one's first", r.frameDrawn())
        r.firstFrameOut()
        assertTrue(r.frameDrawn())
    }
}
