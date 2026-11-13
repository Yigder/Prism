package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.navigation.compose.ComposeNavigator
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LocalSection
import com.prism.music.data.prefs.Preload
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.download.formatGb
import com.prism.music.ui.Routes
import com.prism.music.ui.screens.SettingsIndex
import com.prism.music.ui.screens.settings.MobileDataStored
import com.prism.music.ui.screens.settings.MobileUse
import com.prism.music.ui.screens.settings.SettingsMap
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.SettingsTags
import com.prism.music.ui.screens.settings.SettingsTarget
import com.prism.music.ui.screens.settings.mobileDataToMerged
import com.prism.music.ui.screens.settings.mobileDataToStored
import com.prism.music.ui.screens.settings.oldGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Downloads & data rebuilt with the kit (plan §2.3, the ledger's Downloads & data table): five groups
 * of the five rows within the budgets, a control for every stored setting the ledger gives the page (worked here, and
 * the store follows), "Use mobile data for" as chips over the eight stored switches with Data saver its own switch,
 * the new "Sections in Local files" row, Reset this page, and the page drawn in light, dark, Performance mode and at
 * large text. The window is tall so that the page's lazy list holds every group at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class DownloadsDataPageTest : ListeningPageHarness(SettingsPageId.DOWNLOADS_DATA) {
    private fun t(id: Int, vararg args: Any?) = if (args.isEmpty()) tr(id) else tr(id, *args)

    private val manage = t(R.string.manage_downloads)
    private val wifiOnly = t(R.string.download_on_wi_fi_only)
    private val likes = t(R.string.download_liked_songs_automatically)
    private val keep = t(R.string.keep_playlists_downloaded)
    private val ownFiles = t(R.string.use_your_own_song_files)
    private val scanAgain = t(R.string.scan_your_song_files_again)
    private val folders = t(R.string.folders_in_local_files)
    private val sections = t(R.string.dd_sections_in_local_files)
    private val smart = t(R.string.smart_downloads)
    private val space = t(R.string.space_to_use)
    private val clearSmart = t(R.string.clear_smart_downloads)
    private val saveCovers = t(R.string.save_with_downloads)
    private val savedCovers = t(R.string.saved_animated_covers)
    private val recentCovers = t(R.string.recently_played_covers)
    private val loadAhead = t(R.string.load_ahead)
    private val dataSaver = t(R.string.data_saver)
    private val useFor = t(R.string.dd_use_mobile_data_for)

    private val gDownloads = t(R.string.downloads)
    private val gFiles = t(R.string.dd_files_on_your_phone)
    private val gSmart = t(R.string.dd_downloaded_for_you)
    private val gCovers = t(R.string.animated_covers)
    private val gData = t(R.string.mobile_data)

    /** Smart Downloads' switch schedules its worker: WorkManager has to be there for it (the app's start does that on a phone). */
    @Before fun workManager() {
        val app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
    }

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    /** A chip of the Show-chips row (it ticks on and off). */
    private fun chip(title: String, label: String) {
        compose.onNode(hasText(label) and hasAnyAncestor(rowIs(title)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun chips(title: String): List<String> = MobileUse.entries.map { it.label }
        .filter { compose.onAllNodes(hasText(it) and hasAnyAncestor(rowIs(title)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** Somewhere for a Link row's route to go (the harness's graph holds the page and the equalizer). */
    private fun addRoutes(vararg routes: String) {
        val nc = navController!!
        compose.runOnUiThread {
            val navigator = nc.navigatorProvider.getNavigator(ComposeNavigator::class.java)
            routes.forEach { r -> nc.graph.addDestination(ComposeNavigator.Destination(navigator) { Box(Modifier.fillMaxSize()) }.apply { route = r }) }
        }
    }

    /** A sheet that isn't the kit's own (Local files' sheets, the kept playlists): swiped down by a text it shows. */
    private fun swipeAway(text: String) {
        // (A long way down: a one-line text is too short for its own height to be a swipe.)
        compose.onNode(hasText(text)).performTouchInput { swipeDown(startY = centerY, endY = centerY + 1500f) }
        compose.waitUntil(5_000) { shown(text) == 0 }
        compose.waitForIdle()
    }

    @Test fun fiveGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        // The ledger's layout with a fresh install's settings (Load ahead joined Mobile data: its group was named as its row).
        assertEquals(listOf(gDownloads, gFiles, gSmart, gCovers, gData), groupTitles())
        assertEquals(listOf(4, 3, 3, 3, 3), groupSizes())
        assertEquals("nothing differs from how Prism comes", 0, changedRows())
        // No group is named as the page, and none as one of its own rows (§2.7: "Smart Downloads" and "Load ahead" were both).
        assertTrue(groupTitles().none { it == SettingsPageId.DOWNLOADS_DATA.title })
        // ("Animated covers" is also a chip of "Use mobile data for": a chip, not a row.)
        val rowTitles = listOf(manage, wifiOnly, likes, keep, ownFiles, folders, sections, smart, space, clearSmart, saveCovers, savedCovers, recentCovers, loadAhead, dataSaver, useFor)
        for (title in rowTitles) assertTrue("the row \"$title\" is drawn", hasRow(title))
        for (g in groupTitles()) assertFalse("a group and a row are both \"$g\"", g in rowTitles)
        // Four or more groups: the jump chips are there.
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Each row is the type the ledger gives it, and says its state.
        assertEquals(SettingsTags.ROW_LINK, typeOf(manage))
        assertEquals(t(R.string.no_downloads_yet), valueOf(manage))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(wifiOnly))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(likes))
        assertEquals(SettingsTags.ROW_LINK, typeOf(keep))
        assertEquals(t(R.string.none_chosen), valueOf(keep))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(ownFiles))
        assertFalse("Scan again follows the switch", hasRow(scanAgain))
        assertEquals(SettingsTags.ROW_LINK, typeOf(folders))
        assertEquals(t(R.string.every_folder_with_music), valueOf(folders))
        assertEquals(SettingsTags.ROW_LINK, typeOf(sections))
        assertEquals(LocalSection.entries.joinToString(" · ") { it.label }, valueOf(sections))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(smart))
        assertEquals(SettingsTags.ROW_SLIDER, typeOf(space))
        assertEquals(formatGb(5f), valueOf(space))
        assertEquals(SettingsTags.ROW_LINK, typeOf(clearSmart))
        assertEquals(t(R.string.dd_space_used, "0 MB"), valueOf(clearSmart))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(saveCovers))
        assertEquals(t(R.string.none_yet), valueOf(savedCovers))
        assertEquals(SettingsTags.ROW_LINK, typeOf(recentCovers))
        // ("Load ahead" is also a chip of "Use mobile data for": the row is the one that isn't that row.)
        val loadAheadRow = compose.onNode(rowIs(loadAhead) and !androidx.compose.ui.test.hasAnyDescendant(hasText(useFor))).fetchSemanticsNode()
        assertEquals(SettingsTags.ROW_CHOICE, loadAheadRow.config[SemanticsProperties.TestTag])
        assertEquals(Preload.START.label, loadAheadRow.config[com.prism.music.ui.screens.settings.SettingsRowValue])
        // Data saver is its own switch; the other eight are chips of one row.
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(dataSaver))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(useFor))
        assertEquals(MobileUse.LYRICS.label + " · " + MobileUse.ANIMATED_COVERS.label, valueOf(useFor))
        // The rows that only explained are gone, and no "… on mobile data" switch is left.
        for (id in listOf(R.string.load_ahead_on_mobile_data, R.string.lyrics_on_mobile_data, R.string.animated_covers_on_mobile_data, R.string.get_songs_ready_for_sing_on)) {
            assertEquals(t(id), 0, shown(t(id)))
        }
    }

    /** Principle 8: every stored setting the ledger gives Downloads & data has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(LocalLossless.permission)
        show()
        val d = AppSettings()

        // download_wifi_only, download_likes
        tap(wifiOnly)
        until("download_wifi_only") { !it.downloadWifiOnly }
        tap(likes)
        until("download_likes") { !it.downloadLikes }
        // kept_playlists: the row says how many, and opens the list they are ticked in.
        container.settings.setKeptPlaylist("PLkept", true)
        until("kept_playlists") { it.behavior.keptPlaylists == setOf("PLkept") }
        assertEquals(trp(R.plurals.n_playlists_kept, 1), valueOf(keep))
        tap(keep)
        assertEquals("the sheet's title, over the row's", 2, shown(keep))
        swipeAway(t(R.string.no_playlists_yet))

        // own_lossy_files, with "Scan again" after it.
        tap(ownFiles)
        until("own_lossy_files") { it.sound.ownLossyFiles }
        assertEquals(SettingsTags.ROW_LINK, typeOf(scanAgain))
        assertTrue("the switch says what it found", valueOf(ownFiles).isNotBlank())
        // local_skip_folders: the folders' sheet.
        tap(folders)
        assertEquals(1, shown(t(R.string.no_music_files_found)))
        swipeAway(t(R.string.no_music_files_found))
        container.settings.setLocalSkipFolders(setOf("/storage/emulated/0/Ringtones"))
        until("local_skip_folders") { it.behavior.localSkipFolders.size == 1 }
        assertEquals(trp(R.plurals.n_folders_left_out, 1), valueOf(folders))
        // local_sections: Local files' own sheet, opened from Settings (before 1.8.4 only Local files' Edit chip did).
        tap(sections)
        assertEquals(1, shown(t(R.string.tick_the_sections_you_want_hold)))
        compose.onNode(hasContentDescription(LocalSection.DOWNLOADS.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        until("local_sections") { LocalSection.DOWNLOADS !in it.localSections && it.localSections.size == LocalSection.entries.size - 1 }
        swipeAway(t(R.string.tick_the_sections_you_want_hold))
        assertFalse(LocalSection.DOWNLOADS.label in valueOf(sections))

        // smart_downloads, offline_gb
        tap(smart)
        until("smart_downloads") { it.smartDownloads }
        slide(space, 3f)
        until("offline_gb") { it.offlineGb == 2f }
        assertEquals(formatGb(2f), valueOf(space))
        // canvas_with_downloads
        tap(saveCovers)
        until("canvas_with_downloads") { !it.canvasWithDownloads }
        // preload
        choose(loadAhead, Preload.NEXT3.label)
        until("preload") { it.preload == Preload.NEXT3 }
        // data_saver: its own switch; no chip moved with it.
        val chipsBefore = MobileDataStored.of(stored)
        tap(dataSaver)
        until("data_saver") { it.dataSaver }
        assertEquals(chipsBefore, MobileDataStored.of(stored))
        // The eight chips: no_video_on_data (the other way round), preload_cellular, smart_downloads_cellular,
        // lyrics_on_mobile_data, canvas_on_cellular, mix_tempo_cellular, sing_cellular.
        chip(useFor, MobileUse.VIDEOS.label)
        until("no_video_on_data") { it.behavior.noVideoOnData }
        chip(useFor, MobileUse.LOAD_AHEAD.label)
        until("preload_cellular") { it.preloadCellular }
        chip(useFor, MobileUse.SMART_DOWNLOADS.label)
        until("smart_downloads_cellular") { it.smartDownloadsCellular }
        chip(useFor, MobileUse.LYRICS.label)
        until("lyrics_on_mobile_data") { !it.lyricsOnMobileData }
        chip(useFor, MobileUse.ANIMATED_COVERS.label)
        until("canvas_on_cellular") { !it.canvasOnCellular }
        chip(useFor, MobileUse.MEASURING.label)
        until("mix_tempo_cellular") { it.mixTempoOnCellular }
        chip(useFor, MobileUse.SING.label)
        until("sing_cellular") { it.singOnCellular }
        // canvas_with_downloads_cellular: its chip shows while covers are saved with downloads.
        assertFalse(MobileUse.SAVING_COVERS.label in chips(useFor))
        tap(saveCovers)
        until("canvas_with_downloads") { it.canvasWithDownloads }
        chip(useFor, MobileUse.SAVING_COVERS.label)
        until("canvas_with_downloads_cellular") { it.canvasWithDownloadsCellular }
        tap(saveCovers)
        until("canvas_with_downloads") { !it.canvasWithDownloads }
        assertTrue("a hidden chip keeps what it holds", stored.canvasWithDownloadsCellular)

        // Every row with a setting now differs from how Prism comes, says so, and the page still fits.
        assertBudgets("everything changed")
        assertEquals(listOf(4, 4, 3, 3, 3), groupSizes())
        assertEquals(11, changedRows())
        // The root's line is live.
        val line = downloadsLine()
        assertTrue(line, formatGb(2f) in line && t(R.string.any_network) in line && dataSaver in line)

        // Reset this page lists each of them and puts them all back; the kept playlists are the listener's own list.
        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 11, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("$loadAhead: ${Preload.NEXT3.label} → ${Preload.START.label}") })
        assertTrue(lines.toString(), lines.any { it.startsWith("$useFor: ") && it.endsWith("→ " + listOf(MobileUse.VIDEOS, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS).joinToString(" · ") { u -> u.label }) })
        until("the reset") {
            it.downloadWifiOnly == d.downloadWifiOnly && it.downloadLikes == d.downloadLikes && it.sound.ownLossyFiles == d.sound.ownLossyFiles &&
                it.behavior.localSkipFolders == d.behavior.localSkipFolders && it.localSections == d.localSections && it.smartDownloads == d.smartDownloads &&
                it.offlineGb == d.offlineGb && it.canvasWithDownloads == d.canvasWithDownloads && it.preload == d.preload && it.dataSaver == d.dataSaver &&
                MobileDataStored.of(it) == MobileDataStored.of(d)
        }
        assertEquals(setOf("PLkept"), stored.behavior.keptPlaylists)
        assertEquals(0, changedRows())
        assertEquals(listOf(4, 3, 3, 3, 3), groupSizes())
    }

    /**
     * 1.8.4 review: Reset this page turns Smart Downloads off and its worker with it. The steps after (its space, its
     * mobile data) read the settings from before the reset and started the worker again: scheduled anew, and run now.
     */
    @Test fun resetLeavesSmartDownloadsWorkerStopped() {
        val app = RuntimeEnvironment.getApplication()
        // A WorkManager of this test's own: the one an earlier test of the class started holds a database Robolectric has
        // since closed (its writes fail quietly; this test reads its work back).
        for (field in listOf("sDelegatedInstance", "sDefaultInstance")) {
            androidx.work.impl.WorkManagerImpl::class.java.getDeclaredField(field).apply { isAccessible = true }.set(null, null)
        }
        androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        val wm = androidx.work.WorkManager.getInstance(app)
        container.settings.setSmartDownloads(true)
        container.settings.setSmartDownloadsCellular(true)
        container.settings.setOfflineGb(10f)
        until("Smart Downloads on, on mobile data, with 10 GB") { it.smartDownloads && it.smartDownloadsCellular && it.offlineGb == 10f }
        // While it's on, its worker is scheduled (as the app's start does).
        com.prism.music.download.SmartDownloadWorker.schedule(app, true, cellular = true)
        fun live(name: String) = wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
        assertEquals(1, live("smart_downloads").size)
        show()
        resetLines(confirm = true)
        val d = AppSettings()
        until("the reset") { it.smartDownloads == d.smartDownloads && it.smartDownloadsCellular == d.smartDownloadsCellular && it.offlineGb == d.offlineGb }
        assertEquals("the periodic worker stays cancelled", emptyList<androidx.work.WorkInfo>(), live("smart_downloads"))
        assertEquals("no run was started", emptyList<androidx.work.WorkInfo>(), wm.getWorkInfosForUniqueWork("smart_downloads_now").get())
    }

    private fun downloadsLine(): String = com.prism.music.ui.screens.settings.downloadsDataValue(stored, dark = true)

    /**
     * A16, 1.8.4: a new install (Smart Downloads off, "Not now" on Home's offer) read "Smart Downloads 5 GB · Wi-Fi only"
     * on the root, as if it were on. Off, the root doesn't name it; on, it says how much room it has.
     */
    @Test fun theRootNamesSmartDownloadsOnlyWhileItsOn() {
        val off = com.prism.music.ui.screens.settings.downloadsDataValue(AppSettings(), dark = true)
        assertTrue(off, t(R.string.smart_downloads) !in off && formatGb(5f) !in off)
        assertTrue("the rest of the line stays: $off", t(R.string.wi_fi_only) in off)
        val on = com.prism.music.ui.screens.settings.downloadsDataValue(AppSettings(smartDownloads = true), dark = true)
        assertTrue(on, t(R.string.smart_downloads) in on && formatGb(5f) in on)
    }

    /** §2.5: the eight "… on mobile data" switches as chips of one row; each chip writes its own stored switch and no other. */
    @Test fun useMobileDataForIsChipsOverTheEightSwitches() {
        // As Prism comes, the chips whose switch was hidden are hidden: Videos (Data saver) and Smart Downloads.
        show()
        assertEquals(
            listOf(MobileUse.LOAD_AHEAD, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS, MobileUse.SAVING_COVERS, MobileUse.MEASURING, MobileUse.SING).map { it.label },
            chips(useFor),
        )
        container.settings.setDataSaver(true)
        container.settings.setSmartDownloads(true)
        // 1.8.6: and Canvas using music videos, for its chip.
        container.settings.setCanvasUseVideos(true)
        until("data saver, Smart Downloads and Canvas's music videos on") { it.dataSaver && it.smartDownloads && it.behavior.canvas.useVideos }
        assertEquals(MobileUse.entries.map { it.label }, chips(useFor))

        // Tick and untick each of the eight: the store is what the chips say, and only that chip's switch moved.
        var expected = mobileDataToMerged(MobileDataStored.of(stored))
        assertEquals(setOf(MobileUse.VIDEOS, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS), expected)
        for (round in 0..1) for (use in MobileUse.entries) {
            expected = if (use in expected) expected - use else expected + use
            chip(useFor, use.label)
            val want = mobileDataToStored(expected)
            until("${use.name}, round $round") { MobileDataStored.of(it) == want }
            assertEquals(MobileUse.entries.filter { it in expected }.joinToString(" · ") { it.label }, valueOf(useFor))
            assertTrue("Data saver stays as it was", stored.dataSaver)
        }
        assertEquals(setOf(MobileUse.VIDEOS, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS), mobileDataToMerged(MobileDataStored.of(stored)))

        // Search finds the row by the switches it took over, and by its chips.
        val entry = SettingsIndex.entries.single { it.title == useFor }
        assertEquals(SettingsPageId.DOWNLOADS_DATA, entry.page)
        assertEquals(gData, entry.group)
        for (old in listOf(R.string.lyrics_on_mobile_data, R.string.load_ahead_on_mobile_data, R.string.no_videos_on_mobile_data, R.string.get_songs_ready_for_sing_on)) {
            assertTrue(t(old), SettingsIndex.search(t(old)).any { it.title == useFor })
        }
        // The chip's ⓘ names Load ahead where it is now (above, in the same group), not Playback & sound (§1's fix, kept).
        val source = File("src/main/java/com/prism/music/ui/screens/settings/DownloadsDataPage.kt").readText()
        assertTrue("off_songs_are_only_loaded_ahead_here" in source)
        assertFalse("the retired text is not used", Regex("""R\.string\.off_songs_are_only_loaded_ahead\b(?!_here)""").containsMatchIn(source))
        assertFalse(t(R.string.playback_sound) in t(R.string.off_songs_are_only_loaded_ahead_here))
        assertEquals(gData, SettingsIndex.entries.single { it.title == loadAhead && it.page == SettingsPageId.DOWNLOADS_DATA }.group)
    }

    /** "Manage downloads" stays a Link to its screen. */
    @Test fun manageDownloadsOpensItsScreen() {
        show()
        addRoutes(Routes.DOWNLOADS)
        assertEquals("page", navController!!.currentDestination?.route)
        tap(manage)
        compose.waitUntil(5_000) { navController!!.currentDestination?.route == Routes.DOWNLOADS }
    }

    /** The ledger's flag (b): `local_sections` had no row in Settings. It has one now, found by search, beside the folders' row. */
    @Test fun sectionsInLocalFilesIsNew() {
        show()
        val e = SettingsIndex.entries.single { it.title == sections }
        assertEquals(SettingsPageId.DOWNLOADS_DATA, e.page)
        assertEquals(gFiles, e.group)
        assertTrue(SettingsIndex.search("local files sections").any { it.title == sections })
        // The sheet is Local files' own: its title, its hint, and its way on to the folders.
        tap(sections)
        assertEquals(1, shown(t(R.string.sections)))
        assertEquals(1, shown(t(R.string.choose_folders)))
        // At least one section stays: with one left, its tick can't be taken.
        container.settings.setLocalSections(listOf(LocalSection.SONGS))
        until("one section") { it.localSections == listOf(LocalSection.SONGS) }
        compose.onNode(hasContentDescription(LocalSection.SONGS.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LocalSection.SONGS), stored.localSections)
        swipeAway(t(R.string.tick_the_sections_you_want_hold))
        assertEquals(LocalSection.SONGS.label, valueOf(sections))
        assertTrue(row(sections).fetchSemanticsNode().config[com.prism.music.ui.screens.settings.SettingsRowChanged])
    }

    /** Links written before the rework still land: the old page's groups by their old names, and search's lines on groups the page draws. */
    @Test fun oldGroupNamesOpenTheGroupThatHoldsThemNow() {
        val old = SettingsPageId.DOWNLOADS_DATA.oldGroups
        assertEquals(gFiles, old[t(R.string.your_own_song_files)])
        assertEquals(gFiles, old[t(R.string.local_files)])
        assertEquals(gSmart, old[smart])
        assertEquals(gData, old[loadAhead])
        fun to(group: String) = SettingsMap.resolve("downloads", group)
        assertEquals(SettingsTarget(SettingsPageId.DOWNLOADS_DATA, gSmart), to(smart))
        assertEquals(SettingsTarget(SettingsPageId.DOWNLOADS_DATA, gData), to(loadAhead))
        assertEquals(SettingsTarget(SettingsPageId.DOWNLOADS_DATA, gData), to(gData))
        assertEquals(SettingsTarget(SettingsPageId.DOWNLOADS_DATA, gDownloads), to(gDownloads))
        assertEquals(SettingsTarget(SettingsPageId.DOWNLOADS_DATA, gCovers), to(gCovers))
        // Every line of the page's search names a group the page draws, and every row drawn has its line.
        show()
        val drawn = groupTitles().toSet()
        val mine = SettingsIndex.entries.filter { it.page == SettingsPageId.DOWNLOADS_DATA && it.group.isNotBlank() }
        val lost = mine.filter { it.group !in drawn }
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        for (title in listOf(manage, wifiOnly, likes, keep, ownFiles, scanAgain, folders, sections, smart, space, clearSmart, saveCovers, savedCovers, recentCovers, loadAhead, dataSaver, useFor)) {
            assertEquals(title, 1, mine.count { it.title == title })
        }
        // Rows under a switch say what to turn on first.
        assertFalse(mine.single { it.title == scanAgain }.needs!!.on(AppSettings()))
        assertFalse(mine.single { it.title == savedCovers }.needs!!.on(AppSettings(canvasEnabled = false)))
    }

    /** With animated covers off, their group goes (as before), and the page is four groups. */
    @Test fun withoutAnimatedCoversTheirGroupGoes() {
        container.settings.setCanvasEnabled(false)
        until("animated covers off") { !it.canvasEnabled }
        show()
        assertBudgets("animated covers off")
        assertEquals(listOf(gDownloads, gFiles, gSmart, gData), groupTitles())
        assertFalse(MobileUse.ANIMATED_COVERS.label in chips(useFor) || MobileUse.SAVING_COVERS.label in chips(useFor))
    }

    /** 1.8.6: "Canvas from videos" is a chip of its own, offered only while Canvas is on and uses music videos. */
    @Test fun canvasFromVideosIsAChipWhileCanvasUsesMusicVideos() {
        show()
        val label = t(R.string.canvas_from_videos)
        assertEquals(label, MobileUse.CANVAS_VIDEOS.label)
        assertFalse("music videos off: no chip", label in chips(useFor))
        assertFalse("off as Prism comes", stored.behavior.canvas.videosOnCellular)
        container.settings.setCanvasUseVideos(true)
        until("music videos on") { it.behavior.canvas.useVideos }
        assertTrue(label in chips(useFor))
        // It writes its own switch and no other.
        val before = MobileDataStored.of(stored)
        chip(useFor, label)
        until("canvas_videos_cellular") { it.behavior.canvas.videosOnCellular }
        assertEquals(before.copy(canvasVideosCellular = true), MobileDataStored.of(stored))
        assertTrue(label in valueOf(useFor))
        assertTrue(row(useFor).fetchSemanticsNode().config[com.prism.music.ui.screens.settings.SettingsRowChanged])
        // Canvas off: the chip goes, what it holds stays.
        container.settings.setCanvasEnabled(false)
        until("canvas off") { !it.canvasEnabled }
        assertFalse(label in chips(useFor))
        assertTrue(stored.behavior.canvas.videosOnCellular)
        container.settings.setCanvasEnabled(true)
        until("canvas on") { it.canvasEnabled }
        // Reset this page puts it back too.
        val lines = resetLines(confirm = true)
        assertTrue(lines.toString(), lines.any { it.startsWith("$useFor: ") })
        until("reset") { !it.behavior.canvas.videosOnCellular }
        // Search finds the row by the chip.
        assertTrue(SettingsIndex.search(label).any { it.title == useFor })
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.settings.setDataSaver(true)
        container.settings.setSmartDownloads(true)
        until("every chip shown") { it.dataSaver && it.smartDownloads }
        show(fontScale = 1.5f)
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("downloads-data", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnLight() = drawn("downloads-data-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnDark() = drawn("downloads-data-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnPerformance() = drawn("downloads-data-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** With everything on: Data saver, Smart Downloads and your own files (the page's longest state). */
    @Test @Config(qualifiers = "w360dp-h2200dp-xxhdpi") fun drawnWithEverythingOn() = drawn("downloads-data-dark-everything-on", true) {
        it.copy(themeMode = ThemeMode.DARK, dataSaver = true, smartDownloads = true, offlineGb = 10f, sound = it.sound.copy(ownLossyFiles = true), preloadCellular = true)
    }
}
