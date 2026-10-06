package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Search
import com.prism.music.data.prefs.HomeShortcut
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil3.compose.AsyncImage
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar

/** Last fetched YouTube home shelves, shared with the Catalogue editor. */
val homeShelfCache = MutableStateFlow<List<Shelf>>(emptyList())

fun shelfKey(title: String) = HomeSections.YT_PREFIX + title.trim().lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(bottomPadding: androidx.compose.ui.unit.Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val layoutNow by rememberUpdatedState(settings.homeLayout)
    val home = rememberLoad("home") {
        // YouTube's personalised feed reshuffles per load, so pinned shelves (e.g. "Mixed for you")
        // can land on a later page or drop out entirely. Page further for them, and keep the last
        // copy we saw rather than silently hiding a section the user turned on.
        val wanted = layoutNow.filter { it.visible && it.key.startsWith(HomeSections.YT_PREFIX) }.map { it.key }.toSet()
        val fresh = c.ytm.fullHome(pages = 3, maxPages = 8) { got -> wanted.all { k -> got.any { shelfKey(it.title) == k } } }
        val freshKeys = fresh.map { shelfKey(it.title) }.toSet()
        val kept = homeShelfCache.value.filter { shelfKey(it.title).let { k -> k in wanted && k !in freshKeys } }
        (fresh + kept).also { homeShelfCache.value = it }
    }
    val moods = rememberLoad("moods") { c.taste.rank(runCatching { c.ytm.moodsAndGenres() }.getOrDefault(emptyList())) }
    val recent by c.library.recent.collectAsState()
    val liked by c.library.liked.collectAsState()

    val shelves = (home.state as? Load.Ok)?.value ?: emptyList()
    val layout = remember(settings.homeLayout, shelves, settings.autoAddSections) {
        val known = settings.homeLayout.map { it.key }.toSet()
        val extra = if (settings.autoAddSections) shelves
            .filter { shelfKey(it.title) !in known && !it.title.equals("Quick picks", true) }
            .map { HomeSectionConfig(shelfKey(it.title), it.title) } else emptyList()
        settings.homeLayout.filter { it.visible } + extra
    }

    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val openItem: (BrowseItem) -> Unit = { nav.open(it, onSong) }

    PullToRefreshBox(
        isRefreshing = home.refreshing,
        onRefresh = { home.reload(true); moods.reload(true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item { HomeTopBar() }
            if (home.state is Load.Loading && shelves.isEmpty()) item { LoadingState() }
            (home.state as? Load.Err)?.let { e -> item { ErrorState(e.message) { home.reload(false) } } }

            layout.forEach { cfg ->
                item(key = cfg.key) {
                    when (cfg.key) {
                        HomeSections.GREETING -> GreetingSection(recent)
                        HomeSections.QUICK_PICKS -> {
                            val qp = shelves.firstOrNull { it.title.equals("Quick picks", true) }
                            val items = qp?.items?.filterIsInstance<SongItem>()?.map { it.song } ?: recent.take(16)
                            if (items.isNotEmpty()) SongSection(cfg, "Quick picks", qp?.strapline, items)
                        }
                        HomeSections.RECENT -> if (recent.isNotEmpty()) SongSection(cfg, cfg.title, null, recent.take(20))
                        HomeSections.LIKED -> if (liked.isNotEmpty()) SongSection(cfg, "Liked songs", null, liked.take(20)) { nav.go(Routes.liked()) }
                        HomeSections.DOWNLOADS -> {
                            val dls by c.downloads.downloads.collectAsState()
                            val songs = remember(dls) { c.downloads.completedSongs() }
                            if (songs.isNotEmpty()) SongSection(cfg, "Downloads", null, songs.take(20)) { nav.go(Routes.DOWNLOADS) }
                        }
                        HomeSections.REPLAY -> ReplayTeaser()
                        HomeSections.GENRES -> TopGenresSection()
                        HomeSections.MOODS -> {
                            val m = (moods.state as? Load.Ok)?.value?.picks?.take(12)
                            if (!m.isNullOrEmpty()) {
                                SectionHeader(cfg.title, strapline = "Picked for you", onMore = { nav.go(Routes.MOODS) })
                                LazyHorizontalGrid(
                                    GridCells.Fixed(2), Modifier.height(150.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(m) { mood -> MoodTile(mood, Modifier.width(150.dp).height(68.dp)) { openItem(mood) } }
                                }
                            }
                        }
                        else -> {
                            val shelf = shelves.firstOrNull { shelfKey(it.title) == cfg.key }
                            if (shelf != null) ShelfSection(cfg, shelf, openItem)
                        }
                    }
                }
            }
            if (settings.homeCustomizeButton) item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { nav.go(Routes.CATALOGUE) }.padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Dashboard, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("Customize Home", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar() {
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = settings.greetingText.trim().ifBlank {
        when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    }
    val today = remember { java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date()) }
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = ui.gap(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            com.prism.music.ui.components.Eyebrow(today)
            Text(greeting, style = com.prism.music.ui.components.pageTitleStyle(), maxLines = 2)
            if (settings.greetingShowName && settings.accountName.isNotBlank()) Text(
                settings.accountName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.prism.music.ui.components.RoundAction(Icons.Rounded.Dashboard, "Customize home") { nav.go(Routes.CATALOGUE) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { nav.go(Routes.SETTINGS) },
            contentAlignment = Alignment.Center,
        ) {
            if (settings.accountAvatar.isNotBlank()) AsyncImage(settings.accountAvatar, "Account", Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Person, "Settings", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun GreetingSection(recent: List<Song>) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    data class Tile(val title: String, val icon: ImageVector?, val image: String?, val onClick: () -> Unit)
    val tiles = buildList {
        settings.homeShortcuts.forEach { sc ->
            val (icon, route) = when (sc) {
                HomeShortcut.LIKED -> Icons.Rounded.Favorite to Routes.liked()
                HomeShortcut.DOWNLOADS -> Icons.Rounded.DownloadDone to Routes.DOWNLOADS
                HomeShortcut.REPLAY -> Icons.Rounded.History to Routes.REPLAY
                HomeShortcut.LIBRARY -> Icons.Rounded.LibraryMusic to Routes.LIBRARY
                HomeShortcut.MOODS -> Icons.Rounded.Mood to Routes.MOODS
                HomeShortcut.SEARCH -> Icons.Rounded.Search to Routes.SEARCH
                HomeShortcut.EQUALIZER -> Icons.Rounded.GraphicEq to Routes.EQ
            }
            add(Tile(sc.label, icon, null) { nav.go(route) })
        }
        settings.homePlaylists.forEach { pl ->
            add(Tile(pl.title, null, c.covers.art(pl.id, pl.thumbnail)) { nav.go(Routes.playlist(pl.id)) })
        }
        recent.distinctBy { it.album?.id ?: it.id }.take(settings.homeRecentTiles).forEach { s ->
            add(Tile(s.album?.title ?: s.title, null, s.thumbnail) {
                val albumId = s.album?.id
                if (albumId != null) nav.go(Routes.album(albumId)) else c.player.playSingle(s)
            })
        }
    }
    if (tiles.isEmpty()) return
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = ui.gap(16.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Row(
                        Modifier.weight(1f).height(58.dp).clip(ui.shape(14.dp))
                            .background(scheme.surfaceContainerHigh.copy(alpha = 0.9f))
                            .clickable(onClick = t.onClick)
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t.icon != null) Box(
                            Modifier.size(46.dp).clip(ui.shape(10.dp))
                                .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, null, Modifier.size(22.dp), tint = scheme.onPrimary) }
                        else Artwork(t.image, Modifier.size(46.dp), ui.shape(10.dp), size = 226)
                        Text(
                            t.title, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SongSection(cfg: HomeSectionConfig, title: String, strapline: String?, songs: List<Song>, onMore: (() -> Unit)? = null) {
    val c = LocalContainer.current
    SectionHeader(title, strapline = strapline, onMore = onMore)
    when (cfg.style) {
        SectionStyle.LIST, SectionStyle.GRID -> {
            LazyHorizontalGrid(
                GridCells.Fixed(4), Modifier.height(4 * 64.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                items(songs.size) { i ->
                    Box(Modifier.width(330.dp)) {
                        SongRow(songs[i]) { c.player.playQueue(songs, i, QueueSource(QueueKind.OTHER, title)) }
                    }
                }
            }
        }
        SectionStyle.HERO -> HeroRow(songs.map { SongItem(it) }) { item ->
            c.player.playQueue(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0), QueueSource(QueueKind.OTHER, title))
        }
        SectionStyle.CAROUSEL -> ItemCarousel(songs.map { SongItem(it) }, { item ->
            c.player.playQueue(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0), QueueSource(QueueKind.OTHER, title))
        })
    }
}

@Composable
private fun ShelfSection(cfg: HomeSectionConfig, shelf: Shelf, open: (BrowseItem) -> Unit) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    SectionHeader(shelf.title, strapline = shelf.strapline, onMore = shelf.moreBrowseId?.let { id -> { nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) } })
    val songs = shelf.items.filterIsInstance<SongItem>().map { it.song }
    val allSongs = songs.size == shelf.items.size
    when {
        cfg.style == SectionStyle.HERO -> HeroRow(shelf.items, open)
        cfg.style == SectionStyle.LIST && allSongs -> LazyHorizontalGrid(
            GridCells.Fixed(4), Modifier.height(4 * 64.dp), contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(songs.size) { i ->
                Box(Modifier.width(330.dp)) { SongRow(songs[i]) { c.player.playQueue(songs, i, QueueSource(QueueKind.OTHER, shelf.title)) } }
            }
        }
        cfg.style == SectionStyle.GRID -> LazyHorizontalGrid(
            GridCells.Fixed(2), Modifier.height(2 * 220.dp), contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(shelf.items) { item -> ItemCard(item, width = 140.dp) { open(item) } }
        }
        else -> ItemCarousel(shelf.items, open)
    }
}

@Composable
private fun HeroRow(items: List<BrowseItem>, open: (BrowseItem) -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.take(10)) { item ->
            Box(
                Modifier.width(300.dp).aspectRatio(1.25f).clip(ui.shape(24.dp)).clickable { open(item) },
            ) {
                Artwork(LocalContainer.current.covers.art(item), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), size = 900)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                Row(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.subtitle, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayTeaser() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val ms by produceState(-1L) {
        val start = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
        value = c.db.plays().totalMs(start, System.currentTimeMillis())
    }
    if (ms < 60_000) return
    val total = listenTotal(ms)
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val ui = com.prism.music.ui.theme.LocalUi.current
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = ui.gap(16.dp)).fillMaxWidth().height(150.dp).clip(ui.shape(26.dp))
            .background(Brush.linearGradient(replayGradient()))
            .clickable { nav.go(Routes.REPLAY) }
            .padding(22.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text("REPLAY $year", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            Text(total.full,Modifier.toggleListenUnit(), color = Color.White, style = MaterialTheme.typography.displaySmall)
            Text("See your top songs, artists and genres", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun TopGenresSection() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val liked by c.library.liked.collectAsState()
    var genres by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(liked.size) {
        val sample = liked.take(150)
        val map = c.meta.cachedGenres(sample)
        genres = map.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(8)
    }
    if (genres.isEmpty()) return
    SectionHeader("Your top genres", strapline = "From your liked songs")
    val palette = listOf(0xFF7C5CFF, 0xFFFF3B5C, 0xFF00B4D8, 0xFF2EC27E, 0xFFFF6B35, 0xFFE056FD, 0xFFFFB627, 0xFF3A86FF)
    val ui = com.prism.music.ui.theme.LocalUi.current
    LazyHorizontalGrid(
        GridCells.Fixed(2), Modifier.height(140.dp), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(genres.size) { i ->
            val (g, n) = genres[i]
            val col = Color(palette[i % palette.size])
            Box(
                Modifier.width(160.dp).height(64.dp).clip(ui.tile)
                    .background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f))))
                    .clickable { nav.go(Routes.liked(g)) }.padding(12.dp),
            ) {
                Text(g, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopStart))
                Text("$n songs", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
    }
}
