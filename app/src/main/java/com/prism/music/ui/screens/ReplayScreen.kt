package com.prism.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.MonthTotal
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch
import java.util.Calendar

internal enum class Period(val label: String) { MONTH("This month"), DAYS30("Last 30 days"), YEAR("This year"), LAST_YEAR("Last year"), ALL("All time") }

private data class ReplayData(
    val totalMs: Long,
    val plays: Int,
    val activeDays: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val months: List<MonthTotal>,
    val genres: List<Pair<String, Long>>,
)

internal fun SongPlays.toSong() = Song(
    id = songId, title = title,
    artists = artistName.split(", ").mapIndexed { i, n -> ArtistRef(n, if (i == 0) artistId else null) },
    album = albumTitle?.let { AlbumRef(it) }, durationSec = durationSec, thumbnail = thumbnail,
)

internal fun range(p: Period): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    fun startOfYear(offset: Int) = Calendar.getInstance().apply {
        set(Calendar.YEAR, cal.get(Calendar.YEAR) + offset); set(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }.timeInMillis
    return when (p) {
        Period.MONTH -> Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis to now
        Period.DAYS30 -> now - 30L * 86_400_000 to now
        Period.YEAR -> startOfYear(0) to now
        Period.LAST_YEAR -> startOfYear(-1) to startOfYear(0) - 1
        Period.ALL -> 0L to now
    }
}

