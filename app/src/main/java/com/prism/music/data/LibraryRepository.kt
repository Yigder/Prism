package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SyncState { IDLE, SYNCING, DONE, ERROR }

/** Local mirror of the signed-in user's YouTube Music library, with two-way like sync. */
class LibraryRepository(private val c: AppContainer) {
    private val songs = c.db.songs()

    val likedIds: StateFlow<Set<String>> = songs.likedIdsFlow().map { it.toSet() }
        .stateIn(c.scope, SharingStarted.Eagerly, emptySet())

    val liked = songs.likedFlow().map { list -> list.map { it.toSong() } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    val recent = songs.recentFlow(40).map { list -> list.map { it.toSong() } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    val playlists = MutableStateFlow<List<BrowseItem>>(emptyList())
    val albums = MutableStateFlow<List<BrowseItem>>(emptyList())
    val artists = MutableStateFlow<List<BrowseItem>>(emptyList())
    val syncState = MutableStateFlow(SyncState.IDLE)
    val lastError = MutableStateFlow<String?>(null)

    private val dislikePrefs = c.app.getSharedPreferences("dislikes", android.content.Context.MODE_PRIVATE)
    /** Songs to keep out of autoplay, radio and smart downloads. */
    val dislikedIds = MutableStateFlow(dislikePrefs.getStringSet("ids", emptySet())!!.toSet())

    fun isDisliked(id: String) = id in dislikedIds.value

    fun setDisliked(song: Song, disliked: Boolean) {
        val next = if (disliked) dislikedIds.value + song.id else dislikedIds.value - song.id
        dislikedIds.value = next
        dislikePrefs.edit().putStringSet("ids", next).apply()
        if (disliked && isLiked(song.id)) setLiked(song, false)
        c.scope.launch(Dispatchers.IO) {
            if (disliked && c.downloads.downloads.value.containsKey(song.id) && songs.get(song.id)?.smartDownload == true) {
                c.downloads.remove(song.id)
            }
            // Tells YouTube Music too, so its recommendations learn from it.
            if (c.settings.current.isLoggedIn) runCatching { c.ytm.dislike(song.id, disliked) }
        }
    }

    fun isLiked(id: String) = id in likedIds.value

    fun toggleLike(song: Song) = setLiked(song, !isLiked(song.id))

    fun setLiked(song: Song, liked: Boolean) {
        c.scope.launch(Dispatchers.IO) {
            val existing = songs.get(song.id)
            songs.upsert(song.toEntity(existing).copy(liked = liked, likedAt = if (liked) System.currentTimeMillis() else 0))
            if (liked && c.settings.current.downloadLikes) c.downloads.download(song)
            if (c.settings.current.isLoggedIn) {
                runCatching { c.ytm.like(song.id, liked) }.onFailure { lastError.value = "Couldn't sync like: ${it.message}" }
            }
        }
    }

    suspend fun recordPlayed(song: Song) {
        val existing = songs.get(song.id)
        songs.upsert(song.toEntity(existing).copy(lastPlayed = System.currentTimeMillis()))
    }

    /** Pulls liked songs, playlists, albums and artists from the account. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!c.settings.current.isLoggedIn || syncState.value == SyncState.SYNCING) return@withContext
        syncState.value = SyncState.SYNCING
        try {
            val remote = c.ytm.likedSongs()
            val before = likedIds.value
            val now = System.currentTimeMillis()
            // Chunked: SQLite caps bound parameters at 999 on older Android versions.
            val existing = remote.map { it.id }.chunked(500).flatMap { songs.getAll(it) }.associateBy { it.id }
            // Keep YouTube's order: newest like first.
            songs.upsertAll(remote.mapIndexed { i, s ->
                val e = existing[s.id]
                s.toEntity(e).copy(liked = true, likedAt = if (e?.liked == true && e.likedAt > 0) e.likedAt else now - i)
            })
            if (remote.isNotEmpty()) {
                val remoteIds = remote.map { it.id }.toSet()
                (before - remoteIds).forEach { songs.setLiked(it, false, 0) }
            }
            if (c.settings.current.downloadLikes && before.isNotEmpty()) {
                remote.filter { it.id !in before }.forEach { c.downloads.download(it) }
            }
            runCatching { playlists.value = c.ytm.libraryPlaylists() }
            runCatching { albums.value = c.ytm.libraryAlbums() }
            runCatching { artists.value = c.ytm.libraryArtists() }
            runCatching {
                c.ytm.accountInfo()?.let { c.settings.saveAccountInfo(it.name, it.email, it.avatar) }
            }
            syncState.value = SyncState.DONE
            lastError.value = null
        } catch (e: Exception) {
            lastError.value = e.message
            syncState.value = SyncState.ERROR
        }
    }

    private val thumbPrefs = c.app.getSharedPreferences("artist_thumbs", android.content.Context.MODE_PRIVATE)
    /** Artist photos for artists that only appear as names on songs, fetched once and kept. */
    val artistThumbs = MutableStateFlow<Map<String, String>>(thumbPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    private val thumbsLoading = java.util.Collections.synchronizedSet(HashSet<String>())

    fun loadArtistThumbs(ids: List<String>) {
        val todo = ids.filter { it !in artistThumbs.value && thumbsLoading.add(it) }
        if (todo.isEmpty()) return
        c.scope.launch(Dispatchers.IO) {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                todo.forEach { id ->
                    launch {
                        gate.acquire()
                        try {
                            val thumb = runCatching { c.ytm.artist(id).thumbnail }.getOrNull()
                            if (thumb != null) {
                                thumbPrefs.edit().putString(id, thumb).apply()
                                artistThumbs.value = artistThumbs.value + (id to thumb)
                            }
                        } finally { gate.release() }
                    }
                }
            }
        }
    }

    private fun nameKey(name: String) = "name:" + name.lowercase().trim()

    /** An artist's photo, by id, or by name for songs saved without the artist's id. */
    fun artistPhoto(thumbs: Map<String, String>, id: String?, name: String): String? = id?.let { thumbs[it] } ?: thumbs[nameKey(name)]

    /**
     * Fetches photos for artists from play history (Replay): by id when the song carried one,
     * otherwise by searching for the name. Waits until they're in.
     */
    suspend fun fetchArtistPhotos(artists: List<Pair<String?, String>>) = withContext(Dispatchers.IO) {
        val todo = artists.distinct().filter { (id, name) ->
            artistPhoto(artistThumbs.value, id, name) == null && thumbsLoading.add(id ?: nameKey(name))
        }
        if (todo.isEmpty()) return@withContext
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            todo.forEach { (id, name) ->
                launch {
                    val key = id ?: nameKey(name)
                    gate.acquire()
                    try {
                        val thumb = if (id != null) runCatching { c.ytm.artist(id).thumbnail }.getOrNull() else photoByName(name)
                        if (thumb != null) {
                            thumbPrefs.edit().putString(key, thumb).apply()
                            artistThumbs.value = artistThumbs.value + (key to thumb)
                        }
                    } finally {
                        gate.release()
                        // A miss can be tried again next time.
                        thumbsLoading.remove(key)
                    }
                }
            }
        }
    }

    /** "Drake, 21 Savage" is looked up as written, then as its first artist; only an exact name match counts. */
    private suspend fun photoByName(name: String): String? {
        val first = name.split(", ", " & ").first().trim()
        for (n in listOf(name, first).distinct()) {
            val items = runCatching { c.ytm.search(n, com.prism.music.data.innertube.SearchFilter.ARTISTS).items }.getOrNull() ?: continue
            val want = com.prism.music.data.innertube.SearchRank.norm(n)
            items.filterIsInstance<ArtistItem>().firstOrNull { com.prism.music.data.innertube.SearchRank.norm(it.title) == want }
                ?.thumbnail?.let { return it }
        }
        return null
    }

    /**
     * Deletes one of your playlists from YouTube Music; one you saved from someone else is just
     * taken out of the library. Gone from the list straight away, put back if it fails.
     */
    suspend fun deletePlaylist(item: BrowseItem): Result<Unit> = withContext(Dispatchers.IO) {
        val before = playlists.value
        playlists.value = before.filterNot { it.id == item.id }
        runCatching { c.ytm.deletePlaylist(item.id) }
            .recoverCatching { c.ytm.unsavePlaylist(item.id) }
            .onSuccess {
                val pins = c.settings.current.homePlaylists
                if (pins.any { it.id == item.id }) c.settings.setHomePlaylists(pins.filterNot { it.id == item.id })
            }
            .onFailure { playlists.value = before }
    }

    fun syncInBackground() {
        c.scope.launch { sync() }
    }

    /**
     * Stars an artist (or takes the star off). Kept on the phone either way; signed in, it also
     * subscribes to them on YouTube Music ([channelId]), which is what YouTube's own app does.
     */
    fun setFavoriteArtist(artist: FavoriteArtist, channelId: String?, on: Boolean) {
        c.artistPrefs.setFavorite(artist, on)
        if (on && artists.value.none { it.id == artist.id }) {
            artists.value = listOf(ArtistItem(artist.id, artist.name, "Artist", artist.thumbnail)) + artists.value
        }
        if (c.settings.current.isLoggedIn && channelId != null) c.scope.launch(Dispatchers.IO) {
            runCatching { c.ytm.subscribe(channelId, on) }.onFailure { lastError.value = "Couldn't update ${artist.name} on YouTube Music: ${it.message}" }
        }
    }

    /** Saves an album or playlist to the account's library, or takes it out. False if YouTube Music refused. */
    suspend fun setSaved(playlistId: String, saved: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { c.ytm.setLibrarySaved(playlistId, saved) }.isSuccess.also { ok -> if (ok) refreshCollections() }
    }

    /** Fetches the library's playlists and albums again (after one is saved, created or changed). */
    fun refreshCollections() {
        if (!c.settings.current.isLoggedIn) return
        c.scope.launch(Dispatchers.IO) {
            runCatching { playlists.value = c.ytm.libraryPlaylists() }
            runCatching { albums.value = c.ytm.libraryAlbums() }
        }
    }

    companion object {
        /** Albums your songs come from, most songs first. Fills the Albums tab beyond the ones saved on YouTube Music. */
        fun albumsFrom(songs: List<Song>): List<AlbumItem> = songs
            .filter { it.album?.id != null && !it.isVideo }
            .groupBy { it.album!!.id!! }
            .map { (id, list) ->
                val first = list.first()
                AlbumItem(id, first.album!!.title, first.primaryArtist + " • " + (if (list.size == 1) "1 song" else "${list.size} songs"), first.thumbnail) to list.size
            }
            .sortedByDescending { it.second }
            .map { it.first }

        /** Artists of your songs, most songs first. */
        fun artistsFrom(songs: List<Song>): List<ArtistItem> = songs
            .flatMap { s -> s.artists.filter { it.id != null }.map { it to s } }
            .groupBy { it.first.id!! }
            .map { (id, pairs) ->
                ArtistItem(id, pairs.first().first.name, if (pairs.size == 1) "1 song" else "${pairs.size} songs", null) to pairs.size
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
