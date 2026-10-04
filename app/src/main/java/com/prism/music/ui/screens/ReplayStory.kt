package com.prism.music.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.prism.music.AppContainer
import com.prism.music.data.Habits
import com.prism.music.data.Personality
import com.prism.music.data.ReplayStats
import com.prism.music.data.db.ArtistPlays
import com.prism.music.data.db.SongPlays
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.PrismMediaSourceFactory
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toMediaItem
import com.prism.music.ui.components.PlayingBars
import com.prism.music.ui.theme.ArtistType
import com.prism.music.ui.theme.ArtistTypography
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------- Data

/** Everything the Replay story shows, gathered once when it opens. */
internal class ReplayStory(
    val period: Period,
    /** "2026", "October", "Last 30 days", "All time". */
    val title: String,
    val totalMs: Long,
    val previousMs: Long?,
    val previousLabel: String?,
    val plays: Int,
    val activeDays: Int,
    val songCount: Int,
    val artistCount: Int,
    val songs: List<SongPlays>,
    val artists: List<ArtistPlays>,
    val genres: List<Pair<String, Long>>,
    val genreSongs: Map<String, SongPlays>,
    val habits: Habits,
    /** Artists heard for the first time in this period (null for all time, where everyone is new). */
    val newArtists: List<ArtistPlays>?,
    val personality: Personality,
    val topArtistImage: String?,
    val topArtistType: ArtistType,
)

internal suspend fun loadReplayStory(c: AppContainer, period: Period, year: Int): ReplayStory = withContext(Dispatchers.IO) {
    val (from, to) = range(period)
    val dao = c.db.plays()
    val songs = dao.topSongs(from, to, 100)
    val artists = dao.topArtists(from, to, 20)
    val totalMs = dao.totalMs(from, to)
    val genreMap = c.meta.cachedGenres(songs.map { it.toSong() })
    val genres = songs.groupBy { genreMap[it.songId] ?: "" }.filterKeys { it.isNotEmpty() }
        .mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }.take(5)
    val genreSongs = genres.associate { (g, _) -> g to songs.first { genreMap[it.songId] == g } }

    // The same stretch of time one step back, for "up 24% on last year".
    val (prevFrom, prevTo, prevLabel) = Calendar.getInstance().let {
        fun shift(t: Long, field: Int, by: Int) = Calendar.getInstance().apply { timeInMillis = t; add(field, by) }.timeInMillis
        when (period) {
            Period.YEAR -> Triple(shift(from, Calendar.YEAR, -1), shift(to, Calendar.YEAR, -1), "this time last year")
            Period.LAST_YEAR -> Triple(shift(from, Calendar.YEAR, -1), shift(to, Calendar.YEAR, -1), "${year - 2}")
            Period.MONTH -> Triple(shift(from, Calendar.MONTH, -1), shift(to, Calendar.MONTH, -1), "the same time last month")
            Period.DAYS30 -> Triple(from - 30L * 86_400_000, from - 1, "the 30 days before")
            Period.ALL -> Triple(0L, -1L, null)
        }
    }
    val previousMs = if (prevLabel != null) dao.totalMs(prevFrom, prevTo).takeIf { it > 60_000 } else null

    val habits = ReplayStats.habits(dao.events(from, to))
    val artistCount = dao.artistCount(from, to)
    val newArtists = if (period == Period.ALL || dao.firstEvent()?.let { it >= from } == true) null else dao.newArtists(from, to)
    val activeDays = dao.activeDays(from, to)
    val plays = dao.count(from, to)
    val songCount = dao.songCount(from, to)
    val top = songs.firstOrNull()
    val genreTotal = genres.sumOf { it.second }.coerceAtLeast(1)

    // The top artist's photo and signature typeface, as on their artist page.
    val lead = artists.firstOrNull()
    val leadPage = lead?.artistId?.let { id -> withTimeoutOrNull(5_000) { runCatching { c.ytm.artist(id) }.getOrNull() } }
    val leadGenre = lead?.let { withTimeoutOrNull(3_000) { runCatching { c.meta.artistGenre(it.artistName) }.getOrNull() } }
    val leadType = if (lead?.artistId != null) ArtistTypography.forArtist(lead.artistId, leadGenre, ArtistTypography.parseAudience(leadPage?.subscribers)) else ArtistTypography.standard
    // Artist photos for the artist slides; the story waits a little for them rather than showing album covers.
    val photos = c.scope.launch {
        c.library.fetchArtistPhotos((artists.take(5) + (newArtists ?: emptyList()).take(6)).map { it.artistId to it.artistName })
    }
    withTimeoutOrNull(6_000) { photos.join() }

    ReplayStory(
        period = period,
        title = when (period) {
            Period.YEAR -> "$year"
            Period.LAST_YEAR -> "${year - 1}"
            Period.MONTH -> Calendar.getInstance().getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault()) ?: "This month"
            Period.DAYS30 -> "Last 30 days"
            Period.ALL -> "All time"
        },
        totalMs = totalMs, previousMs = previousMs, previousLabel = prevLabel,
        plays = plays, activeDays = activeDays, songCount = songCount, artistCount = artistCount,
        songs = songs, artists = artists, genres = genres, genreSongs = genreSongs, habits = habits, newArtists = newArtists,
        personality = ReplayStats.personality(
            totalMs = totalMs, topFiveArtistsMs = artists.take(5).sumOf { it.ms }, artistCount = artistCount,
            newArtistCount = newArtists?.size, plays = plays, songCount = songCount,
            topSongPlays = top?.plays ?: 0, topSongTitle = top?.title, activeDays = activeDays,
            topGenreShare = genres.firstOrNull()?.let { it.second.toDouble() / genreTotal }, habits = habits,
        ),
        topArtistImage = leadPage?.thumbnail,
        topArtistType = leadType,
    )
}

