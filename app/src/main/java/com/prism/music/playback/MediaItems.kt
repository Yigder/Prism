package com.prism.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes

const val EXTRA_SONG = "prism.song"
const val EXTRA_VIDEO = "prism.video"

/** prism://audio/<id>[?video=<videoId>] – resolved to a real stream URL at load time. */
fun Song.toMediaItem(videoId: String? = null): MediaItem {
    val uri = Uri.Builder().scheme("prism").authority("audio").appendPath(id).apply {
        if (videoId != null) appendQueryParameter("video", videoId)
    }.build()
    val extras = Bundle().apply {
        putString(EXTRA_SONG, InnerTube.json.encodeToString(Song.serializer(), this@toMediaItem))
        if (videoId != null) putString(EXTRA_VIDEO, videoId)
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistText)
                .setAlbumTitle(album?.title)
                .setArtworkUri(hiRes(thumbnail, 544)?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()
        )
        .build()
}

fun MediaItem.toSong(): Song? = mediaMetadata.extras?.getString(EXTRA_SONG)?.let {
    runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
}

val MediaItem.videoModeId: String? get() = mediaMetadata.extras?.getString(EXTRA_VIDEO)
