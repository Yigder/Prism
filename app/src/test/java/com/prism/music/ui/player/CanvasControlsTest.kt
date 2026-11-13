package com.prism.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.6 Canvas, Full screen: when the controls may fade away, and the darkening that keeps the type readable. */
class CanvasControlsTest {
    private fun fades(
        fullScreen: Boolean = true, hasCanvas: Boolean = true, keep: Boolean = false, playing: Boolean = true, covered: Boolean = false,
        panel: Boolean = false, dragging: Boolean = false, reader: Boolean = false, reduce: Boolean = false,
    ) = canvasControlsMayFade(fullScreen, hasCanvas, keep, playing, covered, panel, dragging, reader, reduce)

    @Test fun theControlsFadeOverAPlayingFullScreenCanvas() = assertTrue(fades())

    @Test fun theyStayWheneverSomeoneNeedsThem() {
        assertFalse("Canvas on the cover, or off", fades(fullScreen = false))
        assertFalse("a song with nothing to show", fades(hasCanvas = false))
        assertFalse("\"Keep controls showing\"", fades(keep = true))
        assertFalse("a paused song", fades(playing = false))
        assertFalse("a sheet over the player", fades(covered = true))
        assertFalse("the lyrics, the queue or the stats", fades(panel = true))
        assertFalse("a finger on the volume", fades(dragging = true))
        assertFalse("TalkBack", fades(reader = true))
        assertFalse("Reduce motion: nothing moves behind them", fades(reduce = true))
    }

    @Test fun theAlbumCoverIsNotSeenOverAFullScreenCanvas() {
        assertEquals("the canvas is up: no cover", 0f, coverOverCanvas(true, 1f, 0f), 0.001f)
        assertEquals("still loading, or the song has none: its cover", 1f, coverOverCanvas(true, 0f, 0f), 0.001f)
        assertEquals("they change places as it fades in", 0.6f, coverOverCanvas(true, 0.4f, 0f), 0.001f)
        assertEquals("the small cover beside the title, over the lyrics and the queue", 1f, coverOverCanvas(true, 1f, 1f), 0.001f)
        assertEquals("Canvas on the cover plays in the cover", 1f, coverOverCanvas(false, 1f, 0f), 0.001f)
    }

    @Test fun theDarkeningLiftsWithTheControlsAndTheTitleStaysReadable() {
        val shown = canvasScrim(1f, 0f, video = false); val away = canvasScrim(0f, 0f, video = false)
        // With the controls up: what a full-screen animated cover had before 1.8.6.
        assertEquals(0.25f, shown[0], 0.001f); assertEquals(0.45f, shown[1], 0.001f); assertEquals(0.75f, shown[2], 0.001f)
        for (i in 0..2) assertTrue("lighter away, part $i", away[i] < shown[i])
        assertTrue("the kept title's ground stays dark", away[2] >= 0.6f)
        assertTrue("darker toward the bottom", away[0] < away[1] && away[1] < away[2])
    }

    /** Owner, 2026-11-13: with the edge-to-edge cover, Full screen stayed a banner and the controls never faded. */
    @Test fun fullScreenIsFullScreenWhateverTheCoverLayout() {
        assertTrue("nothing about the cover's layout is asked", canvasFullScreen(fills = true, videoMode = false, wide = false))
        assertFalse("on the cover", canvasFullScreen(fills = false, videoMode = false, wide = false))
        assertFalse("a music video is playing", canvasFullScreen(fills = true, videoMode = true, wide = false))
        assertFalse("sideways", canvasFullScreen(fills = true, videoMode = false, wide = true))
    }

    /** Owner, 2026-11-13: switched off for a song, the switch went too and Canvas couldn't be put back for it. */
    @Test fun theSongsAnimatedCoverSwitchStaysAfterItIsSwitchedOff() {
        assertTrue(canvasCoverSwitchShown(canvasOn = true, covers = true, hasCover = true, switchedOff = false))
        assertTrue("off for this song: not looked up any more, the switch stays", canvasCoverSwitchShown(canvasOn = true, covers = true, hasCover = false, switchedOff = true))
        assertFalse("a song that has none", canvasCoverSwitchShown(canvasOn = true, covers = true, hasCover = false, switchedOff = false))
        assertFalse("\"Animated covers\" off in settings", canvasCoverSwitchShown(canvasOn = true, covers = false, hasCover = true, switchedOff = true))
        assertFalse("Canvas off", canvasCoverSwitchShown(canvasOn = false, covers = true, hasCover = true, switchedOff = false))
    }

