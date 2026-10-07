package com.prism.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something a long press can play: an album, playlist, artist or song, or one of Prism's own lists. */
sealed interface Playable {
    data class Of(val item: BrowseItem) : Playable
    data object Liked : Playable
    data object Downloads : Playable
}

/** Moods and genres are pages of shelves, not something to play. */
fun BrowseItem.playable(): Playable? = if (this is MoodItem) null else Playable.Of(this)

/**
 * A long-press menu for cards and tiles: call the returned function with what was pressed and the
 * sheet opens (songs get the usual song actions).
 */
@Composable
fun rememberPlayMenu(): (Playable) -> Unit {
    var target by remember { mutableStateOf<Playable?>(null) }
    target?.let { PlayActionsSheet(it) { target = null } }
    return remember { { target = it } }
}

/** Play or shuffle a whole album, playlist or list; for an artist, shuffle their songs or start their mix. [extra] adds rows (e.g. delete). */
@Composable
fun PlayActionsSheet(target: Playable, extra: List<SheetItem> = emptyList(), onDismiss: () -> Unit) {
    val item = (target as? Playable.Of)?.item
    if (item is SongItem) { SongActionsSheet(item.song, onDismiss); return }
    val c = LocalContainer.current
    val context = LocalContext.current
    val liked by c.library.liked.collectAsState()
    val ui = com.prism.music.ui.theme.LocalUi.current
    val (title, subtitle) = when (target) {
        Playable.Liked -> "Liked songs" to "${liked.size} songs"
        Playable.Downloads -> "Downloads" to "${c.downloads.completedSongs().size} songs"
        is Playable.Of -> target.item.title to target.item.subtitle.ifBlank {
            when (target.item) { is AlbumItem -> "Album"; is ArtistItem -> "Artist"; else -> "Playlist" }
        }
    }
    val art = when (target) {
        Playable.Liked -> c.covers.custom("liked")
        Playable.Downloads -> c.covers.custom("downloads")
        is Playable.Of -> c.covers.art(target.item)
    }
    fun play(shuffle: Boolean, mix: Boolean = false) = c.scope.launch(Dispatchers.Main) {
        val loaded = runCatching { withContext(Dispatchers.IO) { songsFor(c, target, shuffle, mix) } }.getOrNull()
        if (loaded == null || loaded.first.isEmpty()) {
            Toast.makeText(context, if (loaded == null) "Couldn't load \"$title\"" else "Nothing to play in \"$title\"", Toast.LENGTH_SHORT).show()
            return@launch
        }
        // An artist's shuffle and mix come already in YouTube's order; the player shouldn't reshuffle them.
        c.player.playQueue(loaded.first, 0, loaded.second, shuffle = shuffle && item !is ArtistItem)
    }

    PrismSheet(onDismiss = onDismiss) { close ->
        Column {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(
                    art, Modifier.size(60.dp), if (item is ArtistItem) CircleShape else ui.art, size = 226,
                    placeholderIcon = when {
                        target == Playable.Liked -> Icons.Rounded.Favorite
                        target == Playable.Downloads -> Icons.Rounded.DownloadDone
                        item is ArtistItem -> Icons.Rounded.Person
                        item is AlbumItem -> Icons.Rounded.Album
                        else -> Icons.AutoMirrored.Rounded.QueueMusic
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (item is ArtistItem) {
                SheetAction(Icons.Rounded.Shuffle, "Shuffle songs") { close { play(shuffle = true) } }
                SheetAction(Icons.Rounded.Radio, "Start mix") { close { play(shuffle = false, mix = true) } }
            } else {
                SheetAction(Icons.Rounded.PlayArrow, "Play") { close { play(shuffle = false) } }
                SheetAction(Icons.Rounded.Shuffle, "Shuffle") { close { play(shuffle = true) } }
            }
            extra.forEach { e -> SheetAction(e.icon, e.label) { close(e.onClick) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The songs to queue for [target], and what the queue is called. */
private suspend fun songsFor(c: AppContainer, target: Playable, shuffle: Boolean, mix: Boolean): Pair<List<Song>, QueueSource> = when (target) {
    Playable.Liked -> c.library.liked.value to QueueSource(QueueKind.PLAYLIST, "Liked songs")
    Playable.Downloads -> c.downloads.completedSongs() to QueueSource(QueueKind.PLAYLIST, "Downloads")
    is Playable.Of -> when (val item = target.item) {
        is AlbumItem -> c.ytm.album(item.id).songs to QueueSource(QueueKind.ALBUM, item.title)
        is PlaylistItem ->
            // Mixes and radios only exist as a watch queue.
            if (item.isMix || item.id.startsWith("RD")) radio(c, null, item.id, null, item.title)
            else c.ytm.playlistAll(item.id, 1000).songs to QueueSource(QueueKind.PLAYLIST, item.title)
        is ArtistItem -> {
            val a = c.ytm.artist(item.id)
            if (shuffle && !mix && a.shufflePlaylistId != null) radio(c, a.shuffleVideoId, a.shufflePlaylistId, a.shuffleParams, a.name)
            else radio(c, a.radioVideoId, a.radioPlaylistId ?: throw java.io.IOException("No mix"), a.radioParams, "${a.name} Mix")
        }
        else -> emptyList<Song>() to QueueSource(QueueKind.OTHER, item.title)
    }
}

private suspend fun radio(c: AppContainer, videoId: String?, playlistId: String, params: String?, title: String): Pair<List<Song>, QueueSource> {
    val r = c.ytm.next(videoId, playlistId, params)
    c.queue.counterparts.putAll(r.counterparts)
    return r.songs to QueueSource(QueueKind.RADIO, title)
}
