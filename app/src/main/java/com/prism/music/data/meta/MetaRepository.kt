package com.prism.music.data.meta

import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.MetaDao
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import kotlin.math.abs

/**
 * YouTube Music's genre categories (its "Moods & genres" page), kept to the
 * mostly English-language ones; every song is sorted into one — there's no
 * "Other". Raw genre names from catalogues (Deezer, iTunes) are mapped onto
 * these, most specific first. Regional scenes (K-Pop, Latin, Bollywood…) fold
 * into the nearest of these rather than getting categories of their own.
 */
object Genres {
    /** Regional / non-English scenes; their YouTube tiles and shelves are left out of Prism. */
    private val regionalWords = listOf(
        "k-pop", "kpop", "korean", "j-pop", "jpop", "j-rock", "japanese", "anime", "city pop", "mandopop", "cantopop", "c-pop",
        "chinese", "mandarin", "cantonese", "opm", "pinoy", "filipino", "tagalog", "bollywood", "indian", "hindi", "punjabi",
        "bhangra", "tamil", "telugu", "desi", "filmi", "bengali", "marathi", "malayalam", "kannada", "gujarati", "haryanvi",
        "bhojpuri", "ghazal", "sufi", "carnatic", "hindustani", "qawwali", "devotional", "arabic", "arab ", "khaleeji",
        "mahraganat", "african", "afro", "amapiano", "highlife", "bongo", "brazil", "sertanejo", "funk carioca", "pagode",
        "forró", "latin", "latino", "español", "espanol", "spanish", "reggaeton", "urbano", "regional mexican", "mexican",
        "mexicano", "corrido", "banda", "mariachi", "norteño", "cumbia", "bachata", "salsa", "merengue", "turkish",
        "french", "german", "italian", "portuguese", "russian", "thai", "indonesian", "vietnamese", "persian", "greek",
        "schlager", "chanson", "fado", "musica", "música",
    )

    /** True for tiles and shelves about non-English regional music ("Bollywood & Indian", "Hip-hop en Español"…). */
    fun isRegional(title: String): Boolean {
        val l = title.lowercase()
        return regionalPatterns.any { it.containsMatchIn(l) }
    }

    // Whole words ("desi" isn't "designer", "musica" isn't "musicals"); a few also start longer words ("afrobeats", "brazilian").
    private val regionalPatterns = regionalWords.map { w ->
        val e = Regex.escape(w.trim())
        if (w.trim() in setOf("afro", "brazil")) Regex("(^|[^\\p{L}])$e") else Regex("(^|[^\\p{L}])$e([^\\p{L}]|$)")
    }

    private val categories: List<Pair<String, List<String>>> = listOf(
        "Reggae & caribbean" to listOf("reggae", "dancehall", "ska", "soca", "calypso", "dub", "caribbean", "zouk"),
        "Christian & gospel" to listOf("christian", "gospel", "worship", "inspirational", "ccm", "praise"),
        "Soundtracks & musicals" to listOf("soundtrack", "score", "film", "movie", "video game", "games", "musical", "broadway", "tv"),
        "Family" to listOf("children", "kids", "lullaby", "family", "nursery"),
        "Hip-hop" to listOf("hip-hop", "hip hop", "hiphop", "rap", "trap", "drill", "grime", "lo-fi", "lofi", "boom bap", "reggaeton", "urbano"),
        "Metal" to listOf("metal", "metalcore", "deathcore", "hardcore", "screamo", "djent", "thrash", "post-hardcore", "grindcore"),
        "R&B & soul" to listOf("r&b", "rnb", "r & b", "soul", "funk", "motown", "quiet storm"),
        "Indie & alternative" to listOf("alternative", "indie", "emo", "shoegaze", "post-punk", "britpop", "grunge", "dream pop", "bedroom"),
        "Dance & electronic" to listOf("electro", "electronic", "dance", "house", "techno", "edm", "trance", "dubstep", "drum", "garage", "synth", "disco", "breakbeat", "ambient", "idm", "hardstyle", "club", "phonk", "amapiano"),
        "Country & Americana" to listOf("country", "americana", "bluegrass", "honky", "outlaw"),
        "Folk & acoustic" to listOf("folk", "singer/songwriter", "singer-songwriter", "songwriter", "acoustic", "celtic", "traditional", "flamenco"),
        "Jazz" to listOf("jazz", "swing", "bebop", "big band", "bossa"),
        "Blues" to listOf("blues"),
        "Classical" to listOf("classical", "orchestra", "opera", "baroque", "piano", "chamber", "new age", "instrumental", "meditation", "choral"),
        "Rock" to listOf("rock", "punk"),
        "Pop" to listOf(
            "pop", "easy listening", "vocal", "adult contemporary", "holiday", "christmas", "comedy", "world", "fitness", "standards", "cabaret", "schlager", "spoken",
            // Regional catalogue genres ("Latin Music", "Asian Music", "Indian Music"…) land here.
            "latin", "asian", "african", "afro", "brazil", "indian", "bollywood", "arab", "korean", "japan", "anime", "chinese", "mpb",
            "samba", "salsa", "cumbia", "bachata", "tropical", "mexican", "mexicano", "corrido", "banda", "mariachi", "norteño", "merengue",
            "highlife", "punjabi", "bhangra", "hindi", "tamil", "telugu", "desi", "filmi", "opm", "pinoy", "filipino", "tagalog", "mandopop", "cantopop",
        ),
    )

