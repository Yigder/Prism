package com.prism.music.data.lyrics

/**
 * A run of kanji in a lyric line and the hiragana reading shown above it (furigana), as character offsets into the
 * line's text: [start] is inclusive and [end] exclusive.
 */
data class RubySpan(val start: Int, val end: Int, val reading: String)

/** Whether [text] has a kanji in it, so [RubySpan]s can be shown above it. */
fun hasKanji(text: String): Boolean = text.any { isKanji(it) }

internal fun isKanji(c: Char): Boolean =
    Character.isIdeographic(c.code) || c == '々' || c == '〆' || c == 'ヶ'
