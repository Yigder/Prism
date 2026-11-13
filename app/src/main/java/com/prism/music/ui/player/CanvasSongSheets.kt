package com.prism.music.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.canvas.CanvasSource
import com.prism.music.data.canvas.ClipRepository
import com.prism.music.data.canvas.PlayerInfo
import com.prism.music.data.canvas.Storyboard
import com.prism.music.data.canvas.pickSource
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.tr
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.spectrumRing
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * 1.8.6 Canvas, for one song (plan-canvas §3.8): the ⋯ menu's "Canvas" switch, "Use:" when the song has both an
 * animated cover and a loop from its music video, and "Pick another part" with stills from the video's preview frames.
 */

/**
 * "Canvas" (the loop from the song's music video) or "Animated cover" for this song, on or off: a switch to a screen
 * reader ("Canvas, On").
 */
internal fun canvasToggleItem(
    on: Boolean,
    label: String = tr(R.string.canvas),
    icon: androidx.compose.ui.graphics.vector.ImageVector = PrismIcons.Video,
    onToggle: () -> Unit,
): ActionItem = ActionItem(icon, label, active = on, keepOpen = true, toggle = on, onClick = onToggle)

/** Which one a song with both shows now: its own "Use:", else the setting "When a song has both". */
internal fun canvasUseNow(settings: AppSettings, row: CanvasClipEntity?): CanvasSource =
    pickSource(hasCover = true, hasClip = true, useVideos = true, videoFirst = settings.behavior.canvas.videoFirst, songChoice = CanvasSource.of(row?.prefer))
        ?: CanvasSource.COVER

internal fun canvasSourceName(s: CanvasSource): String = when (s) {
    CanvasSource.COVER -> tr(R.string.animated_cover)
    CanvasSource.VIDEO -> tr(R.string.music_video_3)
}

/** "Use:" for one song: the animated cover or the music video; the one picked is stored for the song, even if it already shows. */
@Composable
fun CanvasUseSheet(song: Song, now: CanvasSource, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    ActionSheet(com.prism.music.data.PlaylistCovers.artOf(song), onDismiss) { close ->
        val items = CanvasSource.entries.map { s ->
            ActionItem(if (s == CanvasSource.VIDEO) PrismIcons.Video else PrismIcons.AnimatedCover, canvasSourceName(s), active = s == now) {
                c.scope.launch { c.canvasClips.setChoice(song.id, s) }
            }
        }
        ActionGroup(tr(R.string.canvas_use), items) { a -> close(a.onClick) }
    }
}

/**
 * "Pick another part": the part playing first (marked), then the others the finder kept, each as a still from the
 * video's preview frames, or its time ("1:04") when there are none (no network allowed, or none to be had). A tap makes
 * it the song's loop; the song plays on.
 */
@Composable
fun CanvasPartsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val parts by produceState<ClipRepository.Parts?>(null, song.id) { value = c.canvasClips.parts(song.id) }
    val stills by produceState<Map<Long, ImageBitmap>>(emptyMap(), parts) {
        val p = parts ?: return@produceState
        // The same rule the player fetches loops by: on mobile data only with "Canvas from videos", never with Data saver.
        val s = c.settings.current
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        if (!videoNetworkAllowed(metered, s.behavior.canvas.videosOnCellular, s.dataSaver)) return@produceState
        value = loadPartStills(c.http, { id -> c.innerTube.visionOsPlayer(id) }, p.videoId, p.all).mapValues { it.value.asImageBitmap() }
    }
    ActionSheet(com.prism.music.data.PlaylistCovers.artOf(song), onDismiss) { close ->
        Eyebrow(tr(R.string.canvas_pick_part), Modifier.padding(start = 22.dp, bottom = 8.dp))
        val p = parts ?: return@ActionSheet
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).testTag("canvasParts"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            p.all.forEach { start ->
                PartStill(start, stills[start], current = start == p.current) {
                    close { c.scope.launch { c.canvasClips.pickPart(song.id, start) } }
                }
            }
        }
    }
}

/**
 * "Pick another video": the videos found for the song, YouTube's own first, each with its picture, title and who posted
 * it; the one in use is marked. A tap cuts the song's loop from that video (it is the listener's pick from then on).
 */