    /** Every category, in YouTube Music's order. */
    val all: List<String> = categories.map { it.first }.sorted()

    /** These only count as whole words ("dub" isn't "dubstep", "emo" isn't "emotional"). */
    private val wholeWords = setOf("dub", "ska", "tv", "emo", "ccm", "idm", "opm", "rnb", "soca", "pop", "folk", "funk", "club", "drum", "reggae")

    /** Short keywords must start a word ("rap" isn't "trap", but "afro" is in "afrobeats"). */
    private val patterns = categories.map { (name, keys) ->
        name to keys.map { k ->
            val e = Regex.escape(k)
            when {
                k in wholeWords -> Regex("(^|[^a-z])$e([^a-z]|$)")
                k.length <= 4 -> Regex("(^|[^a-z])$e")
                else -> Regex(e)
            }
        }
    }

    /** The category for a raw genre name, or null if it doesn't say enough. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val l = raw.lowercase()
        for ((name, keys) in patterns) if (keys.any { it.containsMatchIn(l) }) return name
        return null
    }
}

/**
 * Genre detection (Deezer, then iTunes) and caching. YouTube Music doesn't
 * expose genres, so songs are classified by their artist (one lookup covers
 * every song by them), falling back to the artist's related artists, and
 * finally to whatever the rest of the collection is mostly made of.
 */
class MetaRepository(private val http: OkHttpClient, private val dao: MetaDao) {
    /** Diagnostics hook (tests print it). */
    var trace: ((String) -> Unit)? = null
    /** Deezer allows ~50 requests per 5 s; iTunes only ~20 a minute, so it's a last resort. */
    private val deezer = Semaphore(6)
    private val itunesLock = Mutex()
    private var lastItunes = 0L

    private fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    // v2 keys: the old cache could hold one song's odd genre saved as its artist's, so it isn't reused.
    private fun artistKey(name: String) = "artist2:${key(name)}"

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").substringBefore(" - ").trim()

    private fun fetchJson(url: okhttp3.HttpUrl): JsonElement? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "PrismMusic/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) null else InnerTube.json.parseToJsonElement(r.body.string())
        }

    private val deezerSlots = Mutex()
    private val deezerSent = ArrayDeque<Long>()

    /**
     * Requests to api.deezer.com are paced to 40 per 5 seconds (its limit is 50) and
     * retried briefly if it still answers "quota exceeded"; everything else goes straight out.
     */
    private suspend fun getJson(url: okhttp3.HttpUrl): JsonElement? {
        if (url.host != "api.deezer.com") return fetchJson(url)
        repeat(4) { attempt ->
            deezerSlots.withLock {
                while (true) {
                    val now = System.currentTimeMillis()
                    while (deezerSent.isNotEmpty() && now - deezerSent.first() > 5_000) deezerSent.removeFirst()
                    if (deezerSent.size < 40) { deezerSent.addLast(now); break }
                    delay(5_000 - (now - deezerSent.first()) + 20)
                }
            }
            val res = fetchJson(url)
            val quota = (res.field("error") as? JsonObject)?.let { it.str("code") == "4" } == true
            if (!quota) return res
            delay(1_200L * (attempt + 1))
        }
        return null
    }

