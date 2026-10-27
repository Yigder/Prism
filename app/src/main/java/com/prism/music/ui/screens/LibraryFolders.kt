package com.prism.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.PrismLibrary
import com.prism.music.data.model.BrowseItem
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/**
 * Every playlist Library lists (Prism's own, saved ones signed out, the account's), by id: what a folder's ids point at.
 * Ids the library no longer has (a playlist deleted elsewhere) simply don't show.
 */
@Composable
internal fun rememberLibraryPlaylists(): List<BrowseItem> {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val playlists by c.library.playlists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    val saved by c.prismLibrary.playlists.collectAsState()
    return remember(playlists, prismLists, saved, settings.isLoggedIn) {
        prismLists.map { it.item() } + (if (settings.isLoggedIn) emptyList() else saved.map { it.item() }) + playlists.filter { it.id != "LM" }
    }
}

/** A folder of playlists in Library (1.7.6): its playlists as rows; renamed or taken apart from the top bar. */
@Composable
fun FolderScreen(id: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val folders by c.prismLibrary.folders.collectAsState()
    val folder = folders.firstOrNull { it.id == id }
    val all = rememberLibraryPlaylists()
    var renaming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<BrowseItem?>(null) }
    if (folder == null) {
        SubPage("Folder", bottomPadding) { item { EmptyState(PrismIcons.Folder, "This folder is gone", "Its playlists are back in Library → Playlists.") } }
        return
    }
    val inside = folder.playlists.mapNotNull { pid -> all.firstOrNull { PrismLibrary.samePlaylist(it.id, pid) } }
    SubPage(folder.name, bottomPadding, subtitle = if (inside.size == 1) "1 playlist" else "${inside.size} playlists", actions = {
        RoundAction(PrismIcons.Edit, "Rename folder") { renaming = true }
        RoundAction(PrismIcons.Delete, "Take the folder apart") { c.prismLibrary.deleteFolder(id); nav.back() }
    }) {
        if (inside.isEmpty()) item {
            EmptyState(PrismIcons.Folder, "Nothing in here yet", "Hold a playlist in Library → Playlists and pick \"Move to a folder\".")
        }
        items(inside, key = { it.id }) { p -> ResultRow(p, onLongClick = { menuFor = p }) { nav.open(p) {} } }
    }
    if (renaming) FolderNameDialog("Rename folder", folder.name, { renaming = false }) { c.prismLibrary.renameFolder(id, it); renaming = false }
    menuFor?.let { p ->
        com.prism.music.ui.components.PlayActionsSheet(
            com.prism.music.ui.components.Playable.Of(p),
            extra = listOf(com.prism.music.ui.components.SheetItem(PrismIcons.Folder, "Take out of \"${folder.name}\"") { c.prismLibrary.moveToFolder(p.id, null) }),
        ) { menuFor = null }
    }
}

/** Where a playlist goes: one of the folders, a new one, or out of its folder. */
@Composable
internal fun MoveToFolderSheet(playlist: BrowseItem, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val folders by c.prismLibrary.folders.collectAsState()
    val current = folders.firstOrNull { f -> f.playlists.any { PrismLibrary.samePlaylist(it, playlist.id) } }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        FolderNameDialog("New folder", "", { naming = false }) { c.prismLibrary.createFolder(it, playlist.id); naming = false; onDismiss() }
        return
    }
    com.prism.music.ui.components.ActionSheet(c.covers.art(playlist), onDismiss) { close ->
        com.prism.music.ui.components.ActionHeader(c.covers.art(playlist), playlist.title, current?.let { "In \"${it.name}\"" } ?: "Move to a folder", placeholderIcon = PrismIcons.QueueMusic)
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            folders.filter { it.id != current?.id }.forEach { f ->
                FolderRow(PrismIcons.Folder, f.name, if (f.playlists.size == 1) "1 playlist" else "${f.playlists.size} playlists") {
                    c.prismLibrary.moveToFolder(playlist.id, f.id); close {}
                }
            }
            FolderRow(PrismIcons.Add, "New folder", null) { naming = true }
            if (current != null) FolderRow(PrismIcons.Remove, "Take out of \"${current.name}\"", null) {
                c.prismLibrary.moveToFolder(playlist.id, null); close {}
            }
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun FolderNameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                text, { text = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Folder name") },
            )
        },
        confirmButton = { PrismTextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
