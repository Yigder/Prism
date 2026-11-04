package com.prism.music.data.imports

import com.prism.music.data.imports.ImportFiles.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.time.Instant

/**
 * YouTube Music's history from Google Takeout (1.8.1): `watch-history.json` (YouTube and YouTube Music → history, as
 * JSON), newest first, each entry `header` ("YouTube Music" for its plays, "YouTube" for videos), `title` ("Watched
 * <song>"), `titleUrl` (…watch?v=<id>), `subtitles` [{name: "<Artist> - Topic"}], `time`. Only YouTube Music's count.
 *
 * Takeout says nothing of how long each was heard: a play counts as the song's length, never more than the time to the
 * next play (it's [estimated]: Replay counts it once its song is known, held to the song's length).
 */
object TakeoutHistory {
    /** The longest a play is counted before its song's length is known. */
    const val CAP_MS = 10 * 60_000L

    /** "Watched X" in the languages Takeout writes it in. */
    private val WATCHED = listOf(
        "Watched ", "Has visto ", "Viste ", "Visto ", "Vous avez regardé ", "A regardé ", "Angesehen: ", "Hai guardato ", "Assistiu a ", "Assistiu ",
        "Bekeken: ", "Obejrzano: ", "Просмотрено: ", "Вы посмотрели ", "İzlendi: ", "Đã xem ", "Menonton ", "Ditonton ",
    )

    fun isHistoryFile(file: String): Boolean = file.substringAfterLast('/').lowercase().let { it.endsWith(".json") && (it.startsWith("watch-history") || it.startsWith("watch history")) }

    /** Reads a picked `watch-history.json`, or a Takeout zip holding one. False when there was none in it. */
    fun read(name: String, input: InputStream, onPlay: (ParsedPlay) -> Unit): Boolean {
        var found = false
        ImportFiles.forEachFile(name, input) { file, stream ->
            if (!isHistoryFile(file)) return@forEachFile
            found = true
            var later: Long? = null
            ImportFiles.arrayItems(stream).forEach { o ->
                val p = entry(o, later) ?: return@forEach
                // Newest first: this play's "next" is the one read before it (out of order: no gap known).
                if (later == null || p.timestamp <= later!!) later = p.timestamp
                onPlay(p)
            }
        }
        return found
    }

    /** One entry, [laterAt] the next play's time; null for anything that isn't a YouTube Music play of a known video. */
    internal fun entry(o: JsonObject, laterAt: Long?): ParsedPlay? {
        if (o.str("header") != "YouTube Music") return null
        val url = o.str("titleUrl") ?: return null
        val id = videoId(url) ?: return null
        val at = o.str("time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        val raw = o.str("title") ?: return null
        val title = WATCHED.firstOrNull { raw.startsWith(it) }?.let { raw.removePrefix(it) } ?: raw
        // An ad YouTube played ("From Google Ads") isn't a song.
        if ((o["details"] as? JsonArray)?.any { (it as? JsonObject)?.str("name")?.contains("Ads", ignoreCase = true) == true } == true) return null
        val channel = ((o["subtitles"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("name").orEmpty()
        val artist = channel.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        val gap = laterAt?.let { it - at }?.takeIf { it > 0 }
        return ParsedPlay(
            timestamp = at, playedMs = gap?.coerceAtMost(CAP_MS) ?: CAP_MS, title = title, artist = artist,
            sourceId = id, youtubeId = id, estimated = true,
        )
    }

    private val ID = Regex("[?&]v=([A-Za-z0-9_-]{11})")

    fun videoId(url: String): String? = ID.find(url)?.groupValues?.get(1)
}
