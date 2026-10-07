package com.prism.music.data.innertube

import com.prism.music.data.model.AccountInfo
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.HomeFeed
import com.prism.music.data.model.NextResult
import com.prism.music.data.model.PlayerExtras
import com.prism.music.data.model.SearchResult
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject

enum class SearchFilter(val label: String, val params: String?) {
    /** Everything, ranked by Prism (see [YouTubeMusic.searchTop]). */
    TOP("Top results", null),
    SONGS("Songs", "EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("Videos", "EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS("Albums", "EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    ARTISTS("Artists", "EgWKAQIgAWoMEA4QChADEAQQCRAF"),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
}

/** High-level YouTube Music operations built on [InnerTube]. */
class YouTubeMusic(private val api: InnerTube) {

    private suspend fun browse(browseId: String, params: String? = null): JsonObject =
        api.post("browse", buildJsonObject {
            put("browseId", browseId)
            params?.let { put("params", it) }
        })

    private suspend fun continueBrowse(token: String): JsonObject =
        api.post("browse", buildJsonObject { put("continuation", token) }, "&ctoken=$token&continuation=$token&type=next")

    // ---------------------------------------------------------------- Home & explore

    suspend fun home(continuation: String? = null): HomeFeed {
        val res = if (continuation == null) browse("FEmusic_home") else continueBrowse(continuation)
        // Continuation pages carry a (stale) "contents" block too; the next token lives in "continuationContents".
        return HomeFeed(Parser.shelves(res), Parser.continuation(res.obj("continuationContents") ?: res.obj("contents")))
    }

    /**
     * Loads the home feed plus a few continuation pages so the catalogue has plenty to offer.
     * Keeps paging (up to [maxPages]) while [satisfied] is false, so shelves the user pinned
     * that YouTube happens to serve further down the feed still turn up.
     */
    suspend fun fullHome(pages: Int = 3, maxPages: Int = pages, satisfied: (List<Shelf>) -> Boolean = { true }): List<Shelf> {
        val all = mutableListOf<Shelf>()
        var feed = home()
        all += feed.shelves
        var n = 1
        while (feed.continuation != null && (n < pages || (n < maxPages && !satisfied(all)))) {
            feed = runCatching { home(feed.continuation) }.getOrNull() ?: break
            all += feed.shelves
            n++
        }
        return all.distinctBy { it.title }
    }

    suspend fun moodsAndGenres(): List<Shelf> = Parser.shelves(browse("FEmusic_moods_and_genres"))

    suspend fun browseShelves(browseId: String, params: String? = null): List<Shelf> =
        Parser.shelves(browse(browseId, params))

    // ---------------------------------------------------------------- Search

    suspend fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val res = api.post("music/get_search_suggestions", buildJsonObject { put("input", query) })
        return res.findAll("searchSuggestionRenderer").mapNotNull { it.obj("suggestion").text() }.distinct()
    }

    /** Songs, albums, artists, playlists and videos at once, ranked by how well they match ([SearchRank]). */
    suspend fun searchTop(query: String): TopSearch = kotlinx.coroutines.coroutineScope {
        val kinds = listOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS, SearchFilter.VIDEOS)
        val results = kinds.map { f -> async { f to runCatching { search(query, f).items }.getOrDefault(emptyList()) } }.awaitAll()
        if (results.all { it.second.isEmpty() }) throw java.io.IOException("Search failed")
        SearchRank.rank(query, results)
    }

