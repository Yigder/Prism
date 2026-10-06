package com.prism.music.ui.components

import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.formatDuration
import com.prism.music.data.model.hiRes
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    size: Int = 544,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholderIcon, null, tint = scheme.onPrimaryContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.38f))
        if (url != null) AsyncImage(
            model = hiRes(url, size),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, strapline: String? = null, onMore: (() -> Unit)? = null) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = ui.gap(26.dp), bottom = ui.gap(10.dp)),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (!strapline.isNullOrBlank()) Text(
                strapline, style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMore != null) Row(
            Modifier.clip(CircleShape).clickable(onClick = onMore).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See all", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "More", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailingInfo: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Marks one of an album's most-played tracks with a star, as Apple Music does. */
    starred: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalContainer.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    val art = ui.smallArt
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = ui.gap(6.dp), bottom = ui.gap(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Row(Modifier.width(38.dp), verticalAlignment = Alignment.CenterVertically) {
                if (starred) Icon(Icons.Rounded.Star, "Popular", Modifier.size(12.dp), tint = scheme.primary)
                else Spacer(Modifier.width(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "$index", style = MaterialTheme.typography.bodyMedium,
                    color = if (playing) scheme.primary else scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box {
            Artwork(song.thumbnail, Modifier.size(50.dp), art, size = 226)
            if (playing) Box(
                Modifier.size(50.dp).clip(art).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { PlayingBars(Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (playing) scheme.primary else scheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) Icon(Icons.Rounded.Explicit, null, Modifier.size(14.dp).padding(end = 2.dp), tint = scheme.onSurfaceVariant)
                if (downloads[song.id]?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
                    Icon(Icons.Rounded.DownloadDone, null, Modifier.size(14.dp).padding(end = 3.dp), tint = scheme.primary)
                }
                Text(
                    listOfNotNull(song.artistText, trailingInfo ?: song.album?.title).joinToString(" • "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        if (song.id in liked) Icon(Icons.Rounded.Favorite, null, Modifier.size(15.dp), tint = scheme.primary)
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = scheme.onSurfaceVariant.copy(alpha = 0.8f)) }
    }
    if (menu) SongActionsSheet(song) { menu = false }
}

@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "b")
    val d by t.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(370), RepeatMode.Reverse), label = "d")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(a, b, d).forEach { h ->
            Box(Modifier.weight(1f).fillMaxSize().graphicsLayer {
                scaleY = h; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }.clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun ItemCard(item: BrowseItem, modifier: Modifier = Modifier, width: Dp = Dp.Unspecified, onClick: () -> Unit) {
    val round = item is ArtistItem
    val ui = com.prism.music.ui.theme.LocalUi.current
    val w = if (width == Dp.Unspecified) ui.cardWidth else width
    Column(modifier.width(w).clip(ui.tile).clickable(onClick = onClick).padding(4.dp)) {
        when (item) {
            is MoodItem -> MoodTile(item, Modifier.fillMaxWidth().aspectRatio(1.6f), onClick)
            else -> {
                val video = item is SongItem && item.song.isVideo
                Artwork(
                    LocalContainer.current.covers.art(item),
                    Modifier.fillMaxWidth().aspectRatio(if (video) 16f / 9f else 1f),
                    if (round) CircleShape else ui.art,
                    placeholderIcon = when (item) {
                        is ArtistItem -> Icons.Rounded.Person
                        is AlbumItem -> Icons.Rounded.Album
                        is PlaylistItem -> Icons.AutoMirrored.Rounded.QueueMusic
                        else -> Icons.Rounded.MusicNote
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title, maxLines = if (video) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize * 1.25f),
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
                if (item.subtitle.isNotBlank()) Text(
                    item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
            }
        }
    }
}

@Composable
fun MoodTile(item: MoodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(item.color or 0xFF000000)
    val ui = com.prism.music.ui.theme.LocalUi.current
    Box(
        modifier
            .clip(ui.tile)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f).compositeOverBlack())))
            .clickable(onClick = onClick),
    ) {
        // A soft sheen in the corner keeps flat colours from looking like plain blocks.
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), radius = 260f, center = androidx.compose.ui.geometry.Offset(0f, 0f))))
        Text(
            item.title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
fun ItemCarousel(items: List<BrowseItem>, onClick: (BrowseItem) -> Unit, cardWidth: Dp = Dp.Unspecified) {
    val w = if (cardWidth == Dp.Unspecified) com.prism.music.ui.theme.LocalUi.current.cardWidth else cardWidth
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.id + it.title }) { item ->
            ItemCard(item, width = if (item is SongItem && item.song.isVideo) w * 1.5f else w) { onClick(item) }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.CloudOff, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(14.dp))
        Text(
            message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(26.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    Text(text, modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500), style = style, color = color, maxLines = 1)
}

/** A search pill for filtering a list in place ("Search in playlist"), as you type. */
@Composable
fun SearchField(query: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(23.dp)).background(scheme.surfaceContainerHigh).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                query, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onChange(""); focus.clearFocus() }) {
            Icon(Icons.Rounded.Close, "Clear search", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Sheets

/**
 * A bottom sheet that always lets go. Material's sheet could be left half-dismissed (blocking the
 * screen) when flicked or tapped away while it was still opening; this watches where the sheet
 * actually lands and finishes the dismissal itself, exactly once. [content] gets a `close { … }`
 * that slides the sheet away first and then runs the action, so a follow-up sheet never overlaps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismSheet(
    onDismiss: () -> Unit,
    containerColor: Color = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val then = remember { arrayOfNulls<() -> Unit>(1) }
    val finish = remember {
        {
            if (done.compareAndSet(false, true)) {
                dismiss()
                then[0]?.invoke()
            }
        }
    }
    LaunchedEffect(state) {
        var shown = false
        androidx.compose.runtime.snapshotFlow { state.currentValue to state.targetValue }.collect { (now, target) ->
            if (now != androidx.compose.material3.SheetValue.Hidden) shown = true
            else if (shown && target == androidx.compose.material3.SheetValue.Hidden) finish()
        }
    }
    val close: (() -> Unit) -> Unit = remember {
        { action ->
            if (then[0] == null) then[0] = action
            scope.launch { try { state.hide() } finally { finish() } }
        }
    }
    ModalBottomSheet(onDismissRequest = finish, sheetState = state, containerColor = containerColor, contentColor = contentColor) {
        content(close)
    }
}

// ---------------------------------------------------------------- Song actions

@Composable
fun SongActionsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    val isLiked = song.id in liked
    val dl = downloads[song.id]

    // The playlist picker takes over from the sheet.
    if (!pickPlaylist) PrismSheet(onDismiss = onDismiss) { hide ->
      fun close(action: () -> Unit) = hide(action)
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnail, Modifier.size(60.dp), com.prism.music.ui.theme.LocalUi.current.art, size = 226)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (song.durationSec > 0) Text(formatDuration(song.durationSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        SheetAction(Icons.Rounded.PlaylistPlay, "Play next") { close { c.player.playNext(song) } }
        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { close { c.player.addToQueue(song) } }
        SheetAction(Icons.Rounded.Radio, "Start radio") { close { c.player.playRadio(song) } }
        SheetAction(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from liked songs" else "Add to liked songs") {
            close { c.library.toggleLike(song) }
        }
        val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
        SheetAction(if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (downloaded) "Remove download" else if (dl != null) "Downloading… ${dl.percent.toInt().coerceAtLeast(0)}%" else "Download") {
            close { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) }
        }
        if (c.settings.current.isLoggedIn) SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
        song.album?.id?.let { id -> SheetAction(Icons.Rounded.Album, "Go to album") { close { nav.go(Routes.album(id)) } } }
        song.artists.firstOrNull { it.id != null }?.let { a -> SheetAction(Icons.Rounded.Person, "Go to ${a.name}") { close { nav.go(Routes.artist(a.id!!)) } } }
        SheetAction(Icons.Rounded.Share, "Share") {
            close {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share song"))
            }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
    if (pickPlaylist) PlaylistPicker(song) { pickPlaylist = false; onDismiss() }
}

/** One extra row for [SongActionsSheet]. */
data class SheetItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(com.prism.music.ui.theme.LocalUi.current.shape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface) }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PlaylistPicker(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val playlists by c.library.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) Text("Sync your library first (Library → refresh).")
                playlists.filterIsInstance<PlaylistItem>().filter { !it.id.startsWith("LM") && !it.isMix }.take(30).forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            scope.launch {
                                val ok = runCatching { c.ytm.addToPlaylist(p.id, song.id) }.isSuccess
                                android.widget.Toast.makeText(context, if (ok) "Added to ${p.title}" else "Couldn't add to ${p.title}", android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(c.covers.art(p), Modifier.size(40.dp), RoundedCornerShape(8.dp), size = 120)
                        Spacer(Modifier.width(12.dp))
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Chips that follow a pager: tap to jump, or swipe the pages and the chip moves along. */
@Composable
fun PagerChips(labels: List<String>, pager: androidx.compose.foundation.pager.PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager.currentPage) { row.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(
        modifier, state = row,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.size) { i ->
            PrismChip(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, labels[i])
        }
    }
}
/** "⇅ Title" — opens a menu of sort orders; the current one is ticked. */
@Composable
fun <T> SortButton(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label(selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(o), fontWeight = if (o == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(o); open = false },
                    trailingIcon = if (o == selected) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

/** A list's count on the left and its [SortButton] on the right. */
@Composable
fun <T> SortBar(count: String, options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        SortButton(options, selected, label, onSelect = onSelect)
    }
}