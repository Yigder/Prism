package com.prism.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.prism.music.AppContainer
import com.prism.music.data.PrismLibrary
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Home sections of the listener's own making (1.7.6, plan H1): an album, playlist, artist or mix pinned to Home, and
 * sections made by a rule over their own listening. Their songs are worked out once a session (a pull to refresh
 * asks again) and kept here, so scrolling a section off and back doesn't load it again.
 */
object OwnSections {
    private val cache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())

    fun isOwn(key: String) = key.startsWith(HomeSections.PIN_PREFIX) || key.startsWith("rule:")

    fun forget() { cache.value = emptyMap() }

    /** A pinned item's section: its key, name and picture. Songs play as a list, so it opens as one. */
    fun pinOf(item: BrowseItem): HomeSectionConfig? = when (item) {
        is AlbumItem -> HomeSectionConfig(HomeSections.pinKey("album", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        is ArtistItem -> HomeSectionConfig(HomeSections.pinKey("artist", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        is PlaylistItem -> if (item.isMix) HomeSectionConfig(HomeSections.pinKey("mix", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
            else HomeSectionConfig(HomeSections.pinKey("playlist", item.id), item.title, style = SectionStyle.LIST, art = item.thumbnail)
        else -> null
    }

    /** Where a pinned section's "more" goes: its own page. */
    fun pageOf(key: String): String? {
        val (kind, id) = key.removePrefix(HomeSections.PIN_PREFIX).split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "album" -> Routes.album(id)
            "playlist" -> Routes.playlist(id)
            "artist" -> Routes.artist(id)
            else -> null
        }
    }

    /** What a rule section says about itself under its name. */
    fun strapline(key: String): String? = when {
        key == HomeSections.LIKED_MONTH -> "Your likes from the last 30 days"
        key == HomeSections.UNPLAYED_DOWNLOADS -> "On the phone, not played yet"
        key.startsWith(HomeSections.YEAR_PREFIX) -> "Your most played that year"
        key.startsWith(HomeSections.PIN_PREFIX) -> "Pinned"
        else -> null
    }

    /** The rule sections Customize Home can add (years with plays in them, newest first). */
    suspend fun rules(c: AppContainer): List<HomeSectionConfig> = withContext(Dispatchers.IO) {
        listOf(
            HomeSectionConfig(HomeSections.LIKED_MONTH, "Liked this month", style = SectionStyle.LIST),
            HomeSectionConfig(HomeSections.UNPLAYED_DOWNLOADS, "Downloads you haven't played"),
        ) + runCatching { c.db.plays().yearsPlayed() }.getOrDefault(emptyList()).take(6)
            .map { y -> HomeSectionConfig(HomeSections.yearKey(y), "Most played in $y") }
    }

    suspend fun load(c: AppContainer, key: String): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            when {
                key == HomeSections.LIKED_MONTH -> c.db.songs().likedSince(System.currentTimeMillis() - 30L * 86_400_000L).map { it.toSong() }.take(40)
                key == HomeSections.UNPLAYED_DOWNLOADS -> {
                    val songs = c.downloads.completedSongs()
                    val played = songs.map { it.id }.chunked(500).flatMap { c.db.plays().playedOf(it) }.toSet()
                    songs.filter { it.id !in played }.take(40)
                }
                key.startsWith(HomeSections.YEAR_PREFIX) -> {
                    val year = key.removePrefix(HomeSections.YEAR_PREFIX).toInt()
                    val from = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis
                    val to = Calendar.getInstance().apply { clear(); set(year + 1, Calendar.JANUARY, 1) }.timeInMillis - 1
                    val ids = c.db.plays().mostPlayedIdsBetween(from, to, 40)
                    val byId = c.db.songs().getAll(ids).associateBy { it.id }
                    ids.mapNotNull { byId[it]?.toSong() }
                }
                key.startsWith(HomeSections.PIN_PREFIX) -> {
                    val (kind, id) = key.removePrefix(HomeSections.PIN_PREFIX).split(':', limit = 2)
                    when (kind) {
                        "album" -> c.ytm.album(id).songs
                        "playlist" -> c.prismLibrary.list(id)?.songs ?: if (PrismLibrary.isPrismList(id)) emptyList() else c.ytm.playlistAll(id, 100).songs
                        "artist" -> c.ytm.artist(id).shelves.firstOrNull { s -> s.items.any { it is SongItem } }
                            ?.items?.filterIsInstance<SongItem>()?.map { it.song }.orEmpty()
                        "mix" -> c.radio(null, id).songs
                        else -> emptyList()
                    }.take(50)
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** [key]'s songs: kept ones at once, else loaded (again after [refreshes] changes). */
    @Composable
    fun rememberSongs(key: String, refreshes: Int): State<List<Song>> {
        val c = LocalContainer.current
        return produceState(cache.value[key] ?: emptyList(), key, refreshes) {
            if (cache.value[key] != null && loadedAt[key] == refreshes) return@produceState
            val songs = load(c, key)
            cache.value = cache.value + (key to songs)
            loadedAt[key] = refreshes
            value = songs
        }
    }

    /** Which pull to refresh each section was last loaded for. */
    private val loadedAt = java.util.concurrent.ConcurrentHashMap<String, Int>()
}
