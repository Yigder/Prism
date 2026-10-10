package com.prism.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import coil3.compose.AsyncImage
import com.prism.music.data.ShareLinks
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.formatDuration
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ArtworkAccent
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.FrostedBackdrop
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.ScrollEdge
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.ShareSheet
import com.prism.music.ui.components.ShareTarget
import com.prism.music.ui.components.SheetItem
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.dissolveBottom
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.rememberArtPalette
import com.prism.music.ui.components.rememberFrost
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
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
        CollectionType.PLAYLIST -> rememberLoad("playlist:$id") { LiveCollection(c.ytm.playlist(id), c.scope, c.ytm::playlistPage) }
        else -> null
    }
    val page: Load<LiveCollection> = when (type) {
        CollectionType.LIKED -> Load.Ok(remember(liked) {
            LiveCollection(CollectionPage("liked", CollectionKind.PLAYLIST, "Liked songs", "${liked.size} songs", thumbnail = null, songs = liked), c.scope, null)
        })
        CollectionType.DOWNLOADS -> {
            val songs = remember(downloads) { c.downloads.completedSongs() }
            Load.Ok(remember(songs) {
                LiveCollection(CollectionPage("downloads", CollectionKind.PLAYLIST, "Downloads", "Available offline", thumbnail = null, songs = songs), c.scope, null)
            })
        }
        else -> remote!!.state
    }

    when (page) {
        Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { LoadingState(Modifier.align(Alignment.Center)) }
        is Load.Err -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ErrorState(page.message, Modifier.align(Alignment.Center)) { remote?.reload(false) } }
        is Load.Ok -> CollectionContent(type, page.value, initialGenre, bottomPadding) { remote?.reload(true) }
    }
}

/** A collection whose songs keep arriving (continuation pages load in the background). */
class LiveCollection(
    val page: CollectionPage,
    scope: kotlinx.coroutines.CoroutineScope,
    loadMore: (suspend (String) -> YouTubeMusic.PlaylistChunk)?,
) {
    val songs = kotlinx.coroutines.flow.MutableStateFlow(page.songs.distinctBy { it.id })
    /** Each song's slot in an owned playlist, for taking it out again. */
    val setVideoIds = kotlinx.coroutines.flow.MutableStateFlow(page.setVideoIds)
    val loadingMore = kotlinx.coroutines.flow.MutableStateFlow(loadMore != null && page.continuation != null)

    init {
        if (loadMore != null && page.continuation != null) scope.launch {
            var token = page.continuation
            while (token != null && songs.value.size < 5_000) {
                val chunk = runCatching { loadMore(token) }.getOrNull() ?: break
                if (chunk.songs.isEmpty()) break
                songs.value = (songs.value + chunk.songs).distinctBy { it.id }
                if (chunk.setVideoIds.isNotEmpty()) setVideoIds.value = setVideoIds.value + chunk.setVideoIds
                token = chunk.next
            }
            loadingMore.value = false
        }
    }
}

