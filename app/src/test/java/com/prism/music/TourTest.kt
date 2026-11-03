package com.prism.music

import com.prism.music.ui.tour.Tour
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
}
