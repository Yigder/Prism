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
