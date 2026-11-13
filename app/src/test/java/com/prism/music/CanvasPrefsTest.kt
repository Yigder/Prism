package com.prism.music

import com.prism.music.data.prefs.AppSettings
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.prism.music.data.prefs.CanvasKinds
import com.prism.music.data.prefs.CanvasPrefs
import com.prism.music.data.prefs.CanvasStored
import com.prism.music.data.prefs.NewInstall
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.screens.settings.canvasAsksForVideos
import com.prism.music.ui.screens.settings.canvasPlaceWords
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
        // As before: on, on the cover; Settings shows Animated covers on and Canvas (the loops) off.
        assertTrue(AppSettings().canvasEnabled)
        assertFalse(AppSettings().canvasFillsBackground)
        assertEquals(CanvasKinds(covers = true, videos = false), CanvasKinds.shown(AppSettings()))
    }

    private fun triple(s: AppSettings) = CanvasStored(s.canvasEnabled, s.behavior.canvas.covers, s.behavior.canvas.useVideos)

    /** Waits for the store to read [want] (a timeout fails the test). */
    private fun expect(want: CanvasStored) = assertEquals(want, triple(read { triple(it) == want }))

    /** Owner, 2026-11-13: Settings' two switches write the three flags in one go, by [CanvasKinds]' rule. */
    @Test fun theTwoSwitchesWriteTheThreeFlagsInOneGo() = runBlocking {
        // Canvas switched off before, with both flags on: both show off.
        repo.editNow { it[booleanPreferencesKey("canvas_enabled")] = false; it[booleanPreferencesKey("canvas_covers")] = true; it[booleanPreferencesKey("canvas_use_videos")] = true }
        assertEquals(CanvasKinds(false, false), CanvasKinds.shown(read { !it.canvasEnabled && it.behavior.canvas.useVideos }))
        // Animated covers on from there: the loops stay off.
        repo.setCanvasKinds(covers = true, videos = false)
        expect(CanvasStored(true, true, false))
        // Canvas on as well.
        repo.setCanvasKinds(covers = true, videos = true)
        expect(CanvasStored(true, true, true))
        // One left alone (null) is read in the same write: two calls in a row don't race. The last one off turns Canvas
        // off and keeps the flags.
        repo.setCanvasKinds(covers = false); repo.setCanvasKinds(videos = false)
        expect(CanvasStored(false, false, true))
        assertEquals(CanvasKinds(false, false), CanvasKinds.shown(read { !it.canvasEnabled }))
        repo.setCanvasKinds(videos = true)
        expect(CanvasStored(true, false, true))
    }

    /** Both off on a store that never had the flags: Canvas off, the flags still unwritten (the question is still to come). */
    @Test fun bothOffLeavesTheFlagsUnwritten() = runBlocking {
        repo.setCanvasKinds(covers = false, videos = false); repo.editNow { }
        assertFalse(read { !it.canvasEnabled }.canvasEnabled)
        assertFalse(repo.canvasVideosAnswered)
        // Writing Canvas's switch answers the one-time question.
        repo.setCanvasKinds(covers = false, videos = true); repo.editNow { }
        expect(CanvasStored(true, false, true))
        withTimeout(5_000) { while (!repo.canvasVideosAnswered) kotlinx.coroutines.delay(20) }
    }

    /**
     * Owner, 2026-11-13: a new install's own defaults, written at the first run only where nothing was ever stored; the
     * data classes' defaults (what someone updating has) stay as they were.
     */
    @Test fun aNewInstallsOwnDefaults() = runBlocking {
        val names = NewInstall.on + NewInstall.motion
        assertEquals(listOf("canvas_fills_background", "pause_fade", "artist_you", "finish_lyric", "album_hero_cover"), NewInstall.on)
        assertEquals(listOf("cover_flight", "cover_flight_back", "backdrop_fade"), NewInstall.motion)
        for (n in names) assertTrue("$n is stored", n in repo.allKeyNames)
        // The data classes keep them off.
        assertEquals(List(8) { false }, on(AppSettings()))
        // Not onboarded: nothing written.
        repo.setOnboarded(false); repo.editNow { }
        assertEquals(List(8) { false }, on(read { true }))
        assertTrue(repo.forBackup(settings = true, accounts = false).keys.none { it in names })
        // Onboarded on a fresh store: all of them.
        repo.setOnboarded(true)
        assertEquals(List(8) { true }, on(read { it.onboarded }))
    }

    /** The eight, in [NewInstall]'s order ([NewInstall.on], then [NewInstall.motion]). */
    private fun on(s: AppSettings) = listOf(
        s.canvasFillsBackground, s.sound.pauseFade, s.behavior.artistYou, s.behavior.finishLyric, s.view.albumHeroCover,
        s.view.coverFlight, s.view.coverFlightBack, s.view.backdropFade,
    )

    @Test fun aNewInstallsDefaultsKeepWhatWasStored() = runBlocking {
        val names = NewInstall.on + NewInstall.motion
        // Each one stored before, off and then on, and the rest not: the stored one stays, the rest come on.
        for ((i, n) in names.withIndex()) for (was in listOf(false, true)) {
            repo.editNow { it.clear(); it[booleanPreferencesKey(n)] = was }
            repo.setOnboarded(true); repo.editNow { }
            val want = List(names.size) { j -> if (j == i) was else true }
            assertEquals("$n stored $was", want, on(read { it.onboarded && on(it) == want }))
        }
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

    /** R6: only a pick of Full screen, from On the cover, with Canvas's loops off, asks. */
    @Test fun whenFullScreenAsksForMusicVideos() {
        val b = listOf(false, true)
        for (was in b) for (picked in b) for (videos in b) {
            assertEquals("$was → $picked, videos $videos", picked && !was && !videos, canvasAsksForVideos(was, picked, videos))
        }
        // "Where it shows" has two choices now (no Off: the two switches are what turn things off).
        assertEquals(listOf("On the cover", "Full screen"), b.map { canvasPlaceWords(it) })
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
