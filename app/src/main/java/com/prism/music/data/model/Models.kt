package com.prism.music.data.model

import kotlinx.serialization.Serializable

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
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" }
    val primaryArtist: String get() = artists.firstOrNull()?.name ?: ""
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
) : BrowseItem

data class ArtistItem(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
) : BrowseItem

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
)

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
)

data class AccountInfo(val name: String, val email: String?, val avatar: String?)

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
            val sized = Regex("=(w\\d+-h\\d+|s\\d+)[^/]*$")
            if (sized.containsMatchIn(url)) url.replace(sized, "=w$size-h$size-l90-rj")
            else "$url=w$size-h$size-l90-rj"
        }
        url.contains("i.ytimg.com/vi/") -> url.replace(Regex("/[a-z0-9_]+default\\.jpg.*$"), "/hqdefault.jpg")
        else -> url
    }
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun parseDuration(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return 0
    return parts.fold(0) { acc, p -> acc * 60 + p }
}
