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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * Stats for nerds as one of the player's panels: the artwork tucks into the header, as it does for
 * lyrics and the queue, and the stats sit on the player's own backdrop below it.
 */
@Composable
fun NerdStatsPanel(modifier: Modifier = Modifier) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Text("Stats for nerds", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        // Nothing has been decoded yet (e.g. a restored queue that hasn't started).
        if (stats.codec.isBlank() && stats.streamBitrateKbps <= 0) {
            Text("Play the song to see its audio details", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // The essentials, big: what the audio is.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Codec", codecName(stats.codec), Modifier.weight(1f))
            StatTile("Bitrate", stats.streamBitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: "—", Modifier.weight(1f))
            StatTile("Sample rate", sampleRateText(stats.sampleRate), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        StatTile("Source", stats.source.ifBlank { "—" }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
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
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MonoStyle, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(92.dp))
                Text(v.ifBlank { "—" }, style = MonoStyle, color = Color.White.copy(alpha = 0.9f))
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
    val song by pc.currentSong.collectAsState()
    val offset by pc.lyricOffset.collectAsState()
    val sync by pc.videoSync.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val scheme = MaterialTheme.colorScheme
    fun fmt(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(kotlin.math.abs(ms) / 1000f)
    fun nudge(d: Long) = pc.setLyricOffset((offset + d).coerceIn(-15_000, 15_000))
    // The same frosted sheet as the player's ⋯ menu it opens from.
    com.prism.music.ui.components.ActionSheet(song?.thumbnail, onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
            Text("Lyrics timing", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(
                if (videoMode) "For this song's music video" else "For this song",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
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
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(com.prism.music.ui.components.quietFill())
                            .clickable { nudge(d) }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                Text("Later", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Earlier", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Slider(offset / 1000f, { pc.setLyricOffset((it * 10).roundToInt() * 100L) }, valueRange = -10f..10f)
            if (offset != 0L) TextButton(onClick = { pc.setLyricOffset(0) }, Modifier.align(Alignment.End)) { Text("Reset") }
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
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                if (sync is VideoSyncState.Synced || sync == VideoSyncState.Unmatched) {
                    TextButton(onClick = { pc.resyncVideo() }) {
                        Text(if (sync == VideoSyncState.Unmatched) "Try again" else "Match again")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val pc = LocalContainer.current.player
    val sleepAt by pc.sleepAt.collectAsState()
    val endOfSong by pc.sleepEndOfSong.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (endOfSong) Text("Stops when this song ends", color = MaterialTheme.colorScheme.primary)
                else if (sleepAt > 0) Text("Stops in ${((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min", color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(endOfSong, { pc.setSleepEndOfSong(true); onDismiss() }, { Text("End of song") })
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(false, { pc.setSleepTimer(m); onDismiss() }, { Text("$m min") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (sleepAt > 0 || endOfSong) ({
            TextButton(onClick = { pc.setSleepTimer(0); pc.setSleepEndOfSong(false); onDismiss() }) { Text("Turn off") }
        }) else null,
    )
}
