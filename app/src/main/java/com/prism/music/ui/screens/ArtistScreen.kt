package com.prism.music.ui.screens

import android.app.Activity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.prism.music.data.FavoriteArtist
import com.prism.music.data.ShareLinks
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
import com.prism.music.ui.components.ArtistSignature
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.ArtworkAccent
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.FrostedBackdrop
import com.prism.music.ui.components.FrostedPanel
import com.prism.music.ui.components.ScrollEdge
import com.prism.music.ui.components.ItemCarousel
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.ShareSheet
import com.prism.music.ui.components.ShareTarget
import com.prism.music.ui.components.SignatureSample
import com.prism.music.ui.components.SongActionsSheet
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.dissolveBottom
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.playable
import com.prism.music.ui.components.rememberArtPalette
import com.prism.music.ui.components.rememberFrost
import com.prism.music.ui.components.rememberPlayMenu
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.ArtistTypography
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch
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

/**
 * An artist's page after Apple Music's (iOS 27): their photo fills the top and frosts over into
 * a backdrop made of its colours, with their name set in a signature typeface, centred, and the
 * Info, Play, Shuffle and Favourite buttons beneath it. Their latest release gets a card of its
 * own; the rest of the page sits on the frosted glass.
 */
@Composable
private fun ArtistContent(page: ArtistPage, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    val open: (BrowseItem) -> Unit = { nav.open(it, onSong) }
    val sections = remember(page) { ArtistSections(page) }
    val list = rememberLazyListState()
    val palette = rememberArtPalette(page.thumbnail)
    val frost = rememberFrost(page.thumbnail)
    val dark = LocalIsDark.current

    // The name's typeface depends on the artist's genre; it's written on once that's known (cached after the first visit).
    val typeInfo by produceState<Pair<Boolean, String?>>(false to null, page.id) {
        value = true to withTimeoutOrNull(1_500) { runCatching { c.meta.artistGenre(page.name) }.getOrNull() }
    }
    val (typeReady, genre) = typeInfo
    val signatures by c.artistPrefs.signatures.collectAsState()
    val type = remember(page.id, genre, signatures[page.id]) {
        ArtistTypography.forArtist(page.id, genre, ArtistTypography.parseAudience(page.subscribers), signatures[page.id])
    }

    // Favourite: starred here, or already subscribed on YouTube Music.
    val favorites by c.artistPrefs.favorites.collectAsState()
    var subscribed by remember(page.id) { mutableStateOf(page.subscribed) }
    val favorite = subscribed || favorites.any { it.id == page.id }
    val toggleFavorite = {
        val on = !favorite
        subscribed = on
        c.library.setFavoriteArtist(FavoriteArtist(page.id, page.name, page.thumbnail), page.channelId, on)
    }

    var showInfo by remember { mutableStateOf(false) }
    var showSignature by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

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
    // Play: their top songs in order (the whole list behind "See all" when it loads quickly).
    val playTop: () -> Unit = {
        val shelf = sections.topSongs
        val quick = shelf?.items?.filterIsInstance<SongItem>()?.map { it.song }.orEmpty()
        scope.launch {
            val full = shelf?.moreBrowseId?.takeIf { it.startsWith("VL") }?.let { id ->
                withTimeoutOrNull(3_000) { runCatching { c.ytm.playlist(id).songs }.getOrNull() }
            }
            val songs = full?.takeIf { it.size >= quick.size } ?: quick
            if (songs.isNotEmpty()) c.player.playQueue(songs, 0, QueueSource(QueueKind.PLAYLIST, "${page.name}: Top songs"))
            else shuffle()
        }
    }
    fun more(shelf: Shelf): (() -> Unit)? = shelf.moreBrowseId?.let { id ->
        { if (id.startsWith("VL")) nav.go(Routes.playlist(id)) else nav.go(Routes.browse(id, shelf.moreParams, shelf.title)) }
    }

    val config = LocalConfiguration.current
    val heroHeight = minOf(config.screenWidthDp.dp * 1.1f, config.screenHeightDp.dp * 0.6f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroPx = with(density) { heroHeight.toPx() }
    val heroScroll = { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else heroPx }
    // How far the bar has taken over from the hero (0 at the top of the page, 1 once the photo has gone).
    val solid by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f
            else ((list.firstVisibleItemScrollOffset - heroPx * 0.72f) / (heroPx * 0.16f)).coerceIn(0f, 1f)
        }
    }
    var pageHeight by remember { mutableStateOf(0.dp) }
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    ArtworkAccent(palette) {
        Box(Modifier.fillMaxSize().onSizeChanged { pageHeight = with(density) { it.height.toDp() } }) {
            FrostedBackdrop(frost, palette, heroHeight, heroScroll)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPadding + 32.dp)) {
                item("hero") {
                    Hero(
                        page, type, typeReady, heroHeight, { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset else 0 },
                        favorite = favorite,
                        onInfo = { showInfo = true },
                        onPlay = playTop,
                        onShuffle = shuffle,
                        onFavorite = toggleFavorite,
                        onSignature = { showSignature = true },
                    )
                }
                latestRelease(sections)?.let { latest ->
                    item("latest") { Spotlight(latest, onOpen = { open(latest) }) }
                }
                sections.topSongs?.let { shelf ->
                    item("top") {
                        AppleHeader("Top Songs", more(shelf))
                        TopSongsGrid(shelf.items.filterIsInstance<SongItem>().map { it.song }, "${page.name}: Top songs")
                    }
                }
                sections.albums?.let { s -> item("albums") { AppleHeader("Albums", more(s)); CardRow(s.items, 168.dp, open = open) } }
                sections.singles?.let { s -> item("singles") { AppleHeader("Singles & EPs", more(s)); CardRow(s.items, 140.dp, open = open) } }
                sections.videos?.let { s -> item("videos") { AppleHeader("Music Videos", more(s)); CardRow(s.items, 260.dp, aspect = 16f / 9f, open = open) } }
                sections.live?.let { s -> item("live") { AppleHeader("Live", more(s)); CardRow(s.items, 260.dp, aspect = 16f / 9f, open = open) } }
                sections.featured?.let { s -> item("featured") { AppleHeader("Appears On", more(s)); CardRow(s.items, 150.dp, open = open) } }
                sections.playlists?.let { s -> item("playlists") { AppleHeader("Artist Playlists", more(s)); CardRow(s.items, 150.dp, open = open) } }
                sections.others.forEach { shelf -> shelfItems(shelf, open) }
                sections.similar?.let { s -> item("similar") { AppleHeader("Similar Artists", more(s)); CardRow(s.items, 128.dp, round = true, open = open) } }
                item("about") { AboutCard(page, genre) { showInfo = true } }
            }

            // Content melts away under the bar into the page's own frost.
            ScrollEdge({ solid }, statusTop + 60.dp + 34.dp, pageHeight) { m -> FrostedBackdrop(frost, palette, heroHeight, heroScroll, m) }
            TopBar(page, { solid }, hasMix = page.radioPlaylistId != null, favorite = favorite,
                onMix = mix, onShare = { sharing = true }, onSignature = { showSignature = true }, onFavorite = toggleFavorite)
        }

        if (showInfo) InfoSheet(page, genre, favorite, onFavorite = toggleFavorite, onShare = { showInfo = false; sharing = true }) { showInfo = false }
        if (showSignature) SignatureSheet(page, genre) { showSignature = false }
    }
    if (sharing) ShareSheet(
        ShareTarget(page.name, page.subscribers.orEmpty(), "Artist", page.thumbnail, ShareLinks.artist(page.id), round = true),
    ) { sharing = false }
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
    page: ArtistPage, type: com.prism.music.ui.theme.ArtistType, typeReady: Boolean, height: Dp, scroll: () -> Int,
    favorite: Boolean, onInfo: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit, onFavorite: () -> Unit, onSignature: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(height)) {
            // The photo frosts over into the backdrop below (a blurred copy of it, lined up exactly).
            Box(Modifier.matchParentSize().clipToBounds().dissolveBottom(0.5f)) {
                AsyncImage(
                    hiRes(page.thumbnail, 2880), null, contentScale = ContentScale.Crop,
                    // Gentle parallax: the photo drifts at half the scroll speed.
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationY = scroll() * 0.5f },
                )
            }
            Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent))))
            ArtistSignature(
                page.name, type, typeReady,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 4.dp),
                onLongPress = onSignature,
            )
        }
        page.subscribers?.let {
            Text(
                it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroButton(Icons.Outlined.Info, "About ${page.name}", onClick = onInfo)
            PlayCapsule(onPlay)
            HeroButton(Icons.Rounded.Shuffle, "Shuffle ${page.name}", onClick = onShuffle)
            HeroButton(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "Remove from favourites" else "Add to favourites",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, onClick = onFavorite,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Round frosted button for the hero row. */
@Composable
private fun HeroButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier.size(50.dp).clip(CircleShape).background(frostFill(1.5f))
            .border(0.7.dp, Color.White.copy(alpha = if (LocalIsDark.current) 0.14f else 0.6f), CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(24.dp), tint = tint) }
}

/** The big Play: white on dark pages, ink on light ones, like Apple's. */
@Composable
private fun PlayCapsule(onClick: () -> Unit) {
    val dark = LocalIsDark.current
    val bg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val fg = if (dark) Color.Black else MaterialTheme.colorScheme.surface
    Row(
        Modifier.height(50.dp).width(146.dp).clip(RoundedCornerShape(25.dp)).background(bg).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(26.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text("Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Slim bar over the page: the back button, then the name once the hero has gone ([solid] goes 0 → 1). */
@Composable
private fun TopBar(
    page: ArtistPage, solid: () -> Float,
    hasMix: Boolean, favorite: Boolean, onMix: () -> Unit, onShare: () -> Unit, onSignature: () -> Unit, onFavorite: () -> Unit,
) {
    val nav = LocalNavigator.current
    val view = LocalView.current
    val dark = LocalIsDark.current
    // Light status-bar icons over the photo; the theme's own once the page's frost has taken over.
    val lightIcons by remember(dark) { derivedStateOf { solid() < 0.5f || dark } }
    DisposableEffect(lightIcons) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = !lightIcons
        onDispose { if (previous != null) ctl.isAppearanceLightStatusBars = previous }
    }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp)) {
            Text(
                page.name, Modifier.align(Alignment.Center).padding(horizontal = 112.dp).graphicsLayer { alpha = solid() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            com.prism.music.ui.components.RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.align(Alignment.CenterStart).padding(start = 12.dp), glass = true) { nav.back() }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasMix) com.prism.music.ui.components.RoundAction(Icons.Rounded.Radio, "Start ${page.name} Mix", glass = true, onClick = onMix)
                Box {
                    com.prism.music.ui.components.RoundAction(Icons.Rounded.MoreHoriz, "More", glass = true) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Share artist") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                        DropdownMenuItem(
                            { Text(if (favorite) "Remove from favourites" else "Add to favourites") }, { menu = false; onFavorite() },
                            leadingIcon = { Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) },
                        )
                        DropdownMenuItem({ Text("Signature style") }, { menu = false; onSignature() }, leadingIcon = { Icon(Icons.Rounded.Draw, null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleHeader(title: String, onMore: (() -> Unit)?) {
    val ui = LocalUi.current
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

/** The latest release in a frosted card of its own, as Apple Music features it. */
@Composable
private fun Spotlight(item: AlbumItem, onOpen: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    // Track count and running time come from the release itself.
    val release by produceState<List<Song>?>(null, item.id) { value = runCatching { c.ytm.album(item.id).songs }.getOrNull() }
    val details = release?.takeIf { it.isNotEmpty() }?.let { songs ->
        val min = (songs.sumOf { it.durationSec } + 30) / 60
        listOfNotNull(if (songs.size == 1) "1 song" else "${songs.size} songs", min.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · ")
    }
    val kind = item.subtitle.split(" • ").firstOrNull { it in setOf("Single", "EP", "Album") } ?: "Album"
    val year = yearOf(item).takeIf { it > 0 }
    val thisYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    FrostedPanel(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item.thumbnail, Modifier.size(112.dp), LocalUi.current.art, size = 544)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(if (year == thisYear) "New release" else "Latest release", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(year?.toString(), kind, details).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .clickable(onClickLabel = "Play ${item.title}") {
                        scope.launch {
                            val songs = release ?: runCatching { c.ytm.album(item.id).songs }.getOrNull().orEmpty()
                            if (songs.isNotEmpty()) c.player.playQueue(songs, 0, QueueSource(QueueKind.ALBUM, item.title))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary) }
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
                        Artwork(s.thumbnail, Modifier.size(48.dp), LocalUi.current.smallArt, size = 226)
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
                    if (i < columns[col].lastIndex) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
    menuFor?.let { s -> SongActionsSheet(s) { menuFor = null } }
}

/** A row of Apple-style cards: artwork, then a title and a quiet second line (the year for releases). */
@Composable
private fun CardRow(items: List<BrowseItem>, width: Dp, aspect: Float = 1f, round: Boolean = false, open: (BrowseItem) -> Unit) {
    val ui = LocalUi.current
    val menu = rememberPlayMenu()
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.id + it.title }) { item ->
            Column(
                Modifier.width(width).clip(ui.shape(10.dp))
                    .combinedClickable(onClick = { open(item) }, onLongClick = item.playable()?.let { p -> { menu(p) } }),
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

private fun facts(page: ArtistPage, genre: String?) = listOfNotNull(
    genre?.let { "Genre" to it },
    page.subscribers?.let { "Listeners" to it.replace(" monthly audience", " monthly") },
    page.views?.let { "Views on YouTube" to it.removeSuffix(" views") },
)

@Composable
private fun AboutCard(page: ArtistPage, genre: String?, onOpen: () -> Unit) {
    val bio = page.description?.takeIf { it.isNotBlank() }
    val facts = facts(page, genre)
    if (bio == null && facts.isEmpty()) return
    Text(
        "About ${page.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp),
    )
    FrostedPanel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            if (bio != null) {
                Text(bio, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("MORE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            facts.forEachIndexed { i, (k, v) ->
                if (bio != null || i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text(v, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The Info button: the whole biography and the facts, with the artist's actions at hand. */
@Composable
private fun InfoSheet(page: ArtistPage, genre: String?, favorite: Boolean, onFavorite: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    PrismSheet(onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(page.thumbnail, Modifier.size(56.dp), CircleShape, size = 226, placeholderIcon = Icons.Rounded.Person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(page.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    page.subscribers?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (favorite) "Remove from favourites" else "Add to favourites", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share artist") }
            }
            facts(page, genre).forEach { (k, v) ->
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text(v, style = MaterialTheme.typography.bodyLarge)
            }
            page.description?.takeIf { it.isNotBlank() }?.let {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Pick how this artist's name is set on their page (or leave it to Prism). Kept on this phone. */
@Composable
private fun SignatureSheet(page: ArtistPage, genre: String?, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val signatures by c.artistPrefs.signatures.collectAsState()
    val chosen = signatures[page.id]
    val auto = remember(page.id, genre) { ArtistTypography.forArtist(page.id, genre, ArtistTypography.parseAudience(page.subscribers)) }
    PrismSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text("Signature", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "How ${page.name}'s name is set on their page. Tip: long-press the name to come back here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item("auto") { SignatureOption(page.name, "Automatic · ${auto.label}", auto, chosen == null) { c.artistPrefs.setSignature(page.id, null) } }
            items(ArtistTypography.all, key = { it.key }) { t ->
                SignatureOption(page.name, t.label, t, chosen == t.key) { c.artistPrefs.setSignature(page.id, t.key) }
            }
        }
    }
}

@Composable
private fun SignatureOption(name: String, label: String, type: com.prism.music.ui.theme.ArtistType, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(label)
            SignatureSample(name, type, Modifier.padding(top = 2.dp))
        }
        if (selected) Icon(Icons.Rounded.Check, "Chosen", tint = MaterialTheme.colorScheme.primary)
    }
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
