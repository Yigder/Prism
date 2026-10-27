package com.prism.music.data.innertube

import com.prism.music.data.model.AccountInfo
import com.prism.music.data.model.ArtistItem
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
import kotlinx.serialization.json.JsonElement
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
    /** Playlists made by YouTube Music itself (#130). */
    FEATURED_PLAYLISTS("Featured playlists", "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"),
    /** Playlists made by people. */
    PLAYLISTS("Community playlists", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"),
    PODCASTS("Podcasts", "EgWKAQJQAWoMEA4QChADEAQQCRAF"),
    EPISODES("Episodes", "EgWKAQJIAWoMEA4QChADEAQQCRAF"),
    /** Streams on air now (lofi radios and the like), from youtube.com's search: YouTube Music has no such filter. */
    LIVE("Live", null),
}

/** High-level YouTube Music operations built on [InnerTube]. */
class YouTubeMusic(private val api: InnerTube, private val songsEverywhere: () -> Boolean = { false }) {
    companion object {
        /** An artist page "id" that's looked up by name ([artistIdByName]). */
        const val ARTIST_BY_NAME = "name:"

        /** Words that only describe a video, not the recording ([songForVideo]). */
        internal val VIDEO_WORDS = setOf("official", "music", "lyric", "lyrics", "video", "audio", "hd", "4k", "visualizer", "visualiser", "mv", "clip", "oficial")
        private val SONG_FOR_VIDEO_BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
        /** How far a video's length may be from its song's (an intro or outro on the video). */
        internal const val SONG_FOR_VIDEO_SLACK_SEC = 8
    }

    private suspend fun browse(browseId: String, params: String? = null): JsonObject {
        val body = buildJsonObject {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }
        return try {
            api.post("browse", body)
        } catch (e: InnerTubeException) {
            // Signed in, a public page can be "not found" for the account (Podcasts outside the countries that have
            // them, #18) while it opens fine signed out. Library pages stay refused that way, so the error stands.
            if (e.code != 404) throw e
            runCatching { api.postAnonymous("browse", body) }.getOrElse { throw e }
        }
    }

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
    suspend fun fullHome(pages: Int = 3, maxPages: Int = pages, satisfied: (List<Shelf>) -> Boolean = { true }): List<Shelf> =
        fullHomePages(pages, maxPages, satisfied).first

    /** [fullHome], with the pages as YouTube sent them (Home keeps those to show straight away at the next start, #126). */
    suspend fun fullHomePages(
        pages: Int = 3, maxPages: Int = pages, satisfied: (List<Shelf>) -> Boolean = { true },
    ): Pair<List<Shelf>, List<JsonObject>> {
        val all = mutableListOf<Shelf>()
        val raw = mutableListOf<JsonObject>()
        var res = browse("FEmusic_home")
        raw += res
        all += Parser.shelves(res)
        var next = Parser.continuation(res.obj("continuationContents") ?: res.obj("contents"))
        var n = 1
        while (next != null && (n < pages || (n < maxPages && !satisfied(all)))) {
            res = runCatching { continueBrowse(next!!) }.getOrNull() ?: break
            raw += res
            all += Parser.shelves(res)
            next = Parser.continuation(res.obj("continuationContents") ?: res.obj("contents"))
            n++
        }
        return all.distinctBy { it.title } to raw
    }

    suspend fun moodsAndGenres(): List<Shelf> = Parser.shelves(browse("FEmusic_moods_and_genres"))

