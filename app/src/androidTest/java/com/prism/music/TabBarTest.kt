package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.data.prefs.NavLabels
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #272 (1.7.9.3): with tab labels "Selected", every tab icon was drawn a little smaller (22 dp, not 24). */
@RunWith(AndroidJUnit4::class)
class TabBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @After fun reset() { runBlocking { Fixture.container.settings.setNavLabels(NavLabels.ALWAYS) } }

    /** The Library tab's icon: the tab bar's, at the bottom of the screen. */
    private fun libraryIconWidth(): Float {
        val nodes = compose.onAllNodes(hasContentDescription(tr(R.string.library)), useUnmergedTree = true).fetchSemanticsNodes()
        val bottom = nodes.maxByOrNull { it.boundsInRoot.top }!!
        return bottom.boundsInRoot.width
    }

    @Test fun tabIconsAreOneSizeInEveryLabelStyle() {
        Fixture.ready()
        Fixture.launch()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(tr(R.string.search)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val widths = NavLabels.entries.filter { it != NavLabels.WORDS }.associateWith { style ->
            runBlocking { Fixture.container.settings.setNavLabels(style) }
            compose.waitForIdle(); Thread.sleep(500)
            libraryIconWidth()
        }
        val always = widths.getValue(NavLabels.ALWAYS)
        widths.forEach { (style, w) -> assertEquals("$style: $widths", always, w, 1f) }
    }
}
