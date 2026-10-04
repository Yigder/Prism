package com.prism.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import coil3.compose.AsyncImage
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.Song
import com.prism.music.data.model.formatDuration
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

enum class CollectionType { ALBUM, PLAYLIST, LIKED, DOWNLOADS }

/** Names of artists YouTube Music lists as similar ("Fans might also like"), for classifying obscure artists. */
private suspend fun relatedArtists(c: com.prism.music.AppContainer, s: Song): List<String> {
    val id = s.artists.firstOrNull { it.id != null }?.id ?: return emptyList()
    val artist = runCatching { c.ytm.artist(id) }.getOrNull() ?: return emptyList()
    return artist.shelves.filter { it.title.contains("like", true) || it.title.contains("similar", true) || it.title.contains("related", true) }
        .flatMap { it.items }.filterIsInstance<com.prism.music.data.model.ArtistItem>().map { it.title }
}

enum class SortMode(val label: String) {
    DEFAULT("Original order"), TITLE("Title"), ARTIST("Artist"), GENRE("Genre"), DURATION("Duration"), ALBUM("Album")
}

@Composable
fun CollectionScreen(type: CollectionType, id: String, initialGenre: String?, bottomPadding: Dp) {
    val c = LocalContainer.current
    val liked by c.library.liked.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()

    val remote = when (type) {
        CollectionType.ALBUM -> rememberLoad("album:$id") { LiveCollection(c.ytm.album(id), c.scope, null) }
        // The first page shows straight away; the rest of a long playlist streams in behind it.
        CollectionType.PLAYLIST -> rememberLoad("playlist:$id") { LiveCollection(c.ytm.playlist(id), c.scope, c.ytm::playlistContinuation) }
        else -> null
    }
    val page: Load<LiveCollection> = when (type) {
        CollectionType.LIKED -> Load.Ok(remember(liked) {
            LiveCollection(CollectionPage("liked", CollectionKind.PLAYLIST, "Liked songs", "${liked.size} songs", thumbnail = null, songs = liked), c.scope, null)
        })
        CollectionType.DOWNLOADS -> {
            val songs = remember(downloads) { c.downloads.completedSongs() }
            Load.Ok(remember(songs) {
                LiveCollection(CollectionPage("downloads", CollectionKind.PLAYLIST, "Downloads", "${songs.size} songs · available offline", thumbnail = null, songs = songs), c.scope, null)
            })
        }
        else -> remote!!.state
    }

    when (page) {
        Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { LoadingState(Modifier.align(Alignment.Center)) }
        is Load.Err -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ErrorState(page.message, Modifier.align(Alignment.Center)) { remote?.reload(false) } }
        is Load.Ok -> CollectionContent(type, page.value, initialGenre, bottomPadding)
    }
}

/** A collection whose songs keep arriving (continuation pages load in the background). */
class LiveCollection(
    val page: CollectionPage,
    scope: kotlinx.coroutines.CoroutineScope,
    loadMore: (suspend (String) -> Pair<List<Song>, String?>)?,
) {
    val songs = kotlinx.coroutines.flow.MutableStateFlow(page.songs.distinctBy { it.id })
    val loadingMore = kotlinx.coroutines.flow.MutableStateFlow(loadMore != null && page.continuation != null)

    init {
        if (loadMore != null && page.continuation != null) scope.launch {
            var token = page.continuation
            while (token != null && songs.value.size < 5_000) {
                val (more, next) = runCatching { loadMore(token) }.getOrNull() ?: break
                if (more.isEmpty()) break
                songs.value = (songs.value + more).distinctBy { it.id }
                token = next
            }
            loadingMore.value = false
        }
    }
}

