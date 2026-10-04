package com.prism.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The browse tree Android Auto (and other media browsers) see:
 * Home · Liked songs · Library · Recently played, plus search.
 */
class MediaTree(private val c: AppContainer) {
    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val LIKED = "liked"
        const val LIBRARY = "library"
        const val RECENT = "recent"
        const val DOWNLOADS = "downloads"
        const val SHUFFLE = "shuffle:"
    }

    /** Every song handed out, so a mediaId coming back can be played. */
    private val songs = ConcurrentHashMap<String, Song>()
    /** Songs per browsed folder, so tapping one song plays the whole list from there. */
    private val folders = ConcurrentHashMap<String, Pair<String, List<Song>>>()
    private var homeShelves: List<Shelf> = emptyList()
    private var searchResults: List<Song> = emptyList()

    private fun folder(id: String, title: String, subtitle: String? = null, art: String? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUri(art))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build()
        ).build()

    private fun playable(song: Song): MediaItem {
        songs[song.id] = song
        val item = song.toMediaItem()
        return item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setArtworkUri(artworkUri(song.thumbnail)).setSubtitle(song.artistText).build()
        ).build()
    }

    fun root(): MediaItem = folder(ROOT, "Prism")

    fun recentRoot(): MediaItem = folder(RECENT, "Recently played", type = MediaMetadata.MEDIA_TYPE_PLAYLIST)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(HOME, "Home"),
            folder(LIKED, "Liked songs", art = c.covers.custom("liked"), type = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(LIBRARY, "Library", type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(RECENT, "Recently played", type = MediaMetadata.MEDIA_TYPE_PLAYLIST),
        )
        parentId == HOME -> {
            homeShelves = runCatching { c.ytm.fullHome(2) }.getOrDefault(homeShelves)
            homeShelves.mapIndexed { i, s -> folder("shelf:$i", s.title, s.strapline, s.items.firstOrNull()?.thumbnail) }
        }
        parentId.startsWith("shelf:") -> {
            val shelf = homeShelves.getOrNull(parentId.removePrefix("shelf:").toIntOrNull() ?: -1) ?: return emptyList()
            val shelfSongs = shelf.items.filterIsInstance<SongItem>().map { it.song }
            if (shelfSongs.isNotEmpty()) folders[parentId] = shelf.title to shelfSongs
            shelf.items.mapNotNull { item ->
                when (item) {
                    is SongItem -> playable(item.song)
                    is AlbumItem -> folder("al:${item.id}", item.title, item.subtitle, item.thumbnail, MediaMetadata.MEDIA_TYPE_ALBUM)
                    is PlaylistItem -> folder(if (item.isMix) "mix:${item.id}" else "pl:${item.id}", item.title, item.subtitle, c.covers.art(item), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    is ArtistItem -> folder("ar:${item.id}", item.title, item.subtitle, item.thumbnail, MediaMetadata.MEDIA_TYPE_ARTIST)
                    else -> null
                }
            }
        }
        parentId == LIKED -> songList(parentId, "Liked songs", c.library.liked.value)
        parentId == RECENT -> songList(parentId, "Recently played", c.library.recent.value)
        parentId == DOWNLOADS -> songList(parentId, "Downloads", c.downloads.completedSongs())
        parentId == LIBRARY -> {
            var playlists = c.library.playlists.value
            if (playlists.isEmpty() && c.settings.current.isLoggedIn) playlists = runCatching { c.ytm.libraryPlaylists() }.getOrDefault(emptyList())
            listOf(folder(DOWNLOADS, "Downloads", type = MediaMetadata.MEDIA_TYPE_PLAYLIST)) +
                playlists.filterIsInstance<PlaylistItem>().filter { it.id != "LM" }.map {
                    folder(if (it.isMix) "mix:${it.id}" else "pl:${it.id}", it.title, it.subtitle, c.covers.art(it), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                }
        }
        parentId.startsWith("pl:") -> runCatching { c.ytm.playlistAll(parentId.removePrefix("pl:"), 200) }.getOrNull()
            ?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith("al:") -> runCatching { c.ytm.album(parentId.removePrefix("al:")) }.getOrNull()
            ?.let { songList(parentId, it.title, it.songs) } ?: emptyList()
        parentId.startsWith("mix:") -> runCatching { c.ytm.next(null, parentId.removePrefix("mix:")) }.getOrNull()
            ?.let { r -> c.queue.counterparts.putAll(r.counterparts); songList(parentId, "Mix", r.songs) } ?: emptyList()
        parentId.startsWith("ar:") -> runCatching { c.ytm.artist(parentId.removePrefix("ar:")) }.getOrNull()
            ?.let { a -> songList(parentId, a.name, a.shelves.flatMap { s -> s.items.filterIsInstance<SongItem>().map { it.song } }.distinctBy { it.id }) }
            ?: emptyList()
        else -> emptyList()
    }

    private fun songList(id: String, title: String, list: List<Song>): List<MediaItem> {
        folders[id] = title to list
        // A "Shuffle play" row first, as in the phone app (the car has no shuffle-all button of its own).
        val shuffle = if (list.size > 1) listOf(shuffleItem(id, list)) else emptyList()
        return shuffle + list.map(::playable)
    }

    private fun shuffleItem(folderId: String, list: List<Song>) = MediaItem.Builder().setMediaId("$SHUFFLE$folderId").setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle("Shuffle play")
            .setSubtitle("${list.size} songs")
            .setArtworkUri(Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/${com.prism.music.R.drawable.ic_auto_shuffle}"))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    ).build()

    /** The songs behind a "Shuffle play" row, loading the folder again if it's not to hand. */
    suspend fun shuffleSongs(mediaId: String): Pair<String, List<Song>>? {
        val folderId = mediaId.removePrefix(SHUFFLE)
        folders[folderId]?.let { return it }
        children(folderId)
        return folders[folderId]
    }

    suspend fun search(query: String): Int {
        searchResults = runCatching { c.ytm.search(query, SearchFilter.SONGS).items.filterIsInstance<SongItem>().map { it.song } }.getOrDefault(emptyList())
        searchResults.forEach { songs[it.id] = it }
        return searchResults.size
    }

    fun searchResults(): List<MediaItem> = searchResults.map(::playable)

    suspend fun item(mediaId: String): MediaItem? = song(mediaId)?.let(::playable)

    /** Turns whatever a controller sent back into a playable song. */
    suspend fun song(mediaId: String, fallback: MediaItem? = null): Song? =
        songs[mediaId] ?: fallback?.toSong() ?: c.db.songs().get(mediaId)?.toSong()
            ?: fallback?.mediaMetadata?.title?.let { Song(mediaId, it.toString()) }

    /** The folder a tapped song was listed in, for playing the rest of it after. */
    fun folderOf(mediaId: String): Pair<String, List<Song>>? =
        if (mediaId.startsWith(SHUFFLE)) null else folders.values.firstOrNull { (_, list) -> list.any { it.id == mediaId } }

    suspend fun searchTop(query: String): Song? {
        search(query)
        return searchResults.firstOrNull()
    }

    private fun artworkUri(url: String?): Uri? {
        val u = hiRes(url, 544) ?: return null
        if (u.startsWith("android.resource:")) return Uri.parse(u)
        val encoded = Base64.encodeToString(u.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse("content://${BuildConfig.APPLICATION_ID}.artwork/$encoded")
    }
}

/**
 * Serves cover art to Android Auto, which only loads content:// images for
 * browse lists. Downloads the (Google-hosted) image once and caches it.
 */
class ArtworkProvider : ContentProvider() {
    private val allowedHosts = listOf("googleusercontent.com", "ggpht.com", "ytimg.com")

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val url = runCatching {
            String(Base64.decode(uri.lastPathSegment ?: return null, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrNull() ?: return null
        // A playlist picture the listener chose, kept in the app's own covers folder.
        if (url.startsWith("file:")) {
            val file = File(Uri.parse(url).path ?: return null)
            val covers = File(ctx.filesDir, "covers").canonicalPath
            if (!file.canonicalPath.startsWith(covers + File.separator) || !file.exists()) return null
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val host = Uri.parse(url).host ?: return null
        if (!url.startsWith("https://") || allowedHosts.none { host.endsWith(it) }) return null
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(ctx.cacheDir, "auto_art").apply { mkdirs() }
        val file = File(dir, "$name.jpg")
        if (!file.exists()) {
            val client = (ctx.applicationContext as? com.prism.music.PrismApp)?.container?.http ?: okhttp3.OkHttpClient()
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val tmp = File(dir, "$name.tmp")
                    tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    tmp.renameTo(file)
                }
            }.onFailure { return null }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
