package com.prism.music

import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.prefs.LyricsSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CensoredLyricsTest {
    private fun lyrics(vararg lines: String) = Lyrics(LyricsSource.LRCLIB, true, lines.mapIndexed { i, t -> LyricLine(i * 1000L, t) })

    @Test fun starredWordsAreCensored() {
        listOf("What the f*ck is this", "Oh f***, here we go", "Holy sh*t", "This is some sh**", "Mother**ckers", "f##k it", "Damn [censored] man")
            .forEach { assertTrue(it, lyrics("A clean line", it).censored) }
    }

    @Test fun ordinaryLyricsAreNot() {
        listOf("I *really* love you", "*laughs* yeah", "Number #1 in the world", "Rock & roll - all night", "Fuck it, we ball", "Shit, I'm late")
            .forEach { assertFalse(it, lyrics(it).censored) }
    }
}
