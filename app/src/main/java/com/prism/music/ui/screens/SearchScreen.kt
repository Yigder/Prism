package com.prism.music.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.TopSearch
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.playable
import com.prism.music.ui.components.rememberPlayMenu
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun history(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)

@Composable
fun SearchScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    // What the results are for: follows the typing, a moment after it pauses.
    var live by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.TOP) }
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember { mutableStateListOf<String>() }
    val recents = remember {
        mutableStateListOf<String>().apply { addAll(history(context).getString("q", "")!!.split("\n").filter { it.isNotBlank() }) }
    }
    var top by remember { mutableStateOf<Load<TopSearch>?>(null) }
    var list by remember { mutableStateOf<Load<List<BrowseItem>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var continuation by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun saveQuery(q: String) {
        val v = q.trim()
        if (v.isBlank()) return
        recents.remove(v); recents.add(0, v)
        while (recents.size > 12) recents.removeAt(recents.lastIndex)
        history(context).edit().putString("q", recents.joinToString("\n")).apply()
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        query = q; live = q.trim()
        focus.clearFocus()
        saveQuery(q)
    }

    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions.clear(); live = ""; return@LaunchedEffect }
        delay(140)
        val s = runCatching { c.ytm.searchSuggestions(query) }.getOrDefault(emptyList())
        suggestions.clear(); suggestions.addAll(s.take(3))
    }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(320)
        live = query.trim()
    }
    LaunchedEffect(live, filter) {
        if (live.isBlank()) { top = null; list = null; return@LaunchedEffect }
        loading = true
        // Earlier results stay on screen until these land, so nothing flashes.
        if (filter == SearchFilter.TOP) {
            if (top == null) top = Load.Loading
            top = runCatching { c.ytm.searchTop(live) }.fold({ Load.Ok(it) }, { Load.Err(it.message ?: "Search failed") })
        } else {
            if (list == null) list = Load.Loading
            list = runCatching { c.ytm.search(live, filter) }
                .fold({ continuation = it.continuation; Load.Ok(it.items) }, { Load.Err(it.message ?: "Search failed") })
        }
        loading = false
    }

    val moods = rememberLoad("moods") { c.taste.rank(runCatching { c.ytm.moodsAndGenres() }.getOrDefault(emptyList())) }
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val open: (BrowseItem) -> Unit = { item -> saveQuery(live); focus.clearFocus(); nav.open(item, onSong) }

    val ui = com.prism.music.ui.theme.LocalUi.current
    Column(Modifier.fillMaxSize()) {
        // The title steps aside while you type, leaving the results more room.
        androidx.compose.animation.AnimatedVisibility(!focused && query.isBlank()) {
            com.prism.music.ui.components.ScreenHeader("Search", Modifier.padding(bottom = 2.dp))
        }
        if (focused || query.isNotBlank()) Spacer(Modifier.statusBarsPadding())
        GlassSurface(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(54.dp), shape = ui.shape(27.dp), elevation = 6.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Songs, albums, artists, lyrics…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
                    )
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; live = ""; top = null; list = null; focusRequester.requestFocus() }) {
                    Icon(Icons.Rounded.Close, "Clear")
                }
            }
        }

        if (query.isBlank()) {
            Browse(recents, moods, bottomPadding, onFill = { query = it }, onPick = { submit(it) }, onRemove = { r ->
                recents.remove(r); history(context).edit().putString("q", recents.joinToString("\n")).apply()
            }, onMood = { nav.open(it, onSong) })
            return@Column
        }

        com.prism.music.ui.components.ChipRow(
            SearchFilter.entries, { it == filter }, { it.label },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp),
        ) { filter = it }
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        }
        // While typing, a few completions sit above the live results.
        AnimatedVisibility(focused && suggestions.isNotEmpty() && suggestions.firstOrNull()?.equals(query.trim(), true) != true) {
            Column {
                suggestions.forEach { s -> SuggestionRow(Icons.Rounded.Search, s, onFill = { query = it }, dense = true) { submit(s) } }
            }
        }

        if (filter == SearchFilter.TOP) {
            when (val r = top) {
                null, Load.Loading -> LoadingState()
                is Load.Err -> ErrorState(r.message)
                is Load.Ok -> TopResults(r.value, bottomPadding, onSeeAll = { filter = it }, open = open, onSongs = { songs, i ->
                    saveQuery(live); focus.clearFocus()
                    c.player.playQueue(songs, i, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "Search"))
                }, onPlay = { item ->
                    saveQuery(live)
                    focus.clearFocus()
                    scope.launch { playTop(c, item) }
                })
            }
        } else when (val r = list) {
            null, Load.Loading -> LoadingState()
            is Load.Err -> ErrorState(r.message)
            is Load.Ok -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
                items(r.value, key = { it.id + it.title }) { item -> ResultRow(item) { open(item) } }
                if (continuation != null) item {
                    LaunchedEffect(continuation) {
                        val token = continuation ?: return@LaunchedEffect
                        runCatching { c.ytm.search(live, filter, token) }.onSuccess { more ->
                            continuation = more.continuation
                            list = Load.Ok(r.value + more.items.filter { n -> r.value.none { it.id == n.id } })
                        }.onFailure { continuation = null }
                    }
                    LoadingState()
                }
            }
        }
    }
}

