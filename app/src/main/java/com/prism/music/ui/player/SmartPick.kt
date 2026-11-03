package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Beside the like button while a Smart Shuffle pick plays: add it to the playlist it was picked for (a playlist on the
 * signed-in account), or "Not for me" (disliked, and on to the next song).
 */
@Composable
fun SmartPickButton(song: Song) {
    val c = LocalContainer.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val source by c.queue.source.collectAsState()
    val playlist = source.originId?.takeIf { source.kind == QueueKind.PLAYLIST && c.settings.current.isLoggedIn && it.startsWith("PL") }
    Box {
        CircleGlyph(PrismIcons.SmartShuffle, tr(R.string.smart_shuffle_pick), active = true) { open = true }
        DropdownMenu(open, { open = false }) {
            Text(
                tr(R.string.picked_for_this_queue_by_smart), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (playlist != null) DropdownMenuItem(
                { Text(tr(R.string.add_to, source.title.ifBlank { tr(R.string.this_playlist_2) })) },
                {
                    open = false
                    c.scope.launch(Dispatchers.Main) {
                        val ok = runCatching { withContext(Dispatchers.IO) { c.ytm.addToPlaylist(playlist, song.id) } }.isSuccess
                        // Kept: it's the playlist's own song now, not a pick.
                        if (ok) c.queue.smartPicks.value = c.queue.smartPicks.value - song.id
                        Toast.makeText(context, if (ok) tr(R.string.added_to, source.title) else tr(R.string.couldn_t_add_it_to_the), Toast.LENGTH_SHORT).show()
                    }
                },
                leadingIcon = { Icon(PrismIcons.PlaylistAdd, null) },
            )
            DropdownMenuItem(
                { Text(tr(R.string.not_for_me)) },
                { open = false; c.library.setDisliked(song, true); c.player.next() },
                leadingIcon = { Icon(PrismIcons.ThumbDown, null) },
            )
        }
    }
}
