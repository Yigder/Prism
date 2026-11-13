package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.CanvasPrefs
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.screens.settings.AnimatedCovers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.8.6 Canvas, the stored settings (plan-canvas §4.7): [CanvasPrefs] in `behavior.canvas`, all off as Prism comes,
 * stored and read back one by one, none of them part of a Look, and Performance mode reading as no music videos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CanvasPrefsTest {
    private val repo by lazy { SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    private val keys = listOf("canvas_use_videos", "canvas_video_first", "canvas_keep_controls", "canvas_lighter", "canvas_videos_cellular")

    /** The store is one per JVM: start and leave it as Prism comes. */
    @Before fun fresh() = runBlocking { repo.editNow { it.clear() } }
    @After fun tidy() = runBlocking { repo.editNow { it.clear() } }

    /** The settings as stored (`flow` is what Prism draws with: [AppSettings.effective]). */
    private fun read(cond: (AppSettings) -> Boolean): AppSettings = runBlocking { withTimeout(5_000) { repo.stored.first(cond) } }

    @Test fun allOffAsPrismComes() {
        assertEquals(CanvasPrefs(false, false, false, false, false), AppSettings().behavior.canvas)
        assertEquals(CanvasPrefs(), read { true }.behavior.canvas)
        // Canvas itself comes as before: on the cover.
        assertEquals(AnimatedCovers.ON_THE_COVER, AnimatedCovers.toMerged(AppSettings().canvasEnabled to AppSettings().canvasFillsBackground))
    }

    @Test fun eachIsStoredAndReadBackAlone() {
        val setters: List<Pair<(Boolean) -> Unit, (CanvasPrefs) -> Boolean>> = listOf(
            repo::setCanvasUseVideos to { c -> c.useVideos },
            repo::setCanvasVideoFirst to { c -> c.videoFirst },
            repo::setCanvasKeepControls to { c -> c.keepControls },
            repo::setCanvasLighter to { c -> c.lighter },
            repo::setCanvasVideosOnCellular to { c -> c.videosOnCellular },
        )
        for ((i, pair) in setters.withIndex()) {
            val (set, get) = pair
            set(true)
            val on = read { get(it.behavior.canvas) }.behavior.canvas
            // Only that one is on.
            assertEquals("setter $i", 1, listOf(on.useVideos, on.videoFirst, on.keepControls, on.lighter, on.videosOnCellular).count { it })
            set(false)
            assertEquals(CanvasPrefs(), read { !get(it.behavior.canvas) }.behavior.canvas)
        }
    }

    @Test fun noneIsPartOfALook() {
        for (k in keys) {
            assertTrue("$k is stored", k in repo.allKeyNames)
            assertTrue("$k is listed as not part of a Look", k in repo.notLookNames)
            assertFalse("$k is not in a Look", k in repo.lookKinds)
        }
        // Full screen's own flag stays in Looks.
        assertTrue("canvas_fills_background" in repo.lookKinds)
    }

    @Test fun performanceModeReadsAsNoMusicVideos() {
        val all = CanvasPrefs(useVideos = true, videoFirst = true, keepControls = true, lighter = true, videosOnCellular = true)
        val s = AppSettings(canvasEnabled = true, canvasFillsBackground = true).let { it.copy(behavior = it.behavior.copy(canvas = all)) }
        // Without Performance mode nothing changes.
        assertEquals(all, s.effective().behavior.canvas)
        // With it: no canvas, and no music videos; the rest is kept as stored.
        val light = s.copy(performanceMode = true).effective()
        assertFalse(light.canvasEnabled)
        assertEquals(all.copy(useVideos = false), light.behavior.canvas)
        // Nothing stored changes.
        assertTrue(s.copy(performanceMode = true).behavior.canvas.useVideos)
    }

    /** R6: only a pick of Full screen, from another choice, with music videos off, asks. */
    @Test fun whenFullScreenAsksForMusicVideos() {
        val all = AnimatedCovers.entries
        for (was in all) for (picked in all) for (videos in listOf(false, true)) {
            val asks = AnimatedCovers.asksForVideos(was, picked, videos)
            assertEquals("$was → $picked, videos $videos", picked == AnimatedCovers.FILL_THE_PLAYER && was != AnimatedCovers.FILL_THE_PLAYER && !videos, asks)
        }
        assertEquals(listOf("Off", "On the cover", "Full screen"), all.map { it.label })
    }

    @Test fun storedThenPerformanceMode() {
        repo.setCanvasUseVideos(true)
        repo.setPerformanceMode(true)
        val s = read { it.behavior.canvas.useVideos && it.performanceMode }
        assertTrue(s.behavior.canvas.useVideos)
        assertFalse(s.effective().behavior.canvas.useVideos)
        // What the player is given reads the same.
        assertFalse(runBlocking { withTimeout(5_000) { repo.flow.first { it.performanceMode } } }.behavior.canvas.useVideos)
    }
}