    private fun JsonElement?.field(k: String): JsonElement? = (this as? JsonObject)?.get(k)

    private suspend fun deezerAlbumGenre(albumId: String): String? {
        val cacheKey = "dzalbum:$albumId"
        dao.genre(cacheKey)?.let { return it.genre }
        val album = getJson("https://api.deezer.com/album/$albumId".toHttpUrl())
        val g = (album.field("genres").field("data") as? JsonArray)?.mapNotNull { it.str("name") }?.joinToString(" / ")
        dao.putGenre(GenreEntity(cacheKey, g, System.currentTimeMillis()))
        return g
    }

    /** Raw genre for a track (closest duration match on Deezer). */
    private suspend fun deezerTrackGenre(song: Song): String? = deezer.withPermit {
        val q = "artist:\"${song.primaryArtist}\" track:\"${cleanTitle(song.title)}\""
        val search = getJson("https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "5").build())
        val data = search.field("data") as? JsonArray ?: return@withPermit null
        val best = data.minByOrNull { abs((it.str("duration")?.toIntOrNull() ?: 0) - song.durationSec) } ?: return@withPermit null
        best.field("album").str("id")?.let { deezerAlbumGenre(it) }
    }

    /** Deezer's fixed top-level genre ids. */
    private val deezerGenres = mapOf(
        132 to "Pop", 116 to "Rap/Hip Hop", 152 to "Rock", 113 to "Dance", 165 to "R&B", 85 to "Alternative", 106 to "Electro",
        466 to "Folk", 144 to "Reggae", 129 to "Jazz", 84 to "Country", 67 to "Salsa", 65 to "Traditional Mexicano",
        98 to "Classical", 173 to "Films/Games", 464 to "Metal", 169 to "Soul & Funk", 2 to "African Music", 16 to "Asian Music",
        153 to "Blues", 75 to "Brazilian Music", 81 to "Indian Music", 95 to "Kids", 197 to "Latin Music",
    )

    /**
     * Raw genres of an artist on Deezer, most common first: a vote across their
     * releases (albums count double, compilations not at all), in two requests.
     */
    private suspend fun deezerArtistGenre(name: String): String? = deezer.withPermit {
        val search = getJson("https://api.deezer.com/search/artist".toHttpUrl().newBuilder().addQueryParameter("q", name).addQueryParameter("limit", "5").build())
        val found = search.field("data") as? JsonArray ?: return@withPermit null
        val artist = found.firstOrNull { key(it.str("name") ?: "") == key(name) } ?: found.firstOrNull() ?: return@withPermit null
        val id = artist.str("id") ?: return@withPermit null
        val albums = getJson("https://api.deezer.com/artist/$id/albums".toHttpUrl().newBuilder().addQueryParameter("limit", "40").build())
            .field("data") as? JsonArray ?: return@withPermit null
        val votes = HashMap<String, Int>()
        albums.forEach { a ->
            val genre = a.str("genre_id")?.toIntOrNull()?.let { deezerGenres[it] } ?: return@forEach
            val weight = when (a.str("record_type")) { "album" -> 2; "compile" -> 0; else -> 1 }
            if (weight > 0) votes.merge(genre, weight, Int::plus)
        }
        votes.entries.sortedByDescending { it.value }.joinToString(" / ") { it.key }.ifBlank { null }
    }

    private suspend fun itunes(url: okhttp3.HttpUrl): JsonElement? = itunesLock.withLock {
        val wait = 3_100 - (System.currentTimeMillis() - lastItunes)
        if (wait > 0) delay(wait)
        lastItunes = System.currentTimeMillis()
        getJson(url)
    }

    private suspend fun itunesArtistGenre(name: String): String? {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", name).addQueryParameter("media", "music").addQueryParameter("entity", "musicArtist").addQueryParameter("limit", "3").build()
        val results = itunes(url).field("results") as? JsonArray ?: return null
        return (results.firstOrNull { key(it.str("artistName") ?: "") == key(name) } ?: results.firstOrNull()).str("primaryGenreName")
    }

    private fun categoryOf(raw: String?): String? = raw?.split(" / ")?.firstNotNullOfOrNull { Genres.normalize(it) } ?: Genres.normalize(raw)

    /** An artist's category, looked up once and cached. */
    suspend fun artistGenre(name: String, relatedNames: (suspend () -> List<String>)? = null): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        val k = artistKey(name)
        dao.genre(k)?.let { cached -> categoryOf(cached.genre)?.let { return@withContext it } }
        val t0 = System.currentTimeMillis()
        val dz = runCatching { deezerArtistGenre(name) }.onFailure { trace?.invoke("deezer error $name: $it") }.getOrNull()
        var g = categoryOf(dz)
        val it = if (g == null) runCatching { itunesArtistGenre(name) }.getOrNull() else null
        if (g == null) g = categoryOf(it)
        trace?.invoke("artist $name: deezer=$dz itunes=$it -> $g (${System.currentTimeMillis() - t0} ms)")
        if (g == null && relatedNames != null) {
            // Not catalogued anywhere: go by the company it keeps (YouTube Music's related artists).
            val votes = runCatching { relatedNames() }.getOrDefault(emptyList()).take(5)
                .mapNotNull { other -> dao.genre(artistKey(other))?.genre?.let(::categoryOf) ?: runCatching { categoryOf(deezerArtistGenre(other)) }.getOrNull() }
            g = votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        if (g != null) dao.putGenre(GenreEntity(k, g, System.currentTimeMillis()))
        g
    }

    /** One song's category: its artist's (consistent across every list); a track search only fills in for unknown artists. */
    suspend fun genreFor(song: Song): String? = withContext(Dispatchers.IO) {
        val k = "song2:${song.id}"
        dao.genre(k)?.let { cached -> categoryOf(cached.genre)?.let { return@withContext it } }
        val g = artistGenre(song.primaryArtist) ?: runCatching { categoryOf(deezerTrackGenre(song)) }.getOrNull()
        if (g != null) dao.putGenre(GenreEntity(k, g, System.currentTimeMillis()))
        g
    }

    /** Categories already known (song, then artist), without any network. */
    suspend fun cachedGenres(songs: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        val bySong = songs.map { "song2:${it.id}" }.chunked(500).flatMap { dao.genres(it) }
            .associate { it.key.removePrefix("song2:") to categoryOf(it.genre) }
        val artistKeys = songs.map { artistKey(it.primaryArtist) }.distinct()
        val byArtist = artistKeys.chunked(500).flatMap { dao.genres(it) }.associate { it.key to categoryOf(it.genre) }
        songs.mapNotNull { s -> (bySong[s.id] ?: byArtist[artistKey(s.primaryArtist)])?.let { s.id to it } }.toMap()
    }

    /**
     * Sorts every song into a category, reporting results in batches. Artists
     * are looked up once each (several at a time); anything still unknown at
     * the end takes the collection's most common category.
     */
    suspend fun resolveGenres(
        songs: List<Song>,
        related: (suspend (Song) -> List<String>)? = null,
        onBatch: (Map<String, String>) -> Unit,
    ) = coroutineScope {
        val known = cachedGenres(songs).toMutableMap()
        if (known.isNotEmpty()) onBatch(known.toMap())
        val pending = songs.filter { it.id !in known }.groupBy { it.primaryArtist }
        // Every artist is looked up concurrently (the rate limiters pace the requests); results
        // stream back and are handed over in small batches as they arrive.
        val results = Channel<Pair<String?, List<Song>>>(Channel.UNLIMITED)
        pending.forEach { (artist, list) ->
            launch { results.send(artistGenre(artist, related?.let { r -> { r(list.first()) } }) to list) }
        }
        val buffer = HashMap<String, String>()
        var lastEmit = System.currentTimeMillis()
        repeat(pending.size) { n ->
            val (g, list) = results.receive()
            if (g != null) list.forEach { buffer[it.id] = g }
            val last = n == pending.size - 1
            if (buffer.isNotEmpty() && (last || buffer.size >= 25 || System.currentTimeMillis() - lastEmit > 600)) {
                known += buffer
                onBatch(buffer.toMap())
                buffer.clear()
                lastEmit = System.currentTimeMillis()
            }
        }
        results.close()
        val missing = songs.filter { it.id !in known }
        if (missing.isNotEmpty()) {
            val fallback = known.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Pop"
            onBatch(missing.associate { it.id to fallback })
        }
    }
}