@Composable
private fun CollectionContent(type: CollectionType, live: LiveCollection, initialGenre: String?, bottomPadding: Dp, reload: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalAppSettings.current
    val current by c.player.currentSong.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    val songs by live.songs.collectAsState()
    val loadingMore by live.loadingMore.collectAsState()
    val page = live.page.copy(songs = songs)
    // Plain map + a version counter: results land in batches, so the list re-sorts a handful
    // of times rather than once per song.
    val genres = remember(page.id) { HashMap<String, String>() }
    // Further genres a song clearly also belongs to (beyond its main one in [genres]).
    val extraGenres = remember(page.id) { HashMap<String, List<String>>() }
    val extrasChecked = remember(page.id) { HashSet<String>() }
    var genreVersion by remember(page.id) { mutableStateOf(0) }
    fun genresOf(id: String): List<String> = listOfNotNull(genres[id]) + extraGenres[id].orEmpty()
    var genreFilter by rememberSaveable(page.id) { mutableStateOf(initialGenre) }
    var sort by rememberSaveable(page.id) { mutableStateOf(SortMode.DEFAULT) }
    var groupByGenre by rememberSaveable(page.id) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable(page.id) { mutableStateOf("") }
    val showGenres = type != CollectionType.ALBUM && songs.size > 1
    val showSearch = type != CollectionType.ALBUM || songs.size > 12

    // Genres are detected quietly per artist, a few at a time; chips fill in as each batch lands.
    // Main genres come first, then any clear second genre an artist also has.
    LaunchedEffect(page.id, songs.size) {
        if (!showGenres) return@LaunchedEffect
        val todo = songs.filter { it.id !in genres }
        if (todo.isNotEmpty()) c.meta.resolveGenres(todo, related = { s -> relatedArtists(c, s) }) { batch ->
            genres.putAll(batch)
            genreVersion++
        }
        val extraTodo = songs.filter { it.id in genres && it.id !in extrasChecked }
        if (extraTodo.isEmpty()) return@LaunchedEffect
        c.meta.resolveExtraGenres(extraTodo, extraTodo.associate { it.id to genres.getValue(it.id) }) { batch ->
            extraGenres.putAll(batch)
            genreVersion++
        }
        extrasChecked += extraTodo.map { it.id }
    }

    val genreCounts = remember(songs, genreVersion) {
        songs.flatMap { genresOf(it.id) }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }
    val visible = remember(songs, genreFilter, sort, query, if (genreFilter != null || sort == SortMode.GENRE || groupByGenre) genreVersion else 0) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = page.songs.filter { s ->
            (genreFilter == null || genreFilter in genresOf(s.id)) &&
                // Every word typed has to turn up in the title, the artists or the album.
                (words.isEmpty() || "${s.title} ${s.artistText} ${s.album?.title.orEmpty()}".lowercase().let { hay -> words.all { it in hay } })
        }
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
    val downloadingCount = page.songs.count { downloads[it.id]?.let { d -> d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED } == true }
    val totalSec = visible.sumOf { it.durationSec }
    val popular = remember(live.page) { if (type == CollectionType.ALBUM) live.page.popularIds else emptySet() }
    // Playlists (and Liked songs) can have a picture of the listener's choosing.
    val coverKey = when (type) { CollectionType.PLAYLIST -> page.id; CollectionType.LIKED -> "liked"; CollectionType.DOWNLOADS -> "downloads"; else -> null }
    val art = coverKey?.let { c.covers.art(it, page.thumbnail) } ?: page.thumbnail
    // Prism's own lists without a picture take their colours from their newest song.
    val frostUrl = art ?: songs.firstOrNull()?.thumbnail
    val palette = rememberArtPalette(frostUrl)
    val frost = rememberFrost(frostUrl)
    val dark = LocalIsDark.current

    // The YouTube Music library bookmark, and an owned playlist's visibility (both change from here).
    var saved by remember(page.id) { mutableStateOf(live.page.savedToLibrary) }
    var privacy by remember(page.id) { mutableStateOf(live.page.privacy) }
    val savableId = when {
        !settings.isLoggedIn || saved == null -> null
        type == CollectionType.ALBUM -> live.page.playlistId
        type == CollectionType.PLAYLIST && !live.page.owned -> live.page.id
        else -> null
    }
    val toggleSaved: () -> Unit = {
        val id = savableId
        val now = saved
        if (id != null && now != null) {
            saved = !now
            scope.launch {
                if (!c.library.setSaved(id, !now)) {
                    saved = now
                    Toast.makeText(context, "Couldn't update your library", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val radioId = when (type) {
        CollectionType.ALBUM -> live.page.playlistId
        CollectionType.PLAYLIST -> live.page.id
        else -> null
    }?.let { "RDAMPL$it" }
    val startRadio: () -> Unit = {
        radioId?.let { rid ->
            scope.launch {
                runCatching { c.ytm.next(null, rid) }.onSuccess { r ->
                    c.queue.counterparts.putAll(r.counterparts)
                    c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, "${page.title} Radio"))
                }.onFailure { Toast.makeText(context, "Couldn't start the radio", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    val allDownloaded = downloadedCount == page.songs.size && page.songs.isNotEmpty()
    val toggleDownloads: () -> Unit = {
        if (allDownloaded) {
            c.downloads.removeMany(page.songs.map { it.id })
            Toast.makeText(context, "Removed ${page.songs.size} downloads", Toast.LENGTH_SHORT).show()
        } else {
            c.downloads.downloadAll(page.songs)
            Toast.makeText(context, "Downloading ${page.songs.size - downloadedCount} songs", Toast.LENGTH_SHORT).show()
        }
    }

    // Owned playlists: a song can be taken out of it.
    val slots by live.setVideoIds.collectAsState()
    fun removeAction(s: Song): List<SheetItem> {
        val slot = slots[s.id]
        if (type != CollectionType.PLAYLIST || !live.page.owned || slot == null) return emptyList()
        return listOf(SheetItem(Icons.Rounded.PlaylistRemove, "Remove from this playlist") {
            val before = live.songs.value
            live.songs.value = before.filterNot { it.id == s.id }
            scope.launch {
                runCatching { c.ytm.removeFromPlaylist(page.id, s.id, slot) }
                    .onSuccess { Toast.makeText(context, "Removed from ${page.title}", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        live.songs.value = before
                        Toast.makeText(context, "Couldn't remove it: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        })
    }

    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete downloads") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    var coverSheet by remember { mutableStateOf(false) }
    if (coverSheet && coverKey != null) CoverSheet(coverKey, page.songs) { coverSheet = false }
    var sharing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var title by remember(page.id) { mutableStateOf(page.title) }
    var description by remember(page.id) { mutableStateOf(page.description) }

    val hero = type != CollectionType.ALBUM && settings.playlistHeroCover
    val config = LocalConfiguration.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroHeight = if (hero) config.screenWidthDp.dp / 1.08f else statusTop + 470.dp
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    val heroScroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else heroPx }
    val shown = page.copy(title = title, description = description)
    // How far the bar has taken over from the header (0 at the top, 1 once the cover has scrolled away).
    val solid by remember(heroPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - heroPx * 0.62f) / (heroPx * 0.18f)).coerceIn(0f, 1f)
        }
    }
    val density = LocalDensity.current
    var pageHeight by remember { mutableStateOf(0.dp) }
    // Search and chips go see-through on the frost (and keep the theme's colour without it).
    val frosted = if (settings.frostedPages) frostFill(1.4f) else Color.Unspecified

    ArtworkAccent(palette) {
        Box(Modifier.fillMaxSize().onSizeChanged { pageHeight = with(density) { it.height.toDp() } }) {
            FrostedBackdrop(frost, palette, heroHeight, heroScroll, parallax = if (hero) 0.5f else 0.35f)
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
                item("header") {
                    if (hero) HeroHeader(type, shown, art, { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0 }, heroHeight)
                    else Header(type, shown, art, coverKey) { coverSheet = true }
                    HeaderDetails(shown, centred = !hero, privacy = privacy, saved = saved.takeIf { savableId != null }, onSaved = toggleSaved)
                }
                if (type == CollectionType.DOWNLOADS) item { DownloadsInfo(page.songs) { text, action -> confirm = text to action } }
                item("play") {
                    PlayShufflePills(
                        enabled = visible.isNotEmpty(),
                        onPlay = { c.player.playQueue(visible, 0, source) },
                        onShuffle = { c.player.playQueue(visible, 0, source, shuffle = true) },
                    )
                }
                if (showSearch || showGenres) item(key = "search") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showSearch) com.prism.music.ui.components.SearchField(
                            query, if (type == CollectionType.ALBUM) "Search in album" else "Search in $title", { query = it }, Modifier.weight(1f),
                            container = frosted,
                        ) else Spacer(Modifier.weight(1f))
                        if (showGenres) Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Sort, "Sort",
                                    tint = if (sort != SortMode.DEFAULT || groupByGenre) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem({ Text(sortLabel(m, type)) }, { sort = m; sortMenu = false }, trailingIcon = { if (m == sort) Icon(Icons.Rounded.Check, null) })
                                }
                                HorizontalDivider()
                                DropdownMenuItem({ Text("Group by genre") }, { groupByGenre = !groupByGenre; sortMenu = false }, trailingIcon = { if (groupByGenre) Icon(Icons.Rounded.Check, null) })
                            }
                        }
                    }
                }
                if (showGenres && genreCounts.isNotEmpty()) item(key = "genres") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { com.prism.music.ui.components.PrismChip(genreFilter == null, { genreFilter = null }, "All", idle = frosted) }
                        items(genreCounts) { (g, n) ->
                            com.prism.music.ui.components.PrismChip(genreFilter == g, { genreFilter = if (genreFilter == g) null else g }, "$g · $n", idle = frosted)
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    if (query.isNotBlank()) EmptyState(Icons.Rounded.Search, "No matches", "Nothing in $title matches \"${query.trim()}\"" + if (loadingMore) " yet — more songs are still loading." else ".")
                    else EmptyState(Icons.Rounded.Favorite, if (genreFilter != null) "No $genreFilter songs" else "Nothing here yet",
                        if (type == CollectionType.LIKED) "Like songs from the player and they'll show up here." else "Songs you add will show up here.")
                }
                if (groupByGenre && showGenres) {
                    // A song with a second genre shows under both.
                    visible.flatMap { s -> genresOf(s.id).ifEmpty { listOf("Sorting…") }.map { it to s } }
                        .groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.second.size }.forEach { (g, songs) ->
                            item(key = "h:$g") {
                                Text(g, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
                            }
                            itemsIndexed(songs, key = { i, s -> "g:$g:${s.id}:$i" }) { _, s ->
                                SongRow(s, playing = current?.id == s.id, trailingInfo = null, extraActions = removeAction(s)) {
                                    c.player.playQueue(songs, songs.indexOf(s), source.copy(title = "$title · $g"))
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
                            extraActions = removeAction(s),
                        ) { c.player.playQueue(visible, i, source) }
                    }
                }
                item("footer") {
                    Text(
                        listOfNotNull(
                            page.year?.toString(),
                            "${visible.size} ${if (visible.size == 1) "song" else "songs"}" + (if (totalSec > 0) ", ${durationText(totalSec)}" else ""),
                            if (downloadedCount > 0) "$downloadedCount downloaded" else null,
                        ).joinToString(" · ") + if (loadingMore) " · loading more…" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                if (type == CollectionType.ALBUM) {
                    if (live.page.otherVersions.isNotEmpty()) item("versions") {
                        SectionHeader("Other versions")
                        ItemCarousel(live.page.otherVersions, { nav.open(it) { s -> c.player.playSingle(s.song) } })
                    }
                    live.page.artists.firstOrNull { it.id != null }?.let { a -> item("moreby") { MoreBy(a.name, a.id!!, page.id) } }
                }
            }

            // Content melts away under the bar into the page's own frost.
            ScrollEdge({ solid }, statusTop + 60.dp + 34.dp, pageHeight) { m ->
                FrostedBackdrop(frost, palette, heroHeight, heroScroll, m, parallax = if (hero) 0.5f else 0.35f)
            }
            CollectionTopBar(
                title, { solid },
                downloadIcon = when {
                    type == CollectionType.DOWNLOADS -> null
                    allDownloaded -> Icons.Rounded.DownloadDone
                    else -> Icons.Rounded.Download
                },
                downloading = downloadingCount > 0,
                onDownload = toggleDownloads,
                onShare = { sharing = true },
            ) { close ->
                if (radioId != null) DropdownMenuItem({ Text("Start radio") }, { close(); startRadio() }, leadingIcon = { Icon(Icons.Rounded.Radio, null) })
                if (savableId != null && saved != null) DropdownMenuItem(
                    { Text(if (saved == true) "Remove from library" else "Add to library") }, { close(); toggleSaved() },
                    leadingIcon = { Icon(if (saved == true) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, null) },
                )
                if (type != CollectionType.DOWNLOADS) DropdownMenuItem(
                    { Text(if (allDownloaded) "Remove downloads" else "Download all") }, { close(); toggleDownloads() },
                    leadingIcon = { Icon(if (allDownloaded) Icons.Rounded.DeleteOutline else Icons.Rounded.Download, null) },
                )
                live.page.artists.firstOrNull { it.id != null }?.let { a ->
                    DropdownMenuItem({ Text("Go to ${a.name}") }, { close(); nav.go(Routes.artist(a.id!!)) }, leadingIcon = { Icon(Icons.Rounded.Person, null) })
                }
                if (coverKey != null) DropdownMenuItem({ Text("Change picture") }, { close(); coverSheet = true }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) })
                if (type == CollectionType.PLAYLIST && live.page.owned) {
                    DropdownMenuItem({ Text("Edit details") }, { close(); editing = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Delete playlist") }, { close(); deleting = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                }
                if (type == CollectionType.DOWNLOADS) DownloadsMenuItems(close) { text, action -> confirm = text to action }
            }
        }

        if (editing) EditPlaylistDialog(title, description.orEmpty(), privacy ?: "PRIVATE", onDismiss = { editing = false }) { t, d, p ->
            editing = false
            scope.launch {
                runCatching {
                    c.ytm.editPlaylist(
                        page.id,
                        title = t.takeIf { it != title },
                        description = d.takeIf { it != description.orEmpty() },
                        privacy = p.takeIf { it != privacy },
                    )
                }.onSuccess {
                    title = t; description = d.ifBlank { null }; privacy = p
                    c.library.refreshCollections()
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete playlist?") },
        text = { Text("\"$title\" will be deleted from your YouTube Music account.") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                scope.launch {
                    c.library.deletePlaylist(PlaylistItem(page.id, title, "", page.thumbnail))
                        .onSuccess { nav.back() }
                        .onFailure { Toast.makeText(context, "Couldn't delete \"$title\": ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
    if (sharing) ShareSheet(
        when (type) {
            CollectionType.ALBUM -> ShareTarget(title, page.artists.joinToString(", ") { it.name }.ifBlank { page.subtitle }, page.releaseKind ?: "Album", art, ShareLinks.album(page.id))
            CollectionType.PLAYLIST -> ShareTarget(
                title, page.subtitle.removePrefix("Playlist • "), "Playlist", art, ShareLinks.playlist(page.id),
                playlistId = page.id.takeIf { live.page.owned }, privacy = privacy.takeIf { live.page.owned },
            )
            CollectionType.LIKED -> ShareTarget("Liked songs", "${songs.size} songs", "Playlist", art, null, songs = songs)
            CollectionType.DOWNLOADS -> ShareTarget("Downloads", "${songs.size} songs", "Playlist", art, null, songs = songs)
        },
    ) { sharing = false }
}

private fun durationText(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h} hr ${m} min"
        else -> "$m min"
    }
}

/** Play and Shuffle side by side, as wide pills. */
@Composable
private fun PlayShufflePills(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(Icons.Rounded.PlayArrow, "Play", primary = true, enabled = enabled, modifier = Modifier.weight(1f), onClick = onPlay)
        Pill(Icons.Rounded.Shuffle, "Shuffle", primary = false, enabled = enabled, modifier = Modifier.weight(1f), onClick = onShuffle)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else frostFill(1.6f)
    val fg = if (primary) scheme.onPrimary else scheme.primary
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg).graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** The bar over the page: back, then the title once scrolled ([solid] goes 0 → 1); download, share and ⋯ on the right. */
@Composable
private fun CollectionTopBar(
    title: String,
    solid: () -> Float,
    downloadIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    downloading: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    menuItems: @Composable (close: () -> Unit) -> Unit,
) {
    val nav = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.prism.music.ui.components.RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", glass = true) { nav.back() }
            Text(
                title, Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = solid() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (downloadIcon != null) {
                    if (downloading) GlassSurface(Modifier.size(42.dp), shape = RoundedCornerShape(50), elevation = 6.dp) {
                        Box(Modifier.fillMaxSize().clickable(onClickLabel = "Downloading", onClick = onDownload), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else com.prism.music.ui.components.RoundAction(downloadIcon, if (downloadIcon == Icons.Rounded.DownloadDone) "Downloaded" else "Download all", glass = true, onClick = onDownload)
                }
                com.prism.music.ui.components.RoundAction(Icons.Rounded.Share, "Share", glass = true, onClick = onShare)
                Box {
                    com.prism.music.ui.components.RoundAction(Icons.Rounded.MoreHoriz, "More", glass = true) { menu = true }
                    DropdownMenu(menu, { menu = false }) { menuItems { menu = false } }
                }
            }
        }
    }
}

/** Centred cover, title and details, Apple Music style; the frosted backdrop behind is the cover itself. */
@Composable
private fun Header(type: CollectionType, page: CollectionPage, art: String?, coverKey: String?, onEditCover: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val cover = LocalUi.current.shape(16.dp)
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.clip(cover).clickable(enabled = coverKey != null, onClickLabel = "Change picture", onClick = onEditCover)) {
            if (art != null) {
                Artwork(art, Modifier.size(248.dp).shadow(30.dp, cover), cover, size = 900)
            } else {
                Box(
                    Modifier.size(248.dp).shadow(30.dp, cover)
                        .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)), cover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (type == CollectionType.DOWNLOADS) Icons.Rounded.DownloadDone else Icons.Rounded.Favorite, null, Modifier.size(96.dp), tint = scheme.onPrimary)
                }
            }
            if (coverKey != null) GlassSurface(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp), shape = RoundedCornerShape(50)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, "Change picture", Modifier.size(20.dp)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            page.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Under the title: the artists (each opens their page) or the playlist's owner, a line of facts
 * (release type and year, explicit, visibility), the library bookmark, and the description.
 */
@Composable
private fun HeaderDetails(page: CollectionPage, centred: Boolean, privacy: String?, saved: Boolean?, onSaved: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val align = if (centred) TextAlign.Center else TextAlign.Start
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (centred) 24.dp else 20.dp, vertical = 2.dp),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        val linked = page.artists.filter { it.id != null }
        if (linked.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                page.artistThumbnail?.let {
                    Artwork(it, Modifier.size(22.dp), CircleShape, size = 120, placeholderIcon = Icons.Rounded.Person)
                    Spacer(Modifier.width(6.dp))
                }
                linked.forEachIndexed { i, a ->
                    if (i > 0) Text(if (i == linked.lastIndex) " & " else ", ", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    Text(
                        a.name, style = MaterialTheme.typography.titleSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { nav.go(Routes.artist(a.id!!)) }.padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        } else if (page.subtitle.isNotBlank()) Text(
            page.subtitle.removePrefix("Playlist • "), style = MaterialTheme.typography.titleSmall, color = scheme.primary,
            fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val facts = buildList {
            if (page.kind == CollectionKind.ALBUM) {
                page.releaseKind?.let(::add)
                page.year?.let { add(it.toString()) }
                if (page.explicit) add("Explicit")
            } else if (page.secondSubtitle.isNotBlank()) add(page.secondSubtitle.replace(" • ", " · "))
        }
        val privacyLabel = when (privacy) { "PRIVATE" -> "Private"; "UNLISTED" -> "Unlisted"; "PUBLIC" -> "Public"; else -> null }
        if (facts.isNotEmpty() || privacyLabel != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (privacyLabel != null) {
                Icon(if (privacy == "PUBLIC") Icons.Rounded.Public else if (privacy == "UNLISTED") Icons.Rounded.Link else Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                (listOfNotNull(privacyLabel) + facts).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, textAlign = align,
            )
        }
        if (saved != null) {
            Row(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(frostFill(1.4f)).clickable(onClick = onSaved)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, null, Modifier.size(18.dp), tint = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (saved) "In your library" else "Add to library", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
        }
        page.description?.takeIf { it.isNotBlank() }?.let {
            var expanded by remember { mutableStateOf(false) }
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis, textAlign = align,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.animateContentSize(),
            )
        }
    }
}

/**
 * A playlist's picture edge to edge, like an artist page: it drifts behind the scroll and frosts
 * over into the page, with the title over its foot.
 */
@Composable
private fun HeroHeader(type: CollectionType, page: CollectionPage, art: String?, scroll: () -> Int, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(height)) {
        Box(Modifier.matchParentSize().clipToBounds().dissolveBottom(0.5f)) {
            if (art != null) AsyncImage(
                hiRes(art, 1440), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = scroll() * 0.5f },
            ) else Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (type == CollectionType.DOWNLOADS) Icons.Rounded.DownloadDone else Icons.Rounded.Favorite, null, Modifier.size(120.dp), tint = scheme.onPrimary.copy(alpha = 0.85f))
            }
        }
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))
        Text(
            page.title,
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.ExtraBold,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = if (scheme.background.luminance() < 0.5f) 0.35f else 0f), blurRadius = 18f),
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "More by …": the artist's other albums and singles, fetched once this part of the page is reached. */
@Composable
private fun MoreBy(name: String, artistId: String, albumId: String) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val items by produceState<List<AlbumItem>?>(null, artistId) {
        value = runCatching { c.ytm.artist(artistId) }.getOrNull()?.shelves.orEmpty()
            .filter { s -> s.title.equals("Albums", true) || s.title.contains("single", true) }
            .flatMap { it.items }.filterIsInstance<AlbumItem>().filter { it.id != albumId }.distinctBy { it.id }.take(14)
    }
    val list = items ?: return
    if (list.isEmpty()) return
    SectionHeader("More by $name", onMore = { nav.go(Routes.artist(artistId)) })
    ItemCarousel(list, { nav.go(Routes.album(it.id)) })
}

/** Rename an owned playlist, rewrite its description, or change who can open it. */
@Composable
private fun EditPlaylistDialog(title: String, description: String, privacy: String, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var d by remember { mutableStateOf(description) }
    var p by remember { mutableStateOf(privacy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(t, { t = it.take(150) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d, { d = it.take(5000) }, label = { Text("Description") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Text("Who can open it", style = MaterialTheme.typography.labelLarge)
                com.prism.music.ui.components.Segmented(listOf("PRIVATE", "UNLISTED", "PUBLIC"), p, { when (it) { "PRIVATE" -> "Private"; "UNLISTED" -> "Unlisted"; else -> "Public" } }) { p = it }
                Text(
                    when (p) {
                        "PRIVATE" -> "Only you can see it."
                        "UNLISTED" -> "Anyone with the link can open it; it doesn't show up in search."
                        else -> "Anyone can find it and open it."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t.trim().ifBlank { title }, d.trim(), p) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose a playlist's picture: a photo, or one of its songs' covers. Kept in Prism on this phone. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(key: String, songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!c.covers.usePhoto(key, uri)) Toast.makeText(context, "Couldn't use that picture", Toast.LENGTH_SHORT).show()
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

private fun sortLabel(m: SortMode, type: CollectionType) =
    if (m == SortMode.DEFAULT && type == CollectionType.DOWNLOADS) "Recently added" else m.label

private fun sizeText(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.0f MB".format(bytes / 1_048_576.0)

/** Under the Downloads header: space used, saved lyrics, and anything still downloading. */
@Composable
private fun DownloadsInfo(songs: List<Song>, ask: (String, () -> Unit) -> Unit) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val all by c.downloads.downloads.collectAsState()
    val levels by c.downloads.lyricLevels.collectAsState()
    val working by c.downloads.lyricsWorking.collectAsState()
    val active = remember(all) { all.filter { it.value.state != Download.STATE_COMPLETED && it.value.state != Download.STATE_FAILED } }
    val bytes = remember(all) { all.values.filter { it.state == Download.STATE_COMPLETED }.sumOf { it.bytes } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${songs.size} song${if (songs.size == 1) "" else "s"} · ${sizeText(bytes)} on this phone",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        val lossless = remember(all) { all.count { it.value.lossless } }
        if (lossless > 0) Text(
            "$lossless lossless from your phone's music files",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        val ids = songs.map { it.id }
        if (ids.any { it in levels }) {
            val withLyrics = ids.count { (levels[it] ?: 0) > 0 }
            val karaoke = ids.count { (levels[it] ?: 0) >= 3 }
            Text(
                "Lyrics for $withLyrics · $karaoke word by word" + if (working > 0) " · getting more…" else "",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        if (active.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloading ${active.size}…", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            TextButton(onClick = { ask("Cancel ${active.size} downloads that haven't finished?") { c.downloads.removeMany(active.keys) } }) { Text("Cancel") }
        }
    }
}

/** Downloads' ⋯ menu entries: lyrics and bulk deletes. */
@Composable
private fun DownloadsMenuItems(close: () -> Unit, ask: (String, () -> Unit) -> Unit) {
    val c = LocalContainer.current
    val all by c.downloads.downloads.collectAsState()
    var smartIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(all.size) {
        smartIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.db.songs().smartDownloads().map { it.id }.toSet() }
    }
    val smart = all.filter { it.value.state == Download.STATE_COMPLETED && it.key in smartIds }.keys
    DropdownMenuItem({ Text("Get missing lyrics") }, { close(); c.downloads.backfillLyrics(force = true) }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null) })
    if (smart.isNotEmpty()) DropdownMenuItem({ Text("Delete smart downloads (${smart.size})") }, {
        close()
        ask("Delete the ${smart.size} songs smart downloads added? They may come back later if smart downloads stays on.") { c.downloads.removeMany(smart) }
    }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) })
    DropdownMenuItem({ Text("Delete all downloads") }, {
        close()
        val lossless = all.count { it.value.lossless }
        ask(
            "Delete all ${all.size} downloads (${sizeText(c.downloads.totalBytes)})? This frees the space straight away." +
                if (lossless > 0) " Your $lossless lossless files stay on the phone; they just leave Downloads." else ""
        ) { c.downloads.removeAll() }
    }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
}
