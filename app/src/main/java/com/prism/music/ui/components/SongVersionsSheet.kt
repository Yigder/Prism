package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.SongVersions
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.formatDuration
import com.prism.music.ui.screens.phoneMatches
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * "Play a different version" (1.7.9, Reddit: a song from an album played as someone's guitar cover): the other
 * recordings YouTube has of the song's title, with their album and length. The search box runs the listener's own
 * query when the title's suggestions miss the recording (a live take, a different title), and finds the phone's own
 * music files with the same words. The one picked plays in the song's place from then on and is kept on the phone
 * ([com.prism.music.AppContainer.pickVersion]); the song's own row goes back to it.
 */
@Composable
fun SongVersionsSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val picks by c.versions.picks.collectAsState()
    val picked = picks[song.id]?.id
    val localTracks by c.localMusic.tracks.collectAsState()
    val focus = LocalFocusManager.current
    // The search for versions of the title: what the box says, and the manual search it last ran (null: the suggestions).
    val defaultQuery = "${song.title} ${song.primaryArtist}".trim()
    var query by remember(song.id) { mutableStateOf(defaultQuery) }
    var asked by remember(song.id) { mutableStateOf<String?>(null) }
    var searchId by remember(song.id) { mutableIntStateOf(0) }
    // null while looking; a failure when the search didn't answer.
    val found by produceState<Result<List<Song>>?>(null, song.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val all = searchRecordings(c, defaultQuery)
                    // Neither search answered (offline, or refused): shown as "couldn't look".
                    ?: throw java.io.IOException()
                if (all.isEmpty()) throw java.io.IOException()
                SongVersions.candidates(song, all)
            }
        }
    }
    // A manual search (the box's query) finds recordings the title's suggestions miss: a live take, another title.
    val searched by produceState<Result<List<Song>>?>(null, song.id, asked, searchId) {
        val q = asked ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val all = searchRecordings(c, q) ?: throw java.io.IOException()
                all.distinctBy { it.id }.filter { it.id != song.id }
            }
        }
    }
    // The same words find the phone's own files, so a version kept only on the phone can be picked too.
    val localHits by produceState<List<Song>>(emptyList(), asked ?: defaultQuery, localTracks) {
        val q = asked ?: defaultQuery
        value = withContext(Dispatchers.Default) { phoneMatches(q, localTracks.map { it.song }).take(12) }
    }
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        asked = q
        searchId++
    }
    fun choose(pick: SongVersions.Pick?) {
        c.pickVersion(song, pick)
        android.widget.Toast.makeText(
            context, if (pick == null) tr(R.string.plays_as_itself_again) else tr(R.string.plays_as_this_version_from_now), android.widget.Toast.LENGTH_SHORT,
        ).show()
        onDismiss()
    }
    PrismSheet(onDismiss) { _ ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 8.dp)) {
                Text(tr(R.string.play_a_different_version), style = MaterialTheme.typography.titleLarge)
                Text(tr(R.string.the_one_you_pick_plays_for), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VersionRow(song, tr(R.string.version_original), picked == null, Modifier.testTag("version:own")) { choose(null) }
            PrismTextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).testTag("version:search"),
                label = { Text(tr(R.string.search_with_another_title)) },
                leadingIcon = { Icon(PrismIcons.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; asked = null; focus.clearFocus() }) {
                        Icon(PrismIcons.Close, tr(R.string.clear_search))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }, onDone = { search() }),
            )
            val manual = asked
            if (manual != null) {
                SheetLabel(tr(R.string.search_2, manual))
                val files = localHits
                if (files.isNotEmpty()) {
                    SheetLabel(tr(R.string.on_this_phone))
                    VersionRows(song, files, picked, local = true) { v ->
                        choose(SongVersions.Pick(v.id, versionLabel(v), v))
                    }
                }
                SheetLabel(tr(R.string.youtube))
                val r = searched
                when {
                    r == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    r.isFailure -> Quiet(tr(R.string.search_failed))
                    r.getOrThrow().isEmpty() -> Quiet(tr(R.string.no_results))
                    else -> VersionRows(song, r.getOrThrow(), picked) { v ->
                        choose(SongVersions.Pick(v.id, versionLabel(v), v))
                    }
                }
            } else {
                // A file picked before shows even when YouTube's suggestions don't list it (they never do).
                val files = (localHits + listOfNotNull(picks[song.id]?.song?.takeIf { it.isLocal })).distinctBy { it.id }
                if (files.isNotEmpty()) {
                    SheetLabel(tr(R.string.on_this_phone))
                    VersionRows(song, files, picked, local = true) { v ->
                        choose(SongVersions.Pick(v.id, versionLabel(v), v))
                    }
                    SheetLabel(tr(R.string.youtube))
                }
                val r = found
                when {
                    r == null -> PrismSpinner(Modifier.padding(22.dp).size(22.dp), stroke = 2.dp)
                    r.isFailure -> Quiet(tr(R.string.couldn_t_look_for_other_versions))
                    r.getOrThrow().isEmpty() -> Quiet(tr(R.string.youtube_has_no_other_version_of))
                    else -> VersionRows(song, r.getOrThrow(), picked) { v ->
                        // The version itself is kept too: the song shows as it from now on (#328).
                        choose(SongVersions.Pick(v.id, versionLabel(v), v))
                    }
                }
            }
        }
    }
}

/** YouTube's songs and videos for [query], or null when neither search answered (offline, or refused). */
private suspend fun searchRecordings(c: com.prism.music.AppContainer, query: String): List<Song>? = coroutineScope {
    val songs = async { runCatching { c.ytm.search(query, SearchFilter.SONGS).items } }
    val videos = async { runCatching { c.ytm.search(query, SearchFilter.VIDEOS).items } }
    val s = songs.await()
    val v = videos.await()
    if (s.isFailure && v.isFailure) null
    else (s.getOrDefault(emptyList()) + v.getOrDefault(emptyList())).filterIsInstance<SongItem>().map { it.song }
}

/** What a version's row (and the song menu, once picked) says of it: artist, album or "Video", length. */
fun versionLabel(v: Song): String = listOfNotNull(
    v.artistText.takeIf { it.isNotBlank() },
    if (v.isVideo) tr(R.string.video) else v.album?.title?.takeIf { it.isNotBlank() },
    formatDuration(v.durationSec).takeIf { it.isNotEmpty() },
).joinToString(" · ")

@Composable
private fun SheetLabel(text: String) = Text(
    text, Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Quiet(text: String) = Text(
    text, Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** [versions] as rows; each picks its recording as the song's from now on. [local]: they're files on the phone. */
@Composable
private fun VersionRows(song: Song, versions: List<Song>, picked: String?, local: Boolean = false, onPick: (Song) -> Unit) {
    versions.forEach { v ->
        val tag = if (local) null else if (SongVersions.sameRecording(song, v)) tr(R.string.another_upload) else null
        VersionRow(v, tag, picked == v.id, Modifier.testTag("version:${v.id}")) { onPick(v) }
    }
}

@Composable
private fun VersionRow(v: Song, tag: String?, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(v.thumbnail, Modifier.size(48.dp), shape = LocalUi.current.shape(6.dp), size = 120)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(tag, versionLabel(v).takeIf { it.isNotBlank() }).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) Icon(PrismIcons.Check, tr(R.string.playing_this_one), Modifier.padding(start = 8.dp).size(20.dp), tint = scheme.onSurface)
    }
}
