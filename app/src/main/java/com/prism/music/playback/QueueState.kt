package com.prism.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class QueueKind { SINGLE, ALBUM, PLAYLIST, RADIO, LIBRARY, REPLAY, OTHER }

data class QueueSource(
    val kind: QueueKind,
    val title: String,
    /** Seed for autoplay / radio continuation. */
    val seedId: String? = null,
)

/** Shared queue bookkeeping between the UI-facing [PlayerConnection] and [PlaybackService]. */
class QueueState {
    val source = MutableStateFlow(QueueSource(QueueKind.OTHER, ""))
    /** audio track id -> official music video id */
    val counterparts = ConcurrentHashMap<String, String>()
    /** ids with no music video available */
    val noVideo = ConcurrentHashMap.newKeySet<String>()
    @Volatile var autoplayContinuation: String? = null
    @Volatile var autoplayLoading = false
    /** Index in the queue where autoplay suggestions begin (-1 when none). */
    val autoplayStart = MutableStateFlow(-1)
    /** Playing along with a Listen Together host: their queue decides what's next (no autoplay or skipping here). */
    @Volatile var followingHost = false
}
