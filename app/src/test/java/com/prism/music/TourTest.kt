package com.prism.music

import com.prism.music.ui.tour.Tour
import com.prism.music.ui.tour.TourStep
import org.junit.Assert.assertEquals
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
        val new = Tour.stepsAfter(54)
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

    /** Every "Show me" opens a page that exists: a settings page by a key Settings knows, or one of the app's routes. */
    @Test
    fun everyShowMeOpensARealPage() {
        val keys = com.prism.music.ui.screens.SettingsSection.entries.map { it.key }.toSet()
        val routes = setOf(
            com.prism.music.ui.Routes.SETTINGS, com.prism.music.ui.Routes.SEARCH, com.prism.music.ui.Routes.CATALOGUE,
            com.prism.music.ui.Routes.BRING_HISTORY,
        )
        for (route in Tour.steps.mapNotNull { it.showMe }) {
            if (route.startsWith("settings/")) {
                val key = route.removePrefix("settings/").substringBefore('?')
                assertTrue("$route: no settings page \"$key\"", key in keys)
            } else assertTrue("$route isn't a page the tour knows", route in routes)
        }
    }
}
