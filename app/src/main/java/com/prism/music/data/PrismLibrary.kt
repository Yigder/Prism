package com.prism.music.data

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** A playlist kept on the phone, made in Prism without a YouTube Music account. */
@Serializable
data class PrismList(
    val id: String,
    val title: String,
    val description: String = "",
    val songs: List<Song> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun item() = PlaylistItem(id, title, "Prism playlist · ${songCount(songs.size)}", songs.firstOrNull()?.thumbnail)
}

/** An album saved to the library on the phone (the bookmark on an album page, without an account). */
@Serializable
data class SavedAlbum(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val playlistId: String? = null,
    val savedAt: Long,
) {
    fun item() = AlbumItem(id, title, subtitle, thumbnail, playlistId)
}

/**
 * A YouTube playlist saved to the library on the phone, without an account (H7): a bookmark, opened live from YouTube
 * each time, so it follows the playlist's own changes. [id] is the playlist's browse id or plain id, as it was opened.
 */
@Serializable
data class SavedPlaylist(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val savedAt: Long,
) {
    fun item() = PlaylistItem(id, title, subtitle, thumbnail)
}

/** A folder of playlists in Library (1.7.6): Prism's own, YouTube's and saved ones alike, by id. */
@Serializable
data class PlaylistFolder(val id: String, val name: String, val playlists: List<String> = emptyList(), val createdAt: Long)

@Serializable
private data class PrismLibraryFile(
    val lists: List<PrismList> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
)

/**
 * Playlists and saved albums kept on the phone, for listeners who don't sign in (and anyone who wants a list that's only
 * Prism's). One JSON file in `files/library/` (so it's in backups), written whole on every change, through a temporary file.
 * Playlists' ids start with [PREFIX], which is how the playlist page knows one.
 */
class PrismLibrary(private val dir: File) {
    private val file = File(dir, "prism.json")
    private val data = MutableStateFlow(read())
    private val _lists = MutableStateFlow(data.value.lists)
    private val _albums = MutableStateFlow(data.value.albums)
    private val _playlists = MutableStateFlow(data.value.playlists)
    private val _folders = MutableStateFlow(data.value.folders)

    /** Library's playlist folders, in the order they were made. */
    val folders: StateFlow<List<PlaylistFolder>> = _folders

    fun folderOf(playlistId: String): PlaylistFolder? = _folders.value.firstOrNull { f -> f.playlists.any { samePlaylist(it, playlistId) } }

    /** A new folder named [name], with [playlistId] in it if given; its id. */
    fun createFolder(name: String, playlistId: String? = null): String {
        val id = "folder:" + UUID.randomUUID().toString().replace("-", "").take(10)
        change { lib ->
            val out = lib.folders.map { f -> f.copy(playlists = f.playlists.filterNot { p -> playlistId != null && samePlaylist(p, playlistId) }) }
            lib.copy(folders = out + PlaylistFolder(id, name.trim().ifBlank { "Folder" }, listOfNotNull(playlistId), System.currentTimeMillis()))
        }
        return id
    }

