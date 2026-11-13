package com.prism.music.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 Canvas: which source a song shows (plan §3.1, §3.8), and the row that keeps a loop with the listener's choices. */
class VideoCanvasShapesTest {
    private val clip = VideoClip("vid", 64_898, 71_900, 1080, 0.4f, Bars(top = 0.12f, bottom = 0.12f), refined = true)

    @Test fun anAnimatedCoverComesFirstByDefault() {
        assertEquals(CanvasSource.COVER, pickSource(hasCover = true, hasClip = true, useVideos = true, videoFirst = false, songChoice = null))
    }

    @Test fun theSettingCanPutTheVideoFirstAndTheCoverStaysTheFallback() {
        assertEquals(CanvasSource.VIDEO, pickSource(true, true, useVideos = true, videoFirst = true, songChoice = null))
        assertEquals(CanvasSource.COVER, pickSource(true, false, useVideos = true, videoFirst = true, songChoice = null))
    }

    @Test fun theSongsOwnChoiceWinsOverTheSetting() {
        assertEquals(CanvasSource.VIDEO, pickSource(true, true, useVideos = true, videoFirst = false, songChoice = CanvasSource.VIDEO))
        assertEquals(CanvasSource.COVER, pickSource(true, true, useVideos = true, videoFirst = true, songChoice = CanvasSource.COVER))
    }

    @Test fun aSongWithOnlyOneSourceShowsItWhateverWasChosen() {
        assertEquals(CanvasSource.VIDEO, pickSource(false, true, useVideos = true, videoFirst = false, songChoice = CanvasSource.COVER))
        assertEquals(CanvasSource.COVER, pickSource(true, false, useVideos = true, videoFirst = true, songChoice = CanvasSource.VIDEO))
    }

    @Test fun videosAreNeverUsedWhileUseMusicVideosIsOff() {
        assertNull(pickSource(false, true, useVideos = false, videoFirst = true, songChoice = CanvasSource.VIDEO))
        assertEquals(CanvasSource.COVER, pickSource(true, true, useVideos = false, videoFirst = true, songChoice = CanvasSource.VIDEO))
    }

    @Test fun nothingToShowIsTheStillCover() {
        assertNull(pickSource(false, false, useVideos = true, videoFirst = false, songChoice = null))
    }

    /**
     * Every mix of setting ("Use music videos", "When a song has both"), the song's own "Use:" and what the song has
     * (plan 4.6): only a song with both is decided by a choice, the song's first, then the setting's.
     */
    @Test fun everyMixOfSettingChoiceAndWhatTheSongHas() {
        val bools = listOf(false, true)
        for (hasCover in bools) for (hasClip in bools) for (useVideos in bools) for (videoFirst in bools)
            for (choice in listOf(null, CanvasSource.COVER, CanvasSource.VIDEO)) {
                val clip = hasClip && useVideos
                val want = when {
                    hasCover && clip -> choice ?: if (videoFirst) CanvasSource.VIDEO else CanvasSource.COVER
                    hasCover -> CanvasSource.COVER
                    clip -> CanvasSource.VIDEO
                    else -> null
                }
                assertEquals("cover=$hasCover clip=$hasClip use=$useVideos first=$videoFirst choice=$choice", want, pickSource(hasCover, hasClip, useVideos, videoFirst, choice))
            }
    }

    @Test fun aRowGivesItsLoopBackWhole() {
        val row = clipRow("song", clip, null, old = null, now = 5L, videoType = OFFICIAL, itag = 137, otherParts = listOf(30_000L, 90_000L))
        assertEquals(clip, row.clip())
        assertEquals(listOf(30_000L, 90_000L), row.otherParts())
        assertEquals("", row.why)
        assertEquals(7_002L, row.clip()!!.lengthMs)
    }

    @Test fun noLoopSaysWhy() {
        val row = clipRow("song", null, NoClip.STILL, old = null, now = 5L)
        assertNull(row.clip())
        assertEquals("STILL", row.why)
        assertEquals(emptyList<Long>(), row.otherParts())
    }

    @Test fun aNewGuessKeepsWhatTheListenerChose() {
        val old = clipRow("song", clip, null, null, 1L).copy(prefer = "video", rejectedVideos = "bad1,bad2")
        val next = clipRow("song", clip.copy(startMs = 10_000, endMs = 17_000), null, old, 9L)
        assertEquals(CanvasSource.VIDEO, CanvasSource.of(next.prefer))
        assertEquals(setOf("bad1", "bad2"), next.rejected())
        assertEquals(9L, next.checkedAt)
        assertFalse(next.picked)
    }

    @Test fun aPickedPartStaysPickedOnlyWhileThereIsALoop() {
        val old = clipRow("song", clip, null, null, 1L, picked = true)
        assertTrue(clipRow("song", clip, null, old, 2L).picked)
        assertFalse(clipRow("song", null, NoClip.REJECTED, old, 2L).picked)
    }

    @Test fun theClipsKeyOutlivesTheLinkAndTellsPartsApart() {
        assertEquals("prism-clip://vid/1080/64898-71900", clip.key)
        assertFalse(clip.key == clip.copy(height = 720).key)
    }
}
