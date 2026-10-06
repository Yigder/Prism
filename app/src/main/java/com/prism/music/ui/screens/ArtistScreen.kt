package com.prism.music.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SongActionsSheet
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.ArtistType
import com.prism.music.ui.theme.ArtistTypography
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ArtistScreen(id: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val load = rememberLoad("artist:$id") { c.ytm.artist(id) }
    when (val s = load.state) {
        Load.Loading -> Box(Modifier.fillMaxSize().statusBarsPadding()) { LoadingState(Modifier.align(Alignment.Center)) }
        is Load.Err -> Box(Modifier.fillMaxSize().statusBarsPadding()) { ErrorState(s.message) { load.reload(false) } }
        is Load.Ok -> ArtistContent(s.value, bottomPadding)
    }
}

/** An artist page's shelves, sorted into Apple Music's sections. */
private class ArtistSections(page: ArtistPage) {
    private val rest = page.shelves.toMutableList()
    private fun take(match: (Shelf) -> Boolean): Shelf? = rest.firstOrNull(match)?.also { rest.remove(it) }
    private fun Shelf.t() = title.lowercase()

    val topSongs = take { s -> s.items.isNotEmpty() && s.items.all { it is SongItem && !it.song.isVideo } && s.t().contains("song") }
    val albums = take { it.t() == "albums" }
    val singles = take { it.t().contains("single") || it.t() == "eps" }
    val videos = take { it.t() == "videos" || it.t().contains("music video") }
    val live = take { it.t().contains("live") }
    val featured = take { it.t().contains("featured on") || it.t().contains("appears on") }
    val playlists = take { it.t().startsWith("playlists") }
    val similar = take { s -> s.items.all { it is ArtistItem } && (s.t().contains("like") || s.t().contains("similar") || s.t().contains("related")) }
    val others: List<Shelf> = rest.toList()
}

private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
private fun yearOf(item: BrowseItem): Int = yearRegex.find(item.subtitle)?.value?.toIntOrNull() ?: 0

