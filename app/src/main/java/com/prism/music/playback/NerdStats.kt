package com.prism.music.playback

/** Snapshot shown in the "Stats for nerds" overlay. */
data class NerdStats(
    val videoId: String = "",
    val source: String = "—",            // Downloaded / Cached / Streaming
    val itag: Int = 0,
    val container: String = "",
    val codec: String = "",
    val decoder: String = "",
    val streamBitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val contentLength: Long = 0,
    val client: String = "",
    val outputEncoding: String = "",
    val outputSampleRate: Int = 0,
    val offload: Boolean = false,
    val bandwidthKbps: Long = 0,
    val bufferMs: Long = 0,
    val loudnessDb: Double? = null,
    val normalizationGainDb: Float = 0f,
    val audioSessionId: Int = 0,
    val outputDevice: String = "",
    val spatializer: String = "",
    val effects: String = "",
    val videoResolution: String = "",
    val videoCodec: String = "",
    val droppedFrames: Int = 0,
    val underruns: Int = 0,
)
