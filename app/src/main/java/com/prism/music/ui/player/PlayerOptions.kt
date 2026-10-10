package com.prism.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AvTimer
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.PlaylistPicker
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.theme.LocalContainer

/** One tile in [PlayerOptionsSheet]. [active] lights it up (a mode that's on). */
private class Option(val icon: ImageVector, val label: String, val active: Boolean = false, val keepOpen: Boolean = false, val onClick: () -> Unit)

/**
 * The player's ⋯ menu: everything on one screen as small tiles in labelled rows (song, lyrics,
 * player, go to) rather than one long list to scroll through.
 */
@Composable
fun PlayerOptionsSheet(
    song: Song,
    hasCanvas: Boolean,
    statsShown: Boolean,
    hasNext: Boolean,
    onLyricsSource: () -> Unit,
    onLyricsTiming: () -> Unit,
    onRedownloadLyrics: () -> Unit,
    onToggleStats: () -> Unit,
    onSleepTimer: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    val hidden by c.canvas.hidden.collectAsState()
    val saved by c.canvas.saved.collectAsState()
    val sleepAt by c.player.sleepAt.collectAsState()
    val sleepEnd by c.player.sleepEndOfSong.collectAsState()
    val together by c.together.state.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    if (sharing) { com.prism.music.ui.components.ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }

    val isLiked = song.id in liked
    val dl = downloads[song.id]
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    val songRow = listOf(
        Option(Icons.Rounded.PlaylistPlay, "Play next") { c.player.playNext(song); toast("Playing next") },
        Option(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { c.player.addToQueue(song); toast("Added to queue") },
        Option(Icons.Rounded.Radio, "Start radio") { c.player.playRadio(song) },
        Option(
            if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
            when { downloaded -> "Downloaded"; dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> "Download" },
            active = downloaded, keepOpen = true,
        ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) },
    )
    val lyricsRow = listOf(
        Option(Icons.Rounded.FormatQuote, "Source", onClick = onLyricsSource),
        Option(Icons.Rounded.AvTimer, "Timing", onClick = onLyricsTiming),
        Option(Icons.Rounded.Refresh, "Re-download") { onRedownloadLyrics(); toast("Downloading lyrics again") },
    )
    val sleepMin = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1 else 0
    val inSession = together !is com.prism.music.playback.together.TogetherState.Idle
    val playerRow = listOfNotNull(
        Option(
            Icons.Rounded.Bedtime, when { sleepEnd -> "Sleep · song end"; sleepAt > 0 -> "Sleep · ${sleepMin}m"; else -> "Sleep timer" },
            active = sleepAt > 0 || sleepEnd, onClick = onSleepTimer,
        ),
        Option(Icons.Rounded.Groups, if (inSession) "Together · on" else "Together", active = inSession) { onCollapse(); nav.go(Routes.TOGETHER) },
        Option(Icons.Rounded.DataUsage, "Stats", active = statsShown, onClick = onToggleStats),
        if (!hasCanvas) null else if (song.id in hidden) Option(Icons.Rounded.Photo, "Still cover", active = true, keepOpen = true) { c.canvas.setHidden(song.id, false) }
        else Option(Icons.Rounded.Animation, "Animated", keepOpen = true) { c.canvas.setHidden(song.id, true) },
        if (!hasCanvas) null else if (saved.has(song.id, com.prism.music.data.canvas.CanvasRepository.albumKey(song))) Option(Icons.Rounded.SaveAlt, "Cover saved", active = true, keepOpen = true) { c.canvasStore.unsave(song) }
        else Option(Icons.Rounded.SaveAlt, "Save cover", keepOpen = true) {
            c.canvasStore.save(song)
            toast("Saving animated covers for this album")
        },
    )
    val goRow = listOfNotNull(
        if (c.settings.current.isLoggedIn) Option(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", keepOpen = true) { pickPlaylist = true } else null,
        song.album?.id?.let { id -> Option(Icons.Rounded.Album, "Album") { onCollapse(); nav.go(Routes.album(id)) } },
        song.artists.firstOrNull { it.id != null }?.let { a -> Option(Icons.Rounded.Person, "Artist") { onCollapse(); nav.go(Routes.artist(a.id!!)) } },
        if (c.library.isDisliked(song.id)) Option(Icons.Rounded.ThumbDown, "Disliked", active = true) { c.library.setDisliked(song, false) }
        else Option(Icons.Rounded.ThumbDownOffAlt, "Not for me") {
            c.library.setDisliked(song, true)
            toast("Got it — Prism will steer clear of this song")
            if (hasNext) c.player.next()
        },
    )

    PrismSheet(onDismiss, containerColor = Color(0xFF1C1C1E), contentColor = Color.White) { close ->
        fun pick(o: Option) { if (o.keepOpen) o.onClick() else close(o.onClick) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            // The song, with like and share at hand.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                Artwork(song.thumbnail, Modifier.size(46.dp), RoundedCornerShape(8.dp), size = 226)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artistText, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundButton(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from liked" else "Like") { c.library.toggleLike(song) }
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.Share, "Share") { sharing = true }
            }
            OptionRow(null, songRow, ::pick)
            OptionRow("Lyrics", lyricsRow, ::pick)
            OptionRow("Player", playerRow, ::pick)
            OptionRow("More", goRow, ::pick)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/** A labelled row of up to four tiles; shorter rows keep the same tile width. */
@Composable
private fun OptionRow(title: String?, options: List<Option>, onClick: (Option) -> Unit) {
    if (options.isEmpty()) return
    if (title != null) Text(
        title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
    ) else Spacer(Modifier.height(8.dp))
    options.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { o -> OptionTile(o, Modifier.weight(1f)) { onClick(o) } }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun OptionTile(o: Option, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(66.dp)
            .clip(com.prism.music.ui.theme.LocalUi.current.shape(14.dp))
            .background(if (o.active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (o.active) Color.Black else Color.White
        Icon(o.icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            o.label, style = MaterialTheme.typography.labelMedium, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
