package com.prism.music.ui.screens

import com.prism.music.ui.theme.lit
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.friendlyMessage
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.pane
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

/** [PRISM]: a playlist made in Prism and kept on the phone ([com.prism.music.data.PrismLibrary]). */
enum class CollectionType { ALBUM, PLAYLIST, LIKED, DOWNLOADS, MIX, PRISM }

/** Names of artists YouTube Music lists as similar ("Fans might also like"), for classifying obscure artists. */
private suspend fun relatedArtists(c: com.prism.music.AppContainer, s: Song): List<String> {
    val id = s.artists.firstOrNull { it.id != null }?.id ?: return emptyList()
    val artist = runCatching { c.ytm.artist(id) }.getOrNull() ?: return emptyList()
    return artist.shelves.filter { it.title.contains("like", true) || it.title.contains("similar", true) || it.title.contains("related", true) }
        .flatMap { it.items }.filterIsInstance<com.prism.music.data.model.ArtistItem>().map { it.title }
}

enum class SortMode(val label: String) {
    DEFAULT("Original order"), TITLE("Title"), ARTIST("Artist"), GENRE("Genre"), DURATION("Duration"), ALBUM("Album"),
    /** Downloads only: the original order (newest first) the other way round (#77). */
    OLDEST("Oldest first"),
}

