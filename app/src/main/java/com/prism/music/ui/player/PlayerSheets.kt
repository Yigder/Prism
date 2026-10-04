package com.prism.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.prism.music.playback.VideoSyncState
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.MonoStyle
import kotlin.math.roundToInt

/** Plain-language codec name from a codec / mime string ("opus" → "Opus", "mp4a.40.2" → "AAC"). */
fun codecName(codec: String): String = when {
    codec.contains("opus", true) -> "Opus"
    codec.contains("mp4a", true) || codec.contains("aac", true) -> "AAC"
    codec.contains("vorbis", true) -> "Vorbis"
    codec.contains("flac", true) -> "FLAC"
    codec.contains("mp3", true) || codec.contains("mpeg", true) -> "MP3"
    codec.isBlank() -> "—"
    else -> codec.substringBefore('.').uppercase()
}

fun sampleRateText(hz: Int): String = if (hz <= 0) "—" else if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)

@Composable
fun NerdStatsOverlay(onClose: () -> Unit) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    var details by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stats for nerds", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        }
        // Nothing has been decoded yet (e.g. a restored queue that hasn't started).
        if (stats.codec.isBlank() && stats.streamBitrateKbps <= 0) {
            Text("Play the song to see its audio details", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            return@Column
        }
        // The essentials, big: what the audio is.
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("Codec", codecName(stats.codec))
            StatTile("Bitrate", stats.streamBitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: "—")
            StatTile("Sample rate", sampleRateText(stats.sampleRate))
            StatTile("Source", stats.source.ifBlank { "—" })
        }
        Text(
            if (details) "Hide details" else "More details",
            color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { details = !details }.padding(vertical = 4.dp, horizontal = 2.dp),
        )
        if (!details) return@Column
        val rows = listOf(
            "Video ID" to stats.videoId,
            "Source" to stats.source,
            "Stream" to listOfNotNull(stats.itag.takeIf { it > 0 }?.let { "itag $it" }, stats.container.takeIf { it.isNotBlank() }, stats.client.takeIf { it.isNotBlank() }?.let { "client $it" }).joinToString(" · "),
            "Codec" to listOfNotNull(stats.codec.takeIf { it.isNotBlank() }, stats.decoder.takeIf { it.isNotBlank() }).joinToString(" · "),
            "Bitrate" to "${stats.streamBitrateKbps} kbps" + if (stats.contentLength > 0) " · %.2f MB".format(stats.contentLength / 1_048_576f) else "",
            "Format" to "${stats.sampleRate} Hz · ${stats.channels} ch",
            "Output" to "${stats.outputEncoding} @ ${stats.outputSampleRate} Hz" + if (stats.offload) " · offload" else "",
            "Device" to stats.outputDevice,
            "Spatializer" to stats.spatializer,
            "Loudness" to (stats.loudnessDb?.let { "%.1f dB · gain %.1f dB".format(it, stats.normalizationGainDb) } ?: "—"),
            "Effects" to stats.effects,
            "Buffer" to "%.1f s · %d kbps est.".format(stats.bufferMs / 1000f, stats.bandwidthKbps),
            "Session" to "${stats.audioSessionId} · underruns ${stats.underruns}",
        ) + if (stats.videoResolution.isNotBlank()) listOf("Video" to "${stats.videoResolution} · ${stats.videoCodec} · dropped ${stats.droppedFrames}") else emptyList()
        rows.forEach { (k, v) ->
            Row {
                Text(k, style = MonoStyle, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.width(92.dp))
                Text(v.ifBlank { "—" }, style = MonoStyle, color = Color.White)
            }
        }
    }
}

/**
 * Per-song lyric timing: nudge earlier / later, plus how the lyrics were
 * matched to the music video when one is playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsTimingSheet(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val offset by pc.lyricOffset.collectAsState()
    val sync by pc.videoSync.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    fun fmt(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(kotlin.math.abs(ms) / 1000f)
    fun nudge(d: Long) = pc.setLyricOffset((offset + d).coerceIn(-15_000, 15_000))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1E), contentColor = Color.White) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("Lyrics timing", style = MaterialTheme.typography.titleLarge)
            Text(
                if (videoMode) "For this song's music video" else "For this song",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (offset == 0L) "In sync" else "${fmt(offset)} · lyrics ${if (offset > 0) "earlier" else "later"}",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(-500L to "−0.5", -100L to "−0.1", 100L to "+0.1", 500L to "+0.5").forEach { (d, label) ->
                    Text(
                        label, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                            .clickable { nudge(d) }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                Text("Later", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.weight(1f))
                Text("Earlier", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f))
            }
            Slider(
                offset / 1000f, { pc.setLyricOffset((it * 10).roundToInt() * 100L) }, valueRange = -10f..10f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            )
            if (offset != 0L) TextButton(onClick = { pc.setLyricOffset(0) }, Modifier.align(Alignment.End)) { Text("Reset", color = Color.White) }
            if (videoMode) {
                Spacer(Modifier.height(10.dp))
                Text("Music video", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (val s = sync) {
                        VideoSyncState.Measuring -> "Matching the lyrics to the video's audio…"
                        is VideoSyncState.Synced -> when {
                            kotlin.math.abs(s.lagMs) < 150 -> "Matched automatically — the video lines up with the song."
                            s.lagMs > 0 -> "Matched automatically — the song starts ${fmt(s.lagMs).removePrefix("+")} into the video, so the lyrics wait for it."
                            else -> "Matched automatically — the video skips the song's first ${fmt(-s.lagMs).removePrefix("−")}."
                        }
                        VideoSyncState.Unmatched -> "Couldn't match this video to the song automatically. Use the controls above to line the lyrics up."
                        VideoSyncState.Off -> "The video uses the song's own timing."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f),
                )
                if (sync is VideoSyncState.Synced || sync == VideoSyncState.Unmatched) {
                    TextButton(onClick = { pc.resyncVideo() }) {
                        Text(if (sync == VideoSyncState.Unmatched) "Try again" else "Match again", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val sleepAt by pc.sleepAt.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (sleepAt > 0) Text("Stops in ${((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min", color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(false, { pc.setSleepTimer(m); onDismiss() }, { Text("$m min") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (sleepAt > 0) ({ TextButton(onClick = { pc.setSleepTimer(0); onDismiss() }) { Text("Turn off") } }) else null,
    )
}
