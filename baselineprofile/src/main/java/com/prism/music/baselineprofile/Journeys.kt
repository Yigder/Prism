package com.prism.music.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app these journeys drive: the release build (and the build types the plugin derives from it). */
const val PACKAGE = "com.prism.music"

/**
 * What people do most in Prism, scripted with UI Automator: the screens whose code should be compiled ahead
 * ([BaselineProfileGenerator]) and whose frames are timed ([JankBenchmark]). Written to work signed out, on a fresh
 * install (it gets past the first-run screens itself) and with whatever Home YouTube serves.
 *
 * Nothing here presses Home or leaves Prism: on the Android 17 emulator, the task snapshot that takes restarts the
 * system (see .claude/emulator.ps1).
 */
object Journeys {
    private const val WAIT = 15_000L

    /** Past the welcome screen, the artist picker and the tour, when they show (a fresh install). */
    fun MacrobenchmarkScope.pastOnboarding() {
        for (label in listOf("Skip for now", "Skip", "Skip tour")) {
            device.wait(Until.findObject(By.text(label)), 4_000)?.click()
            device.waitForIdle(1_000)
        }
        device.wait(Until.hasObject(By.desc("Home")), WAIT)
    }

    /** Down Home a screen at a time and back up: covers, shelves and Prism's own sections compose as they come in. */
    fun MacrobenchmarkScope.scrollHome() {
        tab("Home")
        device.wait(Until.hasObject(By.textContains("Quick picks").pkg(PACKAGE)), 8_000)
        flingPage(down = true, times = 5)
        flingPage(down = false, times = 5)
    }

    /** Search for [query], then scroll through the results. */
    fun MacrobenchmarkScope.search(query: String) {
        tab("Search")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT) ?: return
        field.click()
        field.text = query
        // One of Prism's own suggestions under the box runs the search. (An injected Enter goes to the on-screen
        // keyboard, which took it as "accept the word suggestion" and never searched.)
        // 1.7.6: the first one is "Search for “<query>”".
        val suggestion = device.wait(Until.findObject(By.textStartsWith("Search for").pkg(PACKAGE)), WAIT)
            ?: device.wait(Until.findObjects(By.textStartsWith(query).pkg(PACKAGE)), WAIT)
                ?.firstOrNull { it.className != "android.widget.EditText" }
        if (suggestion == null || !suggestion.tap()) device.pressEnter()
        // The results' first song row (rows have a "More" button).
        device.wait(Until.hasObject(By.desc("More")), WAIT)
        flingPage(down = true, times = 3)
        flingPage(down = false, times = 3)
    }

    /** Plays the first song row on screen, opens the player, looks at the lyrics and puts the player away. */
    fun MacrobenchmarkScope.playAndOpenPlayer() {
        val more = device.wait(Until.findObject(By.desc("More")), WAIT) ?: return
        // The row's title, well left of its ⋯ button (which opens the song's menu instead).
        device.click(device.displayWidth / 3, more.visibleBounds.centerY())
        val play = device.wait(Until.findObject(By.desc("Play/pause")), WAIT) ?: return
        // The mini player: tap its title area, left of the buttons.
        val r = play.visibleBounds
        device.click(device.displayWidth / 3, r.centerY())
        device.wait(Until.hasObject(By.desc("Lyrics")), WAIT)
        device.waitForIdle(2_000)
        device.findObject(By.desc("Lyrics"))?.tap()
        device.waitForIdle(3_000)
        device.findObject(By.desc("Lyrics"))?.tap()
        device.waitForIdle(1_000)
        // Swipe the player down.
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h / 5, w / 2, h * 9 / 10, 25)
        device.waitForIdle(1_500)
        // Still in the player (the lyrics took the swipe, or it didn't count)? Back closes the lyrics, then the player.
        // While the player is open the tab bar is hidden from accessibility, so the Library tab shows it's closed.
        repeat(3) {
            if (device.hasObject(By.desc("Library").pkg(PACKAGE))) return
            device.pressBack()
            device.waitForIdle(1_500)
        }
    }

    /** Library: its tiles and lists. */
    fun MacrobenchmarkScope.library() {
        tab("Library")
        device.waitForIdle(2_000)
        flingPage(down = true, times = 2)
        flingPage(down = false, times = 2)
    }

    /** Settings (its tab), and the Customize Prism page. */
    fun MacrobenchmarkScope.settings() {
        tab("Settings")
        device.wait(Until.findObject(By.text("Customize Prism")), WAIT)?.tap() ?: return
        device.waitForIdle(2_000)
        flingPage(down = true, times = 2)
        device.pressBack()
        device.waitForIdle(1_000)
        device.pressBack()
        device.waitForIdle(1_000)
    }

    /** A click that tolerates the view being redrawn between finding and clicking it (the lyrics button does). */
    private fun UiObject2.tap(): Boolean = try {
        click(); true
    } catch (_: StaleObjectException) {
        false
    }

    private fun MacrobenchmarkScope.tab(name: String) {
        // The tab bar's buttons carry their names as descriptions; Home's top bar has others, so take the lowest one.
        val tabs = device.findObjects(By.desc(name).pkg(PACKAGE))
        tabs.maxByOrNull { it.visibleBounds.centerY() }?.click()
        device.waitForIdle(1_500)
    }

    /** A page-long fling, [times] over: the way a thumb scrolls a list. */
    private fun MacrobenchmarkScope.flingPage(down: Boolean, times: Int) {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(times) {
            if (down) device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 18) else device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 18)
            device.waitForIdle(700)
        }
    }
}