// ---------------------------------------------------------------- Slides

private enum class Kind(val seconds: Int) {
    INTRO(6), MINUTES(9), GENRES(8), TOP_ARTIST(9), TOP_ARTISTS(8), TOP_SONG(9), TOP_SONGS(8), HABITS(9), DISCOVERY(8), PERSONALITY(9), SUMMARY(0)
}

private class Slide(val kind: Kind, val song: SongPlays?, val colors: List<Color>)

private fun palette(vararg hex: Long) = hex.map { Color(it) }

private fun slidesFor(s: ReplayStory): List<Slide> {
    val songs = s.songs
    fun byArtist(name: String?) = songs.firstOrNull { it.artistName == name }
    return buildList {
        add(Slide(Kind.INTRO, songs.getOrNull(2) ?: songs.firstOrNull(), palette(0xFF14092E, 0xFF7C5CFF, 0xFFFF3B5C, 0xFFFFB13B)))
        add(Slide(Kind.MINUTES, songs.getOrNull(3) ?: songs.firstOrNull(), palette(0xFF04241A, 0xFF1ED38A, 0xFFB8FF3B, 0xFF00B8FF)))
        if (s.genres.isNotEmpty()) add(Slide(Kind.GENRES, s.genreSongs[s.genres.first().first], palette(0xFF2A0820, 0xFFFF4FD8, 0xFFFF8A3D, 0xFF7C5CFF)))
        if (s.artists.isNotEmpty()) add(Slide(Kind.TOP_ARTIST, byArtist(s.artists.first().artistName), palette(0xFF0B0B12, 0xFF7C5CFF, 0xFF00C2FF, 0xFFFF3B5C)))
        if (s.artists.size >= 3) add(Slide(Kind.TOP_ARTISTS, byArtist(s.artists[1].artistName), palette(0xFF0A1838, 0xFF3D7BFF, 0xFF00E5FF, 0xFFB44DFF)))
        if (songs.isNotEmpty()) add(Slide(Kind.TOP_SONG, songs.first(), palette(0xFF120A0A, 0xFFFF3B30, 0xFFFF9500, 0xFFFF2D87)))
        if (songs.size >= 3) add(Slide(Kind.TOP_SONGS, songs.first(), palette(0xFF3B0A0A, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A)))
        add(Slide(Kind.HABITS, songs.getOrNull(4) ?: songs.firstOrNull(), palette(0xFF050E26, 0xFF5B6CFF, 0xFF00D1FF, 0xFFFFC857)))
        if (!s.newArtists.isNullOrEmpty()) add(Slide(Kind.DISCOVERY, byArtist(s.newArtists.first().artistName), palette(0xFF04201F, 0xFF00E0B8, 0xFF9BFF5B, 0xFF00A3FF)))
        add(Slide(Kind.PERSONALITY, songs.getOrNull(1) ?: songs.firstOrNull(), palette(0xFF240C4A, 0xFFD53369, 0xFFDAAE51, 0xFF7C5CFF)))
        add(Slide(Kind.SUMMARY, songs.firstOrNull(), palette(0xFF0C0C14, 0xFF7C5CFF, 0xFFFF3B5C, 0xFF00C2FF)))
    }
}

