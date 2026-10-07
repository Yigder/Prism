package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.LibraryRepository
import com.prism.music.data.SyncState
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.SongItem
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.PagerChips
import com.prism.music.ui.components.SortBar
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) { PLAYLISTS("Playlists"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), DOWNLOADS("Downloads") }

private enum class SongSort(val label: String) { RECENT("Recently liked"), TITLE("Title"), ARTIST("Artist"), ALBUM("Album") }
private enum class AlbumSort(val label: String) { LIBRARY("Library order"), MOST("Most songs"), TITLE("Title"), ARTIST("Artist") }
private enum class ArtistSort(val label: String) { LIBRARY("Library order"), MOST("Most songs"), NAME("Name") }

@Composable
fun LibraryScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val playlists by c.library.playlists.collectAsState()
    val remoteAlbums by c.library.albums.collectAsState()
    val remoteArtists by c.library.artists.collectAsState()
    val liked by c.library.liked.collectAsState()
    val sync by c.library.syncState.collectAsState()
    val dls by c.downloads.downloads.collectAsState()
    val thumbs by c.library.artistThumbs.collectAsState()
    val scope = rememberCoroutineScope()
    val startPage = remember { LibTab.entries.indexOfFirst { it.name == settings.libraryStartTab }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startPage) { LibTab.entries.size }
    val asList = settings.libraryView == com.prism.music.data.prefs.LibraryView.LIST
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    // A long press on a playlist offers play / shuffle / delete; the one it asked to delete.
    var menuFor by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    var deleting by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    val menu = com.prism.music.ui.components.rememberPlayMenu()

    // The account's saved albums / artists first, then the ones your liked and downloaded songs come from.
    val mine = remember(liked, dls) {
        (liked + dls.values.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }.mapNotNull { it.song }).distinctBy { it.id }
    }
    val albums = remember(remoteAlbums, mine) {
        (remoteAlbums + LibraryRepository.albumsFrom(mine)).distinctBy { it.id }
    }
    val artists = remember(remoteArtists, mine, thumbs) {
        (remoteArtists + LibraryRepository.artistsFrom(mine)).distinctBy { it.id }.map { a ->
            if (a.thumbnail == null && a is ArtistItem) a.copy(thumbnail = thumbs[a.id]) else a
        }
    }

    // ---- Sorting (each tab remembers its own)
    var songSort by rememberSaveable { mutableStateOf(SongSort.RECENT) }
    var albumSort by rememberSaveable { mutableStateOf(AlbumSort.LIBRARY) }
    var artistSort by rememberSaveable { mutableStateOf(ArtistSort.LIBRARY) }
    val sortedSongs = remember(liked, songSort) {
        when (songSort) {
            SongSort.RECENT -> liked
            SongSort.TITLE -> liked.sortedBy { it.title.lowercase() }
            SongSort.ARTIST -> liked.sortedWith(compareBy<com.prism.music.data.model.Song> { it.primaryArtist.lowercase() }.thenBy { it.album?.title?.lowercase() ?: "" })
            SongSort.ALBUM -> liked.sortedWith(compareBy<com.prism.music.data.model.Song> { it.album?.title?.lowercase() ?: "\uFFFF" }.thenBy { it.title.lowercase() })
        }
    }
    val albumSongs = remember(mine) { mine.filter { it.album?.id != null }.groupBy { it.album!!.id!! } }
    val sortedAlbums = remember(albums, albumSort, albumSongs) {
        fun artistOf(a: com.prism.music.data.model.BrowseItem) = albumSongs[a.id]?.first()?.primaryArtist ?: a.subtitle.split(" • ").firstOrNull { it != "Album" && it != "Single" && it != "EP" } ?: ""
        when (albumSort) {
            AlbumSort.LIBRARY -> albums
            AlbumSort.MOST -> albums.sortedByDescending { albumSongs[it.id]?.size ?: 0 }
            AlbumSort.TITLE -> albums.sortedBy { it.title.lowercase() }
            AlbumSort.ARTIST -> albums.sortedWith(compareBy<com.prism.music.data.model.BrowseItem> { artistOf(it).lowercase() }.thenBy { it.title.lowercase() })
        }
    }
    val artistSongs = remember(mine) { mine.flatMap { s -> s.artists.mapNotNull { it.id } }.groupingBy { it }.eachCount() }
    val sortedArtists = remember(artists, artistSort, artistSongs) {
        when (artistSort) {
            ArtistSort.LIBRARY -> artists
            ArtistSort.MOST -> artists.sortedByDescending { artistSongs[it.id] ?: 0 }
            ArtistSort.NAME -> artists.sortedBy { it.title.lowercase().removePrefix("the ") }
        }
    }
    LaunchedEffect(pager.currentPage == LibTab.ARTISTS.ordinal, artists.size) {
        if (pager.currentPage == LibTab.ARTISTS.ordinal) c.library.loadArtistThumbs(artists.filter { it.thumbnail == null }.take(60).map { it.id })
    }

    LaunchedEffect(settings.isLoggedIn) {
        // Once per session (Prism also syncs at launch); the refresh button forces another.
        if (settings.isLoggedIn && (playlists.isEmpty() || sync == SyncState.IDLE)) c.library.sync()
    }

    Column(Modifier.fillMaxSize()) {
        com.prism.music.ui.components.ScreenHeader("Library") {
            if (settings.isLoggedIn) {
                if (sync == SyncState.SYNCING) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                else com.prism.music.ui.components.RoundAction(Icons.Rounded.CloudSync, "Sync with YouTube Music") { scope.launch { c.library.sync() } }
            }
            com.prism.music.ui.components.RoundAction(
                if (asList) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                if (asList) "Show as grid" else "Show as list",
            ) { c.settings.setLibraryView(if (asList) com.prism.music.data.prefs.LibraryView.GRID else com.prism.music.data.prefs.LibraryView.LIST) }
            if (com.prism.music.data.prefs.NavTab.SETTINGS !in settings.navTabs) com.prism.music.ui.components.RoundAction(Icons.Rounded.Settings, "Settings") { nav.go(Routes.SETTINGS) }
        }
        PagerChips(LibTab.entries.map { it.label }, pager)

        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            val tab = LibTab.entries[page]
            LazyVerticalGrid(
                if (asList) GridCells.Fixed(1) else GridCells.Adaptive(160.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = if (asList) 0.dp else 12.dp, end = if (asList) 0.dp else 12.dp, top = 4.dp, bottom = bottomPadding + 16.dp),
            ) {
                val empty = when (tab) {
                    LibTab.ALBUMS -> albums.isEmpty()
                    LibTab.ARTISTS -> artists.isEmpty()
                    LibTab.SONGS -> liked.isEmpty()
                    else -> false
                }
                if (empty) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!settings.isLoggedIn) {
                            EmptyState(Icons.Rounded.LibraryMusic, "Sign in to sync your library", "Your playlists, albums, artists and likes from YouTube Music will appear here.")
                            Button(onClick = { nav.go(Routes.LOGIN) }) { Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in") }
                        } else {
                            EmptyState(Icons.Rounded.LibraryMusic, "Nothing here yet", "Like or download songs and their  show up here.")
                        }
                    }
                }
                when (tab) {
                    LibTab.PLAYLISTS -> {
                        item { ShortcutTile("Liked songs", "${liked.size} songs", Icons.Rounded.Favorite, asList, art = c.covers.custom("liked"), onLongClick = { menu(com.prism.music.ui.components.Playable.Liked) }) { nav.go(Routes.liked()) } }
                        item { ShortcutTile("Downloads", "${dls.values.count { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }} songs", Icons.Rounded.DownloadDone, asList, onLongClick = { menu(com.prism.music.ui.components.Playable.Downloads) }) { nav.go(Routes.DOWNLOADS) } }
                        item { ShortcutTile("Replay", "Your listening recap", Icons.Rounded.History, asList) { nav.go(Routes.REPLAY) } }
                        if (settings.isLoggedIn) item { ShortcutTile("Import playlists", "From Spotify, Apple Music…", Icons.AutoMirrored.Rounded.PlaylistAdd, asList) { nav.go(Routes.IMPORT) } }
                        if (!settings.isLoggedIn) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(Icons.Rounded.LibraryMusic, "Sign in to sync your playlists", "Your YouTube Music playlists will appear here.")
                                Button(onClick = { nav.go(Routes.LOGIN) }) { Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in") }
                            }
                        }
                        items(playlists.filter { it.id != "LM" }, key = { "p" + it.id }) { p ->
                            LibraryItem(p, asList, onLongClick = { menuFor = p }) { nav.open(p, onSong) }
                        }
                    }
                    LibTab.SONGS -> {
                        if (liked.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar("${liked.size} songs", SongSort.entries, songSort, { it.label }) { songSort = it }
                        }
                        items(sortedSongs, key = { "s" + it.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                            SongRow(s) { c.player.playQueue(sortedSongs, sortedSongs.indexOf(s), com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.LIBRARY, "Liked songs")) }
                        }
                    }
                    LibTab.ALBUMS -> {
                        if (albums.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar("${albums.size} albums", AlbumSort.entries, albumSort, { it.label }) { albumSort = it }
                        }
                        items(sortedAlbums, key = { "a" + it.id }) { a -> LibraryItem(a, asList) { nav.open(a, onSong) } }
                    }
                    LibTab.ARTISTS -> {
                        if (artists.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar("${artists.size} artists", ArtistSort.entries, artistSort, { it.label }) { artistSort = it }
                        }
                        items(sortedArtists, key = { "r" + it.id }) { a -> LibraryItem(a, asList) { nav.open(a, onSong) } }
                    }
                    LibTab.DOWNLOADS -> item(span = { GridItemSpan(maxLineSpan) }) { DownloadsSummary() }
                }
            }
        }
    }

    menuFor?.let { p ->
        com.prism.music.ui.components.PlayActionsSheet(
            com.prism.music.ui.components.Playable.Of(p),
            extra = listOf(com.prism.music.ui.components.SheetItem(Icons.Rounded.Delete, "Delete playlist") { deleting = p }),
        ) { menuFor = null }
    }

    deleting?.let { p ->
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${p.title}\" will be deleted from your YouTube Music account. If it's someone else's playlist, it's only removed from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        c.library.deletePlaylist(p).onFailure {
                            android.widget.Toast.makeText(context, "Couldn't delete \"${p.title}\": ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
/** An album, artist or playlist as a card (grid view) or a row (list view). */
@Composable
private fun LibraryItem(item: com.prism.music.data.model.BrowseItem, asList: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    if (asList) ResultRow(item, onLongClick, onClick) else ItemCard(item, width = 400.dp, onLongClick = onLongClick, onClick = onClick)
}

@Composable
private fun ShortcutTile(title: String, subtitle: String, icon: ImageVector, asList: Boolean, art: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    @Composable
    fun Cover(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, iconSize: Dp) {
        // A picture the listener chose (Liked songs can have one too).
        if (art != null) com.prism.music.ui.components.Artwork(art, modifier, shape, size = 544)
        else Box(
            modifier.clip(shape).background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(iconSize), tint = scheme.onPrimary) }
    }
    if (asList) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = ui.gap(7.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(Modifier.size(56.dp), ui.smallArt, 26.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        return
    }
    Column(Modifier.padding(4.dp).clip(ui.tile).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp)) {
        Cover(Modifier.fillMaxWidth().aspectRatio(1f), ui.art, 56.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadsSummary() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val dls by c.downloads.downloads.collectAsState()
    val done = dls.values.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    val active = dls.values.filter { it.state != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    Column(Modifier.padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.card)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                .clickable { nav.go(Routes.DOWNLOADS) }.padding(20.dp),
        ) {
            Column {
                Text("${done.size} songs offline", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "%.1f MB used".format(done.sumOf { it.bytes } / 1_048_576.0) + if (active.isNotEmpty()) " · ${active.size} in progress" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Text("Open downloads →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        active.forEach { d ->
            d.song?.let { s ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Text("${d.percent.toInt().coerceAtLeast(0)}%", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }
            }
        }
    }
}
