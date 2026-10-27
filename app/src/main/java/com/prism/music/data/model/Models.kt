package com.prism.music.data.model

import kotlinx.serialization.Serializable

/** What a song without an artist (a file with no artist tag) shows. Shown only: never counted as an artist. */
const val UNKNOWN_ARTIST = "Unknown artist"

@Serializable
data class ArtistRef(val name: String, val id: String? = null)

@Serializable
data class AlbumRef(val title: String, val id: String? = null)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val isVideo: Boolean = false,
    val explicit: Boolean = false,
    /** A live stream (e.g. a "Listen together" station): played from its HLS manifest, with no end or cache. */
    val isLive: Boolean = false,
    /** A podcast episode: no lyrics, no Mix blends, no music radio after it, and it picks up where it was left. */
    val isEpisode: Boolean = false,
    /** An episode's show (its browse id, "MPSP…"), for "Go to podcast". */
    val podcastId: String? = null,
    /**
     * Music the listener uploaded to YouTube Music (#101): a song, but only their account may play it, and it's never
     * swapped for YouTube's own recording or given its music video.
     */
    val isUpload: Boolean = false,
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }.ifBlank { UNKNOWN_ARTIST }
    val primaryArtist: String get() = artists.firstOrNull()?.name ?: ""
    /** A music file on the phone (Library → Local music), not a YouTube Music song: it plays from the file. */
    val isLocal: Boolean get() = id.startsWith(LOCAL_PREFIX)

    companion object {
        /** Local songs' ids: this, then the file's MediaStore id. */
        const val LOCAL_PREFIX = "local:"
    }
}

sealed interface BrowseItem {
    val id: String
    val title: String
    val subtitle: String
    val thumbnail: String?
}

data class SongItem(val song: Song) : BrowseItem {
    override val id get() = song.id
    override val title get() = song.title
    override val subtitle get() = song.artistText
    override val thumbnail get() = song.thumbnail
}

data class AlbumItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
    val playlistId: String? = null,
) : BrowseItem

data class PlaylistItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
    /** Radio/mix style lists are played via the watch endpoint rather than browsed. */
    val isMix: Boolean = false,
    /** A station's seed song and watch params, which YouTube needs to start it (artist/"Listen together" stations). */
    val seedVideoId: String? = null,
    val params: String? = null,
) : BrowseItem

data class ArtistItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
) : BrowseItem

/** A podcast show. [id] is its browse id ("MPSP" + its playlist id); [subtitle] is the host. */
data class PodcastItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
) : BrowseItem {
    /** The show's episodes as a YouTube playlist (what "Save to library" works on). */
    val playlistId: String get() = id.removePrefix("MPSP")
}

/** One episode on a show's page, with what the list shows beside it. */
data class Episode(
    val song: Song,
    /** "Oct 2", "1d ago". */
    val date: String?,
    val description: String?,
    /** How much of it the account has heard on YouTube (0..1), when signed in. */
    val progress: Float = 0f,
    val played: Boolean = false,
)

data class PodcastPage(
    val id: String,
    val title: String,
    val author: ArtistRef?,
    val description: String?,
    val thumbnail: String?,
    val episodes: List<Episode>,
    val continuation: String?,
    /** In the account's library; null when signed out. */
    val savedToLibrary: Boolean?,
) {
    val playlistId: String get() = id.removePrefix("MPSP")
}

/** Mood / genre navigation tile from YouTube Music's "Moods & genres". */
data class MoodItem(
    override val id: String,
    override val title: String,
    val params: String?,
    val color: Long,
) : BrowseItem {
    override val subtitle get() = ""
    override val thumbnail: String? get() = null
}

data class Shelf(
    val title: String,
    val strapline: String? = null,
    val items: List<BrowseItem>,
    val moreBrowseId: String? = null,
    val moreParams: String? = null,
    /** YouTube's own grid (an artist's "See all" discography, for one), rather than a carousel. */
    val grid: Boolean = false,
    /** A carousel of song rows (a few per column) rather than cards: how YouTube Music lays out Quick picks. */
    val rows: Boolean = false,
    /** Cards a few per column: how YouTube Music lays out your own Speed dial and Listen again, in any language. */
    val stacked: Boolean = false,
)

