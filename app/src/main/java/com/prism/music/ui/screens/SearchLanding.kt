package com.prism.music.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.SearchSection
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Load
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.theme.BackdropKind
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.drawPattern
import com.prism.music.ui.theme.lit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Something opened from Search's results, kept so its picture can lead the next visit (1.7.6). */
@Serializable
data class OpenedItem(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    val playlistId: String? = null,
    val song: Song? = null,
) {
    fun item(): BrowseItem? = when (kind) {
        "song" -> song?.let(::SongItem)
        "album" -> AlbumItem(id, title, subtitle, thumbnail, playlistId)
        "playlist" -> PlaylistItem(id, title, subtitle, thumbnail)
        "artist" -> ArtistItem(id, title, subtitle, thumbnail)
        else -> null
    }

    companion object {
        fun of(item: BrowseItem): OpenedItem? = when (item) {
            is SongItem -> if (item.song.isLive) null else OpenedItem("song", item.id, item.title, song = item.song)
            is AlbumItem -> OpenedItem("album", item.id, item.title, item.subtitle, item.thumbnail, item.playlistId)
            is PlaylistItem -> if (item.isMix) null else OpenedItem("playlist", item.id, item.title, item.subtitle, item.thumbnail)
            is ArtistItem -> OpenedItem("artist", item.id, item.title, item.subtitle, item.thumbnail)
            else -> null
        }
    }
}

/** What Search remembers on the phone: the last things opened from it (never sent anywhere). */
object SearchMemory {
    private const val MAX = 12
    private fun prefs(context: Context) = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    fun opened(context: Context): List<OpenedItem> = runCatching {
        prefs(context).getString("opened", null)?.let { InnerTube.json.decodeFromString(ListSerializer(OpenedItem.serializer()), it) }
    }.getOrNull().orEmpty()

    fun remember(context: Context, item: BrowseItem) {
        val o = OpenedItem.of(item) ?: return
        val now = (listOf(o) + opened(context).filterNot { it.kind == o.kind && it.id == o.id }).take(MAX)
        prefs(context).edit().putString("opened", InnerTube.json.encodeToString(ListSerializer(OpenedItem.serializer()), now)).apply()
    }

    fun forget(context: Context, o: OpenedItem) {
        val now = opened(context).filterNot { it.kind == o.kind && it.id == o.id }
        prefs(context).edit().putString("opened", InnerTube.json.encodeToString(ListSerializer(OpenedItem.serializer()), now)).apply()
    }
}