@Composable
private fun ArtistContent(page: ArtistPage, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val open: (BrowseItem) -> Unit = { nav.open(it, onSong) }
    val sections = remember(page) { ArtistSections(page) }
    val list = rememberLazyListState()
    val accent = rememberAccent(page.thumbnail) ?: MaterialTheme.colorScheme.primary

    // The name's typeface depends on the artist's genre; it fades in once that's known (cached after the first visit).
    val typeInfo by produceState<Pair<Boolean, String?>>(false to null, page.id) {
        value = true to withTimeoutOrNull(1_500) { runCatching { c.meta.artistGenre(page.name) }.getOrNull() }
    }
    val (typeReady, genre) = typeInfo
    val type = remember(page.id, genre) { ArtistTypography.forArtist(page.id, genre, ArtistTypography.parseAudience(page.subscribers)) }

    fun playList(playlistId: String?, title: String, videoId: String? = null, params: String? = null) {
        if (playlistId == null) return
        scope.launch {
            runCatching { c.ytm.next(videoId, playlistId, params) }.onSuccess { r ->
                c.queue.counterparts.putAll(r.counterparts)
                c.player.playQueue(r.songs, 0, QueueSource(QueueKind.RADIO, title))
            }
        }
    }
    val shuffle = { playList(page.shufflePlaylistId, page.name, page.shuffleVideoId, page.shuffleParams) }
    val mix = { playList(page.radioPlaylistId, "${page.name} Mix", page.radioVideoId, page.radioParams) }
    fun more(shelf: Shelf): (() -> Unit)? = shelf.moreBrowseId?.let { id ->
        { if (id.startsWith("VL")) nav.go(Routes.playlist(id)) else nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) }
    }

    val heroFraction = 1.08f
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPadding + 32.dp)) {
            item("hero") {
                Hero(page, type, typeReady, accent, heroFraction, list.firstVisibleItemIndex == 0, { list.firstVisibleItemScrollOffset }, shuffle)
            }
            latestRelease(sections)?.let { latest ->
                item("latest") { LatestRelease(latest) { open(latest) } }
            }
            sections.topSongs?.let { shelf ->
                item("top") {
                    AppleHeader("Top Songs", more(shelf))
                    TopSongsGrid(shelf.items.filterIsInstance<SongItem>().map { it.song }, shelf.title)
                }
            }
            sections.albums?.let { s -> item("albums") { AppleHeader("Albums", more(s)); CardRow(s.items, 168.dp, open = open) } }
            sections.videos?.let { s -> item("videos") { AppleHeader("Music Videos", more(s)); CardRow(s.items, 260.dp, aspect = 16f / 9f, open = open) } }
            sections.singles?.let { s -> item("singles") { AppleHeader("Singles & EPs", more(s)); CardRow(s.items, 140.dp, open = open) } }
            sections.live?.let { s -> item("live") { AppleHeader("Live", more(s)); CardRow(s.items, 260.dp, aspect = 16f / 9f, open = open) } }
            sections.featured?.let { s -> item("featured") { AppleHeader("Appears On", more(s)); CardRow(s.items, 150.dp, open = open) } }
            sections.playlists?.let { s -> item("playlists") { AppleHeader("Artist Playlists", more(s)); CardRow(s.items, 150.dp, open = open) } }
            sections.others.forEach { shelf -> shelfItems(shelf, open) }
            sections.similar?.let { s -> item("similar") { AppleHeader("Similar Artists", more(s)); CardRow(s.items, 128.dp, round = true, open = open) } }
            item("about") { About(page, genre) }
        }

        // Top bar: fades to solid with the artist's name once the hero has scrolled away.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val heroPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() } / heroFraction
        val solid by remember {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else ((list.firstVisibleItemScrollOffset - heroPx * 0.78f) / (heroPx * 0.14f)).coerceIn(0f, 1f)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f * solid))
                .statusBarsPadding().height(60.dp),
        ) {
            Text(
                page.name, Modifier.align(Alignment.Center).padding(horizontal = 72.dp).graphicsLayer { alpha = solid },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            com.prism.music.ui.components.RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.align(Alignment.CenterStart).padding(start = 12.dp), glass = true) { nav.back() }
            if (page.radioPlaylistId != null) com.prism.music.ui.components.RoundAction(
                Icons.Rounded.Radio, "Start ${page.name} Mix", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), glass = true, onClick = mix,
            )
        }
    }
}

/** The newest release across Albums and Singles (albums win a tie). */
private fun latestRelease(s: ArtistSections): AlbumItem? {
    val album = s.albums?.items?.firstOrNull() as? AlbumItem
    val single = s.singles?.items?.firstOrNull() as? AlbumItem
    return when {
        album == null -> single
        single == null -> album
        yearOf(single) > yearOf(album) -> single
        else -> album
    }
}

@Composable
private fun Hero(
    page: ArtistPage, type: ArtistType, typeReady: Boolean, accent: Color, fraction: Float,
    atTop: Boolean, scroll: () -> Int, onPlay: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().aspectRatio(1f / fraction).clip(RoundedCornerShape(0.dp))) {
        AsyncImage(
            hiRes(page.thumbnail, 2880), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Gentle parallax: the photo drifts at half the scroll speed.
                if (atTop) translationY = scroll() * 0.5f
            },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.18f to Color.Transparent, 0.55f to Color.Transparent, 1f to bg)))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                val alpha by animateFloatAsState(if (typeReady) 1f else 0f, tween(350), label = "name")
                ArtistName(page.name, type, Modifier.graphicsLayer { this.alpha = alpha })
                page.subscribers?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            if (page.shufflePlaylistId != null) Box(
                Modifier.size(56.dp).clip(CircleShape).background(accent).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(32.dp), tint = if (accent.luminance() > 0.6f) Color.Black else Color.White)
            }
        }
    }
}