/** The top card's play button: plays the song, the album or playlist from the start, or shuffles the artist. */
private suspend fun playTop(c: com.prism.music.AppContainer, item: BrowseItem) {
    runCatching {
        when (item) {
            is SongItem -> c.player.playSingle(item.song)
            is AlbumItem -> c.player.playQueue(c.ytm.album(item.id).songs, 0, QueueSource(QueueKind.ALBUM, item.title))
            is PlaylistItem -> c.player.playQueue(c.ytm.playlist(item.id).songs, 0, QueueSource(QueueKind.PLAYLIST, item.title))
            is ArtistItem -> {
                val a = c.ytm.artist(item.id)
                val list = a.shufflePlaylistId ?: a.radioPlaylistId ?: return
                val r = c.ytm.next(a.shuffleVideoId ?: a.radioVideoId, list, a.shuffleParams ?: a.radioParams)
                c.queue.counterparts.putAll(r.counterparts)
                c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, a.name))
            }
            else -> Unit
        }
    }
}

@Composable
private fun Browse(
    recents: List<String>,
    moods: com.prism.music.ui.Loaded<com.prism.music.data.RankedCategories>,
    bottomPadding: Dp,
    onFill: (String) -> Unit,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMood: (com.prism.music.data.model.MoodItem) -> Unit,
) {
    val nav = LocalNavigator.current
    LazyVerticalGrid(
        GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (recents.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) { Text("Recent searches", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
            items(recents.take(6), span = { GridItemSpan(2) }) { r ->
                SuggestionRow(Icons.Rounded.History, r, onFill = onFill, onRemove = { onRemove(r) }) { onPick(r) }
            }
        }
        val ranked = (moods.state as? Load.Ok)?.value
        if (ranked != null) {
            // "For you" is our own ranking (blended with YouTube's personal picks when signed in).
            val groups = listOf("For you" to ranked.picks.take(8)) +
                ranked.sections.filterNot { it.first.contains("for you", true) }.map { it.first to it.second.take(6) }
            groups.forEach { (title, m) ->
                if (m.isEmpty()) return@forEach
                item(span = { GridItemSpan(2) }) {
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.go(com.prism.music.ui.Routes.MOODS) }.padding(top = 14.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "See all", Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(m, key = { title + it.id + it.params }) { mood -> MoodTile(mood, Modifier.fillMaxWidth().height(84.dp)) { onMood(mood) } }
            }
        }
    }
}

/** The best match up top, then songs, albums, artists, playlists and videos, best-matching first. */
@Composable
private fun TopResults(
    r: TopSearch, bottomPadding: Dp, onSeeAll: (SearchFilter) -> Unit, open: (BrowseItem) -> Unit,
    onSongs: (List<com.prism.music.data.model.Song>, Int) -> Unit, onPlay: (BrowseItem) -> Unit,
) {
    if (r.isEmpty) { ErrorState("No results"); return }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp)) {
        r.top?.let { t -> item("top") { TopCard(t, { open(t) }, { onPlay(t) }) } }
        r.sections.forEach { (f, items) ->
            item("h:${f.name}") { SectionHeader(f.label, onMore = { onSeeAll(f) }) }
            when (f) {
                SearchFilter.SONGS -> {
                    val songs = items.filterIsInstance<SongItem>().map { it.song }
                    items(songs.take(4), key = { "s:" + it.id }) { s ->
                        SongRow(s) { onSongs(songs, songs.indexOf(s)) }
                    }
                }
                else -> item("c:${f.name}") {
                    ItemCarousel(items, open, cardWidth = if (f == SearchFilter.VIDEOS) 150.dp else 140.dp)
                }
            }
        }
    }
}

