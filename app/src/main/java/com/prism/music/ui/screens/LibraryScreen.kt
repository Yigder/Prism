package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import androidx.compose.foundation.border
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.friendlyMessage
import com.prism.music.data.prefs.LibraryTile
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.prism.music.ui.components.pane
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.LibraryRepository
import com.prism.music.data.SyncState
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.SongItem
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.PagerChips
import com.prism.music.ui.components.SortBar
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.featureInk
import com.prism.music.ui.components.featurePane
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

// The phone's own music files have their own page (Local files, from Playlists), with Songs, Artists and Albums.
private enum class LibTab(val label: String) { PLAYLISTS(tr(R.string.playlists)), SONGS(tr(R.string.songs)), ALBUMS(tr(R.string.albums)), ARTISTS(tr(R.string.artists)), HISTORY(tr(R.string.history)), DOWNLOADS(tr(R.string.downloads)) }

/** One row of History (#118): a play, or a run of plays of the same song one after another. */
private data class HistoryEntry(val song: com.prism.music.data.model.Song, val at: Long, val times: Int)

/** Plays on this phone under day headings (Today, Yesterday, the weekday, then the date), repeats in a row folded into one. */
private fun historyByDay(rows: List<com.prism.music.data.db.HistoryRow>, now: Long = System.currentTimeMillis()): List<Pair<String, List<HistoryEntry>>> {
    val entries = mutableListOf<HistoryEntry>()
    for (r in rows) {
        val last = entries.lastOrNull()
        if (last != null && last.song.id == r.song.id && dayStart(last.at) == dayStart(r.playedAt)) entries[entries.lastIndex] = last.copy(times = last.times + 1)
        else entries += HistoryEntry(r.song.toSong(), r.playedAt, 1)
    }
    val today = dayStart(now)
    val dayMs = 86_400_000L
    val dateFormat = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), tr(R.string.eeedmmmyyyy)), java.util.Locale.getDefault())
    val weekday = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
    return entries.groupBy { dayStart(it.at) }.map { (day, list) ->
        val title = when {
            day >= today -> tr(R.string.today)
            day >= today - dayMs -> tr(R.string.yesterday)
            day > today - 7 * dayMs -> weekday.format(java.util.Date(day))
            else -> dateFormat.format(java.util.Date(day))
        }
        title to list
    }
}

private fun dayStart(at: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = at
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun HistoryHeading(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private enum class SongSort(val label: String) { RECENT(tr(R.string.recently_liked)), TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), ALBUM(tr(R.string.album)) }
/** DOWNLOADED (Reddit 1.7): albums with the most downloaded songs first, the rest after in library order. */
/** Library → Albums' filter (Reddit, 1.7.9.1): by the kind YouTube gives first in a release's subtitle ("Single • …"). */
private enum class AlbumKind {
    ALL, ALBUMS, SINGLES;

    fun shows(a: com.prism.music.data.model.BrowseItem): Boolean {
        val single = a.subtitle.split(" • ").firstOrNull()?.trim().let { it == "Single" || it == "EP" || it == tr(R.string.single) }
        return when (this) { ALL -> true; ALBUMS -> !single; SINGLES -> single }
    }
}

private enum class AlbumSort(val label: String) { LIBRARY(tr(R.string.library_order)), DOWNLOADED(tr(R.string.downloaded_first)), MOST(tr(R.string.most_songs)), TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)) }
private enum class ArtistSort(val label: String) { LIBRARY(tr(R.string.library_order)), MOST(tr(R.string.most_songs)), NAME(tr(R.string.name)) }
/** Saved by name ([com.prism.music.data.LibraryRepository.playlistSort]); CUSTOM is the listener's own order. */
private enum class PlaylistSort(val label: String) { CUSTOM(tr(R.string.your_order)), LIBRARY(tr(R.string.recent_activity)), TITLE(tr(R.string.title)) }