/** The artist's name in their typeface, shrunk until it fits in two lines. */
@Composable
private fun ArtistName(name: String, type: ArtistType, modifier: Modifier = Modifier) {
    var scale by remember(name, type) { mutableFloatStateOf(1f) }
    var fits by remember(name, type) { mutableStateOf(false) }
    val size = type.size * scale
    Text(
        if (type.caps) name.uppercase() else name,
        modifier.drawWithContent { if (fits) drawContent() },
        maxLines = 2,
        softWrap = true,
        onTextLayout = { r ->
            if ((r.hasVisualOverflow || r.lineCount > 2 || (r.lineCount == 2 && name.length < 12)) && scale > 0.45f) scale *= 0.9f else fits = true
        },
        style = TextStyle(
            fontFamily = type.family,
            fontWeight = type.weight,
            fontStyle = if (type.italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = size.sp,
            lineHeight = (size * type.lineHeight).sp,
            letterSpacing = type.tracking.em,
            color = MaterialTheme.colorScheme.onSurface,
            shadow = Shadow(Color.Black.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.35f else 0f), blurRadius = 18f),
        ),
    )
}

@Composable
private fun AppleHeader(title: String, onMore: (() -> Unit)?) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = ui.gap(26.dp), bottom = ui.gap(10.dp))
            .clip(RoundedCornerShape(8.dp))
            .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        if (onMore != null) Icon(Icons.Rounded.ChevronRight, "See all", Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LatestRelease(item: AlbumItem, onClick: () -> Unit) {
    val c = LocalContainer.current
    // Track count and running time come from the release itself.
    val details by produceState<String?>(null, item.id) {
        value = runCatching { c.ytm.album(item.id).songs }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { songs ->
            val min = (songs.sumOf { it.durationSec } + 30) / 60
            listOfNotNull(if (songs.size == 1) "1 song" else "${songs.size} songs", min.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · ")
        }
    }
    val kind = item.subtitle.split(" • ").firstOrNull { it in setOf("Single", "EP", "Album") } ?: "Album"
    val year = yearOf(item).takeIf { it > 0 }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Latest Release", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(item.thumbnail, Modifier.size(116.dp), com.prism.music.ui.theme.LocalUi.current.art, size = 544)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(year?.toString(), kind).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

/** Apple's Top Songs: columns of four that page sideways, the next column peeking in. */
@Composable
private fun TopSongsGrid(songs: List<Song>, title: String) {
    val c = LocalContainer.current
    val current by c.player.currentSong.collectAsState()
    val columns = remember(songs) { songs.chunked(4) }
    val state = rememberLazyListState()
    val width = (LocalConfiguration.current.screenWidthDp.dp - 20.dp - if (columns.size > 1) 36.dp else 20.dp)
    var menuFor by remember { mutableStateOf<Song?>(null) }
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(columns.size) { col ->
            Column(Modifier.width(width)) {
                columns[col].forEachIndexed { i, s ->
                    val index = col * 4 + i
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { c.player.playQueue(songs, index, QueueSource(QueueKind.OTHER, title)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(s.thumbnail, Modifier.size(48.dp), com.prism.music.ui.theme.LocalUi.current.smallArt, size = 226)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                                color = if (current?.id == s.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                s.album?.title ?: s.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { menuFor = s }, Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.MoreHoriz, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (i < columns[col].lastIndex) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
    menuFor?.let { s -> SongActionsSheet(s) { menuFor = null } }
}


/** A row of Apple-style cards: artwork, then a title and a quiet second line (the year for releases). */
@Composable
private fun CardRow(items: List<BrowseItem>, width: Dp, aspect: Float = 1f, round: Boolean = false, open: (BrowseItem) -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.id + it.title }) { item ->
            Column(
                Modifier.width(width).clip(ui.shape(10.dp)).clickable { open(item) },
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Artwork(
                    LocalContainer.current.covers.art(item), Modifier.fillMaxWidth().aspectRatio(aspect),
                    if (round) CircleShape else ui.art,
                    size = if (aspect > 1f) 720 else 544,
                    placeholderIcon = if (item is ArtistItem) Icons.Rounded.Person else Icons.Rounded.PlayArrow,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start,
                )
                val second = when (item) {
                    is AlbumItem -> yearOf(item).takeIf { it > 0 }?.toString() ?: item.subtitle
                    is ArtistItem -> ""
                    is PlaylistItem -> item.subtitle.removePrefix("Playlist • ")
                    else -> item.subtitle
                }
                if (second.isNotBlank()) Text(
                    second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun About(page: ArtistPage, genre: String?) {
    val bio = page.description?.takeIf { it.isNotBlank() }
    val facts = listOfNotNull(
        genre?.let { "Genre" to it },
        page.subscribers?.let { "Listeners" to it.replace(" monthly audience", " monthly") },
        page.views?.let { "Views on YouTube" to it.removeSuffix(" views") },
    )
    if (bio == null && facts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Text(
        "About ${page.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp),
    )
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { expanded = !expanded }
            .padding(16.dp).animateContentSize(),
    ) {
        if (bio != null) {
            Text(
                bio, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
            )
            if (!expanded) Text("MORE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
        facts.forEachIndexed { i, (k, v) ->
            if (bio != null || i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(v, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A lively colour from the artist's photo, for the play button. */
@Composable
private fun rememberAccent(url: String?): Color? {
    val context = LocalContext.current
    val color by produceState<Color?>(null, url) {
        val u = url ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(u, 200)).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch)?.rgb?.let { Color(it) }
            }.getOrNull()
        }
    }
    return color
}

fun androidx.compose.foundation.lazy.LazyListScope.shelfItems(shelf: Shelf, open: (BrowseItem) -> Unit) {
    val songs = shelf.items.filterIsInstance<SongItem>().map { it.song }
    if (songs.size == shelf.items.size && songs.none { it.isVideo }) {
        item(key = "h:" + shelf.title) {
            val nav = LocalNavigator.current
            SectionHeader(shelf.title, onMore = shelf.moreBrowseId?.let { id ->
                { if (id.startsWith("VL")) nav.go(Routes.playlist(id)) else nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) }
            })
        }
        items(songs.take(6), key = { "s:" + shelf.title + it.id }) { s ->
            val c = LocalContainer.current
            SongRow(s) { c.player.playQueue(songs, songs.indexOf(s), QueueSource(QueueKind.OTHER, shelf.title)) }
        }
    } else {
        item(key = "c:" + shelf.title) {
            val nav = LocalNavigator.current
            SectionHeader(shelf.title, strapline = shelf.strapline, onMore = shelf.moreBrowseId?.let { id -> { nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) } })
            ItemCarousel(shelf.items, open)
        }
    }
}

@Composable
fun BrowseScreen(id: String, params: String?, title: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    // Genre and mood pages, minus regional shelves and playlists (Prism keeps to mostly English-language music).
    val load = rememberLoad("browse:$id:$params") {
        c.ytm.browseShelves(id, params).filter { !com.prism.music.data.meta.Genres.isRegional(it.title) }.map { sh ->
            sh.copy(items = sh.items.filter { i ->
                (i !is com.prism.music.data.model.MoodItem && i !is com.prism.music.data.model.PlaylistItem) || !com.prism.music.data.meta.Genres.isRegional(i.title)
            })
        }.filter { it.items.isNotEmpty() }
    }
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    com.prism.music.ui.components.SubPage(title.ifBlank { "Browse" }, bottomPadding) {
        when (val s = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(s.message) { load.reload(false) } }
            is Load.Ok -> s.value.forEach { shelf -> shelfItems(shelf) { nav.open(it, onSong) } }
        }
    }
}