// ---------------------------------------------------------------- Background music

/**
 * Plays a stretch of each slide's song from around its chorus, crossfading
 * between them, on its own player so the main queue is left alone.
 */
@OptIn(UnstableApi::class)
private class StoryAudio(context: Context, c: AppContainer) {
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(PrismMediaSourceFactory(c.playbackDataSourceFactory(), c.videoDataSourceFactory()))
        .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }
    var current by mutableStateOf<Song?>(null)
        private set
    var muted = false
        set(v) { field = v; player.volume = if (v) 0f else player.volume.coerceAtLeast(if (current != null) 1f else 0f) }
    private var job: Job? = null

    fun play(song: Song, scope: kotlinx.coroutines.CoroutineScope) {
        if (song.id == current?.id) return
        current = song
        job?.cancel()
        job = scope.launch {
            ramp(0f, 300)
            // Roughly where the chorus lands in most songs.
            val start = if (song.durationSec > 90) (song.durationSec * 0.33).toLong().coerceIn(30, 80) * 1000 else 0L
            player.setMediaItem(song.toMediaItem(), start)
            player.prepare()
            player.play()
            withTimeoutOrNull(8_000) { while (player.playbackState != Player.STATE_READY) delay(40) }
            ramp(1f, 1_400)
        }
    }

    private suspend fun ramp(to: Float, ms: Long) {
        val from = player.volume
        val steps = 28
        for (i in 1..steps) {
            player.volume = if (muted) 0f else from + (to - from) * i / steps
            delay(ms / steps)
        }
    }

    fun release() {
        job?.cancel()
        player.release()
    }
}

// ---------------------------------------------------------------- The story

@Composable
internal fun ReplayStoryDialog(story: ReplayStory, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        StoryContent(story, onDismiss)
    }
}

