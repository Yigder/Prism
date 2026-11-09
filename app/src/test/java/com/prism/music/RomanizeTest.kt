package com.prism.music

import com.prism.music.data.lyrics.RubySpan
import com.prism.music.data.lyrics.roman.Cyrillic
import com.prism.music.data.lyrics.roman.Greek
import com.prism.music.data.lyrics.roman.Hangul
import com.prism.music.data.lyrics.roman.Kana
import org.junit.Assert.assertEquals
import org.junit.Test

/** The on-device romanizers: Korean (Revised Romanization), kana (Hepburn), Cyrillic, Greek. */
class RomanizeTest {
    @Test
    fun korean() {
        assertEquals("saranghae", Hangul.romanize("사랑해"))
        assertEquals("saranghaeyo gachi gayo", Hangul.romanize("사랑해요 같이 가요"))
        assertEquals("hamnida", Hangul.romanize("합니다"))
        assertEquals("silla", Hangul.romanize("신라"))
        assertEquals("joko", Hangul.romanize("좋고"))
        assertEquals("joa", Hangul.romanize("좋아"))
        assertEquals("bom", Hangul.romanize("봄"))
        // One piece per syllable, so karaoke timings stay with their syllables.
        assertEquals(listOf("ga", "chi"), Hangul.pieces("같이"))
    }

    @Test
    fun kana() {
        assertEquals("kitte", Kana.romanize("きって"))
        assertEquals("matcha", Kana.romanize("まっちゃ"))
        assertEquals("kyou", Kana.romanize("きょう"))
        assertEquals("koohii", Kana.romanize("コーヒー"))
        assertEquals("shashin", Kana.romanize("しゃしん"))
        assertEquals("fan", Kana.romanize("ファン"))
    }

    @Test
    fun japaneseWithKanji() {
        val ja = com.prism.music.data.lyrics.roman.Japanese.bundled()
        assertEquals("kimi no na wa", ja.romanize("君の名は"))
        assertEquals("yume o mite ita", ja.romanize("夢を見ていた"))
        assertEquals("aishiteru", ja.romanize("愛してる").replace(" ", ""))
        // A small っ at a word's end doubles the next word's consonant; 二人 is "futari", as sung.
        assertEquals("sayonara dakedatta", ja.romanize("さよならだけだった"))
        assertEquals("sono hitokoto de subete ga wakatta", ja.romanize("その一言で全てが分かった"))
        assertEquals("futari dake no sora ga hirogaru yoru ni", ja.romanize("二人だけの空が広がる夜に"))
        assertEquals("kasanatte ita", ja.romanize("重なっていた"))
        // 々 counts as a kanji, so the line stays with the reader rather than going to Google.
        assertEquals(setOf(com.prism.music.data.lyrics.roman.Script.HAN, com.prism.music.data.lyrics.roman.Script.KANA),
            com.prism.music.data.lyrics.roman.Script.foreignIn("騒がしい日々に"))
        assertEquals("sawagashii hibi ni", ja.romanize("騒がしい日々に"))
        // Latin words in a Japanese line stay as written.
        assertEquals("I love you to iu", ja.romanize("I love youという").replace("toiu", "to iu"))
        // One piece per character: a word's reading on its first character.
        val p = ja.pieces("君の")
        assertEquals(listOf("kimi", " no"), p)
        // Furigana: hiragana above the kanji, with the okurigana and kana beside it left out.
        assertEquals(listOf(RubySpan(0, 1, "きみ"), RubySpan(2, 3, "な")), ja.furigana("君の名は"))
        assertEquals(listOf(RubySpan(0, 1, "た")), ja.furigana("食べる"))
        assertEquals(listOf(RubySpan(0, 2, "きょう")), ja.furigana("今日"))
        assertEquals("きみ", Kana.toHiragana("キミ"))
        assertEquals(emptyList<RubySpan>(), ja.furigana("ひらがなだけ"))
    }

    @Test
    fun furiganaUnderstandsCountersNextToDigits() {
        val ja = com.prism.music.data.lyrics.roman.Japanese.bundled()
        assertEquals(listOf(RubySpan(1, 2, "ふたり")), ja.furigana("2人"))
        assertEquals(listOf(RubySpan(1, 2, "ひとり")), ja.furigana("１人"))
        assertEquals(listOf(RubySpan(1, 2, "ふたり")), ja.furigana("2人で"))
        assertEquals(listOf(RubySpan(1, 2, "ふたり"), RubySpan(2, 3, "ぐみ")), ja.furigana("2人組"))
        // Three and more are said にん as the dictionary has them, and the kanji numeral is its own word already.
        assertEquals(listOf(RubySpan(1, 2, "にん")), ja.furigana("3人"))
        assertEquals(listOf(RubySpan(2, 3, "にん")), ja.furigana("10人"))
        assertEquals(listOf(RubySpan(2, 3, "にん")), ja.furigana("11人"))
        assertEquals(listOf(RubySpan(2, 3, "にん")), ja.furigana("12人"))
        assertEquals(listOf(RubySpan(2, 3, "にん")), ja.furigana("21人"))
        assertEquals(listOf(RubySpan(0, 2, "ふたり")), ja.furigana("二人"))
        // Other counters and dates come out as Japanese says them too.
        assertEquals(listOf(RubySpan(1, 2, "しがつ")), ja.furigana("4月"))
        assertEquals(listOf(RubySpan(1, 2, "じ")), ja.furigana("4時"))
        assertEquals(listOf(RubySpan(2, 3, "にち")), ja.furigana("12日"))
        // The romaji reading follows too, with the number as it was written.
        assertEquals("2futari", ja.romanize("2人"))
        assertEquals("4shigatsu", ja.romanize("4月"))
        assertEquals("12nin", ja.romanize("12人"))
        assertEquals("11nin", ja.romanize("11人"))
    }

    @Test
    fun japaneseFromDownloadedFiles() {
        // The phone reads the dictionary from the unpacked download, not the jar: same answers.
        val dir = kotlin.io.path.createTempDirectory("ipadic").toFile()
        for (name in listOf("characterDefinitions", "connectionCosts", "doubleArrayTrie", "tokenInfoDictionary", "tokenInfoFeaturesMap",
            "tokenInfoPartOfSpeechMap", "tokenInfoTargetMap", "unknownDictionary")) {
            val res = com.atilika.kuromoji.ipadic.Tokenizer::class.java.getResourceAsStream("$name.bin")!!
            java.io.File(dir, "$name.bin").outputStream().use { res.copyTo(it) }
        }
        val ja = com.prism.music.data.lyrics.roman.Japanese.load(dir)
        assertEquals("kimi no na wa", ja.romanize("君の名は"))
        dir.deleteRecursively()
    }

    @Test
    fun cyrillicAndGreek() {
        assertEquals("Ya tebya lyublyu", Cyrillic.romanize("Я тебя люблю"))
        assertEquals("S' agapo", Greek.romanize("Σ' αγαπώ"))
    }
}