@Composable
fun LibraryScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val playlists by c.library.playlists.collectAsState()
    val remoteAlbums by c.library.albums.collectAsState()
    val remoteArtists by c.library.artists.collectAsState()
    val liked by c.library.likedShown.collectAsState()
    val sync by c.library.syncState.collectAsState()
    val dls by c.downloadState.collectAsState()
    val thumbs by c.library.artistThumbs.collectAsState()
    val favorites by c.artistPrefs.favorites.collectAsState()
    // Playlists and albums kept on the phone (without an account, the only ones there are).
    val prismLists by c.prismLibrary.lists.collectAsState()
    val savedAlbums by c.prismLibrary.albums.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val startPage = remember { LibTab.entries.indexOfFirst { it.name == settings.libraryStartTab }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startPage) { LibTab.entries.size }
    // Each of Playlists, Albums and Artists can be a list or a grid of its own (1.7.6).
    fun listFor(tab: LibTab) = (settings.libraryViews[tab.name] ?: settings.libraryView) == com.prism.music.data.prefs.LibraryView.LIST
    val viewTab = LibTab.entries[pager.currentPage].takeIf { it == LibTab.PLAYLISTS || it == LibTab.ALBUMS || it == LibTab.ARTISTS }
    val asList = listFor(viewTab ?: LibTab.PLAYLISTS)
    val pinned by c.library.pinned.collectAsState()
    val folders by c.prismLibrary.folders.collectAsState()
    // Without the details (1.7.6): titles only under Library's covers and rows.
    fun shown(item: com.prism.music.data.model.BrowseItem): com.prism.music.data.model.BrowseItem =
        if (settings.librarySubtitles) item else when (item) {
            is com.prism.music.data.model.PlaylistItem -> item.copy(subtitle = "")
            is com.prism.music.data.model.AlbumItem -> item.copy(subtitle = "")
            is ArtistItem -> item.copy(subtitle = "")
            else -> item
        }
    /** Pinned ones first (newest pin on top), then the rest as sorted. */
    fun <T : com.prism.music.data.model.BrowseItem> pinnedFirst(list: List<T>): List<T> {
        if (pinned.isEmpty()) return list
        val rank = pinned.withIndex().associate { (i, id) -> id.removePrefix("VL") to i }
        val (top, rest) = list.partition { it.id.removePrefix("VL") in rank }
        return top.sortedBy { rank[it.id.removePrefix("VL")] } + rest
    }
    var itemMenu by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    var foldering by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    foldering?.let { p -> MoveToFolderSheet(p) { foldering = null } }
    if (newFolder) FolderNameDialog(tr(R.string.new_folder), "", { newFolder = false }) { c.prismLibrary.createFolder(it); newFolder = false }
    val onSong: (SongItem) -> Unit = { c.player.playSingle(it.song) }
    // A long press on a playlist offers play / shuffle / delete; the one it asked to delete.
    var menuFor by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    var deleting by remember { mutableStateOf<com.prism.music.data.model.BrowseItem?>(null) }
    val menu = com.prism.music.ui.components.rememberPlayMenu()
    var editingTiles by remember { mutableStateOf(false) }
    if (editingTiles) LibraryTilesSheet { editingTiles = false }

    // The account's saved albums / artists first, then the ones your liked and downloaded songs come from.
    val mine = remember(liked, dls) {
        (liked + dls.values.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }.mapNotNull { it.song }).distinctBy { it.id }
    }
    // Signed in, Albums is the account's saved albums (and ones saved in Prism), as in YouTube Music (#191: 56 saved
    // showed as many more, singles too, since the albums of every liked and downloaded song were added). Without an
    // account, those songs' albums are all there is to show. Downloaded albums are on the Downloads page either way.
    val signedIn = settings.isLoggedIn
    val albums = remember(remoteAlbums, mine, savedAlbums, signedIn) {
        (savedAlbums.map { it.item() } + remoteAlbums + if (signedIn) emptyList() else LibraryRepository.albumsFrom(mine)).distinctBy { it.id }
    }
    // Starred artists lead, marked as favourites.
    val artists = remember(remoteArtists, mine, thumbs, favorites) {
        val starred = favorites.map { f -> ArtistItem(f.id, f.name, tr(R.string.favorite), f.thumbnail) }
        (starred + remoteArtists + LibraryRepository.artistsFrom(mine)).distinctBy { it.id }.map { a ->
            // Your own photo of them (1.7.6) first.
            val own = c.covers.custom(c.covers.artistKey(a.id))
            if (a is ArtistItem && own != null) a.copy(thumbnail = own)
            else if (a.thumbnail == null && a is ArtistItem) a.copy(thumbnail = thumbs[a.id]) else a
        }
    }

    // ---- Sorting (each tab remembers its own, kept between visits)
    var songSort by remember { mutableStateOf(SongSort.entries.firstOrNull { it.name == c.library.sortOf("library:songs") } ?: SongSort.RECENT) }
    var songQuery by rememberSaveable { mutableStateOf("") }
    var albumSort by remember { mutableStateOf(AlbumSort.entries.firstOrNull { it.name == c.library.sortOf("library:albums") } ?: AlbumSort.LIBRARY) }
    var artistSort by remember { mutableStateOf(ArtistSort.entries.firstOrNull { it.name == c.library.sortOf("library:artists") } ?: ArtistSort.LIBRARY) }
    val sortedSongs = remember(liked, songSort) {
        when (songSort) {
            SongSort.RECENT -> liked
            SongSort.TITLE -> liked.sortedBy { it.title.lowercase() }
            SongSort.ARTIST -> liked.sortedWith(compareBy<com.prism.music.data.model.Song> { it.primaryArtist.lowercase() }.thenBy { it.album?.title?.lowercase() ?: "" })
            SongSort.ALBUM -> liked.sortedWith(compareBy<com.prism.music.data.model.Song> { it.album?.title?.lowercase() ?: "\uFFFF" }.thenBy { it.title.lowercase() })
        }
    }
    val albumSongs = remember(mine) { mine.filter { it.album?.id != null }.groupBy { it.album!!.id!! } }
    // Downloaded songs per album: by the album's id, and by its title for albums YouTube lists under another edition's id.
    val downloadedPerAlbum = remember(dls) {
        val done = dls.values.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }.mapNotNull { it.song }
        done.mapNotNull { s -> s.album?.id?.let { it to s } }.groupingBy { it.first }.eachCount() to
            done.mapNotNull { s -> s.album?.title?.lowercase()?.let { it to s } }.groupingBy { it.first }.eachCount()
    }
    val sortedAlbums = remember(albums, albumSort, albumSongs, downloadedPerAlbum) {
        fun artistOf(a: com.prism.music.data.model.BrowseItem) = albumSongs[a.id]?.first()?.primaryArtist ?: a.subtitle.split(" • ").firstOrNull { it != "Album" && it != "Single" && it != "EP" } ?: ""
        fun downloaded(a: com.prism.music.data.model.BrowseItem) = downloadedPerAlbum.first[a.id] ?: downloadedPerAlbum.second[a.title.lowercase()] ?: 0
        when (albumSort) {
            AlbumSort.LIBRARY -> albums
            AlbumSort.DOWNLOADED -> albums.sortedByDescending { downloaded(it) }
            AlbumSort.MOST -> albums.sortedByDescending { albumSongs[it.id]?.size ?: 0 }
            AlbumSort.TITLE -> albums.sortedBy { it.title.lowercase() }
            AlbumSort.ARTIST -> albums.sortedWith(compareBy<com.prism.music.data.model.BrowseItem> { artistOf(it).lowercase() }.thenBy { it.title.lowercase() })
        }
    }
    var albumKind by remember { mutableStateOf(AlbumKind.entries.firstOrNull { it.name == c.library.sortOf("library:albums:kind") } ?: AlbumKind.ALL) }
    val albumKinds = remember(albums) { albums.map { AlbumKind.ALBUMS.shows(it) }.toSet() }
    val artistSongs = remember(mine) { mine.flatMap { s -> s.artists.mapNotNull { it.id } }.groupingBy { it }.eachCount() }
    val sortedArtists = remember(artists, artistSort, artistSongs, favorites) {
        val fav = favorites.map { it.id }.toSet()
        when (artistSort) {
            ArtistSort.LIBRARY -> artists
            ArtistSort.MOST -> artists.sortedWith(compareByDescending<com.prism.music.data.model.BrowseItem> { it.id in fav }.thenByDescending { artistSongs[it.id] ?: 0 })
            ArtistSort.NAME -> artists.sortedWith(compareByDescending<com.prism.music.data.model.BrowseItem> { it.id in fav }.thenBy { it.title.lowercase().removePrefix("the ") })
        }
    }
    // Playlists: YouTube Music's order, by title, or the listener's own (arranged in Rearrange mode).
    val playlistOrder by c.library.playlistOrder.collectAsState()
    val playlistSortName by c.library.playlistSort.collectAsState()
    val playlistSort = PlaylistSort.entries.firstOrNull { it.name == playlistSortName }
        ?: if (playlistOrder.isEmpty()) PlaylistSort.LIBRARY else PlaylistSort.CUSTOM
    // Signed out, YouTube playlists saved on the phone (H7) sit with Prism's own.
    val savedPlaylists by c.prismLibrary.playlists.collectAsState()
    val myPlaylists = remember(playlists, prismLists, savedPlaylists, settings.isLoggedIn) {
        // A Spotify playlist (1.8.1) says so where Prism's own say "Prism playlist".
        prismLists.map { l -> l.item().let { if (c.spotify.isSpotifyPlaylist(l.id)) it.copy(subtitle = tr(R.string.spot_playlist_subtitle, com.prism.music.data.model.songCount(l.songs.size))) else it } } + (if (settings.isLoggedIn) emptyList() else savedPlaylists.map { it.item() }) + playlists.filter { it.id != "LM" }
    }
    // Spotify playlists the listener follows or saved (1.8.1): named after the playlists, greyed, their songs not shared.
    val spotifyFollowed by c.spotify.followedShown.collectAsState()
    var followedOpen by remember { mutableStateOf<com.prism.music.data.spotify.SpotifyPlaylist?>(null) }
    followedOpen?.let { p -> SpotifyFollowedSheet(p) { followedOpen = null } }
    var openingLink by remember { mutableStateOf(false) }
    if (openingLink) OpenPlaylistLinkDialog({ openingLink = false }) { id -> openingLink = false; nav.go(Routes.playlist(id)) }
    // Playlists in a folder (1.7.6) show in it, not in the list; pinned ones lead.
    val inFolders = remember(folders) { folders.flatMap { f -> f.playlists.map { it.removePrefix("VL") } }.toSet() }
    val sortedPlaylists = remember(myPlaylists, playlistOrder, playlistSort, pinned, inFolders) {
        pinnedFirst(when (playlistSort) {
            PlaylistSort.CUSTOM -> LibraryRepository.inOrder(myPlaylists, playlistOrder)
            PlaylistSort.LIBRARY -> myPlaylists
            PlaylistSort.TITLE -> myPlaylists.sortedBy { it.title.lowercase() }
        }).filterNot { it.id.removePrefix("VL") in inFolders }
    }
    var rearranging by rememberSaveable { mutableStateOf(false) }
    // Moving one (or starting to) saves the whole order as shown, and switches to it.
    fun saveOrder(list: List<com.prism.music.data.model.BrowseItem>) {
        c.library.setPlaylistOrder(list.map { it.id })
        c.library.setPlaylistSort(PlaylistSort.CUSTOM.name)
    }
    val localTracks by c.localMusic.tracks.collectAsState()

    LaunchedEffect(pager.currentPage == LibTab.ARTISTS.ordinal, artists.size) {
        if (pager.currentPage == LibTab.ARTISTS.ordinal) c.library.loadArtistThumbs(artists.filter { it.thumbnail == null }.take(60).map { it.id })
    }

    LaunchedEffect(settings.isLoggedIn) {
        // Once per session (Prism also syncs at launch); the refresh button forces another.
        if (settings.isLoggedIn && (playlists.isEmpty() || sync == SyncState.IDLE)) c.library.sync()
    }

    Column(Modifier.fillMaxSize()) {
        com.prism.music.ui.components.ScreenHeader(tr(R.string.library)) {
            if (settings.isLoggedIn) {
                if (sync == SyncState.SYNCING) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(22.dp), stroke = 2.dp) }
                else com.prism.music.ui.components.RoundAction(PrismIcons.Sync, tr(R.string.sync_with_youtube_music)) { scope.launch { c.library.sync() } }
            }
            // Pick and order the Playlists tab's tiles (it was a tile of its own at the end of them until 1.7.5).
            com.prism.music.ui.components.RoundAction(PrismIcons.Edit, tr(R.string.edit_tiles)) { editingTiles = true }
            // This tab's own view (1.7.6); the others keep theirs.
            if (viewTab != null) com.prism.music.ui.components.RoundAction(
                if (asList) PrismIcons.Customize else PrismIcons.Queue,
                if (asList) tr(R.string.show_as_grid) else tr(R.string.show_as_list),
            ) { c.settings.setLibraryTabView(viewTab.name, if (asList) com.prism.music.data.prefs.LibraryView.GRID else com.prism.music.data.prefs.LibraryView.LIST) }
            if (com.prism.music.data.prefs.NavTab.SETTINGS !in settings.navTabs) com.prism.music.ui.components.RoundAction(PrismIcons.SettingsFilled, tr(R.string.settings)) { nav.go(Routes.SETTINGS) }
        }
        PagerChips(LibTab.entries.map { it.label }, pager)

        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            val tab = LibTab.entries[page]
            @Suppress("NAME_SHADOWING") val asList = listFor(tab)
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            // Swapping playlists and tiles: back to the top, or the list would stay on the tile it showed and hide what moved above.
            if (tab == LibTab.PLAYLISTS) LaunchedEffect(settings.libraryPlaylistsFirst) { gridState.scrollToItem(0) }
            // History (#118): this phone's plays, or the account's (YouTube Music's own history: the app and the web too).
            var fromAccount by rememberSaveable { mutableStateOf(false) }
            var historyLimit by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(300) }
            val phoneHistory = if (tab == LibTab.HISTORY) {
                val rows by remember(historyLimit) { c.db.plays().historyFlow(historyLimit, 0) /* #263: every kept play (how long one takes is the listener's pick) */ }.collectAsState(null)
                remember(rows) { rows?.let { it.size to historyByDay(it) } }
            } else null
            var accountReload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val accountHistory = if (tab == LibTab.HISTORY) {
                androidx.compose.runtime.produceState<Result<List<Pair<String, List<com.prism.music.data.model.Song>>>>?>(null, fromAccount, settings.isLoggedIn, accountReload) {
                    value = null
                    if (fromAccount && settings.isLoggedIn) value = runCatching { c.ytm.historySections() }
                }.value
            } else null
            val fromAccountShown = fromAccount && settings.isLoggedIn
            LazyVerticalGrid(
                when {
                    asList -> GridCells.Fixed(1)
                    settings.libraryColumns.count > 0 -> GridCells.Fixed(settings.libraryColumns.count)
                    else -> GridCells.Adaptive(160.dp)
                },
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = if (asList) 0.dp else 12.dp, end = if (asList) 0.dp else 12.dp, top = 4.dp, bottom = bottomPadding + 16.dp),
            ) {
                val empty = when (tab) {
                    LibTab.ALBUMS -> albums.isEmpty()
                    LibTab.ARTISTS -> artists.isEmpty()
                    LibTab.SONGS -> liked.isEmpty()
                    else -> false
                }
                if (empty) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!settings.isLoggedIn) {
                            // Without an account the library is still the listener's own, kept on the phone.
                            when (tab) {
                                LibTab.SONGS -> EmptyState(PrismIcons.HeartFilled, tr(R.string.no_liked_songs_yet), tr(R.string.tap_the_heart_on_the_player))
                                LibTab.ALBUMS -> EmptyState(PrismIcons.Album, tr(R.string.no_albums_yet), tr(R.string.tap_the_bookmark_on_an_album))
                                else -> EmptyState(PrismIcons.Person, tr(R.string.no_artists_yet), tr(R.string.star_an_artist_on_their_page))
                            }
                            PrismTextButton(onClick = { nav.go(Routes.LOGIN) }) { Text(tr(R.string.or_sign_in_to_sync_your)) }
                        } else {
                            EmptyState(PrismIcons.LibraryFilled, tr(R.string.nothing_here_yet), tr(R.string.like_or_download_songs_or_star))
                        }
                    }
                }
                when (tab) {
                    LibTab.PLAYLISTS -> {
                      // The tiles picked and ordered with the edit button at the top (Settings → Library & Replay too).
                      val tilesPart: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
                        settings.libraryTiles.forEach { t ->
                            when (t) {
                                LibraryTile.LIKED -> item(key = "tile-liked") { ShortcutTile(tr(R.string.liked_songs), com.prism.music.data.model.songCount(liked.size), PrismIcons.HeartFilled, asList, art = c.covers.custom("liked"), onLongClick = { menu(com.prism.music.ui.components.Playable.Liked) }) { nav.go(Routes.liked()) } }
                                LibraryTile.DOWNLOADS -> item(key = "tile-downloads") { ShortcutTile(tr(R.string.downloads), com.prism.music.data.model.songCount(dls.values.count { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }), PrismIcons.DownloadDone, asList, onLongClick = { menu(com.prism.music.ui.components.Playable.Downloads) }) { nav.go(Routes.DOWNLOADS) } }
                                LibraryTile.LOCAL -> item(key = "tile-local") {
                                    ShortcutTile(
                                        tr(R.string.local_files), if (localTracks.isEmpty()) tr(R.string.music_files_on_this_phone) else com.prism.music.data.model.songCount(localTracks.size),
                                        PrismIcons.Phone, asList,
                                    ) { nav.go(Routes.LOCAL) }
                                }
                                LibraryTile.REPLAY -> item(key = "tile-replay") { ShortcutTile(tr(R.string.replay_2), tr(R.string.your_listening_recap), if (LibraryTile.HISTORY in settings.libraryTiles) PrismIcons.Replay else PrismIcons.History, asList) { nav.go(Routes.REPLAY) } }
                                // Replay keeps its clock unless History's tile is there too, which takes it (Replay gets its chart).
                                LibraryTile.HISTORY -> item(key = "tile-history") {
                                    ShortcutTile(tr(R.string.history), tr(R.string.everything_you_ve_played), PrismIcons.History, asList) {
                                        scope.launch { pager.animateScrollToPage(LibTab.HISTORY.ordinal) }
                                    }
                                }
                                // Home's Podcasts button turned off (#28): Podcasts lives here instead.
                                LibraryTile.PODCASTS -> if (!settings.homePodcastsButton) item(key = "tile-podcasts") { ShortcutTile(tr(R.string.podcasts), tr(R.string.shows_and_episodes), PrismIcons.Podcasts, asList) { nav.go(Routes.PODCASTS) } }
                                LibraryTile.TOGETHER -> item(key = "tile-together") { ShortcutTile(tr(R.string.listen_together), tr(R.string.with_friends_on_the_same_wi), PrismIcons.Groups, asList) { nav.go(Routes.TOGETHER) } }
                                LibraryTile.BLEND -> item(key = "tile-blend") { ShortcutTile(tr(R.string.blend), tr(R.string.a_playlist_of_two_tastes), PrismIcons.Blend, asList) { nav.go(Routes.BLENDS) } }
                                LibraryTile.RECOGNIZED -> item(key = "tile-recognized") { ShortcutTile(tr(R.string.recognized), tr(R.string.songs_found_by_listening), PrismIcons.Recognize, asList) { nav.go(Routes.RECOGNIZED) } }
                                LibraryTile.NEW_PLAYLIST -> item(key = "tile-new") { ShortcutTile(tr(R.string.new_playlist), if (settings.isLoggedIn) tr(R.string.make_one_on_youtube_music) else tr(R.string.kept_on_this_phone), PrismIcons.Add, asList) { creating = true } }
                                LibraryTile.IMPORT -> if (settings.isLoggedIn) item(key = "tile-import") { ShortcutTile(tr(R.string.import_playlists), tr(R.string.from_spotify_apple_music), PrismIcons.PlaylistAdd, asList) { nav.go(Routes.IMPORT) } }
                            }
                        }
                      }
                      val playlistsPart: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
                        // Signed out: playlists made here are kept on the phone; signing in brings YouTube Music's too.
                        if (!settings.isLoggedIn && myPlaylists.isEmpty() && spotifyFollowed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(PrismIcons.QueueMusic, tr(R.string.your_playlists), tr(R.string.make_one_with_new_playlist_or))
                                PrismTextButton(onClick = { nav.go(Routes.LOGIN) }) { Text(tr(R.string.or_sign_in_to_bring_your)) }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            // The count and sort on a line of their own: beside the two buttons, on a 448 dp phone, the
                            // count was squeezed into a column a letter wide.
                            Column(Modifier.padding(end = 8.dp)) {
                            if (rearranging) Text(
                                tr(R.string.hold_and_drag_or_use_the), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                            ) else if (myPlaylists.size > 1) SortBar(trp(R.plurals.n_playlists, myPlaylists.size), PlaylistSort.entries, playlistSort, { it.label }) {
                                c.library.setPlaylistSort(it.name)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                                // A playlist from a link (H7), without YouTube's share menu.
                                if (!rearranging) com.prism.music.ui.components.PrismChip(false, { openingLink = true }, tr(R.string.open_a_link), icon = PrismIcons.Link)
                                if (myPlaylists.size > 1) com.prism.music.ui.components.PrismChip(
                                    rearranging,
                                    {
                                        if (!rearranging) saveOrder(sortedPlaylists)
                                        rearranging = !rearranging
                                    },
                                    if (rearranging) tr(R.string.done) else tr(R.string.rearrange),
                                    icon = if (rearranging) PrismIcons.Check else PrismIcons.SwapVertical,
                                )
                            }
                            }
                        }
                        if (rearranging) item(span = { GridItemSpan(maxLineSpan) }) {
                            RearrangePlaylists(sortedPlaylists, ::saveOrder)
                        } else {
                            // Folders first (1.7.6), each opening its playlists.
                            items(folders, key = { "f" + it.id }) { f ->
                                val first = f.playlists.firstNotNullOfOrNull { id -> myPlaylists.firstOrNull { com.prism.music.data.PrismLibrary.samePlaylist(it.id, id) } }
                                ShortcutTile(
                                    f.name, if (!settings.librarySubtitles) "" else trp(R.plurals.folder_n_playlists, f.playlists.size),
                                    PrismIcons.Folder, asList, art = first?.let { c.covers.art(it) },
                                ) { nav.go(Routes.folder(f.id)) }
                            }
                            items(sortedPlaylists, key = { "p" + it.id }) { p ->
                                LibraryItem(shown(p), asList, onLongClick = { menuFor = p }) { nav.open(p, onSong) }
                            }
                            items(spotifyFollowed, key = { "sf" + it.id }) { p ->
                                val item = com.prism.music.data.model.PlaylistItem("spotify-followed:${p.id}", p.name, tr(R.string.spot_followed_subtitle, p.ownerName.ifBlank { tr(R.string.spotify) }), p.image)
                                Box(Modifier.alpha(0.6f)) { LibraryItem(item, asList) { followedOpen = p } }
                            }
                        }
                      }
                      // Playlists above the tiles, or below them as Prism ships (Edit tiles → "Playlists first").
                      if (settings.libraryPlaylistsFirst && myPlaylists.isNotEmpty()) {
                          playlistsPart()
                          item(key = "tiles-gap", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
                          tilesPart()
                      } else {
                          tilesPart()
                          playlistsPart()
                      }
                    }
                    LibTab.SONGS -> {
                        // As the Liked songs page has them (#184: the tab had no search, Shuffle or Play): a search through
                        // your liked songs, and Shuffle / Play for what's shown.
                        val source = com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.LIBRARY, tr(R.string.liked_songs))
                        val shownSongs = if (songQuery.isBlank()) sortedSongs else sortedSongs.filter { s ->
                            listOf(s.title, s.artistText, s.album?.title.orEmpty()).any { it.contains(songQuery.trim(), ignoreCase = true) }
                        }
                        if (liked.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.prism.music.ui.components.SearchField(songQuery, tr(R.string.search_liked_songs), { q -> songQuery = q }, Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                com.prism.music.ui.components.RoundAction(PrismIcons.Shuffle, tr(R.string.shuffle)) { if (shownSongs.isNotEmpty()) c.player.playQueue(shownSongs, 0, source, shuffle = true) }
                                Spacer(Modifier.width(8.dp))
                                com.prism.music.ui.components.RoundAction(PrismIcons.Play, tr(R.string.widget_play)) { if (shownSongs.isNotEmpty()) c.player.playQueue(shownSongs, 0, source) }
                            }
                        }
                        if (liked.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar(com.prism.music.data.model.songCount(shownSongs.size), SongSort.entries, songSort, { it.label }) { songSort = it; c.library.setSort("library:songs", it.name) }
                        }
                        items(shownSongs, key = { "s" + it.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                            SongRow(s) { c.player.playQueue(shownSongs, shownSongs.indexOf(s), source) }
                        }
                    }
                    LibTab.ALBUMS -> {
                        if (albums.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar(trp(R.plurals.n_albums, albums.size), AlbumSort.entries, albumSort, { it.label }) { albumSort = it; c.library.setSort("library:albums", it.name) }
                        }
                        // Albums or singles and EPs (Reddit, 1.7.9.1: YouTube's library fills with singles), when there are both.
                        if (albumKinds.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((kind, label) in listOf(AlbumKind.ALL to tr(R.string.all), AlbumKind.ALBUMS to tr(R.string.albums), AlbumKind.SINGLES to tr(R.string.singles_eps))) {
                                    com.prism.music.ui.components.PrismChip(albumKind == kind, { albumKind = kind; c.library.setSort("library:albums:kind", kind.name) }, label)
                                }
                            }
                        }
                        items(pinnedFirst(sortedAlbums.filter { albumKind.shows(it) }), key = { "a" + it.id }) { a -> LibraryItem(shown(a), asList, onLongClick = { itemMenu = a }) { nav.open(a, onSong) } }
                    }
                    LibTab.ARTISTS -> {
                        if (artists.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            SortBar(trp(R.plurals.n_artists, artists.size), ArtistSort.entries, artistSort, { it.label }) { artistSort = it; c.library.setSort("library:artists", it.name) }
                        }
                        items(pinnedFirst(sortedArtists), key = { "r" + it.id }) { a -> LibraryItem(shown(a), asList, onLongClick = { itemMenu = a }) { nav.open(a, onSong) } }
                    }
                    LibTab.HISTORY -> {
                        if (settings.isLoggedIn) item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.prism.music.ui.components.PrismChip(!fromAccount, { fromAccount = false }, tr(R.string.on_this_phone), icon = PrismIcons.Phone)
                                com.prism.music.ui.components.PrismChip(fromAccount, { fromAccount = true }, tr(R.string.your_account), icon = PrismIcons.Person)
                            }
                        }
                        if (fromAccountShown) {
                            val result = accountHistory
                            val sections = result?.getOrNull()
                            when {
                                result == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(28.dp)) }
                                }
                                sections == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        EmptyState(PrismIcons.History, tr(R.string.couldn_t_load_your_history), result.exceptionOrNull()?.friendlyMessage() ?: tr(R.string.try_again_in_a_moment))
                                        PrismTextButton(onClick = { accountReload++ }) { Text(tr(R.string.try_again)) }
                                    }
                                }
                                sections.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                                    EmptyState(PrismIcons.History, tr(R.string.no_history_on_your_account), tr(R.string.songs_you_play_on_youtube_music))
                                }
                                else -> sections.forEachIndexed { si, (title, songs) ->
                                    if (title.isNotBlank()) item(key = "ah$si", span = { GridItemSpan(maxLineSpan) }) { HistoryHeading(title) }
                                    items(songs.size, key = { "a$si-$it" }, span = { GridItemSpan(maxLineSpan) }) { i ->
                                        SongRow(songs[i]) { c.player.playSingle(songs[i]) }
                                    }
                                }
                            }
                        } else {
                            val (count, days) = phoneHistory ?: (0 to emptyList())
                            if (phoneHistory != null && days.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(PrismIcons.History, tr(R.string.nothing_played_yet), tr(R.string.songs_you_play_in_prism_show))
                            }
                            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                            days.forEachIndexed { di, (title, entries) ->
                                item(key = "ph$di", span = { GridItemSpan(maxLineSpan) }) { HistoryHeading(title) }
                                items(entries.size, key = { "p$di-${entries[it].at}" }, span = { GridItemSpan(maxLineSpan) }) { i ->
                                    val e = entries[i]
                                    val at = time.format(java.util.Date(e.at))
                                    SongRow(e.song, trailingInfo = if (e.times > 1) "$at · ${e.times}×" else at) { c.player.playSingle(e.song) }
                                }
                            }
                            if (count >= historyLimit) item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    PrismTextButton(onClick = { historyLimit += 300 }) { Text(tr(R.string.show_more)) }
                                }
                            }
                        }
                    }
                    LibTab.DOWNLOADS -> item(span = { GridItemSpan(maxLineSpan) }) { DownloadsSummary() }
                }
            }
        }
    }

    if (creating) {
        val context = androidx.compose.ui.platform.LocalContext.current
        com.prism.music.ui.components.NewPlaylistDialog(onDismiss = { creating = false }, local = !settings.isLoggedIn) { name, privacy ->
            creating = false
            if (!settings.isLoggedIn) { nav.go(Routes.playlist(c.prismLibrary.create(name))); return@NewPlaylistDialog }
            scope.launch {
                val id = runCatching { c.ytm.createPlaylist(name, emptyList(), privacy = privacy) }.getOrNull()
                if (id == null) android.widget.Toast.makeText(context, tr(R.string.couldn_t_make_the_playlist), android.widget.Toast.LENGTH_SHORT).show()
                else {
                    c.library.refreshCollections()
                    nav.go(Routes.playlist(id))
                }
            }
        }
    }

    fun pinItem(p: com.prism.music.data.model.BrowseItem): com.prism.music.ui.components.SheetItem {
        val on = pinned.any { it.removePrefix("VL") == p.id.removePrefix("VL") }
        return com.prism.music.ui.components.SheetItem(if (on) PrismIcons.PinFilled else PrismIcons.Pin, if (on) tr(R.string.unpin) else tr(R.string.pin_to_the_top)) {
            c.library.setPinned(pinned.firstOrNull { it.removePrefix("VL") == p.id.removePrefix("VL") } ?: p.id, !on)
        }
    }
    menuFor?.let { p ->
        // A playlist kept on the phone without an account is only a bookmark: it comes out of the library, not deleted.
        val deletable = com.prism.music.data.PrismLibrary.isPrismList(p.id) || settings.isLoggedIn
        com.prism.music.ui.components.PlayActionsSheet(
            com.prism.music.ui.components.Playable.Of(p),
            extra = listOfNotNull(
                pinItem(p),
                com.prism.music.ui.components.SheetItem(PrismIcons.Folder, tr(R.string.move_to_a_folder)) { foldering = p },
                com.prism.music.ui.components.SheetItem(PrismIcons.SwapVertical, tr(R.string.rearrange_playlists)) { saveOrder(sortedPlaylists); rearranging = true },
                if (deletable) com.prism.music.ui.components.SheetItem(PrismIcons.Delete, tr(R.string.delete_playlist_2), danger = true) { deleting = p } else null,
            ),
        ) { menuFor = null }
    }
    itemMenu?.let { a ->
        com.prism.music.ui.components.PlayActionsSheet(com.prism.music.ui.components.Playable.Of(a), extra = listOf(pinItem(a))) { itemMenu = null }
    }

    deleting?.let { p ->
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr(R.string.delete_playlist)) },
            text = {
                Text(
                    if (com.prism.music.data.PrismLibrary.isPrismList(p.id)) tr(R.string.will_be_deleted_from_this_phone, p.title)
                    else tr(R.string.will_be_deleted_from_your_youtube_2, p.title),
                )
            },
            confirmButton = {
                PrismTextButton(onClick = {
                    deleting = null
                    if (com.prism.music.data.PrismLibrary.isPrismList(p.id)) { c.prismLibrary.delete(p.id); return@PrismTextButton }
                    scope.launch {
                        c.library.deletePlaylist(p).onFailure {
                            android.widget.Toast.makeText(context, tr(R.string.couldn_t_delete, p.title, it.friendlyMessage()), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { PrismTextButton(onClick = { deleting = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}
/** The playlists as a list to put in order: hold and drag a row, or nudge it with its arrows. */
@Composable
private fun RearrangePlaylists(playlists: List<com.prism.music.data.model.BrowseItem>, onReorder: (List<com.prism.music.data.model.BrowseItem>) -> Unit) {
    val c = LocalContainer.current
    val ui = com.prism.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    fun move(from: Int, to: Int) {
        if (to !in playlists.indices) return
        onReorder(playlists.toMutableList().apply { add(to, removeAt(from)) })
    }
    com.prism.music.ui.components.ReorderList(
        playlists, { it.id }, onReorder,
        Modifier.padding(horizontal = 8.dp).pane(ui.card).padding(vertical = 6.dp),
    ) { p, i, held, drag ->
        Row(
            drag.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.prism.music.ui.components.Artwork(
                c.covers.art(p), Modifier.size(44.dp), ui.albumArt, size = 226,
                placeholderIcon = PrismIcons.QueueMusic,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                p.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!held) {
                IconButton(onClick = { move(i, i - 1) }, enabled = i > 0) { Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, p.title)) }
                IconButton(onClick = { move(i, i + 1) }, enabled = i < playlists.lastIndex) { Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, p.title)) }
            } else Spacer(Modifier.width(96.dp))
            // The grip moves the playlist at once; the rest of the row after a hold.
            com.prism.music.ui.components.DragGrip(Modifier.dragHandle().padding(end = 8.dp), tr(R.string.drag, p.title))
        }
    }
}

/** An album, artist or playlist as a card (grid view) or a row (list view). */
@Composable
private fun LibraryItem(item: com.prism.music.data.model.BrowseItem, asList: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    if (asList) ResultRow(item, onLongClick, onClick) else ItemCard(item, width = 400.dp, onLongClick = onLongClick, onClick = onClick)
}

/** Which of the spectrum's hues a Library page's tile takes, so they run red to violet down the list. */
private fun shortcutHue(icon: ImageVector): Int = when (icon) {
    PrismIcons.DownloadDone, PrismIcons.Download -> 0
    PrismIcons.Phone -> 1
    PrismIcons.History -> 2
    PrismIcons.Groups -> 3
    PrismIcons.Blend -> 4
    PrismIcons.Recognize -> 5
    else -> 6
}

@Composable
private fun ShortcutTile(title: String, subtitle: String, icon: ImageVector, asList: Boolean, art: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    @Composable
    fun Cover(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, iconSize: Dp) {
        // A picture the listener chose (Liked songs can have one too).
        if (art != null) com.prism.music.ui.components.Artwork(art, modifier, shape, size = 544)
        // Liked songs wears the whole prism; the other pages take one of its hues each, in the spectrum's order.
        else Box(
            modifier.clip(shape).background(
                if (icon == PrismIcons.HeartFilled) com.prism.music.ui.theme.PrismTokens.spectrumRing()
                else androidx.compose.ui.graphics.SolidColor(com.prism.music.ui.theme.PrismTokens.hue(shortcutHue(icon))),
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(iconSize), tint = androidx.compose.ui.graphics.Color.White) }
    }
    if (asList) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = ui.gap(7.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(Modifier.size(56.dp), ui.smallArt, 26.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        return
    }
    Column(Modifier.padding(4.dp).clip(ui.tile).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp)) {
        Cover(Modifier.fillMaxWidth().aspectRatio(1f), ui.art, 56.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/**
 * Edit Library's tiles (Reddit 1.7): tick the ones to show and move them up or down. Changes show at once in Library
 * behind the sheet. Opened from the edit button at the top of Library and from Settings → Library & Replay.
 */
@Composable
fun LibraryTilesSheet(onDismiss: () -> Unit) {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val tiles = s.libraryTiles
    com.prism.music.ui.components.PrismSheet(onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp)) {
            Text(tr(R.string.library_tiles), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                tr(R.string.tick_the_tiles_to_show_at),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            @Composable
            fun TileRow(t: LibraryTile, i: Int, modifier: Modifier, grip: Modifier?, held: Boolean) {
                val on = t in tiles
                Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrismCheck(on, label = t.label) { v -> prefs.setLibraryTiles(if (v) tiles + t else tiles - t) }
                    Text(
                        t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (on && !held) {
                        androidx.compose.material3.IconButton(onClick = { prefs.setLibraryTiles(tiles.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                            Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, t.label))
                        }
                        androidx.compose.material3.IconButton(onClick = { prefs.setLibraryTiles(tiles.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < tiles.lastIndex) {
                            Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, t.label))
                        }
                    }
                    if (grip != null) com.prism.music.ui.components.DragGrip(grip.padding(horizontal = 4.dp), tr(R.string.drag, t.label))
                }
            }
            com.prism.music.ui.components.ReorderList(tiles, { it }, { prefs.setLibraryTiles(it) }) { t, i, held, mod -> TileRow(t, i, mod, Modifier.dragHandle(), held) }
            LibraryTile.entries.filter { it !in tiles }.forEach { t -> TileRow(t, -1, Modifier, null, false) }
            PrismTextButton({ prefs.setLibraryTiles(LibraryTile.entries) }, Modifier.padding(start = 12.dp, top = 8.dp)) { Text(tr(R.string.show_all_in_prism_s_order)) }
            Text(
                tr(R.string.where_your_playlists_go), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            ChoiceCards(
                listOf(false, true), s.libraryPlaylistsFirst,
                title = { if (it) tr(R.string.playlists_first) else tr(R.string.tiles_first) },
                detail = { if (it) tr(R.string.your_playlists_at_the_top_the) else tr(R.string.the_tiles_at_the_top_your) },
                onSelect = { prefs.setLibraryPlaylistsFirst(it) },
            ) { first -> LibraryOrderSample(first) }
        }
    }
}

/** A small Library page: a row of tiles and a few playlist rows, playlists on top when [playlistsFirst]. */
@Composable
private fun LibraryOrderSample(playlistsFirst: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val tiles: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(PrismIcons.HeartFilled, PrismIcons.DownloadDone, PrismIcons.Phone).forEach { icon ->
                Box(
                    Modifier.weight(1f).height(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(scheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant) }
            }
        }
    }
    val playlists: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Color(0xFFE0735A), Color(0xFF5A8BFF)).forEach { col ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(col))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).height(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.onSurface.copy(alpha = 0.22f)))
                }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().height(96.dp).clip(shape).background(scheme.background).then(Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.1f), shape)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playlistsFirst) { playlists(); tiles() } else { tiles(); playlists() }
    }
}