/**
 * Home's Quick picks. Its title is in the account's language ("Selección rápida", "Schnellauswahl"…), so after the
 * English title it's known by its shape: the first carousel of song rows. (Matched by title alone, Home showed your
 * recent plays in its place for anyone using YouTube in another language.) Shelves of your own recent plays ("Listen
 * again", "Speed dial", in any language) are never taken for it.
 */
fun List<Shelf>.quickPicks(): Shelf? = firstOrNull { it.title.equals("Quick picks", true) }
    ?: firstOrNull { s -> s.rows && s.title.lowercase() !in RECENT_SHELVES && s.items.count { it is SongItem } * 2 > s.items.size }

/**
 * YouTube's own Speed dial (or, where it isn't offered, "Listen again"): what Home's Speed dial section starts from.
 * In another language (#131: "Marcación rápida") it's known by its shape, cards stacked a few per column.
 */
fun List<Shelf>.speedDial(): Shelf? = firstOrNull { it.title.equals("Speed dial", true) } ?: firstOrNull { it.title.equals("Listen again", true) }
    ?: firstOrNull { it.stacked && !it.rows }

/** Home shelves that are your own history, by their English titles. */
private val RECENT_SHELVES = setOf("listen again", "speed dial", "recently played", "keep listening")

/** Album/single/EP ordering, by the year in a card's subtitle ("Album • 2024", or just "2024"). */
object Releases {
    private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
    fun yearOf(item: BrowseItem): Int = yearRegex.find(item.subtitle)?.value?.toIntOrNull() ?: 0

    /**
     * Newest first. YouTube's discography lists are mostly newest first but tack live albums, radio
     * specials and remixes on at the end in their own runs; a stable sort keeps its order within a year.
     */
    fun newestFirst(items: List<BrowseItem>): List<BrowseItem> =
        if (items.none { it is AlbumItem }) items else items.sortedByDescending { yearOf(it) }

    fun oldestFirst(items: List<BrowseItem>): List<BrowseItem> =
        items.sortedWith(compareBy<BrowseItem> { yearOf(it).takeIf { y -> y > 0 } ?: Int.MAX_VALUE })
}

data class HomeFeed(val shelves: List<Shelf>, val continuation: String?)

enum class CollectionKind { ALBUM, PLAYLIST }

data class CollectionPage(
    val id: String,
    val kind: CollectionKind,
    val title: String,
    val subtitle: String,
    val secondSubtitle: String = "",
    val description: String? = null,
    val thumbnail: String?,
    val songs: List<Song>,
    val continuation: String? = null,
    val playlistId: String? = null,
    /** For albums: the credited artists, so their names can link to their pages. */
    val artists: List<ArtistRef> = emptyList(),
    /** For albums: each track's play count on YouTube Music, by video id. */
    val trackPlays: Map<String, Long> = emptyMap(),
    /** For albums: the lead artist's photo (YouTube shows it beside their name). */
    val artistThumbnail: String? = null,
    /** A playlist the signed-in account made (it can be edited, renamed and shared). */
    val owned: Boolean = false,
    /** An owned playlist's visibility on YouTube: "PRIVATE", "UNLISTED" or "PUBLIC". */
    val privacy: String? = null,
    /** In the account's library (YouTube's bookmark); null when that isn't known or doesn't apply. */
    val savedToLibrary: Boolean? = null,
    /** Each song's slot in an owned playlist (video id -> setVideoId), needed to take it out again. */
    val setVideoIds: Map<String, String> = emptyMap(),
    /** For albums: YouTube Music's "Other versions" (deluxe, clean, live…). */
    val otherVersions: List<BrowseItem> = emptyList(),
    val explicit: Boolean = false,
) {
    /** An album's second line, "Album • 2026", split up. */
    private val typeParts: List<String> get() = if (kind == CollectionKind.ALBUM) secondSubtitle.split(" • ").map { it.trim() } else emptyList()

    /** "Album", "Single" or "EP". */
    val releaseKind: String? get() = typeParts.firstOrNull { it in setOf("Album", "Single", "EP") }

    /** The release year, when the header gives one. */
    val year: Int? get() = typeParts.firstNotNullOfOrNull { p -> p.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2100 } }

    /**
     * The album's standout tracks (Apple Music marks them with a star): clearly more
     * played than the album's typical track, at most about a third of it, never more than five.
     */
    val popularIds: Set<String> get() = popularTracks(trackPlays)
}

