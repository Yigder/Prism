package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DlSort(val label: String) { RECENT("Recently added"), TITLE("Title"), ARTIST("Artist"), ALBUM("Album") }

/** A group of downloads (one artist or one album). */
private data class DlGroup(val key: String, val title: String, val subtitle: String, val thumbnail: String?, val songs: List<Song>, val bytes: Long, val latest: Long = 0)

private enum class GroupSort(val label: String) { MOST("Most songs"), NAME("A–Z"), SIZE("Size"), RECENT("Recently added") }

private fun List<DlGroup>.sortedBy(s: GroupSort): List<DlGroup> = when (s) {
    GroupSort.MOST -> sortedWith(compareByDescending<DlGroup> { it.songs.size }.thenBy { it.title.lowercase() })
    GroupSort.NAME -> sortedBy { it.title.lowercase().removePrefix("the ") }
    GroupSort.SIZE -> sortedByDescending { it.bytes }
    GroupSort.RECENT -> sortedByDescending { it.latest }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    else -> "%.0f MB".format(bytes / 1_048_576.0)
}

/** Everything that's saved offline: by song, artist or album, with sorting and bulk delete. Kept deliberately quiet. */
@Composable
fun DownloadsScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val all by c.downloads.downloads.collectAsState()
    val current by c.player.currentSong.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val done = remember(all) { all.values.filter { it.state == Download.STATE_COMPLETED && it.song != null } }
    val active = remember(all) { all.filter { it.value.state != Download.STATE_COMPLETED && it.value.state != Download.STATE_FAILED } }
    val bytesOf = remember(all) { all.mapValues { it.value.bytes } }
    var smartIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(all.size) { smartIds = withContext(Dispatchers.IO) { c.db.songs().smartDownloads().map { it.id }.toSet() } }

    var sort by rememberSaveable { mutableStateOf(DlSort.RECENT) }
    var artistSort by rememberSaveable { mutableStateOf(GroupSort.MOST) }
    var albumSort by rememberSaveable { mutableStateOf(GroupSort.MOST) }
    val addedAt = remember(all) { all.mapValues { it.value.addedAt } }
    var filter by rememberSaveable { mutableStateOf<String?>(null) } // "artist:<name>" / "album:<id>"
    var filterTitle by rememberSaveable { mutableStateOf("") }
    val selected: SnapshotStateList<String> = remember { emptyList<String>().toMutableStateList() }
    val selecting = selected.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val songs = remember(done, sort) {
        val list = done.sortedByDescending { it.addedAt }.map { it.song!! }
        when (sort) {
            DlSort.RECENT -> list
            DlSort.TITLE -> list.sortedBy { it.title.lowercase() }
            DlSort.ARTIST -> list.sortedWith(compareBy<Song> { it.primaryArtist.lowercase() }.thenBy { it.album?.title?.lowercase() ?: "" })
            DlSort.ALBUM -> list.sortedWith(compareBy<Song> { it.album?.title?.lowercase() ?: "￿" }.thenBy { it.title.lowercase() })
        }
    }
    val shown = remember(songs, filter) {
        when {
            filter == null -> songs
            filter!!.startsWith("artist:") -> songs.filter { it.primaryArtist == filter!!.removePrefix("artist:") }
            else -> songs.filter { (it.album?.id ?: it.album?.title) == filter!!.removePrefix("album:") }
        }
    }
    val artists = remember(done) {
        done.mapNotNull { it.song }.groupBy { it.primaryArtist.ifBlank { "Unknown artist" } }.map { (name, list) ->
            val b = list.sumOf { bytesOf[it.id] ?: 0L }
            DlGroup("artist:$name", name, "${list.size} song${if (list.size == 1) "" else "s"} · ${sizeText(b)}", list.first().thumbnail, list, b, list.maxOf { addedAt[it.id] ?: 0L })
        }
    }
    val albums = remember(done) {
        done.mapNotNull { it.song }.filter { it.album != null }.groupBy { it.album!!.id ?: it.album.title }.map { (key, list) ->
            val first = list.first()
            val b = list.sumOf { bytesOf[it.id] ?: 0L }
            DlGroup("album:$key", first.album!!.title, "${first.primaryArtist} · ${list.size} song${if (list.size == 1) "" else "s"}", first.thumbnail, list, b, list.maxOf { addedAt[it.id] ?: 0L })
        }
    }

    val pages = listOf("Songs", "Artists", "Albums")
    val pager = rememberPagerState { pages.size }
    val totalBytes = done.sumOf { it.bytes }
    val smartCount = done.count { it.song!!.id in smartIds }
    val src = QueueSource(QueueKind.PLAYLIST, if (filter != null) filterTitle else "Downloads")

    fun ask(text: String, action: () -> Unit) { confirm = text to action }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- Header
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconButton(onClick = { selected.clear() }) { Icon(Icons.Rounded.Close, "Cancel selection") }
                Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val ids = shown.map { it.id }
                    if (selected.containsAll(ids)) selected.clear() else { selected.clear(); selected.addAll(ids) }
                }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                IconButton(onClick = {
                    val ids = selected.toList()
                    ask("Delete ${ids.size} download${if (ids.size == 1) "" else "s"}? (${sizeText(ids.sumOf { bytesOf[it] ?: 0L })})") {
                        c.downloads.removeMany(ids); selected.clear()
                    }
                }) { Icon(Icons.Rounded.DeleteOutline, "Delete selected") }
            } else {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.titleLarge)
                    Text("${done.size} songs · ${sizeText(totalBytes)}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    // Lyrics saved with the songs: how many, how many word by word, and whether more are coming in.
                    val levels by c.downloads.lyricLevels.collectAsState()
                    val working by c.downloads.lyricsWorking.collectAsState()
                    val ids = done.map { it.song!!.id }
                    val withLyrics = ids.count { (levels[it] ?: 0) > 0 }
                    val karaoke = ids.count { (levels[it] ?: 0) >= 3 }
                    if (done.isNotEmpty() && ids.any { it in levels }) Text(
                        "Lyrics for $withLyrics · $karaoke word by word" + if (working > 0) " · getting more…" else "",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { c.player.playQueue(shown, 0, src, shuffle = true) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Play all") }, { menu = false; c.player.playQueue(shown, 0, src) }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
                        DropdownMenuItem({ Text("Select songs") }, { menu = false; scope.launch { pager.animateScrollToPage(0) }; shown.firstOrNull()?.let { selected.add(it.id) } }, leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) })
                        DropdownMenuItem({ Text("Get missing lyrics") }, {
                            menu = false
                            c.downloads.backfillLyrics(force = true)
                        }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null) })
                        if (smartCount > 0) DropdownMenuItem({ Text("Delete smart downloads ($smartCount)") }, {
                            menu = false
                            val ids = done.map { it.song!!.id }.filter { it in smartIds }
                            ask("Delete the $smartCount songs smart downloads added? They may come back later if smart downloads stays on.") { c.downloads.removeMany(ids) }
                        }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) })
                        DropdownMenuItem({ Text("Delete all downloads") }, {
                            menu = false
                            ask("Delete all ${all.size} downloads (${sizeText(c.downloads.totalBytes)})? This frees the space straight away.") { c.downloads.removeAll() }
                        }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                    }
                }
            }
        }
        if (active.isNotEmpty() && !selecting) Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloading ${active.size}…", style = MaterialTheme.typography.labelMedium, color = scheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { ask("Cancel ${active.size} downloads that haven't finished?") { c.downloads.removeMany(active.keys) } }) { Text("Cancel") }
        }

        // ---------------------------------------------------------------- Tabs + sort
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, label ->
                val on = pager.currentPage == i
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) scheme.onSurface else scheme.onSurfaceVariant, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(width = 18.dp, height = 3.dp).clip(CircleShape).background(if (on) scheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                val page = pager.currentPage
                val label = when (page) { 0 -> sort.label; 1 -> artistSort.label; else -> albumSort.label }
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    if (page == 0) DlSort.entries.forEach { m -> SortOption(m.label, m == sort) { sort = m; sortMenu = false } }
                    else GroupSort.entries.forEach { m ->
                        SortOption(m.label, m == (if (page == 1) artistSort else albumSort)) { if (page == 1) artistSort = m else albumSort = m; sortMenu = false }
                    }
                }
            }
        }

        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 24.dp)) {
                    if (filter != null) item {
                        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(filterTitle, style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { filter = null }) { Icon(Icons.Rounded.Close, "Show all downloads", Modifier.size(18.dp)) }
                        }
                    }
                    if (shown.isEmpty()) item {
                        EmptyState(Icons.Rounded.DownloadDone, "No downloads yet", "Download songs, albums or playlists, or turn on smart downloads in Settings.")
                    }
                    itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                        DownloadRow(
                            s, sizeText(bytesOf[s.id] ?: 0L),
                            playing = current?.id == s.id,
                            selecting = selecting, checked = s.id in selected,
                            onClick = { if (selecting) { if (s.id in selected) selected.remove(s.id) else selected.add(s.id) } else c.player.playQueue(shown, i, src) },
                            onLongClick = { if (s.id !in selected) selected.add(s.id) },
                        )
                    }
                }
                else -> {
                    val groupSort = if (page == 1) artistSort else albumSort
                    val groups = remember(artists, albums, page, groupSort) { (if (page == 1) artists else albums).sortedBy(groupSort) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 24.dp)) {
                        if (groups.isEmpty()) item {
                            EmptyState(if (page == 1) Icons.Rounded.Person else Icons.Rounded.Album, "Nothing here yet", "Downloaded songs are grouped here by ${if (page == 1) "artist" else "album"}.")
                        }
                        items(groups, key = { it.key }) { g ->
                            GroupRow(
                                g, round = page == 1,
                                onOpen = {
                                    filter = g.key; filterTitle = g.title
                                    sort = if (page == 1) DlSort.ALBUM else DlSort.RECENT
                                    scope.launch { pager.animateScrollToPage(0) }
                                },
                                onPlay = { c.player.playQueue(g.songs, 0, QueueSource(QueueKind.PLAYLIST, g.title)) },
                                onShuffle = { c.player.playQueue(g.songs, 0, QueueSource(QueueKind.PLAYLIST, g.title), shuffle = true) },
                                onDelete = { ask("Delete ${g.songs.size} downloaded song${if (g.songs.size == 1) "" else "s"} from ${g.title}? (${sizeText(g.bytes)})") { c.downloads.removeMany(g.songs.map { it.id }) } },
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete downloads") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        onClick = onClick,
        trailingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
    )
}

/** A quiet song row: artwork, title, artist and size. Long-press selects. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(song: Song, size: String, playing: Boolean, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (checked) scheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (checked) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Artwork(song.thumbnail, Modifier.size(46.dp), RoundedCornerShape(8.dp), size = 226)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = if (playing) scheme.primary else scheme.onSurface)
            Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Text(size, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun GroupRow(g: DlGroup, round: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(g.thumbnail, Modifier.size(50.dp), if (round) CircleShape else RoundedCornerShape(8.dp), size = 226, placeholderIcon = if (round) Icons.Rounded.Person else Icons.Rounded.Album)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${g.title}", tint = scheme.onSurfaceVariant) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Play") }, { menu = false; onPlay() }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
                DropdownMenuItem({ Text("Shuffle") }, { menu = false; onShuffle() }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null) })
                DropdownMenuItem({ Text("Delete downloads (${sizeText(g.bytes)})") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
            }
        }
    }
}
