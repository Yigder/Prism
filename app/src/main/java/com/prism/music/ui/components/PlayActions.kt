package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something a long press can play: an album, playlist, artist or song, or one of Prism's own lists. */
sealed interface Playable {
    data class Of(val item: BrowseItem) : Playable
    data object Liked : Playable
    data object Downloads : Playable

    /**
     * An album or playlist page's own ⋯: the songs it already has ([load]) rather than a fresh lookup, and its own
     * share sheet ([share]).
     */
    class Page(
        val title: String,
        val subtitle: String,
        val art: String?,
        val album: Boolean,
        val share: ShareTarget,
        val load: suspend () -> Pair<List<Song>, QueueSource>,
    ) : Playable
}

/**
 * [target]'s songs after the current one ([next]) or at the end of the queue, then a note saying so (the menu's Play
 * next / Add to queue, and a release swiped on New from your artists, #248).
 */
fun queuePlayable(c: AppContainer, context: android.content.Context, target: Playable, title: String, next: Boolean) = c.scope.launch(Dispatchers.Main) {
    val loaded = runCatching { withContext(Dispatchers.IO) { songsFor(c, target, shuffle = false, mix = false) } }.getOrNull()
    if (loaded == null || loaded.first.isEmpty()) {
        Toast.makeText(context, if (loaded == null) tr(R.string.couldn_t_load, title) else tr(R.string.nothing_to_play_in, title), Toast.LENGTH_SHORT).show()
        return@launch
    }
    if (next) c.player.playNext(loaded.first, loaded.second) else c.player.addToQueue(loaded.first, loaded.second)
    // (Was English in every language until 1.7.9.2.)
    Toast.makeText(context, com.prism.music.data.model.songCount(loaded.first.size) + " · " + (if (next) tr(R.string.playing_next) else tr(R.string.added_to_queue)), Toast.LENGTH_SHORT).show()
}

/** Moods and genres are pages of shelves, not something to play. */
fun BrowseItem.playable(): Playable? = if (this is MoodItem) null else Playable.Of(this)

/**
 * A long-press menu for cards and tiles: call the returned function with what was pressed and the
 * sheet opens (songs get the usual song actions).
 */
@Composable
fun rememberPlayMenu(): (Playable) -> Unit {
    var target by remember { mutableStateOf<Playable?>(null) }
    target?.let { PlayActionsSheet(it) { target = null } }
    return remember { { target = it } }
}

/**
 * Play or shuffle a whole album, playlist or list; for an artist, shuffle their songs or start their mix. [extra] adds
 * rows (e.g. delete), in a group of their own under [extraTitle] when it's given.
 */
