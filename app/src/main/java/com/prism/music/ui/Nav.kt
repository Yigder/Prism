package com.prism.music.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val REPLAY = "replay"
    const val SETTINGS = "settings"
    const val EQ = "eq"
    const val CATALOGUE = "catalogue"
    const val LOGIN = "login"
    const val DOWNLOADS = "downloads"
    const val MOODS = "moods"
    const val BACKGROUNDS = "backgrounds"
    const val IMPORT = "import"
    fun settings(section: String) = "settings/$section"
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun liked(genre: String? = null) = if (genre == null) "liked" else "liked?genre=${Uri.encode(genre)}"
    fun browse(id: String, params: String?, title: String) =
        "browse/${Uri.encode(id)}?params=${Uri.encode(params ?: "")}&title=${Uri.encode(title)}"
}

/** Everything a screen needs to move around the app. */
class Navigator(val nav: NavController, val openPlayer: () -> Unit) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() = nav.popBackStack()

    fun open(item: BrowseItem, onSong: (SongItem) -> Unit) {
        when (item) {
            is SongItem -> onSong(item)
            is AlbumItem -> go(Routes.album(item.id))
            is PlaylistItem -> go(Routes.playlist(item.id))
            is ArtistItem -> go(Routes.artist(item.id))
            is MoodItem -> go(Routes.browse(item.id, item.params, item.title))
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class LoaderViewModel : ViewModel() {
    val state = MutableStateFlow<Load<Any?>>(Load.Loading)
    var started = false
    var refreshing = MutableStateFlow(false)

    fun load(block: suspend () -> Any?, silent: Boolean = false) {
        started = true
        if (!silent) state.value = Load.Loading
        refreshing.value = silent
        viewModelScope.launch {
            state.value = try {
                Load.Ok(block())
            } catch (e: Exception) {
                if (silent && state.value is Load.Ok) state.value else Load.Err(e.message ?: "Something went wrong")
            }
            refreshing.value = false
        }
    }
}

class Loaded<T>(val state: Load<T>, val refreshing: Boolean, val reload: (silent: Boolean) -> Unit)

/** Loads data once per back-stack entry (survives recomposition and tab switches). */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(key: String, block: suspend () -> T): Loaded<T> {
    val vm: LoaderViewModel = viewModel(key = key)
    LaunchedEffect(key) { if (!vm.started) vm.load(block) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    return Loaded(state as Load<T>, refreshing) { silent -> vm.load(block, silent) }
}
