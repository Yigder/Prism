package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CanvasPrefs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 1.8.6 Canvas, the lines in Share diagnostics (plan-canvas 4.8): the setting, what shows, and why there is no loop. */
class CanvasDiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val song = Song("s1", "Title", listOf(ArtistRef("Artist")), durationSec = 200)

    private fun stored(enabled: Boolean = true, full: Boolean = false, performance: Boolean = false, prefs: CanvasPrefs = CanvasPrefs(useVideos = true)) =
        AppSettings().let { it.copy(canvasEnabled = enabled, canvasFillsBackground = full, performanceMode = performance, behavior = it.behavior.copy(canvas = prefs)) }

    private fun now(
        stored: AppSettings = stored(), metered: Boolean = false, videoMode: Boolean = false, song: Song? = this.song,
        hidden: Boolean = false, cover: CanvasArtwork? = null, row: CanvasClipEntity? = null, kept: List<ClipFiles.Kept> = emptyList(),
    ): String = canvasDiagnostics(stored, stored.effective(), metered, videoMode, song, hidden, cover, row, kept, now).lines()[1]

    private val loopRow = clipRow("s1", VideoClip("vidA", 12_000, 18_500, 1080), null, null, now - 3 * 86_400_000L, videoType = "MUSIC_VIDEO_TYPE_OMV", itag = 137)

    @Test fun theSettingLine() {
        assertEquals(
            "Canvas: full screen, music videos on (video first), videos on mobile data off, performance mode off",
            canvasSettingLine(stored(full = true, prefs = CanvasPrefs(useVideos = true, videoFirst = true))),
        )
        assertEquals(
            "Canvas: on the cover, music videos off, videos on mobile data off, performance mode on (Canvas paused)",
            canvasSettingLine(stored(performance = true, prefs = CanvasPrefs())),
        )
        assertEquals("Canvas: off, music videos on (lighter, 480p), videos on mobile data on, performance mode off",
            canvasSettingLine(stored(enabled = false, prefs = CanvasPrefs(useVideos = true, lighter = true, videosOnCellular = true))))
    }

    @Test fun offPausedOrNothingPlaying() {
        assertEquals("Canvas now: nothing playing", now(song = null))
        assertEquals("Canvas now: still cover (Canvas off)", now(stored(enabled = false)))
        assertEquals("Canvas now: still cover (Canvas paused by performance mode)", now(stored(performance = true)))
        assertEquals("Canvas now: the music video plays (video mode), no canvas", now(videoMode = true))
    }

    @Test fun anAnimatedCoverAndWhyNoLoopWasAsked() {
        val cover = CanvasArtwork("https://mvod.itunes.apple.com/itunes-assets/x.m3u8")
        assertEquals("Canvas now: animated cover from mvod.itunes.apple.com; no loop: not asked: the animated cover shows first", now(cover = cover))
        assertEquals("Canvas now: still cover (no animated cover known); no loop: not asked: music videos off", now(stored(prefs = CanvasPrefs())))
        assertEquals("Canvas now: still cover (no animated cover known); no loop: not asked: Canvas hidden for this song", now(hidden = true))
    }

    @Test fun aLoopFromAKeptFile() {
        val f = File(tmp.root, ClipFiles.name("vidA", 1080, 137, 10_000, 30_000, false)).apply { writeBytes(ByteArray(2 * 1_048_576)) }
        val kept = listOf(ClipFiles.Kept(f, "vidA", 1080, 137, 10_000, 30_000, false, saved = true))
        assertEquals(
            "Canvas now: video loop vidA (MUSIC_VIDEO_TYPE_OMV, 1080p, 6.5 s, storyboard guess) from a kept file (saved, 2.0 MB)",
            now(stored(full = true), row = loopRow, kept = kept),
        )
    }

    @Test fun aLoopFromTheNetworkOrNotOnThisPhone() {
        assertEquals(
            "Canvas now: video loop vidA (MUSIC_VIDEO_TYPE_OMV, 1080p, 6.5 s, storyboard guess) from the network",
            now(row = loopRow),
        )
        assertEquals(
            "Canvas now: still cover (no animated cover known); no loop: vidA not on this phone and no Wi-Fi",
            now(metered = true, row = loopRow),
        )
    }

    @Test fun noLoopAndItsReason() {
        val none = clipRow("s1", null, NoClip.STILL, null, now - 3 * 86_400_000L)
        assertEquals("Canvas now: still cover (no animated cover known); no loop: STILL (checked 3 days ago)", now(row = none))
        assertEquals("Canvas now: still cover (no animated cover known); no loop: not looked up yet", now())
        assertEquals("Canvas now: still cover (no animated cover known); no loop: not looked up yet and no Wi-Fi", now(metered = true))
    }

    @Test fun theSongsOwnChoiceShows() {
        val cover = CanvasArtwork("https://resources.tidal.com/videos/a/b/c/d/e/1280x1280.mp4")
        val chosen = loopRow.copy(prefer = CanvasSource.VIDEO.stored)
        assertEquals(
            "Canvas now: video loop vidA (MUSIC_VIDEO_TYPE_OMV, 1080p, 6.5 s, storyboard guess) from the network, this song set to video",
            now(cover = cover, row = chosen),
        )
    }
}
