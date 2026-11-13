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
        // Animated covers play as they always did (owner, 2026-11-13: on by default, so nothing changes for anyone).
        assertEquals(CanvasPrefs(covers = true, useVideos = false, videoFirst = false, keepControls = false, lighter = false, videosOnCellular = false), AppSettings().behavior.canvas)
        assertEquals(CanvasPrefs(), read { true }.behavior.canvas)
        assertTrue(read { true }.behavior.canvas.covers)
        // Canvas itself comes as before: on the cover.
        assertEquals(AnimatedCovers.ON_THE_COVER, AnimatedCovers.toMerged(AppSettings().canvasEnabled to AppSettings().canvasFillsBackground))
    }

    /** Owner, 2026-11-13: a new install comes with music videos on; someone updating is asked once (nothing stored yet). */
    @Test fun aNewInstallComesWithMusicVideosOnAndAnUpdateIsUnanswered() = runBlocking {
        assertFalse("nothing decided yet: the player asks once", repo.canvasVideosAnswered)
        repo.setOnboarded(true)
        assertTrue(read { it.behavior.canvas.useVideos }.onboarded)
        withTimeout(5_000) { while (!repo.canvasVideosAnswered) kotlinx.coroutines.delay(20) }
        // An answer given before stays: finishing the welcome again doesn't turn them back on.
        repo.setCanvasUseVideos(false)
        read { !it.behavior.canvas.useVideos }
        repo.setOnboarded(true)
        repo.editNow { }
        assertFalse(read { true }.behavior.canvas.useVideos)
        assertTrue(repo.canvasVideosAnswered)
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

    /** Owner, 2026-11-13: "Animated covers" on its own, stored and read back, apart from the music videos. */
    @Test fun animatedCoversAreStoredApartFromMusicVideos() {
        repo.setCanvasUseVideos(true)
        repo.setCanvasCovers(false)
        val off = read { !it.behavior.canvas.covers && it.behavior.canvas.useVideos }.behavior.canvas
        assertEquals(CanvasPrefs(covers = false, useVideos = true), off)
        repo.setCanvasCovers(true)
        assertEquals(CanvasPrefs(covers = true, useVideos = true), read { it.behavior.canvas.covers }.behavior.canvas)
        // Performance mode pauses Canvas as a whole; the stored switch is kept.
        repo.setCanvasCovers(false); repo.setPerformanceMode(true)
        val paused = read { !it.behavior.canvas.covers && it.performanceMode }
        assertFalse(paused.effective().canvasEnabled)
        assertFalse(paused.effective().behavior.canvas.covers)
    }

    @Test fun noneIsPartOfALook() {
        for (k in keys + "canvas_covers") {
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