@Composable
private fun StoryContent(story: ReplayStory, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides = remember(story) { slidesFor(story) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val audio = remember { StoryAudio(context, c) }
    val handedOff = remember { booleanArrayOf(false) }

    // Like the big streaming apps: your music pauses for the story and picks up again after it.
    DisposableEffect(Unit) {
        val main = c.player.player
        val wasPlaying = main?.isPlaying == true
        main?.pause()
        onDispose {
            audio.release()
            if (wasPlaying && !handedOff[0]) c.player.player?.play()
        }
    }

    fun go(i: Int) {
        when {
            i in slides.indices -> { progress = 0f; index = i }
            i < 0 -> progress = 0f
        }
    }

    LaunchedEffect(index) { slides[index].song?.let { audio.play(it.toSong(), scope) } }
    LaunchedEffect(muted) { audio.muted = muted }
    LaunchedEffect(index, paused) {
        val ms = slides[index].kind.seconds * 1000f
        if (paused || ms == 0f) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (progress < 1f) {
            withFrameMillis { now -> progress = (progress + (now - last) / ms).coerceAtMost(1f); last = now }
        }
        go(index + 1)
    }

    val slide = slides[index]
    val base by animateColorAsState(slide.colors[0], tween(700), label = "base")
    val c1 by animateColorAsState(slide.colors[1], tween(700), label = "c1")
    val c2 by animateColorAsState(slide.colors[2], tween(700), label = "c2")
    val c3 by animateColorAsState(slide.colors[3], tween(700), label = "c3")

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationY = drag.coerceAtLeast(0f) * 0.6f
                val s = 1f - (drag.coerceAtLeast(0f) / 4000f).coerceAtMost(0.12f)
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(if (drag > 0) 28.dp else 0.dp))
            .background(base),
    ) {
        Backdrop(base, c1, c2, c3)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(slides) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { o -> if (o.x < size.width * 0.3f) go(index - 1) else go(index + 1) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > 260f) onDismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
                },
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = { (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 1.06f)) togetherWith fadeOut(tween(250)) },
                label = "slide",
            ) { i ->
                SlideView(slides[i], story, onReplay = { go(0) }, onPlayAll = {
                    handedOff[0] = true
                    c.player.playQueue(story.songs.map { it.toSong() }, 0, QueueSource(QueueKind.REPLAY, "Replay · ${story.title}"))
                    onDismiss()
                })
            }
        }

        // Progress, title, mute and close.
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.forEachIndexed { i, _ ->
                    val fill = when { i < index -> 1f; i == index -> progress; else -> 0f }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PRISM REPLAY · ${story.title.uppercase()}", Modifier.weight(1f).padding(start = 4.dp),
                    style = kicker(12.sp), color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                )
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
        }

        // What's playing, Spotify-style.
        audio.current?.let { song ->
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(hiRes(song.thumbnail, 120), null, Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(8.dp))
                if (!muted) PlayingBars(Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${song.title} · ${song.primaryArtist}", color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

/** Slow-drifting colour fields behind every slide. */
@Composable
private fun Backdrop(base: Color, c1: Color, c2: Color, c3: Color) {
    val t by rememberInfiniteTransition(label = "bg").animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val a = t * 2 * PI.toFloat()
        val r = size.maxDimension * 0.62f
        fun blob(color: Color, cx: Float, cy: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(c1, size.width * (0.2f + 0.25f * sin(a)), size.height * (0.18f + 0.12f * cos(a)))
        blob(c2, size.width * (0.85f + 0.15f * cos(a * 2)), size.height * (0.55f + 0.15f * sin(a)))
        blob(c3, size.width * (0.3f + 0.2f * cos(a)), size.height * (0.95f + 0.08f * sin(a * 2)))
        drawRect(Brush.verticalGradient(listOf(base.copy(alpha = 0.15f), base.copy(alpha = 0.55f))))
    }
}

// ---------------------------------------------------------------- Type and motion

private fun display(size: TextUnit, type: ArtistType = ArtistTypography.condensed) = TextStyle(
    fontFamily = type.family, fontWeight = type.weight, fontSize = size, lineHeight = size * 0.94f,
    letterSpacing = type.tracking.em, color = Color.White,
)

private fun kicker(size: TextUnit = 14.sp) = TextStyle(
    fontFamily = ArtistTypography.extended.family, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = 0.06.em, color = Color.White,
)

private val body = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = Color.White)

/** Rises and fades in after [delayMs]. */
@Composable
private fun Modifier.reveal(delayMs: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 90f }
}

private fun minutesOf(ms: Long) = ms / 60_000
private fun fmt(n: Long) = "%,d".format(n)

@Composable
private fun SlideView(slide: Slide, s: ReplayStory, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val pad = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 26.dp, end = 26.dp, top = 84.dp, bottom = 64.dp)
        when (slide.kind) {
            Kind.INTRO -> Intro(s, pad)
            Kind.MINUTES -> Minutes(s, pad)
            Kind.GENRES -> Genres(s, pad)
            Kind.TOP_ARTIST -> TopArtist(s, pad)
            Kind.TOP_ARTISTS -> TopArtists(s, pad)
            Kind.TOP_SONG -> TopSong(s, pad)
            Kind.TOP_SONGS -> TopSongs(s, pad)
            Kind.HABITS -> HabitsSlide(s.habits, pad)
            Kind.DISCOVERY -> Discovery(s, pad)
            Kind.PERSONALITY -> PersonalitySlide(s.personality, pad)
            Kind.SUMMARY -> Summary(s, pad, onReplay, onPlayAll)
        }
    }
}