@Composable
fun PlayActionsSheet(target: Playable, extra: List<SheetItem> = emptyList(), extraTitle: String? = null, onDismiss: () -> Unit) {
    val item = (target as? Playable.Of)?.item
    if (item is SongItem) { SongActionsSheet(item.song, extra, onDismiss = onDismiss); return }
    val c = LocalContainer.current
    val context = LocalContext.current
    val liked by c.library.likedShown.collectAsState()
    var sharing by remember { mutableStateOf<ShareTarget?>(null) }
    sharing?.let { t -> ShareSheet(t) { sharing = null; onDismiss() }; return }
    // Its songs, on their way into a playlist (Reddit R8).
    var adding by remember { mutableStateOf<List<Song>?>(null) }
    val ui = com.prism.music.ui.theme.LocalUi.current
    val (title, subtitle) = when (target) {
        Playable.Liked -> tr(R.string.liked_songs) to com.prism.music.data.model.songCount(liked.size)
        Playable.Downloads -> tr(R.string.downloads) to com.prism.music.data.model.songCount(c.downloads.completedSongs().size)
        is Playable.Page -> target.title to target.subtitle
        is Playable.Of -> target.item.title to target.item.subtitle.ifBlank {
            when (target.item) { is AlbumItem -> tr(R.string.album); is ArtistItem -> tr(R.string.artist); else -> tr(R.string.playlist) }
        }
    }
    val art = when (target) {
        Playable.Liked -> c.covers.custom("liked")
        Playable.Downloads -> c.covers.custom("downloads")
        is Playable.Page -> target.art
        is Playable.Of -> c.covers.art(target.item)
    }
    adding?.let { songs -> PlaylistPicker(songs, title) { adding = null; onDismiss() }; return }
    fun play(shuffle: Boolean, mix: Boolean = false) = c.scope.launch(Dispatchers.Main) {
        val loaded = runCatching { withContext(Dispatchers.IO) { songsFor(c, target, shuffle, mix) } }.getOrNull()
        if (loaded == null || loaded.first.isEmpty()) {
            Toast.makeText(context, if (loaded == null) tr(R.string.couldn_t_load, title) else tr(R.string.nothing_to_play_in, title), Toast.LENGTH_SHORT).show()
            return@launch
        }
        // An artist's shuffle and mix come already in YouTube's order; the player shouldn't reshuffle them.
        c.player.playQueue(loaded.first, 0, loaded.second, shuffle = shuffle && item !is ArtistItem)
    }

    /** The whole list after the current song ([next]) or at the end of the queue. */
    fun queue(next: Boolean) = queuePlayable(c, context, target, title, next)
    val quick = if (item is ArtistItem) listOf(
        ActionItem(PrismIcons.Shuffle, tr(R.string.shuffle)) { play(shuffle = true) },
        ActionItem(PrismIcons.Radio, tr(R.string.mix_2)) { play(shuffle = false, mix = true) },
    ) else listOf(
        ActionItem(PrismIcons.Play, tr(R.string.widget_play)) { play(shuffle = false) },
        ActionItem(PrismIcons.Shuffle, tr(R.string.shuffle)) { play(shuffle = true) },
        ActionItem(PrismIcons.PlaylistPlay, tr(R.string.play_next)) { queue(next = true) },
        ActionItem(PrismIcons.QueueMusic, tr(R.string.add_to_queue)) { queue(next = false) },
    )

    ActionSheet(art, onDismiss) { close ->
        ActionHeader(
            art, title, subtitle,
            shape = if (item is ArtistItem) ui.artistArt else ui.albumArt,
            placeholderIcon = when {
                target == Playable.Liked -> PrismIcons.HeartFilled
                target == Playable.Downloads -> PrismIcons.DownloadDone
                item is ArtistItem -> PrismIcons.Person
                item is AlbumItem || (target as? Playable.Page)?.album == true -> PrismIcons.Album
                else -> PrismIcons.QueueMusic
            },
        ) {
            // Save to (or take out of) the library, or star an artist, right from the cover's long press (Reddit 1.7).
            LibraryToggle(item)
            SheetIconButton(PrismIcons.Share, tr(R.string.share)) {
                sharing = when (target) {
                    Playable.Liked -> ShareTarget(tr(R.string.liked_songs), com.prism.music.data.model.songCount(liked.size), tr(R.string.playlist), art, null, songs = liked)
                    Playable.Downloads -> c.downloads.completedSongs().let { ShareTarget(tr(R.string.downloads), com.prism.music.data.model.songCount(it.size), tr(R.string.playlist), art, null, songs = it) }
                    is Playable.Page -> target.share
                    // A Prism playlist has no link: it's shared as its picture and song list.
                    is Playable.Of -> c.prismLibrary.list(target.item.id)?.let { l ->
                        ShareTarget(l.title, com.prism.music.data.model.songCount(l.songs.size), tr(R.string.playlist), art, null, songs = l.songs)
                    } ?: target.item.shareTarget(art)
                }
            }
        }
        QuickActions(quick) { runAction(it, close) }
        // An album in the library can be taken out from here, by the library's own entry (a remaster or other edition
        // opens as another album page, whose bookmark didn't reach this one).
        val inLibrary = item is AlbumItem && c.settings.current.isLoggedIn && c.library.albums.value.any { it.id == item.id }
        // Saved on the phone (no account): taken out of the phone's library.
        val savedHere = !c.settings.current.isLoggedIn && when (item) {
            is AlbumItem -> c.prismLibrary.isSaved(item.id)
            is PlaylistItem -> c.prismLibrary.isPlaylistSaved(item.id)
            else -> false
        }
        val remove = if (savedHere) listOf(ActionItem(PrismIcons.Delete, tr(R.string.remove_from_library)) {
            if (item is AlbumItem) c.prismLibrary.unsaveAlbum(item.id) else if (item != null) c.prismLibrary.unsavePlaylist(item.id)
            Toast.makeText(context, tr(R.string.removed_from_your_library), Toast.LENGTH_SHORT).show()
        }) else if (inLibrary) listOf(ActionItem(PrismIcons.Delete, tr(R.string.remove_from_library)) {
            c.scope.launch(Dispatchers.Main) {
                val ok = c.library.removeAlbum(item.id, (item as AlbumItem).playlistId)
                Toast.makeText(context, if (ok) tr(R.string.removed_from_your_library) else tr(R.string.couldn_t_update_your_library), Toast.LENGTH_SHORT).show()
            }
        }) else emptyList()
        // Played in the order that blends best, mixed and beat-matched (DJ mode).
        val djSet = if (item is ArtistItem) emptyList() else listOf(ActionItem(PrismIcons.Album, tr(R.string.play_as_a_dj_set)) {
            c.scope.launch(Dispatchers.Main) {
                val loaded = runCatching { withContext(Dispatchers.IO) { songsFor(c, target, shuffle = false, mix = false) } }.getOrNull()
                if (loaded == null || loaded.first.size < 2) {
                    Toast.makeText(context, if (loaded == null) tr(R.string.couldn_t_load, title) else tr(R.string.a_dj_set_needs_more_than), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                c.player.playDjSet(loaded.first, loaded.second)
            }
        })
        // Every song of it into one of your playlists, as a song's menu does for one (Reddit R8).
        val addTo = if (item is ArtistItem) emptyList() else listOf(ActionItem(PrismIcons.PlaylistAdd, tr(R.string.add_to_playlist), keepOpen = true) {
            c.scope.launch(Dispatchers.Main) {
                val loaded = runCatching { withContext(Dispatchers.IO) { songsFor(c, target, shuffle = false, mix = false) } }.getOrNull()
                if (loaded == null || loaded.first.isEmpty()) {
                    Toast.makeText(context, if (loaded == null) tr(R.string.couldn_t_load, title) else tr(R.string.nothing_to_add_in, title), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                adding = loaded.first
            }
        })
        // A YouTube playlist, copied whole into a Prism playlist of your own (H7; signed in or not).
        val copyable: Pair<String, String>? = when {
            item is PlaylistItem && !item.isMix -> item.id to item.title
            target is Playable.Page && !target.album -> target.share.url?.let { PLAYLIST_IN_LINK.find(it)?.groupValues?.get(1) }?.let { it to target.title }
            else -> null
        }?.takeIf { (id, _) -> !com.prism.music.data.PrismLibrary.isMix(id) && !com.prism.music.data.PrismLibrary.isPrismList(id) }
        val copy = copyable?.let { (id, name) ->
            listOf(ActionItem(PrismIcons.PlaylistAdd, tr(R.string.save_a_copy_as_a_prism)) { copyToPrism(c, context, id, name) })
        }.orEmpty()
        // A section of its own on Home (1.7.6): its songs, under its name.
        val pin = item?.let { com.prism.music.ui.screens.OwnSections.pinOf(it) }?.let { cfg ->
            val layout = c.settings.current.homeLayout
            val on = layout.any { it.key == cfg.key }
            listOf(ActionItem(if (on) PrismIcons.PinFilled else PrismIcons.Pin, if (on) tr(R.string.unpin_from_home) else tr(R.string.pin_to_home)) {
                c.settings.setHomeLayout(if (on) layout.filterNot { it.key == cfg.key } else listOf(cfg) + layout)
                Toast.makeText(context, if (on) tr(R.string.taken_off_home) else tr(R.string.pinned_to_the_top_of_home), Toast.LENGTH_SHORT).show()
            })
        }.orEmpty()
        if (extraTitle != null) {
            ActionGroup(null, addTo + djSet + copy + pin + remove) { runAction(it, close) }
            ActionGroup(extraTitle, extra.map { it.toAction() }) { runAction(it, close) }
        } else ActionGroup(null, addTo + djSet + copy + pin + extra.map { it.toAction() } + remove) { runAction(it, close) }
    }
}

/**
 * The header's library button: an album or playlist saved to your library (YouTube Music when signed in; on the phone
 * for albums when not), or an artist starred. Nothing for Prism's own lists, mixes and radios.
 */
@Composable
private fun LibraryToggle(item: BrowseItem?) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val signedIn = c.settings.current.isLoggedIn
    when (item) {
        is AlbumItem -> {
            val albums by c.library.albums.collectAsState()
            val here by c.prismLibrary.albums.collectAsState()
            val saved = if (signedIn) albums.any { it.id == item.id } else here.any { it.id == item.id }
            SheetIconButton(if (saved) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (saved) tr(R.string.remove_from_library) else tr(R.string.add_to_library)) {
                if (!signedIn) {
                    if (saved) c.prismLibrary.unsaveAlbum(item.id)
                    else c.prismLibrary.saveAlbum(com.prism.music.data.SavedAlbum(item.id, item.title, item.subtitle, item.thumbnail, item.playlistId, System.currentTimeMillis()))
                    Toast.makeText(context, if (saved) tr(R.string.removed_from_your_library) else tr(R.string.added_to_your_library), Toast.LENGTH_SHORT).show()
                } else c.scope.launch(Dispatchers.Main) {
                    val ok = if (saved) c.library.removeAlbum(item.id, item.playlistId)
                    else c.library.setSaved(item.playlistId ?: item.id, true)
                    Toast.makeText(context, if (!ok) tr(R.string.couldn_t_update_your_library) else if (saved) tr(R.string.removed_from_your_library) else tr(R.string.added_to_your_library), Toast.LENGTH_SHORT).show()
                }
            }
        }
        is PlaylistItem -> {
            if (item.isMix || com.prism.music.data.PrismLibrary.isMix(item.id) || com.prism.music.data.PrismLibrary.isPrismList(item.id)) return
            if (!signedIn) {
                // Without an account, kept on the phone as a bookmark (H7).
                val here by c.prismLibrary.playlists.collectAsState()
                val saved = here.any { com.prism.music.data.PrismLibrary.samePlaylist(it.id, item.id) }
                SheetIconButton(if (saved) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (saved) tr(R.string.remove_from_library) else tr(R.string.add_to_library)) {
                    if (saved) c.prismLibrary.unsavePlaylist(item.id)
                    else c.prismLibrary.savePlaylist(com.prism.music.data.SavedPlaylist(item.id, item.title, item.subtitle, item.thumbnail, System.currentTimeMillis()))
                    Toast.makeText(context, if (saved) tr(R.string.removed_from_your_library) else tr(R.string.added_to_your_library), Toast.LENGTH_SHORT).show()
                }
                return
            }
            val playlists by c.library.playlists.collectAsState()
            val saved = playlists.any { it.id.removePrefix("VL") == item.id.removePrefix("VL") }
            SheetIconButton(if (saved) PrismIcons.LibraryAdded else PrismIcons.LibraryAdd, if (saved) tr(R.string.remove_from_library) else tr(R.string.add_to_library)) {
                c.scope.launch(Dispatchers.Main) {
                    val ok = c.library.setSaved(item.id, !saved)
                    Toast.makeText(context, if (!ok) tr(R.string.couldn_t_update_your_library) else if (saved) tr(R.string.removed_from_your_library) else tr(R.string.added_to_your_library), Toast.LENGTH_SHORT).show()
                }
            }
        }
        is ArtistItem -> {
            val favorites by c.artistPrefs.favorites.collectAsState()
            val starred = favorites.any { it.id == item.id }
            SheetIconButton(if (starred) PrismIcons.StarFilled else PrismIcons.Star, if (starred) tr(R.string.remove_from_favorites) else tr(R.string.add_to_favorites)) {
                c.library.setFavoriteArtist(com.prism.music.data.FavoriteArtist(item.id, item.title, item.thumbnail), null, !starred)
            }
        }
        else -> Unit
    }
}

private val PLAYLIST_IN_LINK = Regex("[?&]list=([A-Za-z0-9_-]+)")

/**
 * Every song of the YouTube playlist [id] (all its pages), into a new Prism playlist named [title] (H7): the copy is
 * yours, on the phone, and stays as it is when the original changes.
 */
fun copyToPrism(c: AppContainer, context: android.content.Context, id: String, title: String) {
    Toast.makeText(context, tr(R.string.copying, title), Toast.LENGTH_SHORT).show()
    c.scope.launch(Dispatchers.Main) {
        val songs = runCatching { withContext(Dispatchers.IO) { c.ytm.playlistAll(id, 5000).songs } }.getOrNull()
        if (songs.isNullOrEmpty()) {
            Toast.makeText(context, if (songs == null) tr(R.string.couldn_t_load, title) else tr(R.string.has_no_songs_to_copy, title), Toast.LENGTH_SHORT).show()
            return@launch
        }
        c.prismLibrary.create(title, songs)
        Toast.makeText(context, tr(R.string.saved_as_a_prism_playlist, title, com.prism.music.data.model.songCount(songs.size)), Toast.LENGTH_SHORT).show()
    }
}

/** The songs to queue for [target], and what the queue is called. */
private suspend fun songsFor(c: AppContainer, target: Playable, shuffle: Boolean, mix: Boolean): Pair<List<Song>, QueueSource> = when (target) {
    Playable.Liked -> c.library.likedShown.value to QueueSource(QueueKind.PLAYLIST, tr(R.string.liked_songs))
    Playable.Downloads -> c.downloads.completedSongs() to QueueSource(QueueKind.PLAYLIST, tr(R.string.downloads))
    is Playable.Page -> target.load()
    is Playable.Of -> when (val item = target.item) {
        is AlbumItem -> c.ytm.album(item.id).songs to QueueSource(QueueKind.ALBUM, item.title, page = item)
        is PlaylistItem ->
            // A Prism playlist is on the phone; mixes and radios only exist as a watch queue.
            if (com.prism.music.data.PrismLibrary.isPrismList(item.id))
                (c.prismLibrary.list(item.id)?.songs ?: emptyList()) to QueueSource(QueueKind.PLAYLIST, item.title, originId = item.id, page = item)
            else if (item.isMix || item.id.startsWith("RD")) radio(c, item.seedVideoId, item.id, item.params, item.title)
            else c.ytm.playlistAll(item.id, 1000).songs to QueueSource(QueueKind.PLAYLIST, item.title, originId = item.id.removePrefix("VL"), page = item)
        is ArtistItem -> {
            val a = c.ytm.artist(item.id)
            if (shuffle && !mix && a.shufflePlaylistId != null) radio(c, a.shuffleVideoId, a.shufflePlaylistId, a.shuffleParams, a.name)
            else radio(c, a.radioVideoId, a.radioPlaylistId ?: throw java.io.IOException(tr(R.string.no_mix)), a.radioParams, tr(R.string.mix, a.name))
        }
        else -> emptyList<Song>() to QueueSource(QueueKind.OTHER, item.title)
    }
}

private suspend fun radio(c: AppContainer, videoId: String?, playlistId: String, params: String?, title: String): Pair<List<Song>, QueueSource> {
    val r = c.radio(videoId, playlistId, params)
    return r.songs to QueueSource(QueueKind.RADIO, title)
}
