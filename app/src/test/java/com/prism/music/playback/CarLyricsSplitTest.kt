package com.prism.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLyricsSplitTest {
    @Test
    fun shortLineKeepsSongFallback() {
        val (top, bottom) = CarLyrics.splitLyric("Short line", "Title · Artist")
        assertEquals("Short line", top)
        assertEquals("Title · Artist", bottom)
    }

    @Test
    fun longEnglishLineSplitsAtWordBoundary() {
        val line = "Yesterday all my troubles seemed so far away"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 24)
        assertEquals("Yesterday all my", top)
        assertEquals("troubles seemed so far away", bottom)
    }

    @Test
    fun longRemainderIsNotTruncated() {
        val line = "First line is pretty short here but second line continues on and on and on and on and on and never gets truncated"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 30)
        assertEquals("First line is pretty short", top)
        assertEquals("here but second line continues on and on and on and on and on and never gets truncated", bottom)
    }

    @Test
    fun longWordWithoutSpacesSplitsAtLimit() {
        val line = "SupercalifragilisticexpialidociousAndMore"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 20)
        assertEquals("Supercalifragilistic", top)
        assertEquals("expialidociousAndMore", bottom)
    }

    @Test
    fun cjkSplitsAtLimitWhenNoSpaces() {
        val line = "原谅我这一生不羁放纵爱自由也会怕有一天会跌倒"
        val (top, bottom) = CarLyrics.splitLyric(line, "Title · Artist", maxTitle = 14)
        assertEquals("原谅我这一生不羁放纵爱自由也", top) // 14 characters
        assertEquals("会怕有一天会跌倒", bottom)
    }
}