@Composable
private fun CollectionContent(type: CollectionType, live: LiveCollection, initialGenre: String?, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val current by c.player.currentSong.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    val songs by live.songs.collectAsState()
    val loadingMore by live.loadingMore.collectAsState()
    val page = live.page.copy(songs = songs)
    // Plain map + a version counter: results land in batches, so the list re-sorts a handful
    // of times rather than once per song.
    val genres = remember(page.id) { HashMap<String, String>() }
    var genreVersion by remember(page.id) { mutableStateOf(0) }
    var genreFilter by rememberSaveable(page.id) { mutableStateOf(initialGenre) }
    var sort by rememberSaveable(page.id) { mutableStateOf(SortMode.DEFAULT) }
    var groupByGenre by rememberSaveable(page.id) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val showGenres = type != CollectionType.ALBUM && songs.size > 1

    // Genres are detected per artist, a few at a time; chips fill in as each batch lands.
    LaunchedEffect(page.id, songs.size) {
        if (!showGenres) return@LaunchedEffect
        val todo = songs.filter { it.id !in genres }
        if (todo.isEmpty()) return@LaunchedEffect
        c.meta.resolveGenres(todo, related = { s -> relatedArtists(c, s) }) { batch ->
            genres.putAll(batch)
            genreVersion++
        }
    }

    val resolved = remember(songs, genreVersion) { songs.count { it.id in genres } }
    val genreCounts = remember(songs, genreVersion) {
        songs.mapNotNull { genres[it.id] }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }
    val visible = remember(songs, genreFilter, sort, if (genreFilter != null || sort == SortMode.GENRE || groupByGenre) genreVersion else 0) {
        val filtered = if (genreFilter == null) page.songs else page.songs.filter { genres[it.id] == genreFilter }
        when (sort) {
            SortMode.DEFAULT -> filtered
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> filtered.sortedBy { it.artistText.lowercase() }
            SortMode.GENRE -> filtered.sortedWith(compareBy<Song> { genres[it.id] ?: "￿" }.thenBy { it.artistText.lowercase() })
            SortMode.DURATION -> filtered.sortedBy { it.durationSec }
            SortMode.ALBUM -> filtered.sortedBy { it.album?.title?.lowercase() ?: "" }
        }
    }
    val source = QueueSource(if (type == CollectionType.ALBUM) QueueKind.ALBUM else QueueKind.PLAYLIST, page.title + (genreFilter?.let { " · $it" } ?: ""))
    val listState = rememberLazyListState()
    val downloadedCount = page.songs.count { downloads[it.id]?.state == Download.STATE_COMPLETED }
    val totalSec = visible.sumOf { it.durationSec }
    val popular = remember(live.page) { if (type == CollectionType.ALBUM) live.page.popularIds else emptySet() }
    // Playlists (and Liked songs) can have a picture of the listener's choosing.
    val coverKey = when (type) { CollectionType.PLAYLIST -> page.id; CollectionType.LIKED -> "liked"; else -> null }
    var coverSheet by remember { mutableStateOf(false) }
    if (coverSheet && coverKey != null) CoverSheet(coverKey, page.songs) { coverSheet = false }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item { Header(type, page, coverKey) { coverSheet = true } }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { c.player.playQueue(visible, 0, source) }, Modifier.weight(1f).height(50.dp), enabled = visible.isNotEmpty()) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                    }
                    FilledTonalButton(onClick = { c.player.playQueue(visible, 0, source, shuffle = true) }, Modifier.weight(1f).height(50.dp), enabled = visible.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                    }
                    if (type != CollectionType.DOWNLOADS) IconButton(onClick = { c.downloads.downloadAll(page.songs) }) {
                        Icon(
                            if (downloadedCount == page.songs.size && page.songs.isNotEmpty()) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            "Download all",
                        )
                    }
                }
            }
            if (showGenres) item {
                Column {
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Category, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Genres", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Box {
                            androidx.compose.material3.TextButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(sort.label)
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortMode.entries.forEach { m -> DropdownMenuItem({ Text(m.label) }, { sort = m; sortMenu = false }) }
                                DropdownMenuItem({ Text(if (groupByGenre) "✓ Group by genre" else "Group by genre") }, { groupByGenre = !groupByGenre; sortMenu = false })
                            }
                        }
                    }
                    AnimatedVisibility(resolved < page.songs.size) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                            LinearProgressIndicator(progress = { resolved / page.songs.size.toFloat() }, Modifier.fillMaxWidth())
                            Text("Detecting genres… $resolved / ${page.songs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(genreFilter == null, { genreFilter = null }, { Text("All · ${page.songs.size}") }) }
                        items(genreCounts) { (g, n) ->
                            FilterChip(genreFilter == g, { genreFilter = if (genreFilter == g) null else g }, { Text("$g · $n") })
                        }
                    }
                }
            }
            if (visible.isEmpty()) item {
                EmptyState(Icons.Rounded.Favorite, if (genreFilter != null) "No $genreFilter songs" else "Nothing here yet",
                    if (type == CollectionType.LIKED) "Like songs from the player and they'll show up here." else "Songs you add will show up here.")
            }
            if (groupByGenre && showGenres) {
                visible.groupBy { genres[it.id] ?: "Sorting…" }.toList().sortedByDescending { it.second.size }.forEach { (g, songs) ->
                    item(key = "h:$g") {
                        Text(g, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
                    }
                    itemsIndexed(songs, key = { i, s -> "g:$g:${s.id}:$i" }) { _, s ->
                        SongRow(s, playing = current?.id == s.id, trailingInfo = null) {
                            c.player.playQueue(songs, songs.indexOf(s), source.copy(title = "${page.title} · $g"))
                        }
                    }
                }
            } else {
                itemsIndexed(visible, key = { i, s -> "${s.id}:$i" }) { i, s ->
                    SongRow(
                        s,
                        index = if (type == CollectionType.ALBUM) i + 1 else null,
                        starred = s.id in popular,
                        playing = current?.id == s.id,
                        trailingInfo = if (type == CollectionType.ALBUM) formatDuration(s.durationSec) else genreVersion.let { genres[s.id] }?.takeIf { sort == SortMode.GENRE || genreFilter == null && showGenres }?.let { g -> listOfNotNull(s.album?.title, g).joinToString(" • ") },
                    ) { c.player.playQueue(visible, i, source) }
                }
            }
            item {
                Text(
                    "${visible.size} songs" + (if (totalSec > 0) " · ${totalSec / 3600}h ${(totalSec % 3600) / 60}m" else "") +
                        if (loadingMore) " · loading more…" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        GlassSurface(Modifier.statusBarsPadding().padding(12.dp).size(44.dp), shape = RoundedCornerShape(50)) {
            IconButton(onClick = { nav.back() }, Modifier.fillMaxSize()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
    }
}

@Composable
private fun Header(type: CollectionType, page: CollectionPage, coverKey: String?, onEditCover: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val art = coverKey?.let { LocalContainer.current.covers.art(it, page.thumbnail) } ?: page.thumbnail
    Box(Modifier.fillMaxWidth()) {
        if (art != null) {
            AsyncImage(
                hiRes(art, 300), null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(70.dp).graphicsLayer { alpha = 0.55f },
            )
        } else {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.5f), Color.Transparent))))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, scheme.background))))
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = coverKey != null, onClickLabel = "Change picture", onClick = onEditCover)) {
                if (art != null) {
                    Artwork(art, Modifier.size(232.dp).shadow(28.dp, RoundedCornerShape(20.dp)), RoundedCornerShape(20.dp), size = 900)
                } else {
                    Box(
                        Modifier.size(232.dp).shadow(28.dp, RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (type == CollectionType.DOWNLOADS) Icons.Rounded.DownloadDone else Icons.Rounded.Favorite, null, Modifier.size(96.dp), tint = scheme.onPrimary)
                    }
                }
                if (coverKey != null) GlassSurface(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp), shape = RoundedCornerShape(50)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, "Change picture", Modifier.size(20.dp)) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val linked = page.artists.filter { it.id != null }
            if (linked.isNotEmpty()) {
                // Each artist name opens their page.
                val nav = LocalNavigator.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    linked.forEachIndexed { i, a ->
                        if (i > 0) Text(if (i == linked.lastIndex) " & " else ", ", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                        Text(
                            a.name, style = MaterialTheme.typography.titleSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { nav.go(Routes.artist(a.id!!)) }.padding(horizontal = 2.dp, vertical = 2.dp),
                        )
                    }
                }
            } else if (page.subtitle.isNotBlank()) Text(page.subtitle, style = MaterialTheme.typography.titleSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (page.secondSubtitle.isNotBlank()) Text(page.secondSubtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            page.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Choose a playlist's picture: a photo, or one of its songs' covers. Kept in Prism on this phone. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(key: String, songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!c.covers.usePhoto(key, uri)) android.widget.Toast.makeText(context, "Couldn't use that picture", android.widget.Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    val covers = remember(songs) { songs.mapNotNull { it.thumbnail }.distinctBy { hiRes(it, 120) }.take(30) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("Playlist picture", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Choose a photo") },
                supportingContent = { Text("From your phone's photos") },
                leadingContent = { Icon(Icons.Rounded.PhotoLibrary, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            if (covers.isNotEmpty()) {
                Text(
                    "Or a cover from this playlist", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(covers) { url ->
                        Artwork(
                            url, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).clickable { c.covers.useImage(key, hiRes(url, 1200) ?: url); onDismiss() },
                            RoundedCornerShape(12.dp), size = 226,
                        )
                    }
                }
            }
            if (c.covers.custom(key) != null) androidx.compose.material3.ListItem(
                headlineContent = { Text("Use the original picture") },
                leadingContent = { Icon(Icons.Rounded.Restore, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(top = 8.dp).clickable { c.covers.reset(key); onDismiss() },
            )
            Text(
                "The picture shows everywhere in Prism, including Android Auto. YouTube Music keeps its own.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
        }
    }
}