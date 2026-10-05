package com.prism.music.data.lyrics

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer
import kotlin.math.abs

/** One sung word (or syllable) with its own timing, for word-by-word highlighting. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endMs: Long = -1,
    val words: List<LyricWord> = emptyList(),
    /** Second voice in a duet; drawn right-aligned. */
    val alignEnd: Boolean = false,
    /** An instrumental break between lines. */
    val isGap: Boolean = false,
) {
    val isWordSynced: Boolean get() = words.isNotEmpty()
}

data class Lyrics(
    val source: LyricsSource,
    val synced: Boolean,
    val lines: List<LyricLine>,
) {
    val plain: String get() = lines.joinToString("\n") { it.text }
    val wordSynced: Boolean get() = lines.any { it.isWordSynced }
    /** Swear words starred or blanked out ("f**k", "sh*t", "[censored]"). */
    val censored: Boolean by lazy { lines.any { !it.isGap && censorMark.containsMatchIn(it.text) } }
}

/**
 * A word with starred-out letters ("f*ck", "f***", "sh**", "**ck", "f##k") or an outright
 * "[censored]" / "(bleep)". A lone "*laughs*" style aside doesn't count.
 */
private val censorMark = Regex(
    "\\p{L}[*#]+\\p{L}|\\b\\p{L}{1,3}[*#]{2,}|[*#]{2,}\\p{L}{1,3}\\b|[\\[(](?:censored|bleep(?:ed)?)[\\])]",
    RegexOption.IGNORE_CASE,
)

private val creditLine = Regex(
    "^(\\s*(lyrics?|words|composed|composer|written|writer|produced|producer|arranged|music|mixed|mastered|vocals?)\\s*(by)?\\s*[:：]|.*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓)\\s*[:：]).*",
    RegexOption.IGNORE_CASE,
)

private fun String.decodeEntities(): String = replace("&amp;", "&").replace("&apos;", "'").replace("&#39;", "'")
    .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

object Lrc {
    private val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val meta = Regex("^\\[(ar|ti|al|by|offset|length|re|ve|id|au|la|tool|#):.*]$", RegexOption.IGNORE_CASE)

    fun isLrc(text: String) = tag.containsMatchIn(text)

    fun parse(text: String): List<LyricLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || meta.matches(line)) continue
            val stamps = tag.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val content = line.substring(stamps.last().range.last + 1).trim()
            if (creditLine.matches(content)) continue
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                raw += (min * 60_000 + sec * 1000 + ms) to content
            }
        }
        val sorted = raw.sortedBy { it.first }
        return sorted.mapIndexed { i, (t, s) ->
            val end = sorted.getOrNull(i + 1)?.first ?: (t + 5000)
            LyricLine(t, s, end, isGap = s.isBlank())
        }.withLeadingGap()
    }

    fun plainLines(text: String) = text.lines().map { LyricLine(-1, it.trim(), isGap = it.isBlank()) }
}

/** An intro before the first sung line reads as an instrumental break. */
private fun List<LyricLine>.withLeadingGap(): List<LyricLine> {
    val first = firstOrNull { !it.isGap } ?: return this
    return if (first.timeMs > 3000 && firstOrNull()?.isGap != true) listOf(LyricLine(0, "", first.timeMs, isGap = true)) + this else this
}

/** Apple Music TTML (word or line timed), as served by Better Lyrics. */
object Ttml {
    private val para = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val wordSpan = Regex("<span\\b([^>]*)>([^<]*)</span>")
    private fun attr(attrs: String, name: String) = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    fun time(t: String?): Long? {
        if (t.isNullOrBlank()) return null
        val s = t.trim().removeSuffix("s")
        return runCatching { (s.split(":").fold(0.0) { acc, p -> acc * 60 + p.toDouble() } * 1000).toLong() }.getOrNull()
    }