    fun renameFolder(id: String, name: String) = change { lib ->
        lib.copy(folders = lib.folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    /** The folder goes; its playlists go back to Library's list. */
    fun deleteFolder(id: String) = change { lib -> lib.copy(folders = lib.folders.filterNot { it.id == id }) }

    /** Puts the playlist in [folderId] (out of any other), or out of every folder when it's null. */
    fun moveToFolder(playlistId: String, folderId: String?) = change { lib ->
        lib.copy(folders = lib.folders.map { f ->
            val without = f.playlists.filterNot { samePlaylist(it, playlistId) }
            if (f.id == folderId) f.copy(playlists = without + playlistId) else f.copy(playlists = without)
        })
    }

    /** Newest change first. */
    val lists: StateFlow<List<PrismList>> = _lists
    /** Newest saved first. */
    val albums: StateFlow<List<SavedAlbum>> = _albums
    /** YouTube playlists saved on the phone, newest saved first. */
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists

    fun list(id: String): PrismList? = _lists.value.firstOrNull { it.id == id }
    fun isSaved(albumId: String) = _albums.value.any { it.id == albumId }
    fun isPlaylistSaved(id: String) = _playlists.value.any { samePlaylist(it.id, id) }

    /** A new playlist named [title] (with [songs] in it); its id. */
    fun create(title: String, songs: List<Song> = emptyList(), description: String = ""): String {
        val now = System.currentTimeMillis()
        val id = PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        change { it.copy(lists = listOf(PrismList(id, title.trim().ifBlank { "My playlist" }, description, songs.distinctBy { s -> s.id }, now, now)) + it.lists) }
        return id
    }

    fun rename(id: String, title: String, description: String) = edit(id) { it.copy(title = title.trim().ifBlank { it.title }, description = description.trim()) }

    fun delete(id: String) = change { it.copy(lists = it.lists.filterNot { l -> l.id == id }) }

    /** Adds the songs not in it yet, at the end; how many were added. */
    fun add(id: String, songs: List<Song>): Int {
        var added = 0
        edit(id) { l ->
            val have = l.songs.map { it.id }.toHashSet()
            val fresh = songs.filter { have.add(it.id) }
            added = fresh.size
            l.copy(songs = l.songs + fresh)
        }
        return added
    }

    fun remove(id: String, songId: String) = edit(id) { l -> l.copy(songs = l.songs.filterNot { it.id == songId }) }

    /** Puts the songs in this order (a reordered copy of the playlist's own songs). */
    fun reorder(id: String, songs: List<Song>) = edit(id) { l ->
        val ids = l.songs.map { it.id }.toSet()
        l.copy(songs = songs.filter { it.id in ids }.distinctBy { it.id })
    }

    /** Makes the playlist exactly [songs] (a copy brought up to date). */
    fun replace(id: String, songs: List<Song>) = edit(id) { l -> l.copy(songs = songs.distinctBy { it.id }) }

    fun saveAlbum(album: SavedAlbum) = change { it.copy(albums = listOf(album) + it.albums.filterNot { a -> a.id == album.id }) }
    fun unsaveAlbum(id: String) = change { it.copy(albums = it.albums.filterNot { a -> a.id == id }) }

    fun savePlaylist(p: SavedPlaylist) = change { it.copy(playlists = listOf(p) + it.playlists.filterNot { s -> samePlaylist(s.id, p.id) }) }
    fun unsavePlaylist(id: String) = change { it.copy(playlists = it.playlists.filterNot { s -> samePlaylist(s.id, id) }) }

    private fun edit(id: String, f: (PrismList) -> PrismList) = change { lib ->
        val l = lib.lists.firstOrNull { it.id == id } ?: return@change lib
        val changed = f(l).copy(updatedAt = System.currentTimeMillis())
        lib.copy(lists = listOf(changed) + lib.lists.filterNot { it.id == id })
    }

    @Synchronized
    private fun change(f: (PrismLibraryFile) -> PrismLibraryFile) {
        data.update(f)
        _lists.value = data.value.lists
        _albums.value = data.value.albums
        _playlists.value = data.value.playlists
        _folders.value = data.value.folders
        write(data.value)
    }

    private fun read(): PrismLibraryFile = runCatching {
        if (!file.isFile) PrismLibraryFile()
        else InnerTube.json.decodeFromString(PrismLibraryFile.serializer(), file.readText())
    }.getOrElse { PrismLibraryFile() }

    private fun write(lib: PrismLibraryFile) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "prism.json.tmp")
            tmp.writeText(InnerTube.json.encodeToString(PrismLibraryFile.serializer(), lib))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        /** Prism playlists' ids start with this. */
        const val PREFIX = "prism:"
        fun isPrismList(id: String) = id.startsWith(PREFIX)

        /** A playlist's browse id ("VLPL…") and its plain id ("PL…") are the same playlist. */
        fun samePlaylist(a: String, b: String) = a.removePrefix("VL") == b.removePrefix("VL")

        /**
         * A YouTube Music mix (made for you, different each time): not something to keep (H7). YouTube Music's own
         * featured playlists ("RDCLAK5uy_…") start the same way but stay as they are, so they can be kept.
         */
        fun isMix(id: String) = id.removePrefix("VL").let { it.startsWith("RD") && !it.startsWith("RDCLAK") }
    }
}