    suspend fun browseShelves(browseId: String, params: String? = null): List<Shelf> {
        val res = browse(browseId, params)
        val shelves = Parser.shelves(res)
        // A list of songs on its own (an artist's "From your library" opened whole, #69) comes a page at a time.
        val only = shelves.singleOrNull()?.takeIf { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } } ?: return shelves
        var token = Parser.continuation(res.findFirst("musicShelfRenderer") ?: res.findFirst("musicPlaylistShelfRenderer"))
        val items = only.items.toMutableList()
        while (token != null && items.size < 2000) {
            val chunk = runCatching { playlistPage(token!!) }.getOrNull() ?: break
            if (chunk.songs.isEmpty()) break
            items += chunk.songs.map(::SongItem)
            token = chunk.next
        }
        return listOf(only.copy(items = items.distinctBy { it.id }))
    }

    // ---------------------------------------------------------------- Search

    suspend fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val res = api.post("music/get_search_suggestions", buildJsonObject { put("input", query) })
        return res.findAll("searchSuggestionRenderer").mapNotNull { it.obj("suggestion").text() }.distinct()
    }

    /**
     * Typing suggestions (1.7.6 Search): the completions, and the artists, songs and albums YouTube Music offers beside
     * them (with their pictures), in one request. Asked without the account, as searches are (#27).
     */
    suspend fun searchSuggestionsWithItems(query: String): Pair<List<String>, List<BrowseItem>> {
        val res = api.postAnonymous("music/get_search_suggestions", buildJsonObject { put("input", query) })
        val texts = res.findAll("searchSuggestionRenderer").mapNotNull { it.obj("suggestion").text() }.distinct()
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }.distinctBy { it.id }
        return texts to items
    }

    /**
     * Songs, albums, artists, playlists and videos at once, ranked by how well they match and how
     * well they fit the listener's [taste] ([SearchRank]).
     */
    suspend fun searchTop(query: String, taste: SearchRank.Taste = SearchRank.Taste.NONE): TopSearch = kotlinx.coroutines.coroutineScope {
        val kinds = listOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS, SearchFilter.VIDEOS, SearchFilter.LIVE)
        // YouTube's own "Top result" card, from its unfiltered search: it reads abbreviations and typos well.
        val card = async { runCatching { youtubeTopResult(query) }.getOrNull() }
        val attempts = kinds.map { f -> async { f to runCatching { search(query, f).items } } }.awaitAll()
        val results = attempts.map { (f, r) -> f to r.getOrDefault(emptyList()) }
        // Every search failed: keep why (offline, a timeout…), so the message can say so.
        if (results.all { it.second.isEmpty() }) throw java.io.IOException("Search failed", attempts.firstNotNullOfOrNull { it.second.exceptionOrNull() })
        val ranked = SearchRank.rank(query, results, taste, card.await())
        // A title track on top whose album YouTube's album search left out (it varies from one request to the next:
        // "after hours" sometimes has no After Hours by The Weeknd): the song names its album, so look that up.
        val song = (ranked.top as? SongItem)?.song
        val ref = song?.album
        if (song == null || ref?.id == null || !SearchRank.namesItsAlbum(query, song)) return@coroutineScope ranked
        val page = runCatching { album(ref.id) }.getOrNull()
        if (page?.releaseKind != "Album") return@coroutineScope ranked
        SearchRank.withAlbumOnTop(ranked, com.prism.music.data.model.AlbumItem(
            ref.id, page.title, listOfNotNull("Album", page.artists.firstOrNull()?.name ?: song.primaryArtist, page.year?.toString()).joinToString(" • "),
            page.thumbnail, page.playlistId,
        ))
    }

    /** The card at the top of YouTube Music's unfiltered search, if it shows one. */
    suspend fun youtubeTopResult(query: String): BrowseItem? {
        val res = api.postAnonymous("search", buildJsonObject { put("query", query) })
        return res.findAll("musicCardShelfRenderer").firstOrNull()?.let { Parser.card(it) }
    }

    suspend fun search(query: String, filter: SearchFilter, continuation: String? = null): SearchResult {
        if (filter == SearchFilter.LIVE) return searchLive(query, continuation)
        // Without the account: signed in, YouTube keeps every search in the account's history (#27).
        val res = if (continuation != null) {
            api.postAnonymous("search", buildJsonObject { put("continuation", continuation) }, "&ctoken=$continuation&continuation=$continuation&type=next")
        } else {
            api.postAnonymous("search", buildJsonObject {
                put("query", query)
                filter.params?.let { put("params", it.replace("%3D", "=")) }
            })
        }
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { Parser.responsive(it) }
        return SearchResult(items, Parser.continuation(res.obj("contents") ?: res.obj("continuationContents")))
    }

    private suspend fun searchLive(query: String, continuation: String?): SearchResult {
        val res = api.webPost("search", buildJsonObject {
            if (continuation != null) put("continuation", continuation)
            else { put("query", query); put("params", LiveStreams.SEARCH_PARAMS) }
        })
        val items = LiveStreams.parse(res).map { SongItem(it) }
        // A page with nothing on air ends the list, rather than paging through past broadcasts.
        return SearchResult(items, LiveStreams.continuation(res)?.takeIf { items.isNotEmpty() })
    }

    /** What an artist's channel is streaming right now (Lofi Girl's radios, say), from its Live tab. */
    suspend fun liveStreams(channelId: String, owner: com.prism.music.data.model.ArtistRef): List<Song> {
        if (!channelId.startsWith("UC")) return emptyList()
        val res = api.webPost("browse", buildJsonObject {
            put("browseId", channelId)
            put("params", LiveStreams.CHANNEL_TAB_PARAMS)
        })
        return LiveStreams.parse(res, owner)
    }

    /**
     * YouTube's "Most replayed" curve for a video (song uploads have one too, once enough people have played them),
     * from youtube.com's own `next` answer; YouTube Music's doesn't carry it. Null when the video has none.
     */
    suspend fun heatmap(videoId: String): com.prism.music.data.Heatmap? =
        com.prism.music.data.BestPart.parse(api.webPost("next", buildJsonObject { put("videoId", videoId) }))

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
                // An album's tracks are its songs. Signed out, YouTube now lists the label's "Official Audio" /
                // "Lyric Video" uploads here, typed as music videos, and they opened in video mode. With "Songs, not
                // videos" everywhere they stay videos, so they're swapped for the song itself (#61).
                isVideo = s.isVideo && songsEverywhere(),
                artists = s.artists.ifEmpty { artists },
                album = s.album ?: albumRef,
                thumbnail = s.thumbnail ?: thumb,
            )
        }
        val playlistId = res.findFirst("watchPlaylistEndpoint").str("playlistId")
            ?: res.findFirst("microformatDataRenderer").str("urlCanonical")?.substringAfter("list=", "")?.takeIf { it.isNotBlank() }
        // "Other versions" sits beside the track list (deluxe, clean, live editions).
        val others = Parser.shelves(res).firstOrNull { it.title.equals("Other versions", true) }?.items.orEmpty()
            .filter { it.id != browseId }
        return CollectionPage(
            id = browseId,
            kind = CollectionKind.ALBUM,
            title = title,
            subtitle = artistLine,
            secondSubtitle = Parser.canonical(header.obj("subtitle").text() ?: ""),
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
            artistThumbnail = header.obj("straplineThumbnail").bestThumbnail(),
            savedToLibrary = libraryToggle(header),
            otherVersions = others,
            explicit = header.arr("subtitleBadge")?.any { it.str("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE" } == true,
        )
    }

    /** The header's bookmark ("Save to library") state; only meaningful when signed in. */
    private fun libraryToggle(header: JsonElement?): Boolean? {
        if (!api.signedIn) return null
        val toggle = header.arr("buttons")?.firstNotNullOfOrNull { it.obj("toggleButtonRenderer") } ?: return null
        return toggle.str("isToggled") == "true"
    }

    /** A playlist's songs' setVideoIds (what removing one from an owned playlist needs), by video id. */
    private fun setVideoIdsIn(node: JsonElement?): Map<String, String> =
        node.findAll("musicResponsiveListItemRenderer").mapNotNull { r ->
            val id = r.str("playlistItemData", "videoId") ?: return@mapNotNull null
            r.str("playlistItemData", "playlistSetVideoId")?.let { id to it }
        }.toMap()

    suspend fun playlist(playlistId: String): CollectionPage {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val res = browse(browseId)
        // The account's own playlists come with an edit header (and their visibility in it).
        val editable = res.findFirst("musicEditablePlaylistDetailHeaderRenderer")
        val header = res.findFirst("musicResponsiveHeaderRenderer")
            ?: editable.findFirst("musicResponsiveHeaderRenderer")
            ?: res.findFirst("musicDetailHeaderRenderer")
        val shelf = res.findFirst("musicPlaylistShelfRenderer") ?: res.findFirst("musicShelfRenderer")
        val songs = (shelf.arr("contents") ?: emptyList()).mapNotNull {
            it.obj("musicResponsiveListItemRenderer")?.let { r -> (Parser.responsive(r) as? SongItem)?.song }
        }
        val owned = editable != null && api.signedIn
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
            owned = owned,
            privacy = if (owned) editable.findFirst("musicPlaylistEditHeaderRenderer").str("privacy") else null,
            savedToLibrary = if (owned) null else libraryToggle(header),
            setVideoIds = if (owned) setVideoIdsIn(shelf) else emptyMap(),
        )
    }

    /** One more page of a long playlist. */
    class PlaylistChunk(val songs: List<Song>, val next: String?, val setVideoIds: Map<String, String>)

    suspend fun playlistPage(token: String): PlaylistChunk {
        val res = api.post("browse", buildJsonObject { put("continuation", token) })
        val items = res.findAll("musicResponsiveListItemRenderer").mapNotNull { (Parser.responsive(it) as? SongItem)?.song }
        val next = res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token")
            ?: res.findFirst("musicPlaylistShelfContinuation").let { Parser.continuation(it) }
        return PlaylistChunk(items, next, setVideoIdsIn(res))
    }

    suspend fun playlistContinuation(token: String): Pair<List<Song>, String?> =
        playlistPage(token).let { it.songs to it.next }

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

    /**
     * An artist's page id from their name, for artists a song names without a link (#78: "In the City" by Charly García,
     * Sting only links García). Only an exact name match counts.
     */
    suspend fun artistIdByName(name: String): String? {
        val want = SearchRank.norm(name)
        if (want.isBlank()) return null
        artistIds[want]?.let { return it }
        val items = search(name, SearchFilter.ARTISTS).items.filterIsInstance<ArtistItem>()
        return items.firstOrNull { SearchRank.norm(it.title) == want }?.id?.also { artistIds[want] = it }
    }
    private val artistIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [browseId] can be [ARTIST_BY_NAME] + a name, for an artist a song names without a link. */
    suspend fun artist(browseId: String): ArtistPage {
        if (browseId.startsWith(ARTIST_BY_NAME)) {
            val name = browseId.removePrefix(ARTIST_BY_NAME)
            return artist(artistIdByName(name) ?: throw java.io.IOException("Couldn't find $name in Prism Music"))
        }
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
            channelId = header.findFirst("subscribeButtonRenderer").str("channelId"),
            subscribed = header.findFirst("subscribeButtonRenderer").str("subscribed") == "true",
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
        // Without YouTube's own pairing it must be the same recording (GitHub #113: a live album's track,
        // "Nos siguen pegando abajo (Pecado mortal)", played as the studio song from another album). So only the video's
        // own words go ("Official Video", "(Lyric Video)", "HD"); anything else in brackets, and live or session takes,
        // tell recordings apart; and the lengths must agree.
        fun norm(s: String) = s.lowercase()
            .replace(SONG_FOR_VIDEO_BRACKETS) { m -> if (m.value.trim('(', ')', '[', ']', ' ').split(Regex("\\s+")).all { it in VIDEO_WORDS }) " " else m.value }
            .replace(Regex("\\b(${VIDEO_WORDS.joinToString("|")})\\b"), " ")
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
                    (artist.isBlank() || s.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } }) &&
                    (video.durationSec <= 0 || s.durationSec <= 0 || kotlin.math.abs(s.durationSec - video.durationSec) <= SONG_FOR_VIDEO_SLACK_SEC)
            }
            .minByOrNull { s -> if (video.durationSec > 0 && s.durationSec > 0) kotlin.math.abs(s.durationSec - video.durationSec) else 0 }
    }

    /**
     * Finds the official music video for an audio track. Uses the pairing from
     * the watch queue when YouTube provides it, otherwise a filtered video search.
     */
    suspend fun findMusicVideo(song: Song): String? {
        next(song.id).counterparts[song.id]?.let { log("video for ${song.id} \"${song.title}\": YouTube's own pairing $it"); return it }
        if (song.artists.isEmpty() || song.title.isBlank()) {
            log("video for ${song.id} \"${song.title}\": no artist or title to search with")
            return null
        }
        var videos = search("${song.title} ${song.primaryArtist}", SearchFilter.VIDEOS).items
            .filterIsInstance<SongItem>().map { it.song }
        var picked = MusicVideoMatch.pick(song, videos)
        // Fan uploads can crowd the artist's own video off the first page: ask once more, for the official one.
        if (picked == null) {
            val more = runCatching { search("${song.title} ${song.primaryArtist} official video", SearchFilter.VIDEOS).items.filterIsInstance<SongItem>().map { it.song } }.getOrDefault(emptyList())
            picked = MusicVideoMatch.pick(song, more)
            videos = videos + more
        }
        // What was looked at, so a "no video" can be told apart from a wrong rule (shared in playback diagnostics).
        log(
            "video for ${song.id} \"${song.title}\" by ${song.artistText} (${song.durationSec}s): " +
                (picked?.let { "picked $it" } ?: "none of") + " " +
                videos.take(6).joinToString("; ") { "\"${it.title}\" by ${it.artistText} ${it.durationSec}s ${it.id}" },
        )
        return picked
    }

    private fun log(message: String) { runCatching { android.util.Log.i("PrismVideo", message) } }

    suspend fun lyrics(browseId: String): String? {
        val res = browse(browseId)
        return res.findFirst("musicDescriptionShelfRenderer").obj("description").text()
    }

    /**
     * YouTube Music's time-synced lyrics (LyricFind's, as its app shows them), as LRC; null when the song has only
     * plain text. Only the Android app's lyrics page carries the timings ([InnerTube.androidMusicPost]).
     */
    suspend fun syncedLyrics(browseId: String): String? {
        val res = api.androidMusicPost("browse", buildJsonObject { put("browseId", browseId) })
        val lines = res.findFirst("timedLyricsData") as? kotlinx.serialization.json.JsonArray ?: return null
        val out = StringBuilder()
        for (l in lines) {
            val ms = l.str("cueRange", "startTimeMilliseconds")?.toLongOrNull() ?: continue
            val text = l.str("lyricLine") ?: continue
            out.append("[%02d:%02d.%02d]%s\n".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text.trim()))
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** Fetches loudness + playback tracking info for normalization and history sync. */
    /**
     * Fetches loudness + playback tracking info for normalization and history sync. [signatureTimestamp]
     * is the current player JS's: without it the player now answers "Video unavailable" with no
     * tracking URL, and plays never reach the account's history.
     */
    suspend fun playerExtras(videoId: String, signatureTimestamp: Int? = null): PlayerExtras {
        val res = api.post("player", buildJsonObject {
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (signatureTimestamp != null) put("signatureTimestamp", signatureTimestamp)
                }
            }
        })
        return PlayerExtras(
            loudnessDb = res.str("playerConfig", "audioConfig", "loudnessDb")?.toDoubleOrNull(),
            trackingUrl = res.str("playbackTracking", "videostatsPlaybackUrl", "baseUrl"),
            watchtimeUrl = res.str("playbackTracking", "videostatsWatchtimeUrl", "baseUrl"),
        ).also {
            if (it.trackingUrl == null) android.util.Log.w("PrismHistory", "no tracking URL for $videoId: " +
                "${res.str("playabilityStatus", "status")} ${res.str("playabilityStatus", "reason")} (sts $signatureTimestamp)")
        }
    }

    /** Registers a play in the user's YouTube Music history so recommendations stay in sync. */
    suspend fun registerPlayback(extras: PlayerExtras) {
        val base = extras.trackingUrl ?: return
        val cpn = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".random() }.joinToString("")
        val sep = if (base.contains("?")) "&" else "?"
        val code = api.ping("$base${sep}ver=2&c=WEB_REMIX&cver=${api.clientVersion}&cpn=$cpn")
        android.util.Log.i("PrismHistory", "play sent to YouTube Music: HTTP $code")
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

    /**
     * Every channel Prism can switch to: each Google account signed in on this session (up to ten), with its own
     * channel and its brand accounts. [cookie]: a session other than the saved one (just after adding an account).
     */
    suspend fun accounts(cookie: String? = null): List<com.prism.music.data.model.YtAccount> {
        val out = mutableListOf<com.prism.music.data.model.YtAccount>()
        val emails = HashSet<String>()
        for (authUser in 0 until 10) {
            val res = (if (cookie != null) api.accountsList(authUser, cookie) else api.accountsList(authUser)) ?: break
            val email = res.findFirst("googleAccountHeaderRenderer").obj("email").text()
            // Past the last account YouTube answers with the first one again.
            if (email != null && !emails.add(email)) break
            val items = res.findAll("accountItem")
            if (items.isEmpty()) break
            for (item in items) {
                val name = item.obj("accountName").text() ?: continue
                val pageId = item.findFirst("pageIdToken").str("pageId")
                val sync = item.findFirst("datasyncIdToken").str("datasyncIdToken")
                    ?: if (pageId != null) "$pageId||brand" else "||"
                out += com.prism.music.data.model.YtAccount(
                    name = name,
                    byline = item.obj("channelHandle").text() ?: item.obj("accountByline").text(),
                    email = email,
                    avatar = item.obj("accountPhoto").bestThumbnail(),
                    authUser = authUser,
                    dataSyncId = sync,
                )
            }
        }
        return out.distinctBy { it.authUser to it.dataSyncId }
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

    /**
     * A song's credits as YouTube Music's "View song credits" shows them ("Performed by", "Written by", "Produced by",
     * "Music metadata provided by"), each with its names. Empty when there are none (music videos usually have none).
     */
    suspend fun credits(videoId: String): List<Pair<String, List<String>>> {
        val r = browse("MPTC$videoId")
        fun find(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonObject? = when (e) {
            is kotlinx.serialization.json.JsonObject -> (e["dismissableDialogRenderer"] as? kotlinx.serialization.json.JsonObject)
                ?: e.values.firstNotNullOfOrNull { find(it) }
            is kotlinx.serialization.json.JsonArray -> e.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        fun runs(o: kotlinx.serialization.json.JsonObject?): List<String> =
            (o?.get("runs") as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val dialog = find(r) ?: return emptyList()
        return (dialog["sections"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { s ->
            val sec = (s as? kotlinx.serialization.json.JsonObject)?.get("dismissableDialogContentSectionRenderer") as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val title = runs(sec["title"] as? kotlinx.serialization.json.JsonObject).joinToString("").trim()
            val names = runs(sec["subtitle"] as? kotlinx.serialization.json.JsonObject).joinToString("").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isEmpty() || names.isEmpty()) null else title to names
        }
    }

    /** Only the artists the account subscribes to (follows) on YouTube Music. */
    suspend fun librarySubscriptions(): List<com.prism.music.data.model.ArtistItem> =
        libraryItems("FEmusic_library_corpus_artists").filterIsInstance<com.prism.music.data.model.ArtistItem>().distinctBy { it.id }

    suspend fun history(): List<Song> = songsFrom(browse("FEmusic_history"))

    /**
     * The account's history (what was played in the YouTube Music app, on the website and in Prism), as YouTube groups
     * it: "Today", "Yesterday", "This week"… (Library → History → Your account, #118).
     */
    suspend fun historySections(): List<Pair<String, List<Song>>> {
        val res = browse("FEmusic_history")
        val shelves = res.findAll("musicShelfRenderer")
        if (shelves.isEmpty()) return listOf("" to songsFrom(res)).filter { it.second.isNotEmpty() }
        return shelves.map { s -> (s.obj("title").text() ?: "") to songsFrom(s) }.filter { it.second.isNotEmpty() }
    }

    // ---------------------------------------------------------------- Podcasts

    /** YouTube Music's Podcasts page: popular shows and episodes, then shows by category. */
    suspend fun podcastsHome(): List<Shelf> = Parser.shelves(browse("FEmusic_non_music_audio"))

    /** Shows saved to the account's library. */
    suspend fun libraryPodcasts(): List<com.prism.music.data.model.PodcastItem> =
        libraryItems("FEmusic_library_non_music_audio_list", maxPages = 8).filterIsInstance<com.prism.music.data.model.PodcastItem>()

    /** The account's "Episodes for later" (YouTube's "SE" list), newest saved first. */
    suspend fun savedEpisodes(): List<Song> = runCatching { playlist("SE").songs.filter { it.isEpisode } }.getOrDefault(emptyList())

    /** A show's page: its header and the first episodes (newest first); more come from [podcastEpisodes]. */
    suspend fun podcast(browseId: String): com.prism.music.data.model.PodcastPage {
        val id = if (browseId.startsWith("MPSP")) browseId else "MPSP$browseId"
        val res = browse(id)
        val header = res.findFirst("musicResponsiveHeaderRenderer")
        val title = header.obj("title").text() ?: "Podcast"
        val authorRun = header.obj("straplineTextOne").runs().firstOrNull()
        val thumb = header.obj("thumbnail").bestThumbnail()
        val show = com.prism.music.data.model.PodcastItem(id, title, authorRun.str("text") ?: "", thumb)
        val shelf = res.findFirst("musicShelfRenderer")
        return com.prism.music.data.model.PodcastPage(
            id = id,
            title = title,
            author = authorRun?.let {
                com.prism.music.data.model.ArtistRef(it.str("text") ?: "", it.str("navigationEndpoint", "browseEndpoint", "browseId"))
            },
            description = header.obj("description").findFirst("description").text(),
            thumbnail = thumb,
            episodes = episodesIn(shelf, show),
            continuation = Parser.continuation(shelf),
            savedToLibrary = libraryToggle(header),
        )
    }

    /** One more page of a show's episodes. */
    suspend fun podcastEpisodes(token: String, show: com.prism.music.data.model.PodcastItem): Pair<List<com.prism.music.data.model.Episode>, String?> {
        val res = continueBrowse(token)
        val node = res.obj("continuationContents") ?: res
        return episodesIn(node, show) to (Parser.continuation(node) ?: res.findAll("continuationItemRenderer").firstOrNull()?.findFirst("continuationCommand").str("token"))
    }

    private fun episodesIn(node: JsonElement?, show: com.prism.music.data.model.PodcastItem): List<com.prism.music.data.model.Episode> =
        node.findAll("musicMultiRowListItemRenderer").mapNotNull { Parser.episode(it, show) }.distinctBy { it.song.id }

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

    /** Adds many songs to an owned playlist, 50 to a request; ones already in it are skipped. */
    suspend fun addAllToPlaylist(playlistId: String, videoIds: List<String>) {
        videoIds.chunked(50).forEach { chunk ->
            api.post("browse/edit_playlist", buildJsonObject {
                put("playlistId", playlistId.removePrefix("VL"))
                putJsonArray("actions") {
                    chunk.forEach { id ->
                        addJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", id)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    }
                }
            })
        }
    }

    /** Takes many songs out of an owned playlist, 50 to a request; each is (videoId, setVideoId). */
    suspend fun removeAllFromPlaylist(playlistId: String, items: List<Pair<String, String>>) {
        items.chunked(50).forEach { chunk ->
            api.post("browse/edit_playlist", buildJsonObject {
                put("playlistId", playlistId.removePrefix("VL"))
                putJsonArray("actions") {
                    chunk.forEach { (videoId, setVideoId) ->
                        addJsonObject {
                            put("action", "ACTION_REMOVE_VIDEO")
                            put("removedVideoId", videoId)
                            put("setVideoId", setVideoId)
                        }
                    }
                }
            })
        }
    }

    /** Everything in an owned playlist, video id -> setVideoId (its slot, for taking it out). */
    suspend fun playlistSlots(playlistId: String, limit: Int = 10_000): Map<String, String> {
        val first = playlist(playlistId)
        if (!first.owned) throw java.io.IOException("Not your playlist")
        val slots = LinkedHashMap(first.setVideoIds)
        var token = first.continuation
        while (token != null && slots.size < limit) {
            val chunk = playlistPage(token)
            if (chunk.songs.isEmpty()) break
            slots.putAll(chunk.setVideoIds)
            token = chunk.next
        }
        return slots
    }

    suspend fun createPlaylist(title: String, videoIds: List<String>, description: String = "", privacy: String = "PRIVATE"): String? {
        val res = api.post("playlist/create", buildJsonObject {
            put("title", title)
            put("description", description)
            put("privacyStatus", privacy)
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        })
        return res.str("playlistId")
    }

    /** Renames an owned playlist, rewrites its description and/or changes who can open it ("PRIVATE", "UNLISTED", "PUBLIC"). */
    suspend fun editPlaylist(playlistId: String, title: String? = null, description: String? = null, privacy: String? = null) {
        if (title == null && description == null && privacy == null) return
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                title?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", it) } }
                description?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_DESCRIPTION"); put("playlistDescription", it) } }
                privacy?.let { addJsonObject { put("action", "ACTION_SET_PLAYLIST_PRIVACY"); put("playlistPrivacy", it) } }
            }
        })
    }

    /**
     * Takes one song out of an owned playlist; [setVideoId] says which copy (a playlist can hold a song twice).
     * YouTube answers "Precondition check failed" (#87) when that slot is out of date (the playlist changed since the
     * page was loaded, elsewhere or on another device): then the playlist's current slots are read and it's asked
     * again with the song's slot now, or it's done if the song isn't in the playlist any more.
     */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) {
        try {
            removeSlot(playlistId, videoId, setVideoId)
        } catch (e: InnerTubeException) {
            if (e.code != 400) throw e
            val fresh = runCatching { playlistSlots(playlistId)[videoId] }.getOrElse { throw e } ?: return
            if (fresh == setVideoId) throw e
            removeSlot(playlistId, videoId, fresh)
        }
    }

    private suspend fun removeSlot(playlistId: String, videoId: String, setVideoId: String) {
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("removedVideoId", videoId)
                    put("setVideoId", setVideoId)
                }
            }
        })
    }

    /** Saves an album (by its playlist id) or someone else's playlist to the library, or takes it out. */
    suspend fun setLibrarySaved(playlistId: String, saved: Boolean) {
        api.post(if (saved) "like/like" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("playlistId", playlistId.removePrefix("VL")) }
        })
    }

    /** Subscribes to (or unsubscribes from) an artist's channel. */
    suspend fun subscribe(channelId: String, subscribed: Boolean) {
        api.post(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe", buildJsonObject {
            putJsonArray("channelIds") { add(channelId) }
        })
    }

    /** Deletes a playlist the account owns. */
    suspend fun deletePlaylist(playlistId: String) {
        api.post("playlist/delete", buildJsonObject { put("playlistId", playlistId.removePrefix("VL")) })
    }

    /** Takes someone else's playlist out of the library (it isn't deleted). */
    suspend fun unsavePlaylist(playlistId: String) {
        api.post("like/removelike", buildJsonObject {
            putJsonObject("target") { put("playlistId", playlistId.removePrefix("VL")) }
        })
    }
}