@Composable
private fun Intro(s: ReplayStory, modifier: Modifier) {
    val spin by rememberInfiniteTransition(label = "fan").animateFloat(-4f, 4f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "r")
    Column(modifier, verticalArrangement = Arrangement.Center) {
        // A fanned stack of your top three covers.
        Box(Modifier.fillMaxWidth().height(250.dp).reveal(100), contentAlignment = Alignment.Center) {
            s.songs.take(3).reversed().forEachIndexed { i, song ->
                val angle = (i - 1) * 12f + spin * (if (i == 2) 1f else 0.4f)
                AsyncImage(
                    hiRes(song.thumbnail, 544), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(190.dp).offset(x = ((i - 1) * 46).dp).rotate(angle)
                        .shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Text("PRISM REPLAY", style = kicker(), modifier = Modifier.reveal(350))
        Text(s.title.uppercase(), style = display(if (s.title.length > 6) 76.sp else 120.sp), modifier = Modifier.reveal(550))
        Text(
            when (s.period) {
                Period.YEAR, Period.LAST_YEAR -> "Your year in music."
                Period.MONTH -> "Your month in music."
                Period.DAYS30 -> "Your last 30 days in music."
                Period.ALL -> "Everything you've played, in one story."
            },
            style = body.copy(fontSize = 24.sp), modifier = Modifier.reveal(800).padding(top = 8.dp),
        )
        Text("Tap to go on · hold to pause", style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.reveal(1_400).padding(top = 24.dp))
    }
}

@Composable
private fun Minutes(s: ReplayStory, modifier: Modifier) {
    val minutes = minutesOf(s.totalMs)
    val count = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(400); count.animateTo(minutes.toFloat(), tween(2_200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("YOU LISTENED FOR", style = kicker(), modifier = Modifier.reveal(100))
        val text = fmt(count.value.toLong())
        Text(text, style = display(if (fmt(minutes).length > 6) 96.sp else 130.sp), maxLines = 1, modifier = Modifier.reveal(200))
        Text("MINUTES", style = display(46.sp, ArtistTypography.extended), modifier = Modifier.reveal(400))
        Spacer(Modifier.height(28.dp))
        val hours = s.totalMs / 3_600_000.0
        Text(
            if (hours >= 24) "That's %.1f days of music, start to finish.".format(hours / 24) else "That's %.1f hours of music, start to finish.".format(hours),
            style = body, modifier = Modifier.reveal(2_400),
        )
        if (s.previousMs != null && s.previousLabel != null) {
            val change = ((s.totalMs - s.previousMs) * 100.0 / s.previousMs).toInt()
            Row(Modifier.reveal(3_000).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (change >= 0) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (change >= 0) "$change% more than ${s.previousLabel}" else "${-change}% less than ${s.previousLabel}", style = body)
            }
        }
        Row(Modifier.reveal(3_600).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Stat(fmt(s.songCount.toLong()), if (s.songCount == 1) "song" else "songs")
            Stat(fmt(s.artistCount.toLong()), if (s.artistCount == 1) "artist" else "artists")
            Stat(fmt(s.activeDays.toLong()), if (s.activeDays == 1) "day" else "days")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = display(40.sp))
        Text(label, style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
    }
}

@Composable
private fun Genres(s: ReplayStory, modifier: Modifier) {
    val total = s.genres.sumOf { it.second }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("YOUR TOP GENRE", style = kicker(), modifier = Modifier.reveal(100))
        Text(s.genres.first().first.uppercase(), style = display(72.sp), modifier = Modifier.reveal(300))
        Spacer(Modifier.height(30.dp))
        s.genres.forEachIndexed { i, (g, ms) ->
            val share = ms.toFloat() / total
            val w = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(700L + i * 160); w.animateTo(share, tween(900)) }
            Column(Modifier.padding(vertical = 6.dp).reveal(600 + i * 160)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(g, style = body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${(share * 100).toInt()}%", style = body.copy(fontSize = 17.sp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(w.value).clip(CircleShape).background(Color.White.copy(alpha = if (i == 0) 1f else 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun TopArtist(s: ReplayStory, modifier: Modifier) {
    val a = s.artists.first()
    val thumbs by LocalContainer.current.library.artistThumbs.collectAsState()
    val c = LocalContainer.current
    val image = s.topArtistImage ?: c.library.artistPhoto(thumbs, a.artistId, a.artistName)
    val zoom by rememberInfiniteTransition(label = "z").animateFloat(1.05f, 1.18f, infiniteRepeatable(tween(9_000), RepeatMode.Reverse), label = "z")
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(image, 2880), null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.35f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.9f))))
        Column(modifier, verticalArrangement = Arrangement.Bottom) {
            Text("YOUR TOP ARTIST", style = kicker(), modifier = Modifier.reveal(300))
            val type = s.topArtistType
            Text(
                if (type.caps) a.artistName.uppercase() else a.artistName,
                style = TextStyle(
                    fontFamily = type.family, fontWeight = type.weight, fontSize = (type.size * 1.25f).coerceAtMost(72f).sp,
                    lineHeight = (type.size * 1.25f * type.lineHeight).coerceAtMost(72f).sp, letterSpacing = type.tracking.em, color = Color.White,
                    fontStyle = if (type.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                ),
                maxLines = 3, modifier = Modifier.reveal(600),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.reveal(1_200), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Stat(fmt(minutesOf(a.ms)), "minutes")
                Stat(fmt(a.plays.toLong()), "plays")
                Stat("${s.songs.count { it.artistName == a.artistName }}", "songs")
            }
            val share = (a.ms * 100.0 / s.totalMs.coerceAtLeast(1)).toInt()
            Text("$share% of everything you played was them.", style = body, modifier = Modifier.reveal(1_800).padding(top = 16.dp))
        }
    }
}

@Composable
private fun TopArtists(s: ReplayStory, modifier: Modifier) {
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("YOUR TOP ARTISTS", style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.artists.take(5).forEachIndexed { i, a ->
            Row(Modifier.padding(vertical = 8.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 300), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (i == 0) 76.dp else 62.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.artistName, style = body.copy(fontSize = if (i == 0) 24.sp else 20.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${fmt(minutesOf(a.ms))} minutes", style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)))
                }
            }
        }
    }
}

@Composable
private fun TopSong(s: ReplayStory, modifier: Modifier) {
    val song = s.songs.first()
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.035f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "p")
    Box(Modifier.fillMaxSize()) {
        AsyncImage(hiRes(song.thumbnail, 300), null, Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = 0.55f }, contentScale = ContentScale.Crop)
        // Keeps white text readable over light covers.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YOUR #1 SONG", style = kicker(), modifier = Modifier.reveal(100))
            Spacer(Modifier.height(22.dp))
            AsyncImage(
                hiRes(song.thumbnail, 900), null, contentScale = ContentScale.Crop,
                modifier = Modifier.reveal(250).size(270.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .shadow(40.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(26.dp))
            Text(song.title.uppercase(), style = display(48.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.reveal(700))
            Text(song.artistName, style = body.copy(fontSize = 20.sp), textAlign = TextAlign.Center, modifier = Modifier.reveal(900).padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                "You played it ${song.plays} ${if (song.plays == 1) "time" else "times"}, for ${fmt(minutesOf(song.ms))} minutes.",
                style = body, textAlign = TextAlign.Center, modifier = Modifier.reveal(1_500),
            )
        }
    }
}

@Composable
private fun TopSongs(s: ReplayStory, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("YOUR TOP SONGS", style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(20.dp))
        s.songs.take(5).forEachIndexed { i, song ->
            Row(Modifier.padding(vertical = 7.dp).reveal(300 + i * 220), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = display(52.sp), modifier = Modifier.width(44.dp))
                AsyncImage(hiRes(song.thumbnail, 226), null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = body.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artistName} · ${fmt(minutesOf(song.ms))} min", style = body.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HabitsSlide(h: Habits, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(300); grow.animateTo(1f, tween(1_400)) }
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("WHEN YOU LISTEN", style = kicker(), modifier = Modifier.reveal(100).fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        // A 24-hour clock: each spoke is an hour, its length how much you listened then.
        Box(Modifier.size(280.dp).reveal(200), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val max = h.byHour.max().coerceAtLeast(1)
                val inner = size.minDimension * 0.24f
                val outer = size.minDimension * 0.5f
                for (hr in 0 until 24) {
                    val ang = (hr / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                    val len = (outer - inner) * (0.06f + 0.94f * h.byHour[hr] / max.toFloat()) * grow.value
                    val dir = Offset(cos(ang), sin(ang))
                    drawLine(
                        if (hr == h.peakHour) Color.White else Color.White.copy(alpha = 0.45f),
                        center + dir * inner, center + dir * (inner + len), strokeWidth = 11.dp.toPx() * 0.9f, cap = StrokeCap.Round,
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.25f), inner - 8.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PRIME TIME", style = kicker(10.sp))
                Text(ReplayStats.hourLabel(h.peakHour), style = display(34.sp))
            }
        }
        Spacer(Modifier.height(26.dp))
        val fmtDay = DateTimeFormatter.ofPattern("MMM d")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Favourite day", ReplayStats.weekdays[h.topWeekday], 1_200)
            if (h.longestStreak > 1) Fact("Longest streak", "${h.longestStreak} days in a row", 1_450)
            h.biggestDay?.let { Fact("Biggest day", "${it.format(fmtDay)} · ${fmt(minutesOf(h.biggestDayMs))} minutes", 1_700) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, delayMs: Int) {
    Row(
        Modifier.fillMaxWidth().reveal(delayMs).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = kicker(11.sp).copy(color = Color.White.copy(alpha = 0.75f)), modifier = Modifier.weight(1f))
        Text(value, style = body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Discovery(s: ReplayStory, modifier: Modifier) {
    val found = s.newArtists.orEmpty()
    val lib = LocalContainer.current.library
    val thumbs by lib.artistThumbs.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("NEW TO YOU", style = kicker(), modifier = Modifier.reveal(100))
        Text(fmt(found.size.toLong()), style = display(140.sp), modifier = Modifier.reveal(250))
        Text(if (found.size == 1) "NEW ARTIST" else "NEW ARTISTS", style = display(44.sp, ArtistTypography.extended), modifier = Modifier.reveal(450))
        Text("found their way into your rotation.", style = body, modifier = Modifier.reveal(700).padding(top = 8.dp))
        Spacer(Modifier.height(26.dp))
        Row(Modifier.reveal(1_000)) {
            found.take(6).forEachIndexed { i, a ->
                AsyncImage(
                    hiRes(lib.artistPhoto(thumbs, a.artistId, a.artistName), 226), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (-14 * i).dp).size(62.dp).clip(CircleShape).border(3.dp, Color(0xFF04201F), CircleShape),
                )
            }
        }
        found.firstOrNull()?.let { a ->
            Text(
                "Your favourite find: ${a.artistName}, with ${fmt(minutesOf(a.ms))} minutes.",
                style = body, modifier = Modifier.reveal(1_500).padding(top = 22.dp),
            )
        }
    }
}

@Composable
private fun PersonalitySlide(p: Personality, modifier: Modifier) {
    val turn by rememberInfiniteTransition(label = "prism").animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "a")
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text("YOUR LISTENING PERSONALITY", style = kicker(), modifier = Modifier.reveal(100))
        Spacer(Modifier.height(24.dp))
        // A slowly turning prism, Prism's emblem.
        Canvas(Modifier.size(170.dp).reveal(300).rotate(turn)) {
            val w = size.width
            val tri = Path().apply {
                moveTo(w / 2, w * 0.08f); lineTo(w * 0.94f, w * 0.84f); lineTo(w * 0.06f, w * 0.84f); close()
            }
            drawPath(tri, Brush.sweepGradient(listOf(Color(0xFFFF7A9C), Color(0xFFFFD36B), Color(0xFF6BE3FF), Color(0xFFB18CFF), Color(0xFFFF7A9C))))
            drawPath(tri, Color.White.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
        }
        Spacer(Modifier.height(28.dp))
        Text(p.name.uppercase(), style = display(64.sp), modifier = Modifier.reveal(700))
        Text(p.line, style = body, modifier = Modifier.reveal(1_200).padding(top = 14.dp))
    }
}

@Composable
private fun Summary(s: ReplayStory, modifier: Modifier, onReplay: () -> Unit, onPlayAll: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val card = rememberGraphicsLayer()
    val thumbs by c.library.artistThumbs.collectAsState()
    Column(modifier.padding(top = 0.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // The card that gets shared, drawn into a layer so it can be saved as an image.
        Column(
            Modifier.reveal(100).fillMaxWidth()
                .drawWithContent { card.record { this@drawWithContent.drawContent() }; drawLayer(card) }
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B0F3B), Color(0xFF5B3DD6), Color(0xFFE0335A))))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lead = s.artists.firstOrNull()
                AsyncImage(
                    hiRes(s.topArtistImage ?: lead?.let { c.library.artistPhoto(thumbs, it.artistId, it.artistName) } ?: s.songs.firstOrNull()?.thumbnail, 600), null,
                    Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("PRISM REPLAY", style = kicker(12.sp))
                    Text(s.title.uppercase(), style = display(if (s.title.length > 6) 34.sp else 52.sp), maxLines = 1)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row {
                SummaryList("Top artists", s.artists.take(5).map { it.artistName }, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                SummaryList("Top songs", s.songs.take(5).map { it.title }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("MINUTES LISTENED", style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                    Text(fmt(minutesOf(s.totalMs)), style = display(36.sp))
                }
                s.genres.firstOrNull()?.let { (g, _) ->
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("TOP GENRE", style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
                        Text(g, style = body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(s.personality.name, style = body.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.reveal(500), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Share", Icons.Rounded.Share, primary = true) {
                scope.launch {
                    runCatching {
                        val bmp = card.toImageBitmap().asAndroidBitmap()
                        val file = withContext(Dispatchers.IO) {
                            File(context.cacheDir, "share").apply { mkdirs() }.resolve("prism-replay.png").also { f ->
                                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_TEXT, "My Prism Replay · ${s.title}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Share your Replay"))
                    }.onFailure { Toast.makeText(context, "Couldn't share your Replay", Toast.LENGTH_SHORT).show() }
                }
            }
            Pill("Play", Icons.Rounded.PlayArrow, onClick = onPlayAll)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.reveal(700), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c.settings.current.isLoggedIn) Pill("Save playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) {
                scope.launch {
                    val name = "Replay ${s.title}"
                    val id = runCatching { c.ytm.createPlaylist(name, s.songs.take(100).map { it.songId }, "Made with Prism Replay") }.getOrNull()
                    Toast.makeText(context, if (id != null) "Saved \"$name\" to YouTube Music" else "Couldn't create playlist", Toast.LENGTH_SHORT).show()
                }
            }
            Pill("Watch again", Icons.Rounded.Replay, onClick = onReplay)
        }
    }
}

@Composable
private fun SummaryList(title: String, items: List<String>, modifier: Modifier) {
    Column(modifier) {
        Text(title.uppercase(), style = kicker(10.sp).copy(color = Color.White.copy(alpha = 0.75f)))
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { i, t ->
            Text("${i + 1}  $t", style = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) Color.Black else Color.White
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg))
    }
}
