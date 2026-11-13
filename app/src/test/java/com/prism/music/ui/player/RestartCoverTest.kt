package com.prism.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 Canvas: the kept first frame over a video loop's restart, in the order things happen on the phone. */
class RestartCoverTest {
    @Test fun theFirstFrameDrawnIsKeptOnce() {
        val r = RestartCover().apply { reset(atStart = true) }
        assertTrue("the loop's first frame", r.frameDrawn())
        assertFalse("the frames after it", r.frameDrawn())
        assertFalse(r.shown)
    }

    @Test fun nothingShowsUntilAFrameIsKept() {
        val r = RestartCover().apply { reset(atStart = true) }
        r.nearEnd()
        assertFalse(r.shown)
    }

    @Test fun itShowsNearTheEndAndGoesWithTheRealFirstFrame() {
        val r = RestartCover().apply { reset(atStart = true) }
        r.frameDrawn()
        r.nearEnd(); assertTrue(r.shown)
        // The last frames of this round are still being drawn.
        r.frameDrawn(); assertTrue(r.shown)
        r.wentRound()
        // A late frame of the old round, drawn after the loop went back: the picture stays (this is the standstill it hides).
        r.frameDrawn(); assertTrue(r.shown)
        r.firstFrameOut(); assertTrue("out of the decoder, not on screen yet", r.shown)
        assertFalse("kept already, so not asked for again", r.frameDrawn())
        assertFalse(r.shown)
    }

    @Test fun aClipPickedUpMidLoopKeepsItsFirstFrameNextTimeRound() {
        val r = RestartCover().apply { reset(atStart = false) }
        assertFalse(r.frameDrawn())
        r.nearEnd(); assertFalse("no picture yet: this one restart is bare", r.shown)
        r.wentRound(); r.firstFrameOut()
        assertTrue(r.frameDrawn())
        r.nearEnd(); assertTrue(r.shown)
    }

    @Test fun aFirstFrameThatNeverComesLetsGo() {
        val r = RestartCover().apply { reset(atStart = true) }
        r.frameDrawn(); r.nearEnd(); r.wentRound()
        r.giveUp()
        assertFalse(r.shown)
        // The decoder's first frame of a start that isn't a restart (a new surface) changes nothing.
        r.firstFrameOut(); assertFalse(r.frameDrawn())
    }

    @Test fun aNewClipStartsOver() {
        val r = RestartCover().apply { reset(atStart = true) }
        r.frameDrawn(); r.nearEnd()
        r.reset(atStart = true)
        assertFalse(r.shown)
        assertTrue(r.frameDrawn())
    }
}