    /** Removes background-vocal spans (which nest spans of their own). */
    private fun stripBackground(content: String): String {
        val sb = StringBuilder(content)
        while (true) {
            val start = Regex("<span[^>]*ttm:role=\"x-bg\"[^>]*>").find(sb) ?: break
            var depth = 0
            var i = start.range.first
            var end = -1
            val tags = Regex("<span\\b[^>]*>|</span>").findAll(sb, i)
            for (m in tags) {
                if (m.value.startsWith("</")) depth-- else depth++
                if (depth == 0) { end = m.range.last + 1; break }
            }
            if (end < 0) break
            sb.delete(start.range.first, end)
        }
        return sb.toString()
    }

    fun parse(ttml: String): List<LyricLine> {
        val wordTimed = ttml.contains("itunes:timing=\"Word\"") || ttml.contains("timing=\"Word\"")
        // Untimed documents (timing="None") are plain lyrics, one line per <p>, verses split by <div>.
        if (ttml.contains("timing=\"None\"") || !Regex("<p\\b[^>]*\\bbegin=").containsMatchIn(ttml)) {
            val body = ttml.substringAfter("<body", "")
            val divs = Regex("<div\\b[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList().ifEmpty { listOf(body) }
            return divs.flatMapIndexed { i, div ->
                val lines = para.findAll(div).map { LyricLine(-1, it.groupValues[2].replace(Regex("<[^>]+>"), "").decodeEntities().trim()) }
                    .filter { it.text.isNotBlank() }.toList()
                if (i > 0 && lines.isNotEmpty()) listOf(LyricLine(-1, "", isGap = true)) + lines else lines
            }
        }
        val out = mutableListOf<LyricLine>()
        for (m in para.findAll(ttml)) {
            val attrs = m.groupValues[1]
            val begin = time(attr(attrs, "begin")) ?: continue
            val end = time(attr(attrs, "end")) ?: begin
            val agent = attr(attrs, "ttm:agent")
            val content = stripBackground(m.groupValues[2])
            val words = mutableListOf<LyricWord>()
            if (wordTimed) {
                var last = 0
                for (w in wordSpan.findAll(content)) {
                    val between = content.substring(last, w.range.first)
                    if (words.isNotEmpty() && between.contains(Regex("\\s"))) {
                        val prev = words.removeAt(words.lastIndex)
                        words += prev.copy(text = prev.text + " ")
                    }
                    val a = w.groupValues[1]
                    val s = time(attr(a, "begin")) ?: continue
                    val e = time(attr(a, "end")) ?: s
                    words += LyricWord(s, e, w.groupValues[2].decodeEntities())
                    last = w.range.last + 1
                }
            }
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
            else content.replace(Regex("<[^>]+>"), "").decodeEntities().trim()
            if (text.isBlank()) continue
            out += LyricLine(begin, text, end, words, alignEnd = agent != null && agent != "v1")
        }
        return out.withInstrumentalGaps()
    }
}

/** Instrumental breaks of 6s+ between lines become gap rows. */
internal fun List<LyricLine>.withInstrumentalGaps(): List<LyricLine> {
    val withGaps = mutableListOf<LyricLine>()
    sortedBy { it.timeMs }.forEachIndexed { i, l ->
        val prevEnd = withGaps.lastOrNull()?.endMs ?: 0L
        if (i > 0 && l.timeMs - prevEnd > 6000) withGaps += LyricLine(prevEnd, "", l.timeMs, isGap = true)
        withGaps += l
    }
    return withGaps.withLeadingGap()
}

/** LyricsPlus v2 JSON: lines with syllable timings that are glued back into words. */
object LyricsPlusFormat {
    const val PREFIX = "LYRICSPLUS-JSON\n"