@Composable
private fun DownloadsSummary() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val dls by c.downloadState.collectAsState()
    val done = dls.values.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    val active = dls.values.filter { it.state != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    val waiting by c.downloads.waitingForNetwork.collectAsState()
    // Only what's actually moving: ones waiting for Wi-Fi, paused or failed are counted under the card, with what to do.
    val running = active.count {
        when (it.state) {
            androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING, androidx.media3.exoplayer.offline.Download.STATE_RESTARTING -> true
            androidx.media3.exoplayer.offline.Download.STATE_QUEUED -> !waiting
            else -> false
        }
    }
    val ink = featureInk()
    Column(Modifier.padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().featurePane(com.prism.music.ui.theme.LocalUi.current.card)
                .clickable { nav.go(Routes.DOWNLOADS) }.padding(20.dp),
        ) {
            Column {
                Text(tr(R.string.offline, com.prism.music.data.model.songCount(done.size)), style = MaterialTheme.typography.titleLarge, color = ink)
                Text(
                    tr(R.string.t_1f_mb_used).format(done.sumOf { it.bytes } / 1_048_576.0) + if (running > 0) tr(R.string.in_progress, running) else "",
                    style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f),
                )
                Text(tr(R.string.open_downloads), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (active.isNotEmpty()) com.prism.music.ui.screens.DownloadQueueStatus(active)
        active.forEach { d ->
            d.song?.let { s ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Text(com.prism.music.ui.screens.downloadStateText(d, waiting), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }
            }
        }
    }
}


/** A playlist id in a pasted YouTube or YouTube Music link ("…?list=PL…"), or null. */
internal fun playlistIdIn(text: String): String? =
    Regex("""https?://\S*(?:youtube\.com|youtu\.be)\S*[?&]list=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

/** Library → Playlists → Open a link (H7): paste a playlist's link, and it opens (to play, keep or copy). */
@Composable
private fun OpenPlaylistLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf(clipboard.getText()?.text?.takeIf { playlistIdIn(it) != null } ?: "") }
    val id = playlistIdIn(text)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.open_a_playlist_link)) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tr(R.string.youtube_com_playlist_list)) },
                supportingText = if (text.isNotBlank() && id == null) ({ Text(tr(R.string.that_isn_t_a_playlist_s)) }) else null,
                isError = text.isNotBlank() && id == null,
            )
        },
        confirmButton = { PrismTextButton(onClick = { id?.let(onOpen) }, enabled = id != null) { Text(tr(R.string.open)) } },
        dismissButton = { PrismTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
