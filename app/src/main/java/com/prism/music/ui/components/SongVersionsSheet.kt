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
 * recordings YouTube has of the song's title, with their album and length, and a file of the song on the phone
 * (1.8.3, #243). The search box runs the listener's own query when the title's suggestions miss the recording (a live
 * take, a different title), and finds the phone's own music files with the same words. The one picked plays in the
 * song's place from then on and is kept on the phone ([com.prism.music.AppContainer.pickVersion]); the song's own row
 * goes back to it.
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
    // Files on this phone (1.8.3, #243): those with the song's title, and any other the listener picks.
    val canSeeFiles = remember { c.localMusic.hasPermission() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (canSeeFiles && !c.localMusic.scanned.value) c.localMusic.scan() }
    val fileMatches = remember(localTracks, song.id) {
        val want = SongVersions.bare(song.title)
        if (want.isEmpty() || song.isLocal) emptyList() else localTracks.filter { SongVersions.bare(it.song.title) == want }.take(6)
    }
    var choosingFile by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (choosingFile) {
        PhoneFilePicker(localTracks.map { it.song }) { f ->
            choosingFile = false
            f?.let { choose(SongVersions.Pick(it.id, fileLabel(it), it)) }
        }
        return
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
                val hits = localHits
                if (hits.isNotEmpty()) {
                    SheetLabel(tr(R.string.on_this_phone))
                    VersionRows(song, hits, picked, local = true) { v ->
                        choose(SongVersions.Pick(v.id, fileLabel(v), v))
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
                // A file of the song on the phone plays from the file (a FLAC of an album bought elsewhere, say).
                if (!song.isLocal) {
                    fileMatches.forEach { t ->
                        VersionRow(t.song, tr(R.string.your_file), picked == t.song.id, Modifier.testTag("version:${t.song.id}")) {
                            choose(SongVersions.Pick(t.song.id, fileLabel(t.song), t.song))
                        }
                    }
                    // A picked file that doesn't match the title still shows, ticked.
                    picks[song.id]?.song?.takeIf { it.isLocal && fileMatches.none { t -> t.song.id == it.id } }?.let { f ->
                        VersionRow(f, tr(R.string.your_file), true, Modifier.testTag("version:${f.id}")) {}
                    }
                    if (canSeeFiles && localTracks.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().clickable { choosingFile = true }.padding(horizontal = 22.dp, vertical = 12.dp).testTag("version:chooseFile"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PrismIcons.Folder, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(tr(R.string.choose_a_file_on_this_phone), style = MaterialTheme.typography.bodyLarge)
                    }
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

/** A file's row: "Your file · Artist · 3:41" (in the song menu once picked). */
private fun fileLabel(f: Song): String = listOf(tr(R.string.your_file), versionLabel(f)).filter { it.isNotBlank() }.joinToString(" · ")

/** Any music file on the phone, found by typing (1.8.3, #243); null: none picked. */
@Composable
private fun PhoneFilePicker(files: List<Song>, onPick: (Song?) -> Unit) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val shown = remember(files, query) {
        if (query.isBlank()) files.take(300)
        else {
            val words = com.prism.music.data.LibrarySearch.norm(query).split(' ').filter { it.isNotBlank() }
            files.filter { f -> com.prism.music.data.LibrarySearch.norm("${f.title} ${f.artistText} ${f.album?.title.orEmpty()}").let { hay -> words.all { it in hay } } }.take(300)
        }
    }
    PrismSheet({ onPick(null) }) { _ ->
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding().testTag("filePicker")) {
            Text(tr(R.string.choose_a_file_on_this_phone), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp))
            SearchField(query, tr(R.string.search), { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val f = shown[i]
                    VersionRow(f, null, false, Modifier.testTag("file:${f.id}")) { onPick(f) }
                }
            }
        }
    }
}

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
        val tag = if (local) tr(R.string.your_file) else if (SongVersions.sameRecording(song, v)) tr(R.string.another_upload) else null
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
