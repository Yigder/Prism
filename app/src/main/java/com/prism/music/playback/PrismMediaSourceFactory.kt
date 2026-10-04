package com.prism.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Builds audio-only sources normally, and an audio+video merged source when a
 * queue item is switched to its music video. The audio source is always first
 * so the timeline keeps the song's metadata.
 */
@OptIn(UnstableApi::class)
class PrismMediaSourceFactory(
    audioDsf: DataSource.Factory,
    videoDsf: DataSource.Factory,
    localDsf: DataSource.Factory? = null,
    /** A lossless file on the phone to play instead of the stream (the lossless add-on). */
    private val localAudio: ((MediaItem) -> Uri?)? = null,
) : MediaSource.Factory {
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    private val audio = ProgressiveMediaSource.Factory(audioDsf, extractors)
    private val video = ProgressiveMediaSource.Factory(videoDsf, extractors)
    private val local = localDsf?.let { ProgressiveMediaSource.Factory(it, extractors) }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        audio.setDrmSessionManagerProvider(drmSessionManagerProvider)
        local?.setDrmSessionManagerProvider(drmSessionManagerProvider)
        video.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        audio.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        local?.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        video.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val videoId = mediaItem.localConfiguration?.uri?.getQueryParameter("video")
        if (videoId == null) {
            if (local != null) localAudio?.invoke(mediaItem)?.let { uri ->
                return local.createMediaSource(mediaItem.buildUpon().setUri(uri).setCustomCacheKey(null).build())
            }
            val item = if (mediaItem.localConfiguration == null) {
                mediaItem.buildUpon().setUri("prism://audio/${mediaItem.mediaId}").setCustomCacheKey(mediaItem.mediaId).build()
            } else mediaItem
            return audio.createMediaSource(item)
        }
        // A distinct URI marks the audio leg so toggling back rebuilds the source.
        val audioItem = mediaItem.buildUpon()
            .setUri(Uri.parse("prism://audio/$videoId?leg=video"))
            .setCustomCacheKey(videoId)
            .build()
        val videoItem = MediaItem.Builder().setMediaId("video:$videoId").setUri("prism://video/$videoId").build()
        return MergingMediaSource(true, false, audio.createMediaSource(audioItem), video.createMediaSource(videoItem))
    }
}
