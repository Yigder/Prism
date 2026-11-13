package com.prism.music.ui.screens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** 1.8.6: the number over each bar of Replay's "Hours by month" (owner's ask: show the actual hours on the graph). */
class MonthBarLabelTest {
    private val before = Locale.getDefault()
    @Before fun us() = Locale.setDefault(Locale.US)
    @After fun back() = Locale.setDefault(before)

    private val min = 60_000L
    private val hour = 60 * min

    @Test fun anEmptyMonthSaysNothing() {
        assertEquals("", monthBarLabel(0, hours = true))
        assertEquals("", monthBarLabel(0, hours = false))
    }

    @Test fun hoursAreWholeAndNeverRoundedUp() {
        // The owner's October: 116h 29m reads 116h. Seen on the A16: 19h 36m read "20h" over a line saying "19h 36m".
        assertEquals("116h", monthBarLabel(116 * hour + 29 * min, hours = true))
        assertEquals("19h", monthBarLabel(19 * hour + 36 * min, hours = true))
        assertEquals("116h", monthBarLabel(116 * hour + 59 * min, hours = true))
        assertEquals("1h", monthBarLabel(hour, hours = true))
        assertEquals("1,204h", monthBarLabel(1204 * hour, hours = true))
    }

    @Test fun underAnHourIsNotRoundedToNothing() {
        assertEquals("<1h", monthBarLabel(59 * min, hours = true))
        assertEquals("<1h", monthBarLabel(20_000, hours = true))
    }

    @Test fun minutesStayShortEnoughForABar() {
        assertEquals("<1", monthBarLabel(20_000, hours = false))
        assertEquals("940", monthBarLabel(940 * min, hours = false))
        assertEquals("7.0k", monthBarLabel(6_989 * min, hours = false))
        assertEquals("120k", monthBarLabel(119_600 * min, hours = false))
        // Never wider than the hours' own widest ("1,204h"): the bars are about four letters wide.
        listOf(1L, 999, 1_000, 99_999, 100_000, 999_999).forEach { assert(monthBarLabel(it * min, false).length <= 6) }
    }
}
