package com.prism.music

import com.prism.music.ui.screens.settings.SettingsMap
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.SettingsTarget
import com.prism.music.ui.screens.settings.index
import com.prism.music.ui.tour.Tour
import com.prism.music.ui.tour.TourStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour's rules: a new install (or the first release with tours) sees every step; someone who finished the tour at
 * a version sees only steps added since; and no step is dated after the build it ships in (it could never be marked
 * seen, and would come back at every start).
 */
// Robolectric: the tour's words come from strings.xml (1.7.9).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class TourTest {
    @Test
    fun newInstallsSeeTheWholeTour() {
        assertEquals(Tour.steps, Tour.stepsAfter(0))
        assertTrue(Tour.isFullTour(0))
    }

    @Test
    fun aTourSeenAtThisVersionShowsNothingMore() {
        assertTrue(Tour.stepsAfter(BuildConfig.VERSION_CODE).isEmpty())
    }

    @Test
    fun updatesSeeOnlyNewerSteps() {
        for (seen in listOf(1, 38, 39)) {
            assertTrue(Tour.stepsAfter(seen).all { it.since > seen })
            assertEquals(Tour.steps.count { it.since > seen }, Tour.stepsAfter(seen).size)
        }
    }

    @Test
    fun stepsAreDatedNoLaterThanThisBuild() {
        assertTrue(Tour.steps.all { it.since in 1..BuildConfig.VERSION_CODE })
        assertTrue("every step says something", Tour.steps.all { it.title.isNotBlank() && it.body.isNotBlank() })
    }

    /** 1.8.1 (build 55): someone updating from 1.8.0 (build 54) sees its five pages, in order, and nothing older. */
    @Test
    fun anUpdateFrom180ShowsThe181Pages() {
        val new = Tour.stepsAfter(54).filter { it.since <= 55 }
        assertTrue(new.all { it.since == 55 && it.kind == TourStep.Kind.PLAIN })
        assertEquals(
            listOf(R.string.tour_181_history, R.string.tour_181_spotify, R.string.tour_181_replay, R.string.tour_181_yours, R.string.also_new)
                .map { tr(it) },
            new.map { it.title },
        )
        assertEquals(com.prism.music.ui.Routes.BRING_HISTORY, new[0].showMe)
        assertEquals(com.prism.music.ui.Routes.settings("spotify"), new[1].showMe)
        assertEquals("settings/library?find=History", new[2].showMe)
        // Short pages: a few points each.
        assertTrue(new.all { it.points.size in 3..5 })
    }

    /** 1.8.2 (build 57): the lock screen player has its page, which opens where it's switched on. */
    @Test
    fun anUpdateFrom181ShowsTheLockScreenPage() {
        val page = Tour.stepsAfter(56).first { it.title == tr(R.string.tour_182_lock_title) }
        assertEquals(57, page.since)
        assertTrue(page.showMe!!.startsWith("settings/player"))
    }

    /** 1.8.2: the new Home sections are only offered in Customize Home, so this page is how listeners hear of them. */
    @Test
    fun anUpdateFrom181HearsOfTheNewHomeSections() {
        val page = Tour.stepsAfter(56).first { it.title == tr(R.string.tour_182_discover_title) }
        assertEquals(57, page.since)
        assertTrue(page.showMe!!.startsWith("settings/library"))
    }

    /** 1.8.3: an update from 1.8.2.1 (58) shows smart playlists, tags, select many, library search and the rest. */
    @Test
    fun anUpdateFrom182ShowsThe183Pages() {
        val pages = Tour.stepsAfter(58).filter { it.since <= 59 }
        assertEquals(5, pages.size)
        assertTrue(pages.all { it.since == 59 })
        assertEquals(tr(R.string.tour_183_smart_title), pages.first().title)
        assertEquals(com.prism.music.ui.Routes.librarySearch(), pages.first { it.title == tr(R.string.tour_183_search_title) }.showMe)
    }

    /**
     * 1.8.4 (build 61): an update from 1.8.3.1 (60) shows Settings made simple, lyrics timing, the lock screen in the
     * player's look, finding the playing song and the rest, in that order, and nothing older.
     */
    @Test
    fun anUpdateFrom1831ShowsThe184Pages() {
        val pages = Tour.stepsAfter(60).filter { it.since <= 61 }
        assertTrue(pages.all { it.since == 61 && it.kind == TourStep.Kind.PLAIN })
        assertEquals(
            listOf(R.string.tour_184_settings_title, R.string.tour_184_lyrics_title, R.string.tour_184_lock_title, R.string.tour_184_lists_title, R.string.also_new)
                .map { tr(it) },
            pages.map { it.title },
        )
        // As many pages as the releases before it, a few points each.
        assertTrue(pages.all { it.points.size in 3..5 && it.points.all { p -> p.isNotBlank() } })
        // The settings page opens Settings' first page (plan §5); "Also new" opens nothing, as before.
        assertEquals(com.prism.music.ui.Routes.SETTINGS, pages.first().showMe)
        assertNull(pages.last().showMe)
        // A point that names a row says the row's own name (filled in, not a "%1$s").
        assertTrue(pages.flatMap { it.points }.none { "%" in it })
        assertTrue(pages[2].points.first().contains(tr(R.string.settings_player_same_look)))
        assertTrue(pages[3].points.first().contains(tr(R.string.go_to_the_playing_song)))
    }

    /**
     * 1.8.4: each step about a setting opens the page that holds it, at the group that draws it. Checked through
     * [SettingsMap.resolve] (the route's page and group) and the page's search lines (each names the group its row is
     * drawn in; SettingsMapTest holds those lines to the drawn page).
     */
    @Test
    fun the184StepsOpenThePlaceTheyDescribe() {
        val pages = Tour.stepsAfter(60).filter { it.since <= 61 }
        // Step title → (page, group, a row the step talks of that is drawn in that group).
        val places = mapOf(
            tr(R.string.tour_184_lyrics_title) to Triple(SettingsPageId.LYRICS_SING, tr(R.string.ls_sources_and_timing), tr(R.string.ls_offset)),
            tr(R.string.tour_184_lock_title) to Triple(SettingsPageId.PLAYER, tr(R.string.settings_player_modes), tr(R.string.settings_player_same_look)),
            tr(R.string.tour_184_lists_title) to Triple(SettingsPageId.HOME_LIBRARY, tr(R.string.hl_lists), tr(R.string.hl_locate_button)),
        )
        for ((title, place) in places) {
            val (page, group, row) = place
            val step = pages.firstOrNull { it.title == title }
            assertNotNull("no 1.8.4 step \"$title\"", step)
            val route = step!!.showMe
            assertNotNull("\"$title\" has no Show me", route)
            val (key, find) = parse(route!!)
            assertEquals("$route: its own page's key", page.key, key)
            assertEquals(route, SettingsTarget(page, group), SettingsMap.resolve(key, find))
            assertTrue("$route: ${page.title} draws no group \"$group\"", page.index.any { it.group == group })
            assertTrue("$route: \"$row\" isn't in ${page.title} › $group", page.index.any { it.group == group && it.title == row })
        }
    }

    /**
     * 1.8.6 (build 64): an update from 1.8.5 (63) shows Canvas, then the hours on Replay's chart, and nothing older.
     * Canvas's page names the switch and the menu's rows as they are written, and opens the Player page at Cover.
     */
    @Test
    fun anUpdateFrom185ShowsCanvas() {
        val pages = Tour.stepsAfter(63)
        assertEquals(listOf(R.string.tour_186_canvas_title, R.string.also_new).map { tr(it) }, pages.map { it.title })
        assertTrue(pages.all { it.since == 64 && it.kind == TourStep.Kind.PLAIN })
        assertTrue(pages.flatMap { it.points + it.body }.none { "%" in it })
        val canvas = pages.first()
        assertEquals(4, canvas.points.size)
        assertTrue(canvas.points[1].contains(tr(R.string.canvas_use_videos)))
        assertTrue(canvas.points[2].contains(tr(R.string.canvas_pick_part)) && canvas.points[2].contains(tr(R.string.canvas_not_this_video)))
        val (key, find) = parse(canvas.showMe!!)
        assertEquals(com.prism.music.ui.screens.settings.SettingsPageId.PLAYER.key, key)
        assertEquals(tr(R.string.cover), find)
        assertTrue(pages.last().points.single().contains(tr(R.string.hours_by_month)))
        assertEquals(com.prism.music.ui.Routes.REPLAY, pages.last().showMe)
    }

    /**
     * 1.8.5 (build 63): an update from 1.8.4.1 (62) shows Replay's new sections, Rewind 2026, the widgets and notices,
     * and the rest, in that order, and nothing older.
     */
    @Test
    fun anUpdateFrom1841ShowsThe185Pages() {
        val pages = Tour.stepsAfter(62).filter { it.since == 63 }
        assertTrue(pages.all { it.kind == TourStep.Kind.PLAIN })
        assertEquals(
            listOf(R.string.tour_185_replay_title, R.string.tour_185_rewind_title, R.string.tour_185_more_title, R.string.also_new).map { tr(it) },
            pages.map { it.title },
        )
        assertTrue(pages.all { it.points.size in 3..5 && it.points.all { p -> p.isNotBlank() } })
        assertTrue(pages.flatMap { it.points }.none { "%" in it })
        assertEquals(com.prism.music.ui.Routes.REPLAY, pages.first().showMe)
        assertNull(pages.last().showMe)
        // The notices and Finish the lyric are named as their rows are, and the step opens the group that draws them.
        val more = pages[2]
        assertTrue(more.points[1].contains(tr(R.string.replay_notices)))
        assertTrue(more.points[2].contains(tr(R.string.finish_lyric_title)))
        val (key, find) = parse(more.showMe!!)
        val page = SettingsPageId.RECOMMENDATIONS_HISTORY
        assertEquals(SettingsTarget(page, tr(R.string.replay_2)), SettingsMap.resolve(key, find))
        for (row in listOf(R.string.replay_notices, R.string.finish_lyric_title)) {
            assertTrue(page.index.any { it.group == tr(R.string.replay_2) && it.title == tr(row) })
        }
        assertTrue(pages[3].points[2].contains(tr(R.string.playback_face_down_pause)))
    }
    /** `settings/<key>?find=<group>` → its key and group, as the app's route reads it. */
    private fun parse(route: String): Pair<String, String?> {
        val key = route.removePrefix("settings/").substringBefore('?')
        val find = route.substringAfter("?find=", "").takeIf { it.isNotBlank() }?.let { android.net.Uri.decode(it) }
        return key to find
    }

    /**
     * Every "Show me" opens a page that exists: a settings page by a key Settings resolves (1.8.4's page keys and the
     * old ones), or one of the app's routes.
     */
    @Test
    fun everyShowMeOpensARealPage() {
        val routes = setOf(
            com.prism.music.ui.Routes.SETTINGS, com.prism.music.ui.Routes.SEARCH, com.prism.music.ui.Routes.CATALOGUE,
            com.prism.music.ui.Routes.BRING_HISTORY, com.prism.music.ui.Routes.LIBRARY, com.prism.music.ui.Routes.librarySearch(),
            com.prism.music.ui.Routes.REPLAY,
        )
        for (route in Tour.steps.mapNotNull { it.showMe }) {
            if (route.startsWith("settings/")) {
                val (key, find) = parse(route)
                assertNotNull("$route: no settings page \"$key\"", SettingsMap.resolve(key, find))
            } else assertTrue("$route isn't a page the tour knows", route in routes)
        }
    }
}