@Composable
fun CanvasVideosSheet(song: Song, current: String?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val videos by produceState<List<com.prism.music.data.canvas.ClipFinder.VideoChoice>?>(null, song.id) { value = c.canvasClips.videos(song) }
    ActionSheet(com.prism.music.data.PlaylistCovers.artOf(song), onDismiss) { close ->
        Eyebrow(tr(R.string.canvas_pick_video), Modifier.padding(start = 22.dp, bottom = 8.dp))
        val list = videos ?: return@ActionSheet
        if (list.isEmpty()) {
            Text(tr(R.string.canvas_no_videos), Modifier.padding(horizontal = 22.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@ActionSheet
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp).testTag("canvasVideos"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            list.forEach { v ->
                VideoStill(v, current = v.id == current) {
                    close {
                        c.scope.launch {
                            val ok = c.canvasClips.pickVideo(song, v.id, com.prism.music.data.canvas.canvasHeight(c.settings.current))
                            if (!ok) withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, tr(R.string.canvas_video_no_loop), android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoStill(video: com.prism.music.data.canvas.ClipFinder.VideoChoice, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = com.prism.music.ui.theme.LocalUi.current.tile
    Column(
        Modifier.clip(shape).selectable(current, role = Role.Button, onClick = onClick).padding(4.dp).testTag("canvasVideo:${video.id}"),
    ) {
        Box(
            Modifier.size(160.dp, 90.dp).clip(shape).background(scheme.onSurface.copy(alpha = 0.08f))
                .then(if (current) Modifier.spectrumRing(shape, 2.dp) else Modifier),
        ) {
            coil3.compose.AsyncImage("https://i.ytimg.com/vi/${video.id}/mqdefault.jpg", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            video.title, Modifier.width(160.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = if (current) scheme.onSurface else scheme.onSurfaceVariant,
        )
        Text(
            video.by, Modifier.width(160.dp).padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PartStill(startMs: Long, still: ImageBitmap?, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = com.prism.music.ui.theme.LocalUi.current.tile
    val time = partTime(startMs)
    Column(
        Modifier.clip(shape).selectable(current, role = Role.Button, onClick = onClick).padding(4.dp).testTag("canvasPart:$startMs"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(128.dp, 72.dp).clip(shape).background(scheme.onSurface.copy(alpha = 0.08f))
                .then(if (current) Modifier.spectrumRing(shape, 2.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (still != null) Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(time, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(time, style = MaterialTheme.typography.labelMedium, color = if (current) scheme.onSurface else scheme.onSurfaceVariant)
    }
}

/** A part's start as the listener reads a time: "1:04". */
internal fun partTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Whether loops from music videos (and their stills) may use the network now: the player's own rule. */
internal fun videoNetworkAllowed(metered: Boolean, videosOnCellular: Boolean, dataSaver: Boolean): Boolean =
    !metered || (videosOnCellular && !dataSaver)

/**
 * A still for each of [starts] (ms in the video): the preview frame nearest it, cut from the storyboard's sheets (each
 * sheet fetched once). Whatever can't be had is left out; nothing is thrown but cancellation.
 */
internal suspend fun loadPartStills(
    http: OkHttpClient, player: (String) -> JsonObject, videoId: String, starts: List<Long>,
): Map<Long, Bitmap> = withContext(Dispatchers.IO) {
    try {
        val info = PlayerInfo.parse(player(videoId))
        val board = Storyboard.parse(info.storyboardSpec, info.lengthMs) ?: return@withContext emptyMap()
        val spots = starts.associateWith { board.frameAt(it) }
        val out = LinkedHashMap<Long, Bitmap>()
        for (n in spots.values.map { it.sheet }.distinct()) {
            val bytes = http.newCall(Request.Builder().url(board.sheetUrl(n)).build()).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
                ?: continue
            val sheet = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            for ((ms, s) in spots) {
                if (s.sheet != n || s.x + board.frameW > sheet.width || s.y + board.frameH > sheet.height) continue
                out[ms] = Bitmap.createBitmap(sheet, s.x, s.y, board.frameW, board.frameH)
            }
        }
        out
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }
}