/**
 * Search before you type (1.7.6, plan H8): your own way in rather than YouTube's. What you opened last (by its picture)
 * and your searches, your artists, and the moods and genres as Prism-drawn tiles. The sections can be shown, hidden and
 * put in order (Arrange).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SearchLanding(
    recents: List<String>,
    moods: com.prism.music.ui.Loaded<com.prism.music.data.RankedCategories>,
    bottomPadding: Dp,
    opened: List<OpenedItem>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onForget: (OpenedItem) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onMood: (MoodItem) -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    var arranging by remember { mutableStateOf(false) }
    if (arranging) ArrangeSearchSheet { arranging = false }
    val artists = rememberYourArtists()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        settings.searchSections.forEach { section ->
            when (section) {
                SearchSection.RECENT -> {
                    val items = opened.mapNotNull { o -> o.item()?.let { o to it } }
                    if (items.isNotEmpty()) {
                        item(key = "opened-h") { SectionHeader("Recently opened") }
                        item(key = "opened") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(items, key = { (o, _) -> o.kind + o.id }) { (o, item) ->
                                    ItemCard(item, width = 128.dp, onLongClick = { onForget(o) }) { onOpen(item) }
                                }
                            }
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item(key = "recents-h") { SectionHeader("Recent searches") }
                        item(key = "recents") {
                            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                recents.take(10).forEach { r ->
                                    // The chip takes the long press itself: wrapped in another clickable, its own click ate it.
                                    PrismChip(false, { onPick(r) }, r, icon = PrismIcons.History, onLongClick = { onRemove(r) }, onLongClickLabel = "Remove")
                                }
                            }
                        }
                    }
                }
                SearchSection.ARTISTS -> if (artists.isNotEmpty()) {
                    item(key = "artists-h") { SectionHeader("Your artists") }
                    item(key = "artists") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(artists, key = { it.id }) { a -> ItemCard(a, width = 104.dp) { onOpen(a) } }
                        }
                    }
                }
                SearchSection.MOODS -> {
                    val ranked = (moods.state as? Load.Ok)?.value ?: return@forEach
                    // "For you" is Prism's own ranking; the groups after it leave out what it already shows.
                    val shown = HashSet<String>()
                    val forYou = ranked.picks.take(8).onEach { shown += it.id + "|" + it.params }
                    val groups = listOf("For you" to forYou) + ranked.sections.filterNot { it.first.contains("for you", true) }.map { (title, tiles) ->
                        title to tiles.filter { shown.add(it.id + "|" + it.params) }.take(6)
                    }
                    groups.forEach { (title, tiles) ->
                        if (tiles.isEmpty()) return@forEach
                        item(key = "mh:$title") { SectionHeader(if (title == "For you") "Moods & genres for you" else title, onMore = { nav.go(Routes.MOODS) }) }
                        tiles.chunked(2).forEachIndexed { i, pair ->
                            item(key = "m:$title:$i") {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { mood -> PrismMoodTile(mood, Modifier.weight(1f)) { onMood(mood) } }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "arrange") {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                PrismTextButton(onClick = { arranging = true }) {
                    Icon(PrismIcons.SwapVertical, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Arrange this page")
                }
            }
        }
    }
}

/** Your starred and library artists, then the ones you've played lately (their photos loaded as needed). */
@Composable
private fun rememberYourArtists(): List<ArtistItem> {
    val c = LocalContainer.current
    val favorites by c.artistPrefs.favorites.collectAsState()
    val library by c.library.artists.collectAsState()
    val recent by c.library.recent.collectAsState()
    val thumbs by c.library.artistThumbs.collectAsState()
    val list = remember(favorites, library, recent, thumbs) {
        val played = recent.flatMap { s -> s.artists.take(1).mapNotNull { a -> a.id?.let { ArtistItem(it, a.name, "Artist", thumbs[it]) } } }
        (favorites.map { ArtistItem(it.id, it.name, "Artist", it.thumbnail) } + library.filterIsInstance<ArtistItem>() + played)
            .distinctBy { it.id }.take(16)
            .map { a -> c.covers.custom(c.covers.artistKey(a.id))?.let { a.copy(thumbnail = it) } ?: a }
    }
    LaunchedEffect(list.map { it.id }) {
        list.filter { it.thumbnail == null }.map { it.id }.takeIf { it.isNotEmpty() }?.let { c.library.loadArtistThumbs(it) }
    }
    return list
}

/** The simple patterns mood tiles wear, one per mood (by its name), so a mood keeps its look. */
private val MOOD_PATTERNS = listOf(
    BackdropKind.WAVES, BackdropKind.DIAMONDS, BackdropKind.RIPPLES, BackdropKind.ZIGZAG, BackdropKind.HALFTONE,
    BackdropKind.CROSSES, BackdropKind.SCALES, BackdropKind.PINSTRIPE,
)

/** A mood or genre, drawn by Prism: its color deepening down the tile, a quiet pattern, and the split light's hairline. */
@Composable
internal fun PrismMoodTile(mood: MoodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ui = com.prism.music.ui.theme.LocalUi.current
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    val base = Color(mood.color or 0xFF000000).lit()
    val pattern = remember(mood.title) { MOOD_PATTERNS[Math.floorMod(mood.title.hashCode(), MOOD_PATTERNS.size)] }
    Box(modifier.height(88.dp).clip(ui.tile).clickable(onClickLabel = mood.title, onClick = onClick)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f).compositeOver(Color.Black)), Offset.Zero, Offset(size.width, size.height)))
            drawPattern(pattern, Color.Transparent, Color.White, Color.White, dark = true, t = 0f, density = density, glow = 0f)
            drawRect(
                Brush.horizontalGradient(*PrismTokens.spectrumColors(dark).toTypedArray(), startX = 0f, endX = size.width),
                Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()),
            )
        }
        Text(
            mood.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

/** Show, hide and order Search's sections. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ArrangeSearchSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val shown = settings.searchSections
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Arrange Search", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Column(Modifier.padding(top = 8.dp, bottom = 28.dp)) {
            com.prism.music.ui.components.ReorderList(shown, { it }, { c.settings.setSearchSections(it) }) { s, _, _, mod ->
                Row(mod.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(true, enabled = shown.size > 1, label = s.label) { c.settings.setSearchSections(shown - s) }
                    Text(s.label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    com.prism.music.ui.components.DragGrip(Modifier.dragHandle(), "Drag ${s.label}")
                }
            }
            SearchSection.entries.filter { it !in shown }.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(false, label = s.label) { c.settings.setSearchSections(shown + s) }
                    Text(s.label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
