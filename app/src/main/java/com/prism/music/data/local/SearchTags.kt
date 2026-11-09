package com.prism.music.data.local

import android.os.Build
import com.prism.music.data.ModelStore
import com.prism.music.data.lyrics.roman.Cyrillic
import com.prism.music.data.lyrics.roman.Greek
import com.prism.music.data.lyrics.roman.Hangul
import com.prism.music.data.lyrics.roman.Japanese
import com.prism.music.data.lyrics.roman.Kana
import com.prism.music.data.lyrics.roman.Script
import com.prism.music.data.lyrics.roman.plainLatin

/**
 * Latin-letter search tags for text written in another script (#363): a song's title, an artist's name or an album's
 * title. "鳥の歌", "とりのうた" and "トリノウタ" all answer to "tori no uta" and "torinouta". Latin text needs none.
 *
 * The work is done on the phone, with the same romanizers the lyrics use, so search and scanning never ask the network.
 * Japanese kanji need the downloaded IPADIC readings model ([ModelStore]); until it's there a text with kanji gets no
 * tags (rather than the Chinese pinyin Android's own transliterator would give). Kana alone are always read.
 */
object SearchTags {
    private const val CACHE_SIZE = 4_096
    private val SPACES = Regex("\\s+")

    /** Titles are scanned in the thousands: keep the latest answers, and forget the oldest. */
    private val cache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > CACHE_SIZE
    }

    @Volatile private var models: ModelStore? = null
    @Volatile private var japanese: Japanese? = null
    /** Kuromoji's dictionary isn't in the APK: without the model, the fallback is only tried once. */
    @Volatile private var triedBundled = false
    private val readerLock = Any()

    /** The model store [AppContainer] uses, so Japanese kanji can be read once its dictionary is downloaded. */
    fun watch(models: ModelStore?) {
        this.models = models
    }

    /** Latin spellings of [title]'s words: with its spaces, and without them. Empty for a Latin title or no title. */
    fun forTitle(title: String): List<String> = forText(title)

    /** Latin spellings of any tag text: a song's title, an artist's name, an album's title. */
    fun forText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val scripts = Script.foreignIn(trimmed)
        if (scripts.isEmpty()) return emptyList()
        // The readings model may have been installed after this text was cached (or after the last scan): load it
        // now, which clears the cache below, and this text is computed properly.
        if (japanese == null && Script.KANA in scripts && models?.ready(ModelStore.Model.JAPANESE) == true) japaneseReader()
        synchronized(cache) { cache[trimmed] }?.let { return it }
        val tags = tagsOf(trimmed, scripts)
        synchronized(cache) { cache[trimmed] = tags }
        return tags
    }

    private fun tagsOf(text: String, scripts: Set<Script>): List<String> {
        var roman = text
        if (Script.HANGUL in scripts) roman = Hangul.romanize(roman)
        if (Script.KANA in scripts) {
            // Never Android's Han transliteration here: it would read a Japanese title's kanji as Chinese pinyin.
            roman = japaneseReader()?.romanize(roman) ?: Kana.romanize(roman)
        } else if (Script.HAN in scripts) {
            roman = transliterate(roman)
        }
        if (Script.CYRILLIC in scripts) roman = Cyrillic.romanize(roman)
        if (Script.GREEK in scripts) roman = Greek.romanize(roman)
        // Whatever another script is left (Arabic, Hebrew, Thai, the Indian scripts…) Android spells out itself.
        if (Script.KANA !in scripts && Script.foreignIn(roman).isNotEmpty()) roman = transliterate(roman)

        val spaced = plainLatin(roman).trim().replace(SPACES, " ")
        // Nothing was read, it's the text as written, or kanji remain because the Japanese dictionary isn't here:
        // no tag is better than a misleading one (鳥no歌).
        if (spaced.isBlank() || spaced.equals(text, ignoreCase = true) || Script.foreignIn(spaced).isNotEmpty()) return emptyList()
        return listOf(spaced, spaced.replace(" ", "")).filter { it.isNotBlank() }.distinct()
    }

    /** The Japanese reader, loaded once and kept; null until its dictionary is on the phone (or in the test jar). */
    private fun japaneseReader(): Japanese? {
        japanese?.let { return it }
        synchronized(readerLock) {
            japanese?.let { return it }
            val store = models
            val downloaded = store != null && store.ready(ModelStore.Model.JAPANESE)
            if (!downloaded && triedBundled) return null
            val reader = runCatching {
                if (downloaded) {
                    Japanese.load(store.dir(ModelStore.Model.JAPANESE))
                } else {
                    triedBundled = true
                    Japanese.bundled()
                }
            }.onFailure { android.util.Log.w("PrismSearchTags", "Couldn't load the Japanese dictionary: $it") }.getOrNull()
            if (reader != null) {
                japanese = reader
                // Titles read before the reader was available (and cached without it) may read differently now.
                synchronized(cache) { cache.clear() }
            }
            return reader
        }
    }

    /** Android's own transliterator (Android 10+): Han as pinyin, the other scripts as Latin letters. */
    private fun transliterate(text: String): String {
        if (Build.VERSION.SDK_INT < 29) return text
        return runCatching {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text)
        }.getOrDefault(text)
    }
}