    fun parse(body: String): List<LyricLine> {
        val root = runCatching { InnerTube.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val lines = root["lyrics"] as? JsonArray ?: return emptyList()
        if (root.str("type") == "None") return emptyList()
        val agents = (root["metadata"] as? JsonObject)?.get("agents") as? JsonObject
        val multiVoice = (agents?.size ?: 0) > 1
        val out = lines.mapNotNull { line ->
            val start = line.str("time")?.toLongOrNull() ?: return@mapNotNull null
            val dur = line.str("duration")?.toLongOrNull() ?: 0L
            val singer = (line as? JsonObject)?.get("element").str("singer")
            val words = mutableListOf<LyricWord>()
            val current = StringBuilder()
            var ws = 0L
            var we = 0L
            for (syl in (line as? JsonObject)?.get("syllabus") as? JsonArray ?: JsonArray(emptyList())) {
                val t = syl.str("text") ?: continue
                if (t.isBlank()) continue
                val st = syl.str("time")?.toLongOrNull() ?: continue
                if (current.isEmpty()) ws = st
                current.append(t.trim())
                we = st + (syl.str("duration")?.toLongOrNull() ?: 0L)
                if (t.last().isWhitespace()) { words += LyricWord(ws, we, "$current "); current.setLength(0) }
            }
            if (current.isNotEmpty()) words += LyricWord(ws, we, current.toString())
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim() else line.str("text")?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            LyricLine(start, text, if (dur > 0) start + dur else start + 5000, words, alignEnd = multiVoice && singer != null && singer != "v1")
        }
        return out.withInstrumentalGaps()
    }
}

/**
 * Karaoke formats with a timing for every word (or syllable):
 *  - QRC (QQ Music): `[lineStart,lineDur]word(start,dur)word(start,dur)…`
 *  - YRC (NetEase): `[lineStart,lineDur](start,dur,0)word(start,dur,0)word…`
 *  - KRC (KuGou): `[lineStart,lineDur]<offset,dur,0>word<offset,dur,0>word…` (offsets from the line start)
 * Stored with a one-line header naming the format.
 */
object WordFormat {
    enum class Kind { QRC, YRC, KRC }

    const val PREFIX = "WORDS-"
    fun wrap(kind: Kind, body: String) = "$PREFIX${kind.name}\n$body"

    private val lineHead = Regex("^\\[(\\d+),(\\d+)]")
    private val qrcTag = Regex("\\((\\d+),(\\d+)\\)")
    private val yrcTag = Regex("\\((\\d+),(\\d+),-?\\d+\\)")
    private val krcTag = Regex("<(\\d+),(\\d+),-?\\d+>")

    fun parse(stored: String): List<LyricLine> {
        val kind = runCatching { Kind.valueOf(stored.substringAfter(PREFIX).substringBefore('\n').trim()) }.getOrNull() ?: return emptyList()
        return parse(kind, stored.substringAfter('\n'))
    }

    fun parse(kind: Kind, body: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in body.lines()) {
            val line = raw.trim()
            val head = lineHead.find(line) ?: continue
            val start = head.groupValues[1].toLong()
            val dur = head.groupValues[2].toLong()
            val rest = line.substring(head.range.last + 1)
            // Each tag's text and absolute timing, in order.
            val tokens = mutableListOf<Triple<Long, Long, String>>()
            when (kind) {
                Kind.QRC -> {
                    var last = 0
                    for (m in qrcTag.findAll(rest)) {
                        tokens += Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), rest.substring(last, m.range.first))
                        last = m.range.last + 1
                    }
                }
                Kind.YRC, Kind.KRC -> {
                    val tags = (if (kind == Kind.YRC) yrcTag else krcTag).findAll(rest).toList()
                    tags.forEachIndexed { i, m ->
                        val text = rest.substring(m.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: rest.length)
                        val t = m.groupValues[1].toLong() + if (kind == Kind.KRC) start else 0
                        tokens += Triple(t, m.groupValues[2].toLong(), text)
                    }
                }
            }
            if (tokens.isEmpty()) continue
            // Syllables are glued into words; a space (on a syllable or on its own) ends a word.
            val words = mutableListOf<LyricWord>()
            val cur = StringBuilder()
            var ws = 0L
            var we = 0L
            for ((t, d, text) in tokens) {
                if (text.isEmpty()) continue
                if (text.first().isWhitespace() && cur.isNotEmpty()) {
                    words += LyricWord(ws, we, "$cur ")
                    cur.setLength(0)
                }
                val word = text.trim()
                if (word.isNotEmpty()) {
                    if (cur.isEmpty()) ws = t
                    cur.append(word)
                    we = t + d
                }
                if (text.last().isWhitespace() && cur.isNotEmpty()) {
                    words += LyricWord(ws, we, "$cur ")
                    cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) words += LyricWord(ws, we, cur.toString())
            val text = words.joinToString("") { it.text }.decodeEntities().trim()
            if (text.isBlank() || creditLine.matches(text)) continue
            // QQ and KuGou open with a "Title - Artist" card before the song starts.
            if (out.isEmpty() && start < 1000 && text.contains(" - ")) continue
            out += LyricLine(start, text, start + dur, words.map { it.copy(text = it.text.decodeEntities()) })
        }
        return out.withInstrumentalGaps()
    }