    /** Owner, 2026-11-13: the song's Canvas (its video's loop) has its own switch, apart from its animated cover. */
    @Test fun theSongsCanvasSwitchIsForItsLoopAndStaysAfterItIsSwitchedOff() {
        assertTrue(canvasLoopSwitchShown(canvasOn = true, useVideos = true, isSong = true, hasVideo = true, switchedOff = false))
        assertTrue("off for this song: the switch stays", canvasLoopSwitchShown(canvasOn = true, useVideos = true, isSong = true, hasVideo = false, switchedOff = true))
        assertFalse("no video found for it", canvasLoopSwitchShown(canvasOn = true, useVideos = true, isSong = true, hasVideo = false, switchedOff = false))
        assertFalse("music videos off: animated covers only", canvasLoopSwitchShown(canvasOn = true, useVideos = false, isSong = true, hasVideo = true, switchedOff = false))
        assertFalse("a file, a podcast", canvasLoopSwitchShown(canvasOn = true, useVideos = true, isSong = false, hasVideo = true, switchedOff = false))
        assertFalse("Canvas off", canvasLoopSwitchShown(canvasOn = false, useVideos = true, isSong = true, hasVideo = true, switchedOff = true))
    }

    /** "Use:" only for a song that has both, neither switched off for it. */
    @Test fun useIsOfferedOnlyWithBothOn() {
        fun use(covers: Boolean = true, videos: Boolean = true, cover: Boolean = true, video: Boolean = true, coverOff: Boolean = false, loopOff: Boolean = false, on: Boolean = true) =
            canvasUseShown(on, covers, videos, cover, video, coverOff, loopOff)
        assertTrue(use())
        assertFalse(use(cover = false)); assertFalse(use(video = false))
        assertFalse("its animated cover off", use(coverOff = true)); assertFalse("its loop off", use(loopOff = true))
        assertFalse(use(covers = false)); assertFalse(use(videos = false)); assertFalse(use(on = false))
    }

    /** Owner, 2026-11-13: a song's video can be picked as its version can; also for a song that has no loop yet. */
    @Test fun pickAnotherVideoIsOfferedForEverySongWhileCanvasUsesVideos() {
        assertTrue(canvasVideoPickShown(canvasOn = true, useVideos = true, isSong = true, switchedOff = false))
        assertFalse(canvasVideoPickShown(canvasOn = false, useVideos = true, isSong = true, switchedOff = false))
        assertFalse("animated covers only", canvasVideoPickShown(canvasOn = true, useVideos = false, isSong = true, switchedOff = false))
        assertFalse("a file, a podcast", canvasVideoPickShown(canvasOn = true, useVideos = true, isSong = false, switchedOff = false))
        assertFalse("its Canvas loop is off for this song", canvasVideoPickShown(canvasOn = true, useVideos = true, isSong = true, switchedOff = true))
    }

    @Test fun someoneUpdatingWithCanvasOnIsAskedOnceInThePlayer() {
        assertTrue(canvasAsksOnce(canvasOn = true, answered = false, playerOpen = true))
        assertFalse("answered, by the first run, the question or the switch", canvasAsksOnce(canvasOn = true, answered = true, playerOpen = true))
        assertFalse("Canvas off: nothing to ask", canvasAsksOnce(canvasOn = false, answered = false, playerOpen = true))
        assertFalse("not over the lyrics or the queue", canvasAsksOnce(canvasOn = true, answered = false, playerOpen = false))
    }

    @Test fun aVideoAndAListOverItGetMore() {
        val cover = canvasScrim(1f, 0f, video = false); val video = canvasScrim(1f, 0f, video = true); val lyrics = canvasScrim(1f, 1f, video = true)
        for (i in 0..2) { assertTrue(video[i] > cover[i]); assertTrue(lyrics[i] > video[i]); assertTrue(lyrics[i] <= 0.92f) }
        // The middle, where the lines are read, is well covered.
        assertTrue(lyrics[1] >= 0.7f)
        // Half-way values stay in between (the fade passes through them).
        val half = canvasScrim(0.5f, 0.5f, video = true)
        for (i in 0..2) assertTrue(half[i] > canvasScrim(0f, 0f, true)[i] && half[i] < lyrics[i])
    }
}