@Composable
private fun TopCard(item: BrowseItem, onClick: () -> Unit, onPlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val kind = when (item) {
        is ArtistItem -> "Artist"
        is AlbumItem -> item.subtitle.substringBefore(" • ").takeIf { it in setOf("Album", "Single", "EP") } ?: "Album"
        is PlaylistItem -> "Playlist"
        is SongItem -> if (item.song.isVideo) "Video" else "Song"
        else -> ""
    }
    val detail = when (item) {
        is SongItem -> item.song.artistText
        is ArtistItem -> item.subtitle.removePrefix("Artist • ")
        else -> item.subtitle.split(" • ").drop(1).joinToString(" · ").ifBlank { item.subtitle }
    }
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(ui.card)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(scheme.surfaceContainerHigh, scheme.primaryContainer.copy(alpha = 0.55f))))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            LocalContainer.current.covers.art(item), Modifier.size(96.dp), if (item is ArtistItem) CircleShape else ui.art, size = 544,
            placeholderIcon = if (item is ArtistItem) Icons.Rounded.Person else Icons.Rounded.Album,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("TOP RESULT", style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.Bold)
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(kind, detail).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledIconButton(onClick = onPlay, Modifier.size(48.dp)) { Icon(Icons.Rounded.PlayArrow, "Play") }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onFill: (String) -> Unit,
    onRemove: (() -> Unit)? = null, dense: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = if (dense) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(if (dense) 18.dp else 24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) IconButton(onClick = onRemove, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) }
        else IconButton(onClick = { onFill(text) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.NorthWest, "Fill", Modifier.size(18.dp)) }
    }
}

@Composable
fun ResultRow(item: BrowseItem, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    if (item is SongItem) {
        SongRow(item.song, trailingInfo = if (item.song.isVideo) "Video" else null, onClick = onClick)
        return
    }
    val ui = com.prism.music.ui.theme.LocalUi.current
    val menu = rememberPlayMenu()
    val longPress = onLongClick ?: item.playable()?.let { p -> { menu(p) } }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = longPress).padding(horizontal = 16.dp, vertical = ui.gap(7.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            LocalContainer.current.covers.art(item), Modifier.size(56.dp),
            if (item is ArtistItem) CircleShape else ui.smallArt, size = 226,
            placeholderIcon = when (item) {
                is ArtistItem -> Icons.Rounded.Person
                is AlbumItem -> Icons.Rounded.Album
                else -> Icons.AutoMirrored.Rounded.QueueMusic
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle.ifBlank { when (item) { is ArtistItem -> "Artist"; is AlbumItem -> "Album"; is PlaylistItem -> "Playlist"; else -> "" } },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
