package com.prism.music.data.lyrics.roman

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import com.prism.music.data.lyrics.RubySpan
import java.io.File
import java.io.InputStream

/**
 * Japanese with kanji, in Latin letters: Kuromoji splits each line into words and gives every word its reading,
 * which [Kana] then spells out in Hepburn, with the particles said as they're said (は "wa", へ "e", を "o") and a space
 * between words ("君の名は" → "kimi no na wa").
 */
class Japanese private constructor(private val tokenizer: Tokenizer) {
    /**
     * Each character of [text] spelled out: a word's whole reading on its first Japanese character (with a space before
     * it when it starts a new word), nothing on the rest. Numbers stay as they were written, with the reading after them.
     */
    fun pieces(text: String): List<String> {
        val out = MutableList(text.length) { text[it].toString() }
        // Numbers are fed to Kuromoji as Japanese numerals (2人 → 二人), so counters, dates and times get the reading
        // Japanese gives them; the line itself keeps the numbers as they were written.
        val tokens = tokenizer.tokenize(numbersAsKanji(text))
        var first = true
        // A word whose reading ends in っ (だっ, 分かっ) doubles the consonant of the next one: "datta", "wakatta".
        var doubling = false
        for (t in tokens) {
            val at = t.position
            val len = t.surface.length
            if (at < 0 || at + len > text.length) continue
            if (t.surface.isBlank()) { first = true; doubling = false; continue }
            val original = text.substring(at, at + len)
            // Where the word starts on screen: a number Kuromoji read as Japanese stays a number, so the reading goes on
            // the first character of it that isn't one.
            val place = (at until at + len).firstOrNull { isJapanese(text[it]) }
            val reading = readingAt(t, text, at)
            var roman = romaji(t, reading)
            // Not Japanese (Latin words, digits, punctuation) stays as written.
            if (roman == null || place == null) { first = original.last().isWhitespace(); doubling = false; continue }
            if (doubling && roman.isNotEmpty()) roman = when {
                roman.startsWith("ch") -> "t$roman"
                roman.first() in "aeiou" -> roman
                else -> roman.first() + roman
            }
            doubling = reading.endsWith('ッ')
            val joins = !first && attaches(t)
            // A number stays the number it was written as, with the reading after it: 2人 is "2futari".
            val space = !first && !joins && !text[place - 1].isWhitespace() && !isNumber(text[place - 1])
            out[place] = (if (space) " " else "") + roman
            for (k in 0 until len) {
                val i = at + k
                if (i != place && isJapanese(text[i])) out[i] = ""
            }
            first = false
        }
        return out
    }

    fun romanize(text: String): String = pieces(text).joinToString("")