/** Replay / Rewind: a private, on-device recap of your listening. */
@Composable
fun ReplayScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by rememberSaveable { mutableStateOf(Period.YEAR) }
    val data by produceState<ReplayData?>(null, period) {
        value = null
        val (from, to) = range(period)
        val dao = c.db.plays()
        val songs = dao.topSongs(from, to, 100)
        val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
        val genres = songs.groupBy { genreMap[it.songId] ?: "Unknown" }
            .mapValues { (_, v) -> v.sumOf { it.ms } }
            .filterKeys { it != "Unknown" }.toList().sortedByDescending { it.second }.take(6)
        value = ReplayData(
            totalMs = dao.totalMs(from, to), plays = dao.count(from, to), activeDays = dao.activeDays(from, to),
            songs = songs, artists = dao.topArtists(from, to, 12), months = dao.monthly(from, to), genres = genres,
        )
        // Fill in genres for the top tracks in the background so the next visit is richer.
        songs.take(40).filter { it.songId !in genreMap }.forEach { runCatching { c.meta.genreFor(it.toSong()) } }
    }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var story by remember { mutableStateOf<ReplayStory?>(null) }
    var storyLoading by remember { mutableStateOf(false) }
    val openStory: () -> Unit = {
        if (!storyLoading) scope.launch {
            storyLoading = true
            story = runCatching { loadReplayStory(c, period, year) }.getOrNull()
            if (story == null) Toast.makeText(context, "Couldn't build your Replay", Toast.LENGTH_SHORT).show()
            storyLoading = false
        }
    }
    story?.let { ReplayStoryDialog(it) { story = null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
                Text("Replay", style = MaterialTheme.typography.headlineMedium)
                Text("Your listening, recapped on-device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Period.entries) { p -> FilterChip(p == period, { period = p }, { Text(if (p == Period.YEAR) "$year" else if (p == Period.LAST_YEAR) "${year - 1}" else p.label) }) }
            }
        }
        val d = data
        if (d == null) { item { LoadingState() }; return@LazyColumn }
        if (d.plays == 0) {
            item { EmptyState(Icons.Rounded.AutoAwesome, "Nothing to replay yet", "Listen for a while — songs you play for 10 seconds or more count toward your Replay.") }
            return@LazyColumn
        }
        item { HeroCard(d, period, year, storyLoading, openStory) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val songs = d.songs.map { it.toSong() }
                val replaySource = QueueSource(QueueKind.REPLAY, "Replay · ${period.label}")
                Button(onClick = { c.player.playQueue(songs, 0, replaySource) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                }
                FilledTonalButton(onClick = { c.player.playQueue(songs, 0, replaySource, shuffle = true) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                }
                if (c.settings.current.isLoggedIn) androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            val name = "Replay " + if (period == Period.YEAR) "$year" else period.label
                            val id = runCatching { c.ytm.createPlaylist(name, songs.take(100).map { it.id }, "Made with Prism Replay") }.getOrNull()
                            Toast.makeText(context, if (id != null) "Saved \"$name\" to YouTube Music" else "Couldn't create playlist", Toast.LENGTH_SHORT).show()
                            if (id != null) nav.go(Routes.playlist(id))
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save as a playlist") }
            }
        }
        if (d.months.size > 1) item {
            SectionHeader("Minutes by month")
            MonthChart(d.months, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(160.dp))
        }
        if (d.artists.isNotEmpty()) item {
            // Artist photos, not the cover of whichever of their songs came first.
            val thumbs by c.library.artistThumbs.collectAsState()
            LaunchedEffect(d.artists) { c.library.fetchArtistPhotos(d.artists.map { it.artistId to it.artistName }) }
            SectionHeader("Top artists")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.artists) { i, a ->
                    Column(
                        Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = a.artistId != null) { a.artistId?.let { nav.go(Routes.artist(it)) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Artwork(
                                c.library.artistPhoto(thumbs, a.artistId, a.artistName), Modifier.size(104.dp), CircleShape, size = 300,
                                placeholderIcon = Icons.Rounded.Person,
                            )
                            Text(
                                "${i + 1}", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.BottomStart).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 9.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(a.artistName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.ms / 60_000} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (d.genres.isNotEmpty()) item {
            SectionHeader("Top genres")
            val max = d.genres.maxOf { it.second }.coerceAtLeast(1)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.genres.forEachIndexed { i, (g, ms) ->
                    val anim = remember(g, period) { Animatable(0f) }
                    LaunchedEffect(g, period) { anim.animateTo(ms / max.toFloat(), tween(900, delayMillis = i * 90)) }
                    Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Box(Modifier.fillMaxWidth(anim.value).height(40.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))))
                        Text(g, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                        Text("${ms / 60_000} min", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
                    }
                }
            }
        }
        item { SectionHeader("Top songs") }
        itemsIndexed(d.songs.take(50), key = { _, s -> s.songId }) { i, s ->
            val songs = remember(d) { d.songs.map { it.toSong() } }
            Row(
                Modifier.fillMaxWidth().clickable { c.player.playQueue(songs, i, QueueSource(QueueKind.REPLAY, "Replay")) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.titleLarge,
                    color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Black,
                )
                Artwork(s.thumbnail, Modifier.size(52.dp), RoundedCornerShape(10.dp), size = 226)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(s.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(minutes(s.ms), style = MaterialTheme.typography.labelMedium)
                    Text("${s.plays} ${if (s.plays == 1) "play" else "plays"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(d: ReplayData, period: Period, year: Int, loading: Boolean, onOpen: () -> Unit) {
    val minutes = d.totalMs / 60_000
    val anim = remember(period) { Animatable(0f) }
    LaunchedEffect(period, minutes) { anim.animateTo(minutes.toFloat(), tween(1400)) }
    val top = d.songs.firstOrNull()
    Box(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B0F3B), Color(0xFF7C5CFF), Color(0xFFFF3B5C))))
            .clickable(onClick = onOpen)
            .padding(24.dp),
    ) {
        Column {
            Text(
                ("REPLAY " + when (period) { Period.YEAR -> "$year"; Period.LAST_YEAR -> "${year - 1}"; else -> period.label.uppercase() }),
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text("%,d".format(anim.value.toLong()), color = Color.White, style = MaterialTheme.typography.displayLarge)
            Text("minutes listened", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Stat("${d.plays}", if (d.plays == 1) "play" else "plays")
                Stat("${d.songs.size}", if (d.songs.size == 1) "song" else "songs")
                Stat("${d.activeDays}", if (d.activeDays == 1) "day" else "days")
            }
            if (top != null) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(top.thumbnail, Modifier.size(52.dp), RoundedCornerShape(10.dp), size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Your #1 song", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        Text(top.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${minutes(top.ms)} · ${top.artistName}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // The story: Prism's take on Wrapped / Replay.
            Row(
                Modifier.clip(CircleShape).background(Color.White).padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Watch your Replay", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "1 min", "37 min", "1,204 min" — songs under a minute still show "<1 min". */
private fun minutes(ms: Long): String {
    val m = ms / 60_000
    return if (m == 0L && ms > 0) "<1 min" else "%,d min".format(m)
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MonthChart(months: List<MonthTotal>, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val anim = remember(months) { Animatable(0f) }
    LaunchedEffect(months) { anim.animateTo(1f, tween(900)) }
    val names = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val max = months.maxOf { it.ms }.coerceAtLeast(1)
            val gap = 8.dp.toPx()
            val w = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { i, m ->
                val h = size.height * (m.ms / max.toFloat()) * anim.value
                drawRoundRect(
                    Brush.verticalGradient(listOf(tertiary, primary)),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            months.forEach { m ->
                val idx = m.month.substringAfter("-").toIntOrNull()?.minus(1) ?: 0
                Text(names[idx.coerceIn(0, 11)], Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