@Composable
fun CollectionScreen(type: CollectionType, id: String, initialGenre: String?, bottomPadding: Dp) {
    val c = LocalContainer.current
    val liked by c.library.likedShown.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()

    val remote = when (type) {
        CollectionType.ALBUM -> rememberLoad("album:$id") { orSaved(c, id) { LiveCollection(c.ytm.album(id), c.scope, null) } }
        // The first page shows straight away; the rest of a long playlist streams in behind it.
        CollectionType.PLAYLIST -> rememberLoad("playlist:$id") { orSaved(c, id) { LiveCollection(c.ytm.playlist(id), c.scope, c.ytm::playlistPage) } }
        // Prism's own mixes (Daily Mix, the time-of-day mix, Release Radar): kept on the phone.
        CollectionType.MIX -> rememberLoad("mix:$id") {
            // Daily Mixes are kept in the database; mixes from your music are made on the spot (again, if need be).
            val mix = c.forYou.mix(id)
                ?: c.yourMusic.mix(id)
                ?: (if (com.prism.music.data.foryou.YourMusic.isLibraryMix(id)) c.yourMusic.sections(!c.network.isOnline()).mixes.firstOrNull { it.id == id } else null)
                ?: throw IllegalStateException("This mix isn't made yet")
            LiveCollection(CollectionPage(mix.id, CollectionKind.PLAYLIST, mix.title, mix.subtitle, thumbnail = mix.covers.firstOrNull(), songs = mix.songs), c.scope, null)
        }
        else -> null
    }
    // Opened from YouTube Music: kept (with all its songs, once they're in) to open offline later (#55).
    (remote?.state as? Load.Ok)?.value?.takeIf { !it.offline }?.let { live ->
        val more by live.loadingMore.collectAsState()
        LaunchedEffect(live, more) {
            if (!more) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.offline.savePage(id, live.page.copy(songs = live.songs.value)) }
        }
    }
    val prismLists by c.prismLibrary.lists.collectAsState()
    val page: Load<LiveCollection> = when (type) {
        CollectionType.PRISM -> {
            val list = prismLists.firstOrNull { it.id == id }
            if (list == null) Load.Err("This playlist isn't on this phone any more")
            else Load.Ok(remember(list) {
                LiveCollection(
                    CollectionPage(list.id, CollectionKind.PLAYLIST, list.title, "Prism playlist · on this phone", description = list.description.ifBlank { null },
                        thumbnail = list.songs.firstOrNull()?.thumbnail, songs = list.songs),
                    c.scope, null,
                )
            })
        }
        CollectionType.LIKED -> Load.Ok(remember(liked) {
            LiveCollection(CollectionPage("liked", CollectionKind.PLAYLIST, "Liked songs", com.prism.music.data.model.songCount(liked.size), thumbnail = null, songs = liked), c.scope, null)
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

/**
 * Offline, the copy kept from the last time the playlist or album was opened (#55); online, it's loaded as usual, and
 * the kept copy stands in only if that fails.
 */
private suspend fun orSaved(c: com.prism.music.AppContainer, id: String, load: suspend () -> LiveCollection): LiveCollection {
    fun saved() = c.offline.page(id)?.let { LiveCollection(it, c.scope, null, offline = true) }
    if (!c.network.isOnline()) saved()?.let { return it }
    return try { load() } catch (e: Exception) { saved() ?: throw e }
}

/** A collection whose songs keep arriving (continuation pages load in the background). */
class LiveCollection(
    val page: CollectionPage,
    scope: kotlinx.coroutines.CoroutineScope,
    loadMore: (suspend (String) -> YouTubeMusic.PlaylistChunk)?,
    /** The copy kept on the phone, shown without a network. */
    val offline: Boolean = false,
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
    // Liked songs', Downloads' and each playlist's sort is kept, so leaving the page doesn't undo it.
    val sortList = when (type) {
        CollectionType.LIKED -> "liked"
        CollectionType.DOWNLOADS -> "downloads"
        CollectionType.PLAYLIST, CollectionType.PRISM -> "playlist:${page.id}"
        else -> null
    }
    var sort by remember(page.id) {
        mutableStateOf(sortList?.let(c.library::sortOf)?.let { n -> SortMode.entries.firstOrNull { it.name == n } } ?: SortMode.DEFAULT)
    }
    var groupByGenre by remember(page.id) { mutableStateOf(sortList?.let { c.library.sortOf("$it:genre") } != null) }
    fun pickSort(m: SortMode) {
        sort = m
        sortList?.let { c.library.setSort(it, m.name.takeIf { m != SortMode.DEFAULT }) }
    }
    fun toggleGenreGroups() {
        groupByGenre = !groupByGenre
        sortList?.let { c.library.setSort("$it:genre", if (groupByGenre) "on" else null) }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable(page.id) { mutableStateOf("") }
    val showGenres = type != CollectionType.ALBUM && songs.size > 1
    val showSearch = type != CollectionType.ALBUM || songs.size > 12

    // Starting a song waits on its stream (a few seconds through NewPipe), so on Wi-Fi the first track's is fetched
    // while the page is being looked at: Play then starts straight away.
    LaunchedEffect(page.id, songs.isNotEmpty()) {
        val first = songs.firstOrNull { !c.downloads.isDownloaded(it.id) } ?: return@LaunchedEffect
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.isActiveNetworkMetered != false || type == CollectionType.DOWNLOADS) return@LaunchedEffect
        kotlinx.coroutines.delay(600) // not for pages only passed through
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.warmUp(first.id) }
    }

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
            SortMode.OLDEST -> filtered.asReversed()
        }
    }
    // The page's own id goes with the queue: exclusions ("leave this playlist out of my taste") and Smart Shuffle's
    // "Add to this playlist" know which playlist is playing.
    val source = QueueSource(
        if (type == CollectionType.ALBUM) QueueKind.ALBUM else QueueKind.PLAYLIST, page.title + (genreFilter?.let { " · $it" } ?: ""),
        originId = page.playlistId ?: page.id.removePrefix("VL").takeIf { type == CollectionType.PLAYLIST || type == CollectionType.MIX } ?: page.id,
    )
    // Offline (#55): only songs on the phone (downloaded, cached whole, or files) play; the rest show greyed out.
    val online by c.network.online.collectAsState()
    val ready = remember(songs, downloads, online) { if (online) null else songs.filter { c.availableOffline(it.id) }.map { it.id }.toSet() }
    val playable = remember(visible, ready) { if (ready == null) visible else visible.filter { it.id in ready } }
    val playFrom: (List<Song>, Song, QueueSource) -> Unit = { list, s, src ->
        if (ready != null && s.id !in ready) Toast.makeText(context, "You're offline, and this song isn't downloaded", Toast.LENGTH_SHORT).show()
        else {
            val q = if (ready == null) list else list.filter { it.id in ready }
            c.player.playQueue(q, q.indexOfFirst { it.id == s.id }.coerceAtLeast(0), src)
        }
    }
    val dim: (Song) -> Modifier = { s -> if (ready != null && s.id !in ready) Modifier.alpha(0.4f) else Modifier }
    val listState = rememberLazyListState()
    val downloadedCount = page.songs.count { downloads[it.id]?.state == Download.STATE_COMPLETED }
    val downloadingCount = page.songs.count { downloads[it.id]?.let { d -> d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED } == true }
    val totalSec = visible.sumOf { it.durationSec }
    val popular = remember(live.page) { if (type == CollectionType.ALBUM) live.page.popularIds else emptySet() }
    // Playlists (and Liked songs) can have a picture of the listener's choosing.
    val coverKey = when (type) { CollectionType.PLAYLIST, CollectionType.PRISM -> page.id; CollectionType.LIKED -> "liked"; CollectionType.DOWNLOADS -> "downloads"; else -> null }
    val art = coverKey?.let { c.covers.art(it, page.thumbnail) } ?: page.thumbnail
    // Prism's own lists without a picture take their colors from their newest song.
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
                val done = if (type == CollectionType.ALBUM && now) c.library.removeAlbum(page.id, id) else c.library.setSaved(id, !now)
                if (!done) {
                    saved = now
                    Toast.makeText(context, "Couldn't update your library", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Without an account, an album or a YouTube playlist is saved to the library on the phone instead (Library →
    // Albums / Playlists; playlists since 1.7.6, H7). Mixes change every time, so they're not kept.
    val savedAlbums by c.prismLibrary.albums.collectAsState()
    val savedPlaylists by c.prismLibrary.playlists.collectAsState()
    val localPlaylist = type == CollectionType.PLAYLIST && !settings.isLoggedIn && !com.prism.music.data.PrismLibrary.isMix(page.id)
    val localAlbum = (type == CollectionType.ALBUM || localPlaylist) && !settings.isLoggedIn
    val savedHere = if (localPlaylist) savedPlaylists.any { com.prism.music.data.PrismLibrary.samePlaylist(it.id, page.id) }
        else localAlbum && savedAlbums.any { it.id == page.id }
    val toggleSavedHere: () -> Unit = {
        if (localPlaylist) {
            if (savedHere) c.prismLibrary.unsavePlaylist(page.id)
            else c.prismLibrary.savePlaylist(
                com.prism.music.data.SavedPlaylist(
                    page.id, page.title,
                    page.subtitle.takeIf { it.isNotBlank() } ?: "Playlist",
                    page.thumbnail, System.currentTimeMillis(),
                ),
            )
            Toast.makeText(context, if (savedHere) "Removed from your library" else "Saved to your library", Toast.LENGTH_SHORT).show()
        } else if (savedHere) {
            c.prismLibrary.unsaveAlbum(page.id)
            Toast.makeText(context, "Removed from your library", Toast.LENGTH_SHORT).show()
        } else {
            val by = page.artists.joinToString(", ") { it.name }.ifBlank { page.subtitle }
            c.prismLibrary.saveAlbum(
                com.prism.music.data.SavedAlbum(
                    page.id, page.title, listOfNotNull(page.releaseKind ?: "Album", by.takeIf { it.isNotBlank() }, page.year?.toString()).joinToString(" • "),
                    page.thumbnail, page.playlistId, System.currentTimeMillis(),
                ),
            )
            Toast.makeText(context, "Saved to your library", Toast.LENGTH_SHORT).show()
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
                runCatching { c.radio(null, rid) }.onSuccess { r ->
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
            Toast.makeText(context, "Downloading ${com.prism.music.data.model.songCount(page.songs.size - downloadedCount)}", Toast.LENGTH_SHORT).show()
        }
    }

    // Owned playlists: a song can be taken out of it.
    val slots by live.setVideoIds.collectAsState()
    fun removeAction(s: Song): List<SheetItem> {
        if (type == CollectionType.PRISM) return listOf(SheetItem(PrismIcons.PlaylistRemove, "Remove from this playlist") {
            c.prismLibrary.remove(page.id, s.id)
            Toast.makeText(context, "Removed from ${page.title}", Toast.LENGTH_SHORT).show()
        })
        val slot = slots[s.id]
        if (type != CollectionType.PLAYLIST || !live.page.owned || slot == null) return emptyList()
        return listOf(SheetItem(PrismIcons.PlaylistRemove, "Remove from this playlist") {
            val before = live.songs.value
            live.songs.value = before.filterNot { it.id == s.id }
            scope.launch {
                runCatching { c.ytm.removeFromPlaylist(page.id, s.id, slot) }
                    .onSuccess { Toast.makeText(context, "Removed from ${page.title}", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        live.songs.value = before
                        Toast.makeText(context, "Couldn't remove it: ${it.friendlyMessage()}", Toast.LENGTH_SHORT).show()
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
            confirmButton = { PrismTextButton(onClick = { action(); confirm = null }) { Text("Delete") } },
            dismissButton = { PrismTextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    var coverSheet by remember { mutableStateOf(false) }
    if (coverSheet && coverKey != null) CoverSheet(coverKey, page.title, page.songs) { coverSheet = false }
    // Liked songs: made into (and kept in step with) a real YouTube Music playlist.
    var likedPlaylistSheet by remember { mutableStateOf(false) }
    val likedPlaylistId by c.likedPlaylist.playlistId.collectAsState()
    if (likedPlaylistSheet) LikedPlaylistSheet(art ?: songs.firstOrNull()?.thumbnail) { likedPlaylistSheet = false }
    var sharing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var title by remember(page.id) { mutableStateOf(page.title) }
    var description by remember(page.id) { mutableStateOf(page.description) }
    var pageMenu by remember { mutableStateOf(false) }
    fun shareTarget(): ShareTarget = when (type) {
        CollectionType.ALBUM -> ShareTarget(title, page.artists.joinToString(", ") { it.name }.ifBlank { page.subtitle }, page.releaseKind ?: "Album", art, ShareLinks.album(page.id))
        CollectionType.PLAYLIST -> ShareTarget(
            title, page.subtitle.removePrefix("Playlist • "), "Playlist", art, ShareLinks.playlist(page.id),
            playlistId = page.id.takeIf { live.page.owned }, privacy = privacy.takeIf { live.page.owned },
        )
        CollectionType.LIKED -> ShareTarget("Liked songs", com.prism.music.data.model.songCount(songs.size), "Playlist", art, null, songs = songs)
        CollectionType.DOWNLOADS -> ShareTarget("Downloads", com.prism.music.data.model.songCount(songs.size), "Playlist", art, null, songs = songs)
        CollectionType.MIX -> ShareTarget(title, page.subtitle, "Mix", art, null, songs = songs)
        CollectionType.PRISM -> ShareTarget(title, com.prism.music.data.model.songCount(songs.size), "Playlist", art, null, songs = songs)
    }

    // A wide landscape window (a tablet on its side) keeps the cover and its details beside the songs, as Apple Music
    // does on an iPad, instead of a header the width of the screen above them.
    val window = com.prism.music.ui.theme.LocalWindowLayout.current
    val sideBySide = window.width == com.prism.music.ui.theme.WidthClass.EXPANDED && window.landscape
    val hero = type != CollectionType.ALBUM && settings.playlistHeroCover && !sideBySide
    val config = LocalConfiguration.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var pageWidth by remember { mutableStateOf(config.screenWidthDp.dp) }
    val heroHeight = when {
        sideBySide -> statusTop + 160.dp
        // Never taller than most of the window (a phone on its side would otherwise get a hero taller than the screen).
        hero -> minOf(pageWidth / 1.08f, config.screenHeightDp.dp * 0.62f)
        else -> statusTop + 470.dp
    }
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
    // Search and chips go see-through on the frost (and keep the theme's color without it).
    val frosted = if (settings.frostedPages) frostFill(1.4f) else Color.Unspecified

    ArtworkAccent(palette) {
        Box(Modifier.fillMaxSize().onSizeChanged { with(density) { pageHeight = it.height.toDp(); pageWidth = it.width.toDp() } }) {
            FrostedBackdrop(frost, palette, heroHeight, heroScroll, parallax = if (hero) 0.5f else 0.35f, bleed = true)
            val header: @Composable () -> Unit = {
                if (hero) HeroHeader(type, shown, art, { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0 }, heroHeight)
                else Header(type, shown, art, coverKey) { coverSheet = true }
                HeaderDetails(
                    shown, centred = !hero, privacy = privacy,
                    saved = if (localAlbum) savedHere else saved.takeIf { savableId != null },
                    onSaved = if (localAlbum) toggleSavedHere else toggleSaved,
                )
            }
            val playRow: @Composable () -> Unit = {
                PlayShufflePills(
                    enabled = playable.isNotEmpty(),
                    onPlay = { c.player.playQueue(playable, 0, source) },
                    onShuffle = { c.player.playQueue(playable, 0, source, shuffle = true) },
                )
            }
            val sideWidth = (pageWidth * 0.4f).coerceIn(320.dp, 460.dp)
            if (sideBySide) Column(
                Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(top = statusTop + 40.dp, bottom = bottomPadding + 24.dp),
            ) {
                header()
                playRow()
            }
            LazyColumn(
                state = listState,
                modifier = if (sideBySide) Modifier.padding(start = sideWidth) else Modifier,
                contentPadding = PaddingValues(top = if (sideBySide) statusTop + 60.dp else 0.dp, bottom = bottomPadding + 24.dp),
            ) {
                if (!sideBySide) item("header") { header() }
                if (type == CollectionType.DOWNLOADS) item { DownloadsInfo(page.songs) { text, action -> confirm = text to action } }
                if (type == CollectionType.DOWNLOADS) item("downloaded-collections") {
                    DownloadedCollections(visible, sort, query, filtered = genreFilter != null || query.isNotBlank())
                }
                if (ready != null && type != CollectionType.DOWNLOADS) item("offline") {
                    Text(
                        "You're offline · ${ready.size} of ${songs.size} songs are on this phone and play" +
                            if (live.offline) " · as it was when you last opened it" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }
                if (!sideBySide) item("play") { playRow() }
                if (showSearch || showGenres) item(key = "search") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showSearch) com.prism.music.ui.components.SearchField(
                            query, if (type == CollectionType.ALBUM) "Search in album" else "Search in $title", { query = it }, Modifier.weight(1f),
                            container = frosted,
                        ) else Spacer(Modifier.weight(1f))
                        if (showGenres) Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(
                                    PrismIcons.Sort, "Sort",
                                    tint = if (sort != SortMode.DEFAULT || groupByGenre) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortMode.entries.filter { it != SortMode.OLDEST || type == CollectionType.DOWNLOADS }.forEach { m ->
                                    DropdownMenuItem({ Text(sortLabel(m, type)) }, { pickSort(m); sortMenu = false }, trailingIcon = { if (m == sort) Icon(PrismIcons.Check, null) })
                                }
                                HorizontalDivider()
                                DropdownMenuItem({ Text("Group by genre") }, { toggleGenreGroups(); sortMenu = false }, trailingIcon = { if (groupByGenre) Icon(PrismIcons.Check, null) })
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
                    if (query.isNotBlank()) EmptyState(PrismIcons.Search, "No matches", "Nothing in $title matches \"${query.trim()}\"" + if (loadingMore) " yet — more songs are still loading." else ".")
                    else EmptyState(when (type) { CollectionType.LIKED -> PrismIcons.HeartFilled; CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; else -> PrismIcons.Song }, if (genreFilter != null) "No $genreFilter songs" else "Nothing here yet",
                        when (type) {
                            CollectionType.LIKED -> "Like songs from the player and they'll show up here."
                            CollectionType.PRISM -> "Add songs from any song's menu (⋯ or a long press) → Add to playlist."
                            else -> "Songs you add will show up here."
                        })
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
                                com.prism.music.ui.components.SwipeToQueue(s) {
                                    SongRow(s, dim(s), playing = current?.id == s.id, trailingInfo = null, extraActions = removeAction(s)) {
                                        playFrom(songs, s, source.copy(title = "$title · $g"))
                                    }
                                }
                            }
                        }
                } else {
                    itemsIndexed(visible, key = { i, s -> "${s.id}:$i" }) { i, s ->
                        // Swipe right: play next; left: add to the queue.
                        com.prism.music.ui.components.SwipeToQueue(s) {
                            SongRow(
                                s,
                                dim(s),
                                index = if (type == CollectionType.ALBUM) i + 1 else null,
                                starred = s.id in popular,
                                playing = current?.id == s.id,
                                trailingInfo = if (type == CollectionType.ALBUM) formatDuration(s.durationSec) else genreVersion.let { genres[s.id] }?.takeIf { sort == SortMode.GENRE || genreFilter == null && showGenres }?.let { g -> listOfNotNull(s.album?.title, g).joinToString(" • ") },
                                extraActions = removeAction(s),
                            ) { playFrom(visible, s, source) }
                        }
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
                        ItemCarousel(rememberTellApart(live.page.otherVersions), { nav.open(it) { s -> c.player.playSingle(s.song) } })
                    }
                    live.page.artists.firstOrNull { it.id != null }?.let { a -> item("moreby") { MoreBy(a.name, a.id!!, page.id) } }
                }
            }

            // Content melts away under the bar into the page's own frost.
            ScrollEdge({ solid }, statusTop + 60.dp + 34.dp, pageHeight) { m ->
                FrostedBackdrop(frost, palette, heroHeight, heroScroll, m, parallax = if (hero) 0.5f else 0.35f, bleed = true)
            }
            CollectionTopBar(
                title, { solid },
                downloadIcon = when {
                    type == CollectionType.DOWNLOADS -> null
                    allDownloaded -> PrismIcons.DownloadDone
                    else -> PrismIcons.Download
                },
                downloading = downloadingCount > 0,
                onDownload = toggleDownloads,
                onShare = { sharing = true },
                onMore = { pageMenu = true },
            )
        }

        // The page's ⋯ is the same sheet as a long press on its card (play, shuffle, play next, queue, DJ set), with
        // what only the page can do (radio, library, downloads, picture, edit…) in a group of its own.
        if (pageMenu) {
            val excludedLists by c.exclusions.playlists.collectAsState()
            val downloadsItems = if (type == CollectionType.DOWNLOADS) downloadsSheetItems { text, action -> confirm = text to action } else emptyList()
            val items = buildList {
                if (radioId != null) add(SheetItem(PrismIcons.Radio, "Start radio") { startRadio() })
                if (savableId != null && saved != null) add(
                    SheetItem(if (saved == true) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (saved == true) "Remove from library" else "Add to library") { toggleSaved() },
                )
                if (localAlbum) add(
                    SheetItem(if (savedHere) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (savedHere) "Remove from library" else "Add to library") { toggleSavedHere() },
                )
                if (type != CollectionType.DOWNLOADS) add(
                    SheetItem(if (allDownloaded) PrismIcons.Delete else PrismIcons.Download, if (allDownloaded) "Remove downloads" else "Download all") { toggleDownloads() },
                )
                live.page.artists.firstOrNull { it.id != null }?.let { a -> add(SheetItem(PrismIcons.Person, "Go to ${a.name}") { nav.go(Routes.artist(a.id!!)) }) }
                if (type == CollectionType.LIKED) add(
                    SheetItem(PrismIcons.PlaylistAdd, if (likedPlaylistId != null) "Playlist · kept up to date" else "Make it a playlist") { likedPlaylistSheet = true },
                )
                if (coverKey != null) add(SheetItem(PrismIcons.Photo, "Change picture") { coverSheet = true })
                if ((type == CollectionType.PLAYLIST && live.page.owned) || type == CollectionType.PRISM) add(SheetItem(PrismIcons.Edit, "Edit details") { editing = true })
                // A sleep or kids' playlist: plays as usual, but doesn't shape recommendations.
                if (type == CollectionType.PLAYLIST) {
                    val listId = page.playlistId ?: page.id.removePrefix("VL")
                    val out = excludedLists.any { it.id == listId }
                    add(SheetItem(PrismIcons.Block, if (out) "Back in my taste" else "Leave out of my taste") {
                        c.exclusions.setPlaylist(com.prism.music.data.foryou.Excluded(listId, title, art), !out)
                        Toast.makeText(context, if (out) "Its plays count towards your recommendations again" else "Its plays won't shape your recommendations", Toast.LENGTH_SHORT).show()
                    })
                }
                addAll(downloadsItems)
                if ((type == CollectionType.PLAYLIST && live.page.owned) || type == CollectionType.PRISM) add(SheetItem(PrismIcons.Delete, "Delete playlist", danger = true) { deleting = true })
            }
            // The songs as the page shows them (filtered, sorted; offline only those on the phone).
            val queue = playable
            val target = com.prism.music.ui.components.Playable.Page(
                title, live.page.subtitle.ifBlank { com.prism.music.data.model.songCount(songs.size) }, art ?: songs.firstOrNull()?.thumbnail,
                album = type == CollectionType.ALBUM, share = shareTarget(),
            ) { queue to source }
            com.prism.music.ui.components.PlayActionsSheet(
                target, items,
                extraTitle = when (type) { CollectionType.ALBUM -> "This album"; CollectionType.DOWNLOADS -> "Downloads"; else -> "This playlist" },
            ) { pageMenu = false }
        }

        if (editing && type == CollectionType.PRISM) EditPlaylistDialog(title, description.orEmpty(), null, onDismiss = { editing = false }) { t, d, _ ->
            editing = false
            c.prismLibrary.rename(page.id, t, d)
            title = t; description = d.ifBlank { null }
        } else if (editing) EditPlaylistDialog(title, description.orEmpty(), privacy ?: "PRIVATE", onDismiss = { editing = false }) { t, d, p ->
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
                }.onFailure { Toast.makeText(context, "Couldn't save: ${it.friendlyMessage()}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete playlist?") },
        text = { Text(if (type == CollectionType.PRISM) "\"$title\" will be deleted from this phone. The songs in it stay where they are." else "\"$title\" will be deleted from your YouTube Music account.") },
        confirmButton = {
            PrismTextButton(onClick = {
                deleting = false
                if (type == CollectionType.PRISM) { c.prismLibrary.delete(page.id); nav.back(); return@PrismTextButton }
                scope.launch {
                    c.library.deletePlaylist(PlaylistItem(page.id, title, "", page.thumbnail))
                        .onSuccess { nav.back() }
                        .onFailure { Toast.makeText(context, "Couldn't delete \"$title\": ${it.friendlyMessage()}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { PrismTextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
    if (sharing) ShareSheet(shareTarget()) { sharing = false }
}

private fun durationText(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h} hr ${m} min"
        else -> "$m min"
    }
}

/**
 * An album's "Other versions" with the editions that share a name told apart: YouTube lists them all as "After Hours
 * (Deluxe) · Album • The Weeknd", so those are looked up (a few at most) and show their song count, year and whether
 * they're explicit instead.
 */
@Composable
private fun rememberTellApart(items: List<com.prism.music.data.model.BrowseItem>): List<com.prism.music.data.model.BrowseItem> {
    val c = LocalContainer.current
    val repeated = remember(items) {
        items.filterIsInstance<com.prism.music.data.model.AlbumItem>().groupBy { it.title.lowercase() }.values.filter { it.size > 1 }.flatten().take(6)
    }
    val details by produceState(emptyMap<String, String>(), repeated) {
        if (repeated.isEmpty()) return@produceState
        value = kotlinx.coroutines.coroutineScope {
            repeated.map { a ->
                async {
                    runCatching { withContext(Dispatchers.IO) { c.ytm.album(a.id) } }.getOrNull()?.let { p ->
                        a.id to listOfNotNull(
                            com.prism.music.data.model.songCount(p.songs.size), p.year?.toString(), if (p.explicit) "Explicit" else null,
                        ).joinToString(" • ")
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }
    return remember(items, details) {
        items.map { item -> details[item.id]?.let { d -> (item as? com.prism.music.data.model.AlbumItem)?.copy(subtitle = d) } ?: item }
    }
}

/** Play and Shuffle side by side, as wide pills. */
@Composable
private fun PlayShufflePills(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(PrismIcons.Play, "Play", primary = true, enabled = enabled, modifier = Modifier.weight(1f), onClick = onPlay)
        Pill(PrismIcons.Shuffle, "Shuffle", primary = false, enabled = enabled, modifier = Modifier.weight(1f), onClick = onShuffle)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Play: a solid pill in the text color, glowing in the spectrum's colors; Shuffle: a pill of glass.
    val bg = if (primary) scheme.onSurface else frostFill(1.6f)
    val fg = if (primary) scheme.surface else scheme.onSurface
    val pill = RoundedCornerShape(24.dp)
    Row(
        modifier.height(48.dp)
            .then(if (primary && enabled) Modifier.shadow(14.dp, pill, ambientColor = Color(0xFFFF4D5E).lit(), spotColor = Color(0xFF7C5CFF).lit()) else Modifier)
            .clip(pill).background(bg)
            .then(if (!primary) Modifier.border(1.dp, com.prism.music.ui.components.glassEdge(), pill) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
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
    onMore: () -> Unit,
) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.prism.music.ui.components.RoundAction(PrismIcons.ArrowBack, "Back", glass = true) { nav.back() }
            Text(
                title, Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = solid() },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (downloadIcon != null) {
                    if (downloading) com.prism.music.ui.components.FloatingDisc { ink ->
                        Box(Modifier.fillMaxSize().clickable(onClickLabel = "Downloading", onClick = onDownload), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
                        }
                    } else com.prism.music.ui.components.RoundAction(downloadIcon, if (downloadIcon == PrismIcons.DownloadDone) "Downloaded" else "Download all", glass = true, onClick = onDownload)
                }
                com.prism.music.ui.components.RoundAction(PrismIcons.Share, "Share", glass = true, onClick = onShare)
                com.prism.music.ui.components.RoundAction(PrismIcons.More, "More", glass = true, onClick = onMore)
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
                        .background(com.prism.music.ui.theme.PrismTokens.spectrumRing(), cover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(when (type) { CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; CollectionType.LIKED -> PrismIcons.HeartFilled; else -> PrismIcons.QueueMusic }, null, Modifier.size(96.dp), tint = scheme.onPrimary)
                }
            }
            if (coverKey != null) GlassSurface(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp), shape = RoundedCornerShape(50), onMedia = true, inPage = true) {
                Box(contentAlignment = Alignment.Center) { Icon(PrismIcons.Edit, "Change picture", Modifier.size(20.dp)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            page.title, style = MaterialTheme.typography.headlineMedium,
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
                    Artwork(it, Modifier.size(22.dp), CircleShape, size = 120, placeholderIcon = PrismIcons.Person)
                    Spacer(Modifier.width(6.dp))
                }
                linked.forEachIndexed { i, a ->
                    if (i > 0) Text(if (i == linked.lastIndex) " & " else ", ", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    Text(
                        a.name, style = MaterialTheme.typography.titleSmall, color = scheme.primary, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { nav.go(Routes.artist(a.id!!)) }.padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        } else if (page.subtitle.isNotBlank()) Text(
            page.subtitle.removePrefix("Playlist • "), style = MaterialTheme.typography.titleSmall, color = scheme.primary,
            fontWeight = FontWeight.Medium, textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                Icon(if (privacy == "PUBLIC") PrismIcons.Globe else if (privacy == "UNLISTED") PrismIcons.Link else PrismIcons.Lock, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
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
                Icon(if (saved) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, null, Modifier.size(18.dp), tint = scheme.primary)
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
                // Liked songs and Downloads have no cover of their own: a soft wash of the split light over the page,
                // corner to corner, with their icon on a round of glass.
                Modifier.fillMaxSize().background(scheme.background).background(
                    Brush.linearGradient(com.prism.music.ui.theme.PrismTokens.spectrumColors.map { (_, c) -> c.copy(alpha = 0.42f) }),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(150.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.6f))
                        .border(1.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(when (type) { CollectionType.DOWNLOADS -> PrismIcons.DownloadDone; CollectionType.LIKED -> PrismIcons.HeartFilled; else -> PrismIcons.QueueMusic }, null, Modifier.size(64.dp), tint = scheme.onSurface)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))
        Text(
            page.title,
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = if (scheme.background.luminance() < 0.5f) 0.35f else 0f), blurRadius = 18f),
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Downloads' albums and playlists, as two rows of cards above the songs (nothing shows without any). Albums: any album
 * two or more downloaded songs come from, newest download first. Playlists: those kept on the phone
 * ([com.prism.music.data.OfflineLibrary], i.e. opened since 1.6.8) whose every song is downloaded, as their own page
 * marks them; a fully downloaded album kept there counts too.
 *
 * The songs' search, genre filter and sort apply to them too (Reddit 1.7: "the filters work for songs, can they for
 * albums?"): albums come from the songs left by the filters ([visible]), kept pages must match what's typed, and the
 * cards follow the songs' order by title, artist or age.
 */
@Composable
private fun DownloadedCollections(visible: List<Song>, sort: SortMode, query: String, filtered: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val all by c.downloads.downloads.collectAsState()
    // Only a finished download changes this (not every progress tick), so the rows aren't worked out again for those.
    val done = remember(all) { all.filterValues { it.state == Download.STATE_COMPLETED }.keys }
    val kept by produceState<List<Pair<String, CollectionPage>>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.offline.pages() }
    }
    val (albums, playlists) = remember(kept, done, visible, sort, query, filtered) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun matches(p: CollectionPage) = words.isEmpty() ||
            "${p.title} ${p.artists.joinToString(" ") { it.name }}".lowercase().let { hay -> words.all { it in hay } }
        // A single's page is one song: that's already in the list below.
        val (keptAlbums, keptPlaylists) = kept.orEmpty()
            .filter { (_, p) -> p.songs.size >= 2 && p.songs.all { it.id in done } && matches(p) }
            .distinctBy { it.second.id }
            .partition { it.second.kind == CollectionKind.ALBUM }
        // One card per album name and artist: YouTube lists some albums twice (two editions, two ids), and the songs
        // can come from either. The card opens the edition most of them are from. With a filter on, only the songs it
        // left count (so a genre shows its albums).
        val pool = if (filtered) visible.filter { it.id in done } else c.downloads.completedSongs()
        val fromSongs = pool.filter { it.album?.id != null }
            .groupBy { it.album!!.title.lowercase() to it.primaryArtist.lowercase() }.values
            .filter { it.size >= 2 }
            .map { songs ->
                val id = songs.groupingBy { it.album!!.id!! }.eachCount().maxBy { it.value }.key
                AlbumItem(id, songs.first().album!!.title, listOf(songs.first().primaryArtist, "${songs.size} downloaded").joinToString(" • "), songs.first().thumbnail)
            }
        val albums = (fromSongs + keptAlbums.map { (id, p) ->
            AlbumItem(id, p.title, listOfNotNull(p.artists.firstOrNull()?.name, com.prism.music.data.model.songCount(p.songs.size)).joinToString(" • "), p.thumbnail)
        }).distinctBy { it.title.lowercase() to it.subtitle.substringBefore(" • ").lowercase() }
        val lists = keptPlaylists.map { (id, p) -> PlaylistItem(id, p.title, com.prism.music.data.model.songCount(p.songs.size), p.thumbnail) }
        fun <T : com.prism.music.data.model.BrowseItem> ordered(items: List<T>): List<T> = when (sort) {
            SortMode.TITLE, SortMode.ALBUM -> items.sortedBy { it.title.lowercase() }
            SortMode.ARTIST -> items.sortedBy { it.subtitle.substringBefore(" • ").lowercase() }
            SortMode.OLDEST -> items.asReversed()
            else -> items
        }
        ordered(albums) to ordered(lists)
    }
    if (albums.isEmpty() && playlists.isEmpty()) return
    Column {
        if (albums.isNotEmpty()) {
            SectionHeader("Albums")
            ItemCarousel(albums, { nav.go(Routes.album(it.id)) })
        }
        if (playlists.isNotEmpty()) {
            SectionHeader("Playlists")
            ItemCarousel(playlists, { nav.go(Routes.playlist(it.id)) })
        }
        SectionHeader("Songs")
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
private fun EditPlaylistDialog(title: String, description: String, privacy: String?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var d by remember { mutableStateOf(description) }
    var p by remember { mutableStateOf(privacy ?: "PRIVATE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.prism.music.ui.components.PrismTextField(t, { t = it.take(150) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                com.prism.music.ui.components.PrismTextField(d, { d = it.take(5000) }, label = { Text("Description") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                // A Prism playlist (no [privacy]) is only on this phone: nobody else can open it anyway.
                if (privacy == null) return@Column
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
        confirmButton = { PrismTextButton(onClick = { onSave(t.trim().ifBlank { title }, d.trim(), p) }) { Text("Save") } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose a playlist's picture: a photo, or one of its songs' covers. Kept in Prism on this phone. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(key: String, title: String, songs: List<Song>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    if (making) { CoverMakerSheet(key, title) { making = false; onDismiss() }; return }
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
                leadingContent = { Icon(PrismIcons.Photo, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            // 1.7.6: a cover made here, from its name, two colors and a pattern.
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Make a cover") },
                supportingContent = { Text("Its name on your colors and a pattern") },
                leadingContent = { Icon(PrismIcons.Palette, null) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { making = true },
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
                leadingContent = { Icon(PrismIcons.History, null) },
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
    val videos by c.downloads.videos.collectAsState()
    val active = remember(all) { all.filter { it.value.state != Download.STATE_COMPLETED && it.value.state != Download.STATE_FAILED } }
    val bytes = remember(all) { all.values.filter { it.state == Download.STATE_COMPLETED }.sumOf { it.bytes } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            "${songs.size} song${if (songs.size == 1) "" else "s"} · ${sizeText(bytes)} on this phone" +
                if (videos.isNotEmpty()) " (${sizeText(bytes + videos.values.sumOf { it.bytes })} with videos)" else "",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        // Saved music videos take far more room than songs, so they get their own line.
        val savedVideos = remember(videos) { videos.values.filter { it.state == Download.STATE_COMPLETED } }
        val videosLoading = remember(videos) { videos.values.count { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING } }
        if (videos.isNotEmpty()) Text(
            "${savedVideos.size} music video${if (savedVideos.size == 1) "" else "s"} · ${sizeText(videos.values.sumOf { it.bytes })}" +
                if (videosLoading > 0) " · $videosLoading downloading" else "",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        val lossless = remember(all) { all.count { it.value.lossless } }
        if (lossless > 0) Text(
            "$lossless lossless from your phone's music files",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        val ids = songs.map { it.id }
        if (ids.any { it in levels }) {
            val withLyrics = ids.count { (levels[it] ?: 0) > 0 }
            val karaoke = ids.count { (levels[it] ?: 0) >= 3 }
            Text(
                "Lyrics for $withLyrics · $karaoke karaoke" + if (working > 0) " · getting more…" else "",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        if (active.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloading ${active.size}…", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            PrismTextButton(onClick = { ask("Cancel ${active.size} downloads that haven't finished?") { c.downloads.removeMany(active.keys) } }) { Text("Cancel") }
        }
        val unfinished = remember(all) { all.values.filter { it.state != Download.STATE_COMPLETED } }
        if (unfinished.isNotEmpty()) DownloadQueueStatus(unfinished)
    }
}

/** How far one download has got, in words: a percentage, or why it isn't moving. */
fun downloadStateText(d: com.prism.music.download.DownloadInfo, waitingForNetwork: Boolean): String = when (d.state) {
    Download.STATE_FAILED -> "Failed"
    Download.STATE_QUEUED -> if (waitingForNetwork) "Waiting for Wi-Fi" else "Queued"
    Download.STATE_STOPPED -> "Paused"
    Download.STATE_RESTARTING -> "Restarting"
    else -> if (d.percent >= 0) "${d.percent.toInt()}%" else "Starting"
}

/**
 * Why unfinished downloads aren't moving, with what to do about it (#55): ones held back for Wi-Fi can go on mobile
 * data, and failed ones can be tried again (they're also retried by themselves when the phone is back online).
 */
@Composable
fun DownloadQueueStatus(unfinished: Collection<com.prism.music.download.DownloadInfo>) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val waiting by c.downloads.waitingForNetwork.collectAsState()
    val failed = unfinished.count { it.state == Download.STATE_FAILED }
    val queued = unfinished.count { it.state == Download.STATE_QUEUED }
    if (waiting && queued > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$queued waiting for Wi-Fi", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        // It switches the setting off for good (Settings → Downloads & storage), so it says so.
        PrismTextButton(onClick = { c.settings.setDownloadWifiOnly(false) }) { Text("Turn off Wi-Fi only") }
    }
    var showFailed by remember { mutableStateOf(false) }
    if (failed > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (failed == 1) "1 download failed" else "$failed downloads failed", style = MaterialTheme.typography.labelMedium, color = scheme.error)
        // Which ones, and why (#98).
        PrismTextButton(onClick = { showFailed = true }) { Text("See which") }
        PrismTextButton(onClick = { c.downloads.retryFailed() }) { Text("Try again") }
    }
    if (showFailed) FailedDownloadsSheet { showFailed = false }
}

/**
 * The downloads that failed, each with why (GitHub #98), and a way to try each again or remove it. Songs that failed
 * before Prism kept the reason say so.
 */
@Composable
fun FailedDownloadsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    val all by c.downloads.downloads.collectAsState()
    val why by c.downloads.failures.collectAsState()
    val failed = remember(all) { all.filter { it.value.state == Download.STATE_FAILED }.toList().sortedByDescending { it.second.addedAt } }
    // Everything tried again or removed: nothing left to show.
    LaunchedEffect(failed.isEmpty()) { if (failed.isEmpty()) onDismiss() }
    ActionSheet(art = null, onDismiss = onDismiss) { close ->
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Text(if (failed.size == 1) "1 download failed" else "${failed.size} downloads failed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
            Text(
                "Prism also tries them again by itself when the phone is back online.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).pane(ui.card)) {
            failed.take(MAX_FAILED_ROWS).forEachIndexed { i, (id, d) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = scheme.onSurface.copy(alpha = 0.07f))
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(d.song?.thumbnail, Modifier.size(46.dp), ui.art, size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.song?.title ?: "Unknown song", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        d.song?.let { Text(it.artistText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(
                            why[id] ?: "Failed before Prism kept the reason",
                            style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 3,
                        )
                    }
                    IconButton(onClick = { c.downloads.retryFailed(setOf(id)) }) { Icon(PrismIcons.Refresh, "Try again") }
                    IconButton(onClick = { c.downloads.removeMany(setOf(id)) }) { Icon(PrismIcons.Delete, "Remove") }
                }
            }
        }
        if (failed.size > MAX_FAILED_ROWS) Text(
            "and ${failed.size - MAX_FAILED_ROWS} more",
            Modifier.padding(start = 28.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PrismTextButton(onClick = { close { c.downloads.removeMany(failed.map { it.first }) } }) { Text("Remove all") }
            com.prism.music.ui.components.PrismButton(onClick = { close { c.downloads.retryFailed() } }) { Text("Try all again") }
        }
    }
}

private const val MAX_FAILED_ROWS = 60

/** Downloads' ⋯ menu entries: lyrics and bulk deletes. */
@Composable
private fun downloadsSheetItems(ask: (String, () -> Unit) -> Unit): List<SheetItem> {
    val c = LocalContainer.current
    val all by c.downloads.downloads.collectAsState()
    var smartIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(all.size) {
        smartIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.db.songs().smartDownloads().map { it.id }.toSet() }
    }
    val smart = all.filter { it.value.state == Download.STATE_COMPLETED && it.key in smartIds }.keys
    val videos by c.downloads.videos.collectAsState()
    return buildList {
        add(SheetItem(PrismIcons.Lyrics, "Get missing lyrics") { c.downloads.backfillLyrics(force = true) })
        if (smart.isNotEmpty()) add(SheetItem(PrismIcons.Delete, "Delete smart downloads (${smart.size})", danger = true) {
            ask("Delete the ${com.prism.music.data.model.songCount(smart.size)} smart downloads added? They may come back later if smart downloads stays on.") { c.downloads.removeMany(smart) }
        })
        if (videos.isNotEmpty()) add(SheetItem(PrismIcons.Delete, "Delete music videos (${sizeText(videos.values.sumOf { it.bytes })})", danger = true) {
            ask("Delete the ${videos.size} saved music videos? The songs stay downloaded.") { videos.keys.forEach(c.downloads::removeVideo) }
        })
        add(SheetItem(PrismIcons.Delete, "Delete all downloads", danger = true) {
            val lossless = all.count { it.value.lossless }
            ask(
                "Delete all ${all.size} downloads (${sizeText(c.downloads.totalBytes)})? This frees the space straight away." +
                    if (lossless > 0) " Your $lossless lossless files stay on the phone; they just leave Downloads." else ""
            ) { c.downloads.removeAll() }
        })
    }
}
