package com.prism.music.data.canvas

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CanvasPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.6 Canvas, the one rule for whether a song's loop from its music video is wanted, at which height, and whether the
 * network may be used for it (plan-canvas 4.8): the player, the next-song preparation, downloads and diagnostics all
 * read it.
 */
class CanvasWantTest {
    private fun s(
        enabled: Boolean = true, full: Boolean = false, dataSaver: Boolean = false, performance: Boolean = false,
        prefs: CanvasPrefs = CanvasPrefs(useVideos = true),
    ): AppSettings = AppSettings().let {
        it.copy(
            canvasEnabled = enabled, canvasFillsBackground = full, dataSaver = dataSaver, performanceMode = performance,
            behavior = it.behavior.copy(canvas = prefs),
        )
    }.effective()

    private fun want(
        settings: AppSettings = s(), metered: Boolean = false, hasCover: Boolean = false, choice: CanvasSource? = null,
        hidden: Boolean = false, isSong: Boolean = true,
    ) = canvasWant(settings, metered, hasCover, choice, hidden, isSong)

    @Test fun aSongWithoutAnAnimatedCoverWantsItsLoopAt720OnTheCover() {
        val w = want()
        assertTrue(w.video)
        assertEquals(720, w.height)
        assertTrue(w.network)
        assertNull(w.notAsked)
    }

    @Test fun heightFollowsWhereItShows() {
        assertEquals(1080, want(s(full = true)).height)
        assertEquals(720, want(s(full = false)).height)
        assertEquals(480, want(s(full = true, prefs = CanvasPrefs(useVideos = true, lighter = true))).height)
        assertEquals(480, want(s(full = false, prefs = CanvasPrefs(useVideos = true, lighter = true))).height)
    }

    @Test fun canvasOffOrVideosOffAsksNothing() {
        assertEquals(NotAsked.CANVAS_OFF, want(s(enabled = false)).notAsked)
        assertEquals(NotAsked.VIDEOS_OFF, want(s(prefs = CanvasPrefs(useVideos = false))).notAsked)
        assertFalse(want(s(enabled = false)).video)
        assertEquals(0, want(s(enabled = false)).height)
    }

    @Test fun performanceModeReadsAsCanvasOff() {
        val w = want(s(performance = true, full = true))
        assertFalse(w.video)
        assertEquals(NotAsked.CANVAS_OFF, w.notAsked)
    }

    @Test fun theAnimatedCoverShowsFirstUnlessVideoFirstOrTheSongsChoice() {
        assertEquals(NotAsked.COVER_FIRST, want(hasCover = true).notAsked)
        assertTrue(want(s(prefs = CanvasPrefs(useVideos = true, videoFirst = true)), hasCover = true).video)
        assertTrue(want(hasCover = true, choice = CanvasSource.VIDEO).video)
        // The song's own choice wins over the setting, both ways.
        assertEquals(NotAsked.COVER_FIRST, want(s(prefs = CanvasPrefs(useVideos = true, videoFirst = true)), hasCover = true, choice = CanvasSource.COVER).notAsked)
        // "Cover" for a song without one falls through to the loop.
        assertTrue(want(hasCover = false, choice = CanvasSource.COVER).video)
    }

    @Test fun aHiddenSongOrOneThatIsNotASongAsksNothing() {
        assertEquals(NotAsked.HIDDEN, want(hidden = true).notAsked)
        assertEquals(NotAsked.NOT_A_SONG, want(isSong = false).notAsked)
    }

    @Test fun mobileDataNeedsTheChipAndNoDataSaver() {
        assertFalse(want(metered = true).network)
        assertTrue(want(metered = true).video) // wanted: a kept loop still plays
        assertTrue(want(s(prefs = CanvasPrefs(useVideos = true, videosOnCellular = true)), metered = true).network)
        assertFalse(want(s(dataSaver = true, prefs = CanvasPrefs(useVideos = true, videosOnCellular = true)), metered = true).network)
        // Data saver is about mobile data: Wi-Fi is unaffected.
        assertTrue(want(s(dataSaver = true), metered = false).network)
    }

    @Test fun preparingAheadIsWiFiOnlyAndNeverWithDataSaver() {
        assertTrue(canvasMayPrepare(s(), metered = false))
        assertFalse(canvasMayPrepare(s(prefs = CanvasPrefs(useVideos = true, videosOnCellular = true)), metered = true))
        assertFalse(canvasMayPrepare(s(dataSaver = true), metered = false))
    }

    @Test fun everyMixAgreesWithPickSource() {
        for (cover in listOf(true, false)) for (first in listOf(true, false)) for (choice in listOf(null, CanvasSource.COVER, CanvasSource.VIDEO)) {
            val w = want(s(prefs = CanvasPrefs(useVideos = true, videoFirst = first)), hasCover = cover, choice = choice)
            assertEquals("cover=$cover first=$first choice=$choice", pickSource(cover, true, true, first, choice) == CanvasSource.VIDEO, w.video)
        }
    }
}
