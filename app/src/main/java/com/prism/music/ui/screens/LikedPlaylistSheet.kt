package com.prism.music.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionHeader
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.Segmented
import com.prism.music.ui.components.runAction
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * Liked songs as a real YouTube Music playlist that keeps itself up to date
 * ([com.prism.music.data.LikedPlaylist]): make it here, then open, refresh or stop it.
 */
@Composable
fun LikedPlaylistSheet(art: String?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    // The app's scope: these outlive the sheet (some only start once it has slid away).
    val scope = remember { kotlinx.coroutines.CoroutineScope(c.scope.coroutineContext + kotlinx.coroutines.Dispatchers.Main) }
    val mirror = c.likedPlaylist
    val id by mirror.playlistId.collectAsState()
    val title by mirror.title.collectAsState()
    val syncing by mirror.syncing.collectAsState()
    val lastSynced by mirror.lastSynced.collectAsState()
    val error by mirror.lastError.collectAsState()
    val liked by c.library.liked.collectAsState()
    var name by remember { mutableStateOf("My liked songs") }
    var privacy by remember { mutableStateOf("PRIVATE") }
    var making by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the playlist?") },
            text = { Text("\"${title ?: "The playlist"}\" will be deleted from your YouTube Music account. Your liked songs stay liked.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { mirror.stop(delete = true).onFailure { toast("Couldn't delete it: ${it.message}") } }
                }) { Text("Delete", color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    ActionSheet(art, onDismiss) { close ->
        ActionHeader(
            art, "Liked songs playlist",
            if (id != null) "Kept up to date" else "${liked.size} songs",
            placeholderIcon = Icons.Rounded.Favorite,
        )
        when {
            !settings.isLoggedIn -> Text(
                "Sign in to YouTube Music to turn Liked songs into a playlist of your own.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            id == null -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Makes a YouTube Music playlist of every song you've liked, one you can share, pin or open in other apps. " +
                        "Prism keeps it up to date: songs you like are added, songs you unlike are taken out.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                OutlinedTextField(name, { name = it.take(150) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Segmented(listOf("PRIVATE", "UNLISTED", "PUBLIC"), privacy, { when (it) { "PRIVATE" -> "Private"; "UNLISTED" -> "Unlisted"; else -> "Public" } }) { privacy = it }
                Button(
                    onClick = {
                        making = true
                        scope.launch {
                            mirror.create(name.trim(), privacy)
                                .onSuccess { toast("Made \"${name.trim()}\" with ${liked.size} songs") }
                                .onFailure { toast("Couldn't make the playlist: ${it.message}") }
                            making = false
                        }
                    },
                    enabled = name.isNotBlank() && !making,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (making) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Making it…")
                    } else Text("Make playlist", fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (syncing) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(
                        when {
                            syncing -> "Updating…"
                            error != null -> "Last update failed: $error"
                            lastSynced > 0 -> "\"${title ?: "Playlist"}\" · updated ${DateUtils.getRelativeTimeSpanString(lastSynced, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
                            else -> "\"${title ?: "Playlist"}\""
                        },
                        style = MaterialTheme.typography.bodyMedium, color = if (error != null && !syncing) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                ActionGroup(null, listOf(
                    ActionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Open the playlist") { nav.go(Routes.playlist(id!!)) },
                    ActionItem(Icons.Rounded.Refresh, "Update now", keepOpen = true) { scope.launch { mirror.sync(full = true) } },
                )) { runAction(it, close) }
                ActionGroup(null, listOf(
                    ActionItem(Icons.Rounded.SyncDisabled, "Stop updating it") { scope.launch { mirror.stop(delete = false); toast("The playlist stays, but won't change any more") } },
                    ActionItem(Icons.Rounded.Delete, "Delete the playlist", danger = true, keepOpen = true) { confirmDelete = true },
                )) { runAction(it, close) }
            }
        }
    }
}