    suspend fun search(query: String, filter: SearchFilter, continuation: String? = null): SearchResult {
        val res = if (continuation != null) {
            api.post("search", buildJsonObject { put("continuation", continuation) }, "&ctoken=$continuation&continuation=$continuation&type=next")
        } else {
            api.post("search", buildJsonObject {
                put("query", query)
                filter.params?.let { put("params", it.replace("%3D", "=")) }
            })
        }
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }
        return SearchResult(items, Parser.continuation(res.obj("contents") ?: res.obj("continuationContents")))
    }

    // ---------------------------------------------------------------- Collections

    private fun songsFrom(res: JsonObject): List<Song> =
        res.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song }

    suspend fun album(browseId: String): CollectionPage {
        val res = browse(browseId)
        val header = res.findFirst("musicResponsiveHeaderRenderer") ?: res.findFirst("musicDetailHeaderRenderer")
        val title = header.obj("title").text() ?: "Album"
        val artistLine = header.obj("straplineTextOne").text() ?: ""
        val thumb = header.obj("thumbnail").bestThumbnail()
        val artistRuns = header.obj("straplineTextOne").runs()
        val artists = Parser.artistsFromRuns(artistRuns)
        val albumRef = com.prism.music.data.model.AlbumRef(title, browseId)
        val songs = songsFrom(res).map { s ->
            s.copy(
                artists = s.artists.ifEmpty { artists },
                album = s.album ?: albumRef,
                thumbnail = s.thumbnail ?: thumb,
            )
        }
        val playlistId = res.findFirst("watchPlaylistEndpoint").str("playlistId")
            ?: res.findFirst("microformatDataRenderer").str("urlCanonical")?.substringAfter("list=", "")?.takeIf { it.isNotBlank() }
        return CollectionPage(
            id = browseId,
            kind = CollectionKind.ALBUM,
            title = title,
            subtitle = artistLine,
            secondSubtitle = header.obj("subtitle").text() ?: "",
            description = header.obj("description").findFirst("description").text()
                ?: header.obj("description").text(),
            thumbnail = thumb,
            songs = songs,
            playlistId = playlistId,
            artists = artists,
            trackPlays = res.findAll("musicResponsiveListItemRenderer").mapNotNull { r ->
                val id = r.str("playlistItemData", "videoId") ?: return@mapNotNull null
                Parser.playCount(r)?.let { id to it }
            }.toMap(),
        )
    }

    suspend fun playlist(playlistId: String): CollectionPage {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val res = browse(browseId)
        val header = res.findFirst("musicResponsiveHeaderRenderer")
            ?: res.findFirst("musicEditablePlaylistDetailHeaderRenderer").findFirst("musicResponsiveHeaderRenderer")
            ?: res.findFirst("musicDetailHeaderRenderer")
        val shelf = res.findFirst("musicPlaylistShelfRenderer") ?: res.findFirst("musicShelfRenderer")
        val songs = (shelf.arr("contents") ?: emptyList()).mapNotNull {
            it.obj("musicResponsiveListItemRenderer")?.let { r -> (Parser.responsive(r) as? SongItem)?.song }
        }
        return CollectionPage(
            id = playlistId.removePrefix("VL"),
            kind = CollectionKind.PLAYLIST,
            title = header.obj("title").text() ?: "Playlist",
            subtitle = header.obj("straplineTextOne").text() ?: header.obj("subtitle").text() ?: "",
            secondSubtitle = header.obj("secondSubtitle").text() ?: "",
            description = header.obj("description").findFirst("description").text(),
            thumbnail = header.obj("thumbnail").bestThumbnail(),
            songs = songs,
            continuation = Parser.continuation(shelf),
            playlistId = playlistId.removePrefix("VL"),
        )
    }

    suspend fun playlistContinuation(token: String): Pair<List<Song>, String?> {
        val res = api.post("browse", buildJsonObject { put("continuation", token) })
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song }
        val next = res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token")
            ?: res.findFirst("musicPlaylistShelfContinuation").let { Parser.continuation(it) }
        return items to next
    }

    /** Loads every song of a playlist, following continuations up to [limit]. */
    suspend fun playlistAll(playlistId: String, limit: Int = 2000): CollectionPage {
        val first = playlist(playlistId)
        val songs = first.songs.toMutableList()
        var token = first.continuation
        while (token != null && songs.size < limit) {
            val (more, next) = runCatching { playlistContinuation(token!!) }.getOrNull() ?: break
            if (more.isEmpty()) break
            songs += more
            token = next
        }
        return first.copy(songs = songs.distinctBy { it.id }, continuation = null)
    }

    suspend fun artist(browseId: String): ArtistPage {
        val res = browse(browseId)
        val header = res.findFirst("musicImmersiveHeaderRenderer") ?: res.findFirst("musicVisualHeaderRenderer")
            ?: res.findFirst("musicResponsiveHeaderRenderer")
        val shelves = Parser.shelves(res)
        val mix = (header.findFirst("startRadioButton") ?: res.findFirst("startRadioButton")).findFirst("watchPlaylistEndpoint")
        // No "Mix" button (common when signed out): seed a radio from the artist's top song.
        val topSong = shelves.flatMap { it.items }.filterIsInstance<SongItem>().firstOrNull { !it.song.isVideo }?.song?.id
        // The "About" section has the full biography; the header's text is often just a promo line.
        val about = res.findFirst("musicDescriptionShelfRenderer")
        return ArtistPage(
            id = browseId,
            name = header.obj("title").text() ?: "Artist",
            thumbnail = header.obj("thumbnail").bestThumbnail() ?: header.obj("foregroundThumbnail").bestThumbnail(),
            description = about.obj("description").text()?.takeIf { it.isNotBlank() } ?: header.obj("description").text(),
            views = about.obj("subheader").text()?.takeIf { it.contains("view") },
            subscribers = header.obj("monthlyListenerCount").text()
                ?: header.findFirst("subscriberCountText").text()
                ?: header.obj("subscriptionButton", "subscribeButtonRenderer", "longSubscriberCountText").text(),
            shelves = shelves,
            radioPlaylistId = mix.str("playlistId") ?: topSong?.let { "RDAMVM$it" },
            radioParams = mix.str("params"),
            radioVideoId = if (mix.str("playlistId") == null) topSong else null,
            shufflePlaylistId = header.findFirst("playButton").let { b ->
                b.findFirst("watchEndpoint").str("playlistId") ?: b.findFirst("watchPlaylistEndpoint").str("playlistId")
            },
            shuffleVideoId = header.findFirst("playButton").findFirst("watchEndpoint").str("videoId"),
            shuffleParams = header.findFirst("playButton").let { b ->
                b.findFirst("watchEndpoint").str("params") ?: b.findFirst("watchPlaylistEndpoint").str("params")
            },
        )
    }

    // ---------------------------------------------------------------- Queue / radio

    suspend fun next(
        videoId: String?,
        playlistId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): NextResult {
        val res = api.post("next", buildJsonObject {
            videoId?.let { put("videoId", it) }
            playlistId?.let { put("playlistId", it) }
            params?.let { put("params", it) }
            continuation?.let { put("continuation", it) }
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        })
        val counterparts = mutableMapOf<String, String>()
        val counterpartSongs = mutableMapOf<String, Song>()
        val songs = mutableListOf<Song>()
        val panelItems = (res.findFirst("playlistPanelRenderer") ?: res.findFirst("playlistPanelContinuation"))
            .arr("contents") ?: emptyList()
        for (item in panelItems) {
            item.obj("playlistPanelVideoRenderer")?.let { Parser.panelVideo(it)?.let(songs::add) }
            item.obj("playlistPanelVideoWrapperRenderer")?.let { w ->
                val primary = w.obj("primaryRenderer", "playlistPanelVideoRenderer")?.let { Parser.panelVideo(it) }
                if (primary != null) {
                    songs += primary
                    val other = w.arr("counterpart")?.firstOrNull()?.obj("counterpartRenderer", "playlistPanelVideoRenderer")
                    other?.str("videoId")?.let { counterparts[primary.id] = it }
                    other?.let { Parser.panelVideo(it) }?.let { counterpartSongs[primary.id] = it }
                }
            }
        }
        val tabs = res.findFirst("watchNextTabbedResultsRenderer").arr("tabs") ?: emptyList()
        val lyricsId = tabs.firstNotNullOfOrNull { t ->
            t.str("tabRenderer", "endpoint", "browseEndpoint", "browseId")?.takeIf { it.startsWith("MPLY") }
        }
        val cont = res.findFirst("playlistPanelRenderer")?.let { Parser.continuation(it.arr("continuations")) }
            ?: Parser.continuation(res.obj("continuationContents"))
        return NextResult(songs, cont, lyricsId, counterparts, counterpartSongs)
    }

    /**
     * The song a music video or live performance belongs to. Official videos are paired with
     * their audio track in the watch queue; anything else (live sets, performances) is matched
     * by a song search on the cleaned-up title and artist, with the duration as a tie-breaker.
     */
    suspend fun songForVideo(video: Song): Song? {
        val r = next(video.id)
        r.counterpartSongs[video.id]?.takeIf { !it.isVideo }?.let { return it }
        r.songs.firstOrNull { !it.isVideo && r.counterparts[it.id] == video.id }?.let { return it }
        fun norm(s: String) = s.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("\\b(official|music|lyric|lyrics|video|audio|live|performance|session|hd|4k|visualizer)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        // "Artist - Title (Live at …)" videos put the artist in the title.
        val artist = norm(video.primaryArtist)
        val head = video.title.substringBefore(" - ", "")
        val rawTitle = if (head.isNotEmpty() && norm(head).let { a -> artist.isBlank() || a.contains(artist) || artist.contains(a) })
            video.title.substringAfter(" - ") else video.title
        val title = norm(rawTitle)
        if (title.isBlank()) return null
        val query = "${rawTitle.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()} ${video.primaryArtist}".trim()
        // Only an exact title by the same artist counts; a near miss would put the wrong song's lyrics on the video.
        return search(query, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song }
            .filter { s ->
                !s.isVideo && norm(s.title) == title &&
                    (artist.isBlank() || s.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } })
            }
            .minByOrNull { s -> if (video.durationSec > 0 && s.durationSec > 0) kotlin.math.abs(s.durationSec - video.durationSec) else 0 }
    }

    /**
     * Finds the official music video for an audio track. Uses the pairing from
     * the watch queue when YouTube provides it, otherwise a filtered video search.
     */
    suspend fun findMusicVideo(song: Song): String? {
        next(song.id).counterparts[song.id]?.let { return it }
        fun norm(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
        val title = norm(song.title)
        val artist = norm(song.primaryArtist)
        if (title.isBlank()) return null
        val videos = search("${song.title} ${song.primaryArtist}", SearchFilter.VIDEOS).items
            .filterIsInstance<SongItem>().map { it.song }
        return videos.firstOrNull { v ->
            val vt = norm(v.title)
            val artistOk = artist.isBlank() || vt.contains(artist) ||
                v.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } }
            val durationOk = song.durationSec == 0 || v.durationSec == 0 || kotlin.math.abs(v.durationSec - song.durationSec) < 150
            vt.contains(title) && artistOk && durationOk && !vt.contains("lyric") && !vt.contains("cover") && !vt.contains("live")
        }?.id
    }

    suspend fun lyrics(browseId: String): String? {
        val res = browse(browseId)
        return res.findFirst("musicDescriptionShelfRenderer").obj("description").text()
    }

    /** Fetches loudness + playback tracking info for normalization and history sync. */
    suspend fun playerExtras(videoId: String): PlayerExtras {
        val res = api.post("player", buildJsonObject {
            put("videoId", videoId)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") { put("html5Preference", "HTML5_PREF_WANTS") }
            }
        })
        return PlayerExtras(
            loudnessDb = res.str("playerConfig", "audioConfig", "loudnessDb")?.toDoubleOrNull(),
            trackingUrl = res.str("playbackTracking", "videostatsPlaybackUrl", "baseUrl"),
            watchtimeUrl = res.str("playbackTracking", "videostatsWatchtimeUrl", "baseUrl"),
        )
    }

    /** Registers a play in the user's YouTube Music history so recommendations stay in sync. */
    suspend fun registerPlayback(extras: PlayerExtras) {
        val base = extras.trackingUrl ?: return
        val cpn = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".random() }.joinToString("")
        val sep = if (base.contains("?")) "&" else "?"
        api.ping("$base${sep}ver=2&c=WEB_REMIX&cver=${api.clientVersion}&cpn=$cpn")
    }

    // ---------------------------------------------------------------- Account & library

    suspend fun accountInfo(): AccountInfo? {
        val res = api.post("account/account_menu")
        val h = res.findFirst("activeAccountHeaderRenderer") ?: return null
        return AccountInfo(
            name = h.obj("accountName").text() ?: "YouTube Music",
            email = h.obj("email").text() ?: h.obj("channelHandle").text(),
            avatar = h.obj("accountPhoto").bestThumbnail(),
        )
    }

    suspend fun likedSongs(limit: Int = 5000): List<Song> = playlistAll("LM", limit).songs

    suspend fun libraryPlaylists(): List<BrowseItem> =
        browse("FEmusic_liked_playlists").let { res ->
            res.findAll("musicTwoRowItemRenderer").mapNotNull { Parser.twoRow(it) }
        }

    /** A library page plus its continuations (they come 25 at a time). */
    private suspend fun libraryItems(browseId: String, maxPages: Int = 20): List<BrowseItem> {
        val out = mutableListOf<BrowseItem>()
        var res = browse(browseId)
        var pages = 0
        while (true) {
            out += res.findAll("musicTwoRowItemRenderer").mapNotNull { Parser.twoRow(it) }
            out += res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }
            val token = Parser.continuation(res) ?: break
            if (++pages >= maxPages) break
            res = runCatching { continueBrowse(token) }.getOrNull() ?: break
        }
        return out.distinctBy { it.id }
    }

    suspend fun libraryAlbums(): List<BrowseItem> =
        libraryItems("FEmusic_liked_albums").filterIsInstance<com.prism.music.data.model.AlbumItem>()

    /** Artists of songs in the library, then artists the account subscribes to. */
    suspend fun libraryArtists(): List<BrowseItem> {
        val tracks = runCatching { libraryItems("FEmusic_library_corpus_track_artists") }.getOrDefault(emptyList())
        val subs = runCatching { libraryItems("FEmusic_library_corpus_artists") }.getOrDefault(emptyList())
        return (tracks + subs).filterIsInstance<com.prism.music.data.model.ArtistItem>().distinctBy { it.id }
    }

    suspend fun history(): List<Song> = songsFrom(browse("FEmusic_history"))

    suspend fun like(videoId: String, liked: Boolean) {
        api.post(if (liked) "like/like" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("videoId", videoId) }
        })
    }

    suspend fun dislike(videoId: String, disliked: Boolean) {
        api.post(if (disliked) "like/dislike" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("videoId", videoId) }
        })
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                addJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                }
            }
        })
    }

    suspend fun createPlaylist(title: String, videoIds: List<String>, description: String = ""): String? {
        val res = api.post("playlist/create", buildJsonObject {
            put("title", title)
            put("description", description)
            put("privacyStatus", "PRIVATE")
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        })
        return res.str("playlistId")
    }
}
