package com.prism.music.playback

import com.prism.music.data.model.Song

/**
 * The album a playing song came without (#312: songs from Speed dial had no album to go to), from YouTube's queue for
 * it, whose first item is the song itself as YouTube Music's player lists it.
 */
object AlbumFill {
    /** Worth asking for: a song from YouTube Music with no album (videos, episodes, live streams and files have none). */
    fun wanted(song: Song): Boolean =
        song.album?.id == null && !song.isVideo && !song.isEpisode && !song.isLive && !song.isLocal && !song.isUpload

    /** [song] with the album [found] lists for it, or null when it lists none (or not this song). */
    fun withAlbum(song: Song, found: List<Song>): Song? {
        val album = found.firstOrNull { it.id == song.id }?.album?.takeIf { it.id != null && it.title.isNotBlank() } ?: return null
        return song.copy(album = album)
    }
}
