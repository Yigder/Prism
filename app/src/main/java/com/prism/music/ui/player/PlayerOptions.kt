package com.prism.music.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
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
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prism.music.data.model.Song
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PlaylistPicker
import com.prism.music.ui.components.QuickActions
import com.prism.music.ui.components.SheetIconButton
import com.prism.music.ui.components.runAction
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.theme.LocalContainer

/**
 * The player's ⋯ menu, in the app's frosted look (the song's cover as frosted glass): like and
 * share up top, the everyday actions as tiles, then lyrics, player and "go to" groups.
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
    val disliked by c.library.dislikedIds.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    if (sharing) { com.prism.music.ui.components.ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }

    val isLiked = song.id in liked
    val dl = downloads[song.id]
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val sleepMin = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1 else 0
    val sleeping = sleepAt > 0 || sleepEnd
    val inSession = together !is com.prism.music.playback.together.TogetherState.Idle

    val quick = listOfNotNull(
        ActionItem(Icons.Rounded.Radio, "Radio") { c.player.playRadio(song) },
        ActionItem(
            if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Download",
            value = when { downloaded -> "Downloaded"; dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> null },
            active = downloaded, keepOpen = true,
        ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) },
        if (c.settings.current.isLoggedIn) ActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", keepOpen = true) { pickPlaylist = true } else null,
        ActionItem(
            Icons.Rounded.Bedtime, "Sleep",
            value = when { sleepEnd -> "Song end"; sleepAt > 0 -> "${sleepMin} min"; else -> null },
            active = sleeping, onClick = onSleepTimer,
        ),
    )
    val lyrics = listOf(
        ActionItem(Icons.Rounded.FormatQuote, "Lyrics source", onClick = onLyricsSource),
        ActionItem(Icons.Rounded.AvTimer, "Lyrics timing", onClick = onLyricsTiming),
        ActionItem(Icons.Rounded.Refresh, "Download lyrics again") { onRedownloadLyrics(); toast("Downloading lyrics again") },
    )
    val cover = com.prism.music.data.canvas.CanvasRepository.albumKey(song)
    val player = listOfNotNull(
        ActionItem(Icons.Rounded.Groups, "Listen together", value = if (inSession) "On" else null, active = inSession) { onCollapse(); nav.go(Routes.TOGETHER) },
        ActionItem(Icons.Rounded.DataUsage, "Stats for nerds", active = statsShown, onClick = onToggleStats),
        if (hasCanvas) ActionItem(Icons.Rounded.Animation, "Animated cover", active = song.id !in hidden, keepOpen = true) { c.canvas.setHidden(song.id, song.id !in hidden) } else null,
        if (!hasCanvas) null else if (saved.has(song.id, cover)) ActionItem(Icons.Rounded.SaveAlt, "Animated cover saved", active = true, keepOpen = true) { c.canvasStore.unsave(song) }
        else ActionItem(Icons.Rounded.SaveAlt, "Save animated cover", keepOpen = true) {
            c.canvasStore.save(song)
            toast("Saving animated covers for this album")
        },
    )
    val go = listOfNotNull(
        song.album?.id?.let { id -> ActionItem(Icons.Rounded.Album, "Go to album") { onCollapse(); nav.go(Routes.album(id)) } },
        song.artists.firstOrNull { it.id != null }?.let { a -> ActionItem(Icons.Rounded.Person, "Go to ${a.name}") { onCollapse(); nav.go(Routes.artist(a.id!!)) } },
        if (song.id in disliked) ActionItem(Icons.Rounded.ThumbDown, "Not for me", active = true, keepOpen = true) { c.library.setDisliked(song, false) }
        else ActionItem(Icons.Rounded.ThumbDownOffAlt, "Not for me") {
            c.library.setDisliked(song, true)
            toast("Got it — Prism will steer clear of this song")
            if (hasNext) c.player.next()
        },
    )

    ActionSheet(song.thumbnail, onDismiss) { close ->
        ActionHeader(song.thumbnail, song.title, song.artistText) {
            SheetIconButton(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from liked" else "Like", active = isLiked) {
                c.library.toggleLike(song)
            }
            SheetIconButton(Icons.Rounded.Share, "Share") { sharing = true }
        }
        QuickActions(quick) { runAction(it, close) }
        ActionGroup("Lyrics", lyrics) { runAction(it, close) }
        ActionGroup("Player", player) { runAction(it, close) }
        ActionGroup("More", go) { runAction(it, close) }
    }
}