    /** KuGou's KRC files: "krc1", then XOR with a fixed key, then zlib. */
    fun decodeKrc(base64: String): String? = runCatching {
        val bytes = java.util.Base64.getMimeDecoder().decode(base64)
        if (bytes.size < 5 || String(bytes, 0, 4, Charsets.US_ASCII) != "krc1") return null
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val data = ByteArray(bytes.size - 4) { i -> (bytes[i + 4].toInt() xor key[i % 16]).toByte() }
        java.util.zip.InflaterInputStream(data.inputStream()).bufferedReader(Charsets.UTF_8).readText()
    }.getOrNull()
}

/** Fetches lyrics from several providers, with per-source caching. */
class LyricsRepository(
    private val http: OkHttpClient,
    private val dao: LyricsDao,
    private val ytm: YouTubeMusic,
    private val settings: () -> AppSettings,
) {
    private val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private fun cleanTitle(t: String) = t
        .replace(Regex("\\s*[(\\[](official|lyric|lyrics|audio|video|visualizer|music video|mv|hd|4k|explicit)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*-\\s*(official|lyric).*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun getText(url: okhttp3.HttpUrl, headers: Map<String, String> = emptyMap()): String? {
        val b = Request.Builder().url(url).header("User-Agent", browserUa)
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) null else r.body.string() }
    }

    private fun getJson(url: okhttp3.HttpUrl, headers: Map<String, String> = emptyMap()): JsonElement? =
        getText(url, headers)?.let { runCatching { InnerTube.json.parseToJsonElement(it) }.getOrNull() }

    private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun build(source: LyricsSource, text: String?): Lyrics? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith(WordFormat.PREFIX) -> WordFormat.parse(trimmed).takeIf { l -> l.any { !it.isGap } }
                ?.let { Lyrics(source, true, it) }
            trimmed.startsWith(LyricsPlusFormat.PREFIX) -> LyricsPlusFormat.parse(trimmed.removePrefix(LyricsPlusFormat.PREFIX))
                .takeIf { it.isNotEmpty() }?.let { lines -> Lyrics(source, lines.any { it.timeMs >= 0 }, lines) }
            trimmed.startsWith("<tt") -> Ttml.parse(trimmed).takeIf { l -> l.any { !it.isGap } }
                ?.let { lines -> Lyrics(source, lines.any { it.timeMs >= 0 }, lines) }
            Lrc.isLrc(text) -> Lrc.parse(text).takeIf { l -> l.any { !it.isGap } }?.let { Lyrics(source, true, it) }
            else -> Lyrics(source, false, Lrc.plainLines(text))
        }
    }

    // ------------------------------------------------------------ Providers

    /** Apple Music's own word-timed lyrics (TTML), via the Better Lyrics API. */
    private fun betterLyricsUrl(base: String, song: Song) = base.toHttpUrl().newBuilder()
        .addQueryParameter("s", cleanTitle(song.title))
        .addQueryParameter("a", song.primaryArtist)
        .apply {
            if (song.durationSec > 0) addQueryParameter("d", song.durationSec.toString())
            song.album?.title?.let { addQueryParameter("al", it) }
        }.build()

    private fun fetchApple(song: Song): String? {
        for (base in listOf("https://api.betterlyrics.org/getLyrics", "https://lyrics-api.boidu.dev/getLyrics")) {
            runCatching { getJson(betterLyricsUrl(base, song)).str("ttml") }.getOrNull()?.takeIf { it.contains("<p") }?.let { return it }
        }
        return null
    }

    /**
     * QQ Music's word-timed karaoke lyrics, as Better Lyrics serves them ("Portato").
     * Only songs Better Lyrics already has on file come back; it no longer looks up new ones without a key.
     */
    private fun fetchQQ(song: Song): String? {
        val xml = getJson(betterLyricsUrl("https://api.betterlyrics.org/qq/getLyrics", song)).str("lyrics") ?: return null
        val content = Regex("LyricContent=\"(.*?)\"\\s*/?>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.decodeEntities()
            ?: return null
        return when {
            Regex("\\[\\d+,\\d+]").containsMatchIn(content) -> WordFormat.wrap(WordFormat.Kind.QRC, content)
            Lrc.isLrc(content) -> content
            else -> null
        }
    }

    private fun fetchLrcLib(song: Song): String? {
        val title = cleanTitle(song.title)
        val base = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", song.primaryArtist)
        song.album?.title?.let { base.addQueryParameter("album_name", it) }
        if (song.durationSec > 0) base.addQueryParameter("duration", song.durationSec.toString())
        val exact = runCatching { getJson(base.build()) }.getOrNull()
        val pick = exact ?: run {
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", song.primaryArtist).build()
            val list = (runCatching { getJson(search) }.getOrNull() as? JsonArray) ?: return null
            list.minByOrNull { abs((it.str("duration")?.toDoubleOrNull() ?: 0.0) - song.durationSec) }
        }
        return pick.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: pick.str("plainLyrics")
    }

    internal fun fetchNetEase(song: Song): String? {
        val headers = mapOf("Referer" to "https://music.163.com/")
        val search = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${cleanTitle(song.title)} ${song.primaryArtist}")
            .addQueryParameter("type", "1").addQueryParameter("limit", "10").build()
        val songs = getJson(search, headers).field("result").field("songs") as? JsonArray ?: return null
        val wantTitle = norm(cleanTitle(song.title))
        val wantArtist = norm(song.primaryArtist)
        val candidates = songs.filter { s ->
            val t = norm(s.str("name") ?: "")
            val artists = (s.field("artists") as? JsonArray)?.map { norm(it.str("name") ?: "") } ?: emptyList()
            (t == wantTitle || t.contains(wantTitle) || wantTitle.contains(t)) &&
                artists.any { it.isNotBlank() && (it.contains(wantArtist) || wantArtist.contains(it)) }
        }.sortedBy { abs((it.str("duration")?.toLongOrNull() ?: 0L) / 1000 - song.durationSec) }
        // Several editions often match; not all of them carry lyrics.
        for (best in candidates.take(4)) {
            val id = best.str("id") ?: continue
            val lyric = "https://music.163.com/api/song/lyric".toHttpUrl().newBuilder()
                .addQueryParameter("id", id).addQueryParameter("lv", "1").addQueryParameter("kv", "1")
                .addQueryParameter("yv", "1").addQueryParameter("tv", "-1").build()
            val res = getJson(lyric, headers)
            // Word-by-word (YRC) when NetEase has it, else line-timed LRC.
            res.field("yrc").str("lyric")?.takeIf { it.contains(Regex("\\[\\d+,\\d+]\\(")) }?.let { return WordFormat.wrap(WordFormat.Kind.YRC, it) }
            res.field("lrc").str("lyric")?.takeIf { Lrc.isLrc(it) }?.let { return it }
        }
        return null
    }

    private suspend fun fetchYtMusic(song: Song): String? {
        val next = ytm.next(song.id)
        val id = next.lyricsBrowseId ?: return null
        return ytm.lyrics(id)
    }

    private fun fetchKuGou(song: Song): String? {
        fun search(keyword: String) = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("duration", (song.durationSec * 1000L).toString())
            .addQueryParameter("hash", "").build()
        // KuGou matches "Artist - Title" far more often than the other way round.
        val candidates = listOf("${song.primaryArtist} - ${cleanTitle(song.title)}", "${cleanTitle(song.title)} - ${song.primaryArtist}")
            .firstNotNullOfOrNull { (getJson(search(it)).field("candidates") as? JsonArray)?.takeIf { c -> c.isNotEmpty() } } ?: return null
        val best = candidates.minByOrNull {
            abs((it.str("duration")?.toLongOrNull() ?: 0L) - song.durationSec * 1000L)
        } ?: return null
        val id = best.str("id") ?: return null
        val key = best.str("accesskey") ?: return null
        fun download(fmt: String) = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", fmt).addQueryParameter("charset", "utf8").addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1").addQueryParameter("id", id).addQueryParameter("accesskey", key).build()
        // Word-by-word KRC first, line-timed LRC as the fallback.
        getJson(download("krc")).str("content")?.let(WordFormat::decodeKrc)?.takeIf { it.contains(Regex("\\[\\d+,\\d+]<")) }
            ?.let { return WordFormat.wrap(WordFormat.Kind.KRC, it) }
        val content = getJson(download("lrc")).str("content") ?: return null
        return String(java.util.Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
    }

    /** Plain lyrics with [Verse]/[Chorus] headers from Genius. */
    private fun fetchGenius(song: Song): String? {
        val q = "${cleanTitle(song.title)} ${song.primaryArtist}"
        val search = "https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q", q).build()
        val sections = getJson(search).field("response").field("sections") as? JsonArray ?: return null
        val hits = sections.firstOrNull { it.str("type") == "song" }.field("hits") as? JsonArray ?: return null
        val title = norm(cleanTitle(song.title))
        val artist = norm(song.primaryArtist)
        val url = hits.map { it.field("result") }.firstOrNull { r ->
            val t = norm(r.str("title") ?: "")
            val a = norm(r.str("primary_artist_names") ?: r.field("primary_artist").str("name") ?: "")
            (t.contains(title) || title.contains(t)) && (a.contains(artist) || artist.contains(a))
        }.str("url") ?: return null
        val doc = Jsoup.parse(getText(url.toHttpUrl()) ?: return null)
        val containers = doc.select("div[data-lyrics-container=true]")
        if (containers.isEmpty()) return null
        val text = containers.joinToString("\n") { c ->
            c.select("[data-exclude-from-selection=true]").remove()
            c.select("br").append("\\n")
            c.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }
        }
        return text.replace(Regex("\\n{3,}"), "\n\n").trim().takeIf { it.isNotBlank() }
    }

    private fun fetchLyricsOvh(song: Song): String? {
        val url = "https://api.lyrics.ovh/v1/".toHttpUrl().newBuilder()
            .addPathSegment(song.primaryArtist).addPathSegment(cleanTitle(song.title)).build()
        return getJson(url).str("lyrics")
    }

    /** Apple Music TTML through the Bini catalogue (a second matcher over Apple's lyrics). */
    private fun fetchBini(song: Song): String? {
        fun query(withDuration: Boolean) = "https://lyrics-api.binimum.org/".toHttpUrl().newBuilder()
            .addQueryParameter("track", cleanTitle(song.title))
            .addQueryParameter("artist", song.primaryArtist)
            .apply { if (withDuration && song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString()) }
            .build()
        // Its duration match is strict, and YouTube's length often differs by a second or two.
        val results = (getJson(query(true)).field("results") as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: getJson(query(false)).field("results") as? JsonArray ?: return null
        val title = norm(cleanTitle(song.title))
        // Prefer word-timed editions, then line-timed, then the closest length.
        fun timingRank(t: String?) = when (t) { "word" -> 0; "line" -> 1; else -> 2 }
        val best = results.filter { norm(it.str("track_name") ?: "").let { t -> t == title || t.startsWith(title) } }
            .sortedWith(compareBy({ timingRank(it.str("timing_type")) }, { abs((it.str("duration")?.toIntOrNull() ?: 0) - song.durationSec) }))
            .firstOrNull() ?: results.firstOrNull() ?: return null
        val doc = best.str("lyricsUrl")?.takeIf { it.startsWith("https://") } ?: return null
        return getText(doc.toHttpUrl())?.takeIf { it.contains("<tt") }
    }

    private val lyricsPlusMirrors = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    @Volatile private var lyricsPlusGood: String? = null
    private val quickHttp by lazy {
        http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Syllable-timed lyrics from LyricsPlus (Apple / QQ / Musixmatch), across volunteer mirrors. */
    private fun fetchLyricsPlus(song: Song): String? {
        val hosts = lyricsPlusGood?.let { listOf(it) + lyricsPlusMirrors.filter { m -> m != it } } ?: lyricsPlusMirrors
        for (host in hosts) {
            val url = "$host/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", cleanTitle(song.title))
                .addQueryParameter("artist", song.primaryArtist)
                .apply {
                    if (song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString())
                    song.album?.title?.let { addQueryParameter("album", it) }
                }.build()
            val body = runCatching {
                quickHttp.newCall(Request.Builder().url(url).header("User-Agent", browserUa).build()).execute()
                    .use { r -> if (r.isSuccessful) r.body.string() else null }
            }.getOrNull() ?: continue
            if (LyricsPlusFormat.parse(body).isNotEmpty()) {
                lyricsPlusGood = host
                return LyricsPlusFormat.PREFIX + body
            }
        }
        return null
    }

    /** Community-submitted lyrics (TTML, LRC or plain) from Unison. */
    private fun fetchUnison(song: Song): String? {
        val url = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", cleanTitle(song.title))
            .addQueryParameter("artist", song.primaryArtist)
            .apply {
                song.album?.title?.let { addQueryParameter("album", it) }
                if (song.durationSec > 0) addQueryParameter("duration", song.durationSec.toString())
            }.build()
        val res = getJson(url) ?: return null
        if (res.str("success") != "true") return null
        return res.field("data").str("lyrics")?.takeIf { it.isNotBlank() }
    }

    suspend fun fetch(song: Song, source: LyricsSource, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        if (!force) dao.get(song.id, source.name)?.let { cached ->
            // A real answer is kept; a "not found" is retried after a day.
            // NetEase and KuGou answers from before they went word-by-word are fetched again once.
            val upgrade = source in setOf(LyricsSource.NETEASE, LyricsSource.KUGOU) && cached.fetchedAt < WORDS_SINCE &&
                !cached.content.startsWith(WordFormat.PREFIX)
            if (cached.content.isNotEmpty() && !upgrade) return@withContext build(source, cached.content)
            if (cached.content.isEmpty() && System.currentTimeMillis() - cached.fetchedAt < 86_400_000L) return@withContext null
        }
        val text = try {
            when (source) {
                LyricsSource.APPLE -> fetchApple(song)
                LyricsSource.LYRICSPLUS -> fetchLyricsPlus(song)
                LyricsSource.BINI -> fetchBini(song)
                LyricsSource.QQ -> fetchQQ(song)
                LyricsSource.LRCLIB -> fetchLrcLib(song)
                LyricsSource.NETEASE -> fetchNetEase(song)
                LyricsSource.YTMUSIC -> fetchYtMusic(song)
                LyricsSource.KUGOU -> fetchKuGou(song)
                LyricsSource.UNISON -> fetchUnison(song)
                LyricsSource.GENIUS -> fetchGenius(song)
                LyricsSource.LYRICSOVH -> fetchLyricsOvh(song)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never record a cancelled lookup as "no lyrics".
            throw e
        } catch (e: Exception) {
            // Network trouble isn't an answer either; don't cache it.
            return@withContext null
        }
        val lyrics = build(source, text)
        dao.put(LyricsEntity(song.id, source.name, lyrics?.synced == true, if (lyrics == null) "" else text ?: "", System.currentTimeMillis()))
        lyrics
    }

    private companion object {
        /** When NetEase and KuGou started returning word-by-word lyrics (Oct 2026). */
        const val WORDS_SINCE = 1_791_080_000_000L

        /** Sources that can have a timing for every word. */
        val WORD_SOURCES = setOf(
            LyricsSource.APPLE, LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.QQ,
            LyricsSource.NETEASE, LyricsSource.KUGOU, LyricsSource.UNISON,
        )
    }

    /** Remembers which source the listener picked for a song (song id -> source name). */
    var choices: android.content.SharedPreferences? = null

    fun choose(song: Song, source: LyricsSource) {
        choices?.edit()?.putString(song.id, source.name)?.apply()
    }

    private fun chosen(song: Song): LyricsSource? =
        choices?.getString(song.id, null)?.let { n -> LyricsSource.entries.firstOrNull { it.name == n } }

    /** Lyrics already saved on the phone (downloaded or seen before), without touching the network: karaoke first. */
    private suspend fun saved(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val s = settings()
        val rows = dao.allFor(song.id).associateBy { it.source }
        val all = s.lyricsOrder.mapNotNull { src -> rows[src.name]?.let { build(src, it.content) } }
            .filterNot { skipCensored() && it.censored }
        if (!s.preferSynced) return@withContext all.firstOrNull()
        all.maxByOrNull { level(it) }
    }

    private fun skipCensored() = settings().skipCensored

    /** Starred-out lyrics are passed over while another source might have the real words. */
    private fun unwanted(l: Lyrics) = skipCensored() && l.censored

    /** How good a set of lyrics is: word-by-word 3, line-synced 2, plain 1, none 0. */
    fun level(l: Lyrics?): Int = when {
        l == null -> 0
        l.wordSynced -> 3
        l.synced -> 2
        else -> 1
    }

    /** The best lyrics saved on the phone for [song], as a [level]. */
    suspend fun savedLevel(song: Song): Int = level(saved(song))

    /**
     * For downloads: karaoke (word-by-word) lyrics from any source that has them, then
     * line-synced, then plain, so the best version is on the phone for offline listening.
     * The listener's own pick for the song still wins.
     */
    suspend fun download(song: Song): Lyrics? {
        chosen(song)?.let { src -> fetch(song, src)?.let { return it } }
        saved(song)?.takeIf { it.wordSynced }?.let { return it }
        val order = settings().lyricsOrder
        var best: Lyrics? = null
        var censored: Lyrics? = null
        for (src in order.filter { it in WORD_SOURCES } + order.filter { it !in WORD_SOURCES }) {
            // Line-synced is already in hand: the rest of the sources only ever have that or less.
            if (src !in WORD_SOURCES && level(best) >= 2) break
            val l = fetch(song, src) ?: continue
            if (unwanted(l)) { if (level(l) > level(censored)) censored = l; continue }
            if (l.wordSynced) return l
            if (level(l) > level(best)) best = l
        }
        return best ?: censored
    }

    /**
     * The listener's own pick for this song if they made one, else whatever's
     * already saved (so downloaded songs have lyrics offline, instantly), else
     * sources in the user's order, preferring synced lyrics when enabled.
     */
    suspend fun auto(song: Song, force: Boolean = false): Lyrics? {
        if (!force) {
            chosen(song)?.let { src -> fetch(song, src)?.let { return it } }
            saved(song)?.let { return it }
        }
        val s = settings()
        var plainFallback: Lyrics? = null
        var censored: Lyrics? = null
        for (src in s.lyricsOrder) {
            val l = fetch(song, src, force) ?: continue
            if (unwanted(l)) { if (censored == null || (l.synced && !censored.synced)) censored = l; continue }
            if (l.synced || !s.preferSynced) return l
            if (plainFallback == null) plainFallback = l
        }
        // Censored words beat no words at all.
        return plainFallback ?: censored
    }
}