fun popularTracks(plays: Map<String, Long>): Set<String> {
    if (plays.size < 3) return emptySet()
    val sorted = plays.entries.sortedByDescending { it.value }
    val median = sorted.map { it.value }.sorted()[sorted.size / 2].coerceAtLeast(1)
    val cap = ((sorted.size + 2) / 3).coerceIn(1, 5)
    return sorted.take(cap).filter { it.value >= median * 2 }.map { it.key }.toSet()
}

data class ArtistPage(
    val id: String,
    val name: String,
    val thumbnail: String?,
    val description: String?,
    val subscribers: String?,
    val shelves: List<Shelf>,
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
    val radioParams: String? = null,
    val radioVideoId: String? = null,
    val shuffleVideoId: String? = null,
    val shuffleParams: String? = null,
    /** Lifetime views on YouTube ("46,777,176,063 views"), from the About section. */
    val views: String? = null,
    /** The channel YouTube subscribes to for this artist (not always [id]: that can be the music "topic" channel). */
    val channelId: String? = null,
    /** The signed-in account subscribes to the artist. */
    val subscribed: Boolean = false,
)

data class SearchResult(val items: List<BrowseItem>, val continuation: String?)

data class NextResult(
    val songs: List<Song>,
    val continuation: String?,
    val lyricsBrowseId: String?,
    val counterparts: Map<String, String>,
    /** The other half of each pairing (the song for a music video, and the reverse), by the panel item's id. */
    val counterpartSongs: Map<String, Song> = emptyMap(),
) {
    /**
     * The queue as songs (#39): a music video that YouTube pairs with its song is swapped for the song (the video stays
     * its counterpart, for the player's Video button), and videos with no song version (fan uploads, live clips) are
     * left out. If that would leave almost nothing, the queue stays as it was.
     */
    fun songsOnly(keepFirst: Boolean = false): NextResult {
        val pairs = counterparts.toMutableMap()
        val swapped = songs.mapIndexedNotNull { i, s ->
            if (!s.isVideo || s.isLive || s.isEpisode || (keepFirst && i == 0)) return@mapIndexedNotNull s
            val song = counterpartSongs[s.id]?.takeIf { !it.isVideo } ?: return@mapIndexedNotNull null
            pairs[song.id] = s.id
            song
        }.distinctBy { it.id }
        if (swapped.size < minOf(3, songs.size)) return this
        return copy(songs = swapped, counterparts = pairs)
    }
}

data class AccountInfo(val name: String, val email: String?, val avatar: String?)

/**
 * A YouTube channel Prism can use: a Google account's own, or one of its brand accounts (one email can have several).
 * [authUser] picks the Google account in the session, [dataSyncId] the channel ("brand||user", or "user||").
 */
data class YtAccount(
    val name: String,
    /** The channel's @handle, or what YouTube shows under the name. */
    val byline: String?,
    /** The Google account it belongs to. */
    val email: String?,
    val avatar: String?,
    val authUser: Int,
    val dataSyncId: String,
)

data class PlayerExtras(
    val loudnessDb: Double?,
    val trackingUrl: String?,
    val watchtimeUrl: String?,
)

/** Rewrites a YouTube / Google image URL to request a given square size. */
fun hiRes(url: String?, size: Int = 1080): String? {
    if (url == null) return null
    return when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") -> {
            if (GOOGLE_SIZE.containsMatchIn(url)) url.replace(GOOGLE_SIZE, "=w$size-h$size-l90-rj")
            else "$url=w$size-h$size-l90-rj"
        }
        url.contains("i.ytimg.com/vi/") -> url.replace(YTIMG_NAME, "/hqdefault.jpg")
        else -> url
    }
}

// Compiled once: [hiRes] runs for every cover each time it's drawn into a list (it built both every call, 1.7.4).
private val GOOGLE_SIZE = Regex("=(w\\d+-h\\d+|s\\d+)[^/]*$")
private val YTIMG_NAME = Regex("/[a-z0-9_]+default\\.jpg.*$")

/** "1 song", "12 songs". */
fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun parseDuration(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    // Episodes give their length in words: "2 hr 54 min", "47 min", "1 hr", "35 sec".
    if (!text.contains(':') && Regex("\\d+\\s*(hr|min|sec)").containsMatchIn(text)) {
        fun part(unit: String) = Regex("(\\d+)\\s*$unit").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return part("hr") * 3600 + part("min") * 60 + part("sec")
    }
    val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return 0
    return parts.fold(0) { acc, p -> acc * 60 + p }
}