    /**
     * Each run of kanji in [text] with the hiragana it's read as: 君の名は → きみ above 君, な above 名. Okurigana and
     * other kana shared by the word and its reading are left out of the reading, so only the kanji is annotated.
     */
    fun furigana(text: String): List<RubySpan> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<RubySpan>()
        // As [pieces]: Kuromoji reads 2人 as 二人, while the line keeps its Arabic number.
        for (t in tokenizer.tokenize(numbersAsKanji(text))) {
            val at = t.position
            val surface = t.surface
            if (at < 0 || at + surface.length > text.length) continue
            if (surface.none(::isKanji)) continue
            if (t.reading.isBlank() || t.reading == "*") continue
            val surfaceKana = Kana.toKatakana(surface)
            var readingKana = readingAt(t, text, at)
            var start = 0
            var end = surface.length
            // Kana the word shares with its reading at either end (お茶 → オチャ, 食べる → タベル) belongs to those
            // characters, not to the kanji between them.
            while (start < end && surfaceKana[start].let(Kana::isKana) && readingKana.firstOrNull() == surfaceKana[start]) {
                start++
                readingKana = readingKana.drop(1)
            }
            while (end > start && surfaceKana[end - 1].let(Kana::isKana) && readingKana.lastOrNull() == surfaceKana[end - 1]) {
                end--
                readingKana = readingKana.dropLast(1)
            }
            if (readingKana.isBlank()) continue
            // A number on screen stays a number: its furigana goes above the kanji the line actually has (2人's ふたり
            // above 人), not above the digit.
            var firstKanji = -1
            var lastKanji = -1
            for (i in at + start until at + end) {
                if (isKanji(text[i])) {
                    if (firstKanji < 0) firstKanji = i
                    lastKanji = i
                }
            }
            if (firstKanji < 0) continue
            out += RubySpan(firstKanji, lastKanji + 1, Kana.toHiragana(readingKana))
        }
        return out
    }

    private fun isKanji(c: Char) = com.prism.music.data.lyrics.isKanji(c)

    /** Whether [c] is written in Japanese (kana or kanji): digits and Latin words are not. */
    private fun isJapanese(c: Char) = Kana.isKana(c) || isKanji(c) || c == 'ー' || c == '々'

    private fun isNumber(c: Char) = c in '0'..'9' || c in '０'..'９'

    /** [text] with each Arabic or full-width digit as a kanji numeral: the same length, one character for one. */
    private fun numbersAsKanji(text: String): String {
        if (text.none(::isNumber)) return text
        return buildString(text.length) {
            for (c in text) append(
                when (c) {
                    '0', '０' -> '〇'
                    '1', '１' -> '一'
                    '2', '２' -> '二'
                    '3', '３' -> '三'
                    '4', '４' -> '四'
                    '5', '５' -> '五'
                    '6', '６' -> '六'
                    '7', '７' -> '七'
                    '8', '８' -> '八'
                    '9', '９' -> '九'
                    else -> c
                },
            )
        }
    }

    /** The word's reading spelled out, or null when it isn't Japanese. */
    private fun romaji(t: Token, reading: String = reading(t)): String? {
        if (t.surface.none { Kana.isKana(it) || Character.isIdeographic(it.code) || it == 'ー' || it == '々' }) return null
        // Particles are written as they're said.
        if (t.partOfSpeechLevel1 == "助詞") when (t.surface) {
            "は" -> return "wa"
            "へ" -> return "e"
            "を" -> return "o"
        }
        return Kana.romanize(reading).lowercase()
    }

    private fun reading(t: Token): String = Kana.toKatakana(t.reading.takeIf { it.isNotBlank() && it != "*" } ?: t.surface)

    /**
     * [t]'s reading, when it starts inside a number written with more than one digit: the user dictionary's words (二人,
     * 一日) can start there too (十二人 was 十 + 二人), so only the part after the number is read.
     */
    private fun readingAt(t: Token, text: String, at: Int): String {
        if (at <= 0 || !isNumber(text[at - 1]) || !isNumber(text[at])) return reading(t)
        var skip = 0
        while (skip < t.surface.length && at + skip < text.length && isNumber(text[at + skip])) skip++
        if (skip == 0 || skip >= t.surface.length) return reading(t)
        // Read the rest after a longer number, so the counter keeps that reading (人 after 十 is にん, not ひと). 十三 is
        // just a number long enough that its own reading doesn't come out special (十人 is じゅうにん, 十日 is とおか).
        val prefix = "十三"
        val words = tokenizer.tokenize(prefix + t.surface.substring(skip))
        val after = words.filter { it.position >= prefix.length }
        if (after.isEmpty()) return reading(t)
        return after.joinToString("") { w -> Kana.toKatakana(w.reading.takeIf { it.isNotBlank() && it != "*" } ?: w.surface) }
    }

    /** Words that are said joined to the one before (verb endings, auxiliaries, suffixes): "mite", "aishiteru". */
    private fun attaches(t: Token): Boolean =
        t.partOfSpeechLevel1 == "助動詞" ||
            (t.partOfSpeechLevel1 == "助詞" && t.partOfSpeechLevel2 == "接続助詞") ||
            t.partOfSpeechLevel2 == "接尾"

    companion object {
        /**
         * Words IPADIC reads the way they're written in a list rather than as they're sung (二人 as "ni nin"): read
         * as songs say them. Kuromoji's user dictionary format: surface, segmentation, reading, part of speech.
         */
        private val LYRIC_WORDS = listOf(
            "二人" to "フタリ", "一人" to "ヒトリ", "一人きり" to "ヒトリキリ", "二人きり" to "フタリキリ", "今日" to "キョウ",
            "明日" to "アシタ", "昨日" to "キノウ", "大人" to "オトナ", "一日" to "イチニチ", "今夜" to "コンヤ",
            "永遠" to "エイエン", "運命" to "ウンメイ", "世界" to "セカイ", "未来" to "ミライ", "心" to "ココロ",
            "涙" to "ナミダ", "笑顔" to "エガオ", "一緒" to "イッショ", "宇宙" to "ウチュウ", "何処" to "ドコ", "何故" to "ナゼ",
        )

        private fun userDictionary() = LYRIC_WORDS.joinToString("\n") { (w, r) -> "$w,$w,$r,カスタム名詞" }.byteInputStream()

        /** A reader over IPADIC from [dir] (unpacked by [com.prism.music.data.ModelStore]). Takes a second or two. */
        fun load(dir: File): Japanese = Japanese((FromFiles(dir).userDictionary(userDictionary()) as Tokenizer.Builder).build())

        /** A reader over the dictionary inside Kuromoji's own jar (tests on the PC have it; the APK leaves it out). */
        fun bundled(): Japanese = Japanese((Tokenizer.Builder().userDictionary(userDictionary()) as Tokenizer.Builder).build())
    }

    /** Kuromoji's IPADIC tokenizer, reading its dictionary files from a folder instead of from its own jar. */
    private class FromFiles(private val dir: File) : Tokenizer.Builder() {
        override fun loadDictionaries() {
            // Kuromoji's own defaults for IPADIC (they only matter in its search modes).
            penalties = arrayListOf(2, 3000, 7, 1700)
            resolver = ResourceResolver { name -> File(dir, name).inputStream().buffered() as InputStream }
            doubleArrayTrie = DoubleArrayTrie.newInstance(resolver)
            connectionCosts = ConnectionCosts.newInstance(resolver)
            tokenInfoDictionary = TokenInfoDictionary.newInstance(resolver)
            characterDefinitions = CharacterDefinitions.newInstance(resolver)
            unknownDictionary = UnknownDictionary.newInstance(resolver, characterDefinitions, totalFeatures)
            insertedDictionary = InsertedDictionary(totalFeatures)
        }
    }
}
