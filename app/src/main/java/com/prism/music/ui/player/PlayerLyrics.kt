package com.prism.music.ui.player

import com.prism.music.ui.theme.withLyricsFont
import kotlin.math.roundToInt
import androidx.compose.ui.unit.em
import com.prism.music.ui.theme.PrismIcons
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.prism.music.ui.theme.LocalAppSettings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.prism.music.ui.components.pane
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.LyricsSource
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 400
private val FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private val FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)
private const val UNSUNG_ALPHA = 0.38f

enum class ProviderState { UNKNOWN, LOADING, FOUND_KARAOKE, FOUND_SYNCED, FOUND_PLAIN, NONE }

@Stable
class LyricsState(
    private val repo: LyricsRepository,
    val song: Song,
    /** Outlives the source sheet, so closing it never cancels a switch half-way. */
    private val scope: CoroutineScope,
) {
    var lyrics by mutableStateOf<Lyrics?>(null)
    var loading by mutableStateOf(true)
    var source by mutableStateOf<LyricsSource?>(null)
    val providers = mutableStateMapOf<LyricsSource, ProviderState>()
    /** Sources whose lyrics star out swear words. */
    val censoredSources = mutableStateMapOf<LyricsSource, Boolean>()
    /** Every answer already seen for this song, so switching back is instant. */
    private val found = HashMap<LyricsSource, Lyrics?>()
    private var selectJob: Job? = null

    /** Not looked up yet: on mobile data with "Lyrics on mobile data" off, they wait for the lyrics to be opened (#95). */
    var waiting by mutableStateOf(false)
        private set
    /** A look-up has finished (the whole one, or the saved-only one while [waiting]). */
    var settled = false
        private set

    val lines: List<LyricLine> get() = lyrics?.lines.orEmpty()
    val unavailable: Boolean get() = !loading && lyrics == null && !waiting

    /** [savedOnly]: only what's saved on the phone, no lyric service asked (then [waiting] if there's nothing). */
    suspend fun loadAuto(savedOnly: Boolean = false) {
        // Podcast episodes have no lyrics: don't ask ten lyric services about one.
        if (song.isEpisode) { lyrics = null; loading = false; settled = true; return }
        loading = true
        if (savedOnly) {
            lyrics = try { repo.savedOnly(song) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            waiting = lyrics == null
        } else {
            lyrics = try { repo.auto(song) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            waiting = false
        }
        source = lyrics?.source
        lyrics?.let { found[it.source] = it; providers[it.source] = it.state() }
        loading = false
        settled = true
    }

    private suspend fun lookup(src: LyricsSource): Lyrics? {
        if (found.containsKey(src)) return found[src]
        val l = repo.fetch(song, src)
        found[src] = l
        return l
    }

    /** Switches to [src]; the pick is remembered for this song (and its saved lyrics used offline). */
    fun select(src: LyricsSource) {
        selectJob?.cancel()
        source = src
        repo.choose(song, src)
        if (found.containsKey(src)) {
            lyrics = found[src]
            loading = false
            return
        }
        loading = true
        providers[src] = ProviderState.LOADING
        selectJob = scope.launch {
            val l = try { lookup(src) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (source == src) {
                lyrics = l
                loading = false
            }
            providers[src] = l.state()
        }
    }

    /** Fetches the current source's lyrics again, replacing the saved copy (or, with none found yet, asks every source again). */
    fun redownload() {
        selectJob?.cancel()
        val src = source ?: run {
            loading = true
            selectJob = scope.launch {
                val l = try { repo.auto(song, force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                found.clear(); providers.clear()
                lyrics = l
                source = l?.source
                l?.let { found[it.source] = it; providers[it.source] = it.state() }
                loading = false
            }
            return
        }
        loading = true
        providers[src] = ProviderState.LOADING
        selectJob = scope.launch {
            val l = try { repo.fetch(song, src, force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            // Keep what we had if the fresh download failed.
            if (l != null || found[src] == null) found[src] = l
            if (source == src) {
                lyrics = found[src]
                loading = false
            }
            providers[src] = found[src].state()
        }
    }

    /** What the lyrics are looked up as, when the listener changed it (#63): (title, artist). */
    val searchAs: Pair<String, String>? get() = repo.searchAs(song)

    /**
     * Looks the lyrics up again as [title] by [artist] (#63), from every source: what was found under the song's own
     * title is forgotten, and so is a source picked for it.
     */
    fun searchAgainAs(title: String, artist: String, sources: List<LyricsSource>) {
        repo.setSearchAs(song, title, artist)
        repo.forgetChoice(song)
        selectJob?.cancel()
        found.clear(); providers.clear(); censoredSources.clear()
        loading = true
        selectJob = scope.launch {
            val l = try { repo.auto(song, force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            lyrics = l
            source = l?.source
            l?.let { found[it.source] = it; providers[it.source] = it.state() }
            loading = false
            probeAll(sources)
        }
    }

    /** Checks every source in the background to show which ones have lyrics. */
    fun probeAll(sources: List<LyricsSource>) {
        sources.forEach { src ->
            if (providers[src].let { it != null && it != ProviderState.UNKNOWN }) return@forEach
            providers[src] = ProviderState.LOADING
            scope.launch {
                providers[src] = try { lookup(src).state() } catch (e: CancellationException) {
                    providers[src] = ProviderState.UNKNOWN
                    throw e
                } catch (e: Exception) { ProviderState.UNKNOWN }
            }
        }
    }

    private fun Lyrics?.state(): ProviderState {
        if (this != null) censoredSources[source] = censored
        return when {
            this == null -> ProviderState.NONE
            wordSynced -> ProviderState.FOUND_KARAOKE
            synced -> ProviderState.FOUND_SYNCED
            else -> ProviderState.FOUND_PLAIN
        }
    }
}

/**
 * The lyrics for [song], looked up as it starts. With "Lyrics on mobile data" off (#95) and the phone on mobile data,
 * only lyrics already saved on the phone are shown until [opened] (the lyrics are on screen), or until Wi-Fi is back.
 */
@Composable
fun rememberLyricsState(song: Song, opened: Boolean = true): LyricsState {
    val c = LocalContainer.current
    val repo = c.lyrics
    val scope = rememberCoroutineScope()
    val state = remember(song.id) { LyricsState(repo, song, scope) }
    val metered by c.network.metered.collectAsState()
    val hold = metered && !LocalAppSettings.current.lyricsOnMobileData
    LaunchedEffect(song.id, opened, hold) {
        if (!state.settled || state.waiting && (opened || !hold)) state.loadAuto(savedOnly = hold && !opened)
    }
    return state
}

/**
 * The playhead for word sweeps and line hand-offs, advanced every frame by the
 * frame time and gently pulled toward the player's reported position. The
 * player's own position moves in uneven steps (audio buffer timestamps), which
 * made the sweep stutter; this keeps it gliding at exactly the frame rate while
 * staying within a few ms of the audio, and snaps on seeks.
 */
@Composable
fun rememberLyricClock(active: Boolean = true): State<Long> {
    val pc = LocalContainer.current.player
    val clock = remember { mutableLongStateOf(pc.lyricPosition) }
    // Android runs ordinary app content at 60 Hz; ask for the panel's full rate while lyrics move.
    val view = LocalView.current
    // Only while they move: paused, a 120 Hz screen goes back to its own rate (it stayed at 120 under paused lyrics).
    val playing by pc.isPlaying.collectAsState()
    DisposableEffect(active && playing) {
        if (active && playing && Build.VERSION.SDK_INT >= 35) {
            val before = view.requestedFrameRate
            view.requestedFrameRate = 120f
            onDispose { view.requestedFrameRate = before }
        } else onDispose { }
    }
    val shift by pc.lyricShift.collectAsState()
    LaunchedEffect(active, shift) {
        var shown = pc.lyricPosition.toDouble()
        clock.longValue = shown.toLong()
        if (!active) return@LaunchedEffect
        var last = -1L
        while (true) {
            // Paused or buffering, the playhead stands still: so do the lyrics (they used to creep up to 250 ms ahead
            // of the audio and then jump back). It's then looked at a few times a second (a seek still moves them),
            // rather than on every frame of the screen.
            if (pc.player?.isPlaying != true) {
                withFrameNanos { }
                last = -1L
                shown = pc.lyricPosition.toDouble()
                clock.longValue = shown.toLong()
                delay(PAUSED_CLOCK_MS)
                continue
            }
            withFrameNanos { now ->
            val actual = pc.lyricPosition
            if (last < 0 || pc.player?.isPlaying != true) shown = actual.toDouble() else {
                val speed = pc.player?.playbackParameters?.speed ?: 1f
                shown += (now - last) / 1_000_000.0 * speed
                val err = actual - shown
                shown = if (abs(err) > 250) actual.toDouble() else shown + err * 0.06
            }
            last = now
            clock.longValue = shown.toLong()
            }
        }
    }
    return clock
}

/** How often a paused song's lyric clock is looked at (a seek while paused shows within this). */
private const val PAUSED_CLOCK_MS = 40L

private fun List<LyricLine>.indexAt(ms: Long): Int {
    var idx = -1
    for (i in indices) if (this[i].timeMs in 0..ms) idx = i else if (this[i].timeMs > ms) break
    return idx
}

/**
 * Every line being sung at [ms], in order: the latest to start, and any earlier one still going (#65: when two voices
 * overlap, the first line stayed lit only until the second began, though it was still being sung). Lines with no
 * end of their own (LRC) end where the next begins, so they never overlap.
 */
internal fun List<LyricLine>.activeAt(ms: Long): List<Int> {
    val latest = indexAt(ms)
    if (latest < 0) return emptyList()
    val out = ArrayList<Int>(2)
    // Overlaps are short: a few lines back is plenty.
    for (i in maxOf(0, latest - 4) until latest) {
        val l = this[i]
        if (!l.isGap && l.isWordSynced && l.endMs > ms && l.timeMs <= ms) out += i
    }
    out += latest
    return out
}

/** Current lyric, one line, directly above the scrubber. */
@Composable
fun CurrentLyricStrip(state: LyricsState, isPlaying: Boolean, onClick: () -> Unit) {
    // Romanized "instead": the strip sings the Latin spelling too (same timings).
    val roman = rememberRomanized(state)
    val instead = romanizeMode(state.song.id) == com.prism.music.data.prefs.RomanizeMode.INSTEAD
    // As the lyrics page draws them (1.7.5, "Same look everywhere"), else as the strip always has.
    val settings = LocalAppSettings.current
    val pageLook = rememberLyricsLook(settings, state.song.thumbnail)
    val look = if (settings.lyricsEverywhere) pageLook else LyricsLook.Classic
    val lines = remember(state.lines, roman, instead, look.uppercase) {
        (if (instead && roman != null) state.lines.mapIndexed { i, l -> roman.getOrNull(i) ?: l } else state.lines).map(look::shown)
    }
    val synced = lines.any { it.timeMs >= 0 }
    // Centered and up to two lines (#121), as the listener picks. Two lines keep their room all the time, so the
    // player doesn't move up and down as lines change length; the line sits at the bottom of it, over the scrubber.
    val centered = settings.lyricsStripAlign == com.prism.music.data.prefs.LyricsAlign.CENTER
    val rows = if (settings.lyricsStripTwoLines) 2 else 1
    val baseStyle = MaterialTheme.typography.titleMedium.let { if (centered) it.copy(textAlign = TextAlign.Center) else it }
    val room = if (rows > 1) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val line = baseStyle.lineHeight.takeIf { it.isSp } ?: (baseStyle.fontSize * 1.35f)
        Modifier.heightIn(min = with(density) { line.toDp() } * rows + 8.dp)
    } else Modifier
    Box(Modifier.fillMaxWidth().then(room).offset(y = 6.dp), contentAlignment = if (centered) Alignment.BottomCenter else Alignment.BottomStart) {
        when {
            // A podcast episode has no lyrics to look for: the line keeps its room, empty.
            state.song.isEpisode -> Text(" ", style = baseStyle, modifier = Modifier.padding(vertical = 4.dp))
            lines.isNotEmpty() && !synced -> StripRow(onClick, note = true, centered = centered) {
                Text("Open lyrics", style = baseStyle, color = Color.White, maxLines = 1)
            }
            lines.isNotEmpty() -> {
                val clock = rememberLyricClock(isPlaying)
                val index by remember(lines) { derivedStateOf { lines.indexAt(clock.value) } }
                val current = lines.getOrNull(index)
                val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
                val instrumental = current == null || current.isGap
                val text = when {
                    instrumental && index < firstSung -> "Intro"
                    instrumental -> "Instrumental"
                    else -> current!!.text
                }
                // A right-to-left line (Arabic, Hebrew…) runs from the right of the strip, its arrow at its end on the left.
                val rtl = !instrumental && isRtl(text)
                val stripStyle = baseStyle.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content).withLyricsFont()
                StripRow(onClick, note = instrumental, rtl = rtl, centered = centered) {
                    AnimatedContent(
                        index to text,
                        transitionSpec = {
                            (fadeIn(tween(340, easing = FastOutSlowInEasing)) + slideInVertically(tween(340, easing = FastOutSlowInEasing)) { (it * 0.35f).toInt() }) togetherWith
                                (fadeOut(tween(340, easing = FastOutSlowInEasing)) + slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { -(it * 0.35f).toInt() }) using
                                SizeTransform(clip = false)
                        },
                        label = "strip",
                    ) { (i, t) ->
                        val line = lines.getOrNull(i)
                        if (line != null && !line.isGap && (line.isWordSynced || look.style != com.prism.music.data.prefs.LyricsStyle.PRISM)) {
                            SungLine(line, clock, stripStyle.copy(shadow = look.shadow), look, null, maxLines = rows, strip = true)
                        } else {
                            Text(
                                t, style = stripStyle, color = if (instrumental) Color.White.copy(alpha = 0.5f) else Color.White, maxLines = rows, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // On mobile data with "Lyrics on mobile data" off (#95): opening them looks them up.
            state.waiting -> StripRow(onClick, note = false, centered = centered) {
                Text("Tap to load lyrics", style = baseStyle, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
            }
            state.unavailable -> {
                var visible by remember(state.song.id) { mutableStateOf(true) }
                LaunchedEffect(state.song.id) { delay(4000); visible = false }
                val a by animateFloatAsState(if (visible) 0.55f else 0f, tween(900), label = "na")
                Text("Lyrics not available", style = baseStyle, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(vertical = 4.dp).graphicsLayer { alpha = a })
            }
            else -> Text("Looking for lyrics…", style = baseStyle, color = Color.White.copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun StripRow(onClick: () -> Unit, note: Boolean, rtl: Boolean = false, centered: Boolean = false, content: @Composable () -> Unit) {
    // The strip follows the line's own direction, not the phone's: a right-to-left line starts at the right and
    // its arrow (mirrored) follows it on the left.
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (note) {
                Icon(PrismIcons.Song, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.weight(1f, fill = false)) { content() }
            Spacer(Modifier.width(6.dp))
            Icon(PrismIcons.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Whether [text] reads right to left (Arabic, Hebrew, Persian…): its first letter with a direction decides, as
 * Android's own text does. Lyrics take their direction from their words, not the phone's language.
 */
internal fun isRtl(text: String): Boolean {
    for (ch in text) when (Character.getDirectionality(ch)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
    }
    return false
}

/**
 * A word-timed line: a dim copy with a bright copy over it, clipped to exactly
 * what has been sung so far — the boundary travels through each word in time
 * (leftwards in a right-to-left line).
 */
@Composable
fun SweptLine(
    line: LyricLine,
    clock: State<Long>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    dimAlpha: Float = UNSUNG_ALPHA,
    /** What the sung part is filled with (null: white); the part still to come ([dimColor], else dim white); a glow round the sung part. */
    litBrush: Brush? = null,
    dimColor: Color? = null,
    glow: androidx.compose.ui.graphics.Shadow? = null,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val offsets = remember(line) {
        var acc = 0
        line.words.map { w -> val start = acc; acc += w.text.length; start to acc }
    }
    val rtl = remember(line) { isRtl(line.text) }
    // In a right-to-left layout a right-to-left line already starts at the right, so it can keep its own width.
    val ltrLayout = LocalLayoutDirection.current == LayoutDirection.Ltr
    Box(modifier) {
        // Full width, so centred and right-aligned lines (and right-to-left ones) sit where their plain copies do.
        val wide = if ((rtl && ltrLayout) || style.textAlign == TextAlign.Center || style.textAlign == TextAlign.End) Modifier.fillMaxWidth() else Modifier
        Text(line.text, wide, style = style, color = dimColor ?: Color.White.copy(alpha = dimAlpha), maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
        Text(
            line.text, style = style.copy(brush = litBrush, shadow = glow ?: style.shadow), color = if (litBrush != null) Color.Unspecified else Color.White,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = wide
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val now = clock.value
                    // Characters sung so far, with a fractional part inside the current word.
                    var sung = 0f
                    for ((i, w) in line.words.withIndex()) {
                        val (s, e) = offsets[i]
                        if (now >= w.endMs) sung = e.toFloat()
                        else if (now >= w.startMs) {
                            val f = ((now - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            sung = s + (w.text.trimEnd().length) * f
                            break
                        } else break
                    }
                    if (sung <= 0f) return@drawWithContent
                    drawContent()
                    // Erase what hasn't been sung yet, with a soft edge so the light glides through the word.
                    val feather = size.height.coerceAtMost(l.getLineBottom(0) - l.getLineTop(0)) * 0.55f
                    val textLen = line.text.length
                    for (li in 0 until l.lineCount) {
                        val ls = l.getLineStart(li)
                        val le = l.getLineEnd(li, visibleEnd = true)
                        val top = l.getLineTop(li)
                        val bottom = l.getLineBottom(li)
                        if (sung >= le) continue
                        if (sung <= ls) {
                            drawRect(Color.Black, Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bottom - top), blendMode = BlendMode.DstOut)
                            continue
                        }
                        val ci = sung.toInt().coerceIn(0, textLen - 1)
                        val box = l.getBoundingBox(ci)
                        // A right-to-left letter is read from its right edge.
                        val rtlChar = l.getBidiRunDirection(ci) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                        val x = if (rtlChar) box.right - box.width * (sung - ci) else box.left + box.width * (sung - ci)
                        // What's still to come lies after x: to its right, or to its left in a right-to-left line.
                        val rtlLine = l.getParagraphDirection(ls) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                        drawRect(
                            if (rtlLine) Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x - feather / 2, endX = x + feather / 2)
                            else Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = x - feather / 2, endX = x + feather / 2),
                            Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bottom - top), blendMode = BlendMode.DstOut,
                        )
                    }
                },
        )
    }
}

/**
 * A thin line of split light growing under the line being sung, from [startMs] to [endMs] on [clock]; [origin] is where
 * it grows from (0 the start, 0.5 the middle for centred lyrics, 1 the end). Read in the draw phase, so it costs no
 * recomposition.
 */
@Composable
fun LyricProgress(startMs: Long, endMs: Long, clock: State<Long>, origin: Float, modifier: Modifier = Modifier) {
    // Settings → Lyrics → Progress line: off keeps its room (lines don't move), it just isn't drawn.
    val show = com.prism.music.ui.theme.LocalAppSettings.current.lyricsProgressLine
    androidx.compose.foundation.Canvas(modifier.height(2.dp)) {
        if (!show) return@Canvas
        val f = ((clock.value - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        // At most 70% of the width, so it reads as a mark under the words rather than a rule across the page.
        val full = size.width * 0.7f
        val w = full * f
        val x = when (origin) { 0.5f -> (size.width - w) / 2; 1f -> size.width - w; else -> 0f }
        drawRoundRect(
            com.prism.music.ui.theme.PrismTokens.spectrum(true, x, x + full),
            androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

/** Weights of [WeightSweptLine]: words still to come, and sung ones; the word being sung moves between them. */
private const val LIGHT_WEIGHT = 300
private const val SUNG_WEIGHT = 500
private const val WEIGHT_STEP = 20
private const val WEIGHT_STEPS = (SUNG_WEIGHT - LIGHT_WEIGHT) / WEIGHT_STEP

/**
 * For [word] in [style] (Prism's axis family), at each weight step from Light to Medium: the letter spacing (em) that
 * brings it back to its Light width. [fontPx] is the style's font size in pixels.
 */
internal fun weightSpacing(
    measurer: androidx.compose.ui.text.TextMeasurer, word: String, style: TextStyle, fontPx: Float,
    light: Int = LIGHT_WEIGHT, steps: Int = WEIGHT_STEPS,
): FloatArray {
    if (word.isEmpty()) return FloatArray(steps + 1)
    fun width(weight: Int) = measurer.measure(word, style.copy(fontWeight = FontWeight(weight), letterSpacing = 0.em)).size.width
    val base = width(light)
    return FloatArray(steps + 1) { k -> if (k == 0) 0f else (base - width(light + k * WEIGHT_STEP)).toFloat() / word.length / fontPx }
}

/**
 * Spectrum's karaoke line (1.7): set light, and each word grows from Light to Medium on Prism's typeface's weight axis
 * as it's sung, brightening as it goes, instead of a light wiping through it. A heavier word is wider, so each word
 * gives that width back as letter spacing: the line never shifts. [axis] is the lyrics' typeface on its weight axis
 * ([AppFonts.axis]: any font Prism ships); System and imported fonts use [SweptLine].
 */
@Composable
fun WeightSweptLine(
    line: LyricLine, clock: State<Long>, style: TextStyle, axis: androidx.compose.ui.text.font.FontFamily, modifier: Modifier = Modifier,
    /** The words' weight before and after they're sung (1.7.5: the lyrics' weight setting; Light to Medium as before). */
    lightWeight: Int = LIGHT_WEIGHT, sungWeight: Int = SUNG_WEIGHT,
    /** The sung words' fill (null: white). */
    litBrush: Brush? = null,
    dimAlpha: Float = UNSUNG_ALPHA,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val steps = ((sungWeight - lightWeight) / WEIGHT_STEP).coerceAtLeast(0)
    val axisStyle = style.copy(fontFamily = axis, fontWeight = FontWeight(lightWeight))
    val fontPx = with(androidx.compose.ui.platform.LocalDensity.current) { style.fontSize.toPx() }
    // For each word, at each weight step: the letter spacing (em) that brings it back to its light width.
    val spacing = remember(line, axisStyle, fontPx, steps) { line.words.map { w -> weightSpacing(measurer, w.text.trimEnd(), axisStyle, fontPx, lightWeight, steps) } }
    // Each word's weight step and brightness (0–10), recomposing only when one of them changes.
    val marks by remember(line, steps) {
        derivedStateOf {
            val now = clock.value
            line.words.map { w ->
                val f = when {
                    now >= w.endMs -> 1f
                    now < w.startMs -> 0f
                    else -> ((now - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                (f * steps).roundToInt() to (f * 10).roundToInt()
            }
        }
    }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        line.words.forEachIndexed { i, w ->
            val (step, lit) = marks[i]
            val a = dimAlpha + (1f - dimAlpha) * lit / 10f
            val core = w.text.trimEnd()
            val weight = FontWeight(lightWeight + step * WEIGHT_STEP)
            val ls = (spacing[i].getOrElse(step) { 0f }).em
            // A colored fill comes in as the word is sung; until then the word is dim white.
            pushStyle(
                if (litBrush != null && lit > 0) androidx.compose.ui.text.SpanStyle(brush = litBrush, alpha = a, fontWeight = weight, letterSpacing = ls)
                else androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = a), fontWeight = weight, letterSpacing = ls),
            )
            append(core)
            pop()
            append(w.text.substring(core.length))
        }
    }
    Text(text, modifier, style = axisStyle)
}

/**
 * Shows the player's controls on a drag down and hides them on a drag up, once per drag. Showing or hiding them
 * resizes the list under the finger, which reads as a drag the other way: allowed to switch again within the same
 * drag, they flickered in and out for as long as the finger moved (reported on a 120 Hz Galaxy, scrolling the lyrics
 * up after changing their source).
 */
class ControlsOnScroll(private val onHide: () -> Unit, private val onReveal: () -> Unit) : NestedScrollConnection {
    private var acc = 0f
    private var switched = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || switched) return Offset.Zero
        acc = if ((acc > 0) == (available.y > 0)) acc + available.y else available.y
        if (acc < -40f) { onHide(); switched = true } else if (acc > 40f) { onReveal(); switched = true }
        return Offset.Zero
    }

    /** The finger is up: the next drag may switch them again. */
    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
        acc = 0f
        switched = false
        return androidx.compose.ui.unit.Velocity.Zero
    }
}

/** The phone's language as Google Translate names it (Chinese by script, old Java codes renamed). */
fun defaultTranslateTarget(): String {
    val l = java.util.Locale.getDefault()
    return when (l.language) {
        "zh" -> if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        "iw" -> "he"
        "in" -> "id"
        "" -> "en"
        else -> l.language
    }
}

/** Full lyrics inside the player: big tight type, the sung line crisp, the rest falling out of focus. */
@Composable
fun LyricsPanel(
    state: LyricsState,
    isPlaying: Boolean,
    controlsOpen: Boolean,
    onRevealControls: () -> Unit,
    onHideControls: () -> Unit,
    modifier: Modifier = Modifier,
    /** The lyrics background is drawn behind this panel (the phone's player; a wide player's pane has none of its own). */
    lyricsGround: Boolean = true,
) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    // How the lines are drawn (Settings → Lyrics, 1.7.5): style, colors, weight, spacing and the rest.
    val look = rememberLyricsLook(settings, state.song.thumbnail).let { if (lyricsGround) it else it.withoutCard(settings.lyricsOthersAlpha) }
    val lines = state.lines
    val synced = remember(lines) { lines.any { it.timeMs >= 0 } }
    val clock = rememberLyricClock(isPlaying)
    val active by remember(lines) { derivedStateOf { if (!synced) -1 else lines.indexAt(clock.value) } }
    // Lines sung at the same time as [active] (two voices overlapping) stay lit with it (#65).
    val alsoActive by remember(lines) { derivedStateOf { if (!synced) emptyList() else lines.activeAt(clock.value) } }
    // The list follows the earliest line still being sung, so an overlapping pair stays in view together.
    val lead by remember(lines) { derivedStateOf { if (!synced) -1 else lines.activeAt(clock.value + 300).firstOrNull() ?: -1 } }
    val listState = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
    var placed by remember(state.song.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) browsing = true }
    }
    LaunchedEffect(browsing, listState.isScrollInProgress, isPlaying) {
        if (browsing && !listState.isScrollInProgress && isPlaying) { delay(4000); browsing = false }
    }
    LaunchedEffect(lead, browsing, lines) {
        if (!synced || browsing || lead < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lead }
        when {
            !placed -> { listState.scrollToItem(lead); placed = true }
            visible != null -> listState.animateScrollBy(visible.offset.toFloat(), tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING))
            else -> listState.animateScrollToItem(lead)
        }
    }
    val controlsOnScroll = remember { ControlsOnScroll(onHide = onHideControls, onReveal = onRevealControls) }

    // A translation under each line, when it's turned on and the song isn't already in that language.
    val translateTo = settings.lyricsTranslateTo.ifBlank { defaultTranslateTarget() }
    var translation by remember(lines, settings.lyricsTranslate, translateTo) { mutableStateOf<com.prism.music.data.lyrics.LyricsTranslator.Translation?>(null) }
    LaunchedEffect(lines, settings.lyricsTranslate, translateTo) {
        if (!settings.lyricsTranslate || lines.isEmpty()) return@LaunchedEffect
        val texts = lines.map { l -> if (l.isGap || (l.text.startsWith("[") && l.text.endsWith("]"))) "" else l.text }
        translation = runCatching { c.translator.translate(texts, translateTo) }
            .onFailure { android.util.Log.w("PrismLyrics", "Couldn't translate the lyrics: $it") }.getOrNull()
    }
    val translations = translation?.takeUnless { it.sameLanguage }?.translations
    // Turned on for a song already in that language, nothing changes: say why, once, right after it's turned on.
    val context = androidx.compose.ui.platform.LocalContext.current
    var translateWasOn by remember { mutableStateOf(settings.lyricsTranslate) }
    var justTurnedOn by remember { mutableStateOf(false) }
    LaunchedEffect(settings.lyricsTranslate) {
        if (settings.lyricsTranslate && !translateWasOn) justTurnedOn = true
        translateWasOn = settings.lyricsTranslate
    }
    LaunchedEffect(translation, justTurnedOn) {
        val t = translation ?: return@LaunchedEffect
        if (!justTurnedOn) return@LaunchedEffect
        justTurnedOn = false
        if (t.sameLanguage) {
            val name = java.util.Locale.forLanguageTag(t.to).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { t.to }
            android.widget.Toast.makeText(context, "These lyrics are already in $name. Translations show for songs in other languages.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // Lyrics in a script the listener can't read, in Latin letters: under each line, or in its place.
    val roman = rememberRomanized(state)
    val romanInstead = romanizeMode(state.song.id) == com.prism.music.data.prefs.RomanizeMode.INSTEAD

    if (lines.isEmpty()) {
        Box(modifier.pointerInput(Unit) { detectTapGestures { onRevealControls() } }, contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            else Text("No lyrics for this track", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f))
        }
        return
    }

    // Focus blur at the strength picked (Settings → Lyrics; 1 is 1.7's), on Android 12 and later.
    val canBlur = Build.VERSION.SDK_INT >= 31 && look.blur > 0f
    val scale = settings.lyricsScale * look.sizeScale
    // The sung word's weight moves along the lyrics' typeface's axis: any font Prism ships (not System or an imported one).
    val lyricsChoice = if (settings.lyricsFont.isEmpty()) settings.fontChoice
        else com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == settings.lyricsFont }
    val axisFamily = remember(lyricsChoice) { lyricsChoice?.let(com.prism.music.ui.theme.AppFonts::axis) }
    // Bold card: the lines sit on the cover's own color (drawn behind the player), the words to come dark.
    val onCard = look.card != null
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    // Where the sung line settles: near the top (as before), a third down or in the middle.
    val settleAt = maxOf(30.dp, maxHeight * look.position)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(controlsOnScroll)
            .pointerInput(controlsOpen) { if (!controlsOpen) detectTapGestures { onRevealControls() } }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.06f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        contentPadding = PaddingValues(top = settleAt, bottom = if (look.position == 0f) 400.dp else maxOf(400.dp, maxHeight - settleAt)),
    ) {
        itemsIndexed(lines) { index, line ->
            if (!synced && line.text.startsWith("[") && line.text.endsWith("]")) {
                Box(Modifier.padding(top = if (index == 0) 6.dp else 24.dp, bottom = 8.dp)) {
                    Text(
                        line.text.trim('[', ']').uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
                        color = look.others.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                }
                return@itemsIndexed
            }
            if (!synced && line.isGap) { Spacer(Modifier.height(14.dp * look.spacing)); return@itemsIndexed }
            val isActive = synced && (index == active || index in alsoActive)
            val distance = when { active < 0 -> 4; isActive -> 0; else -> alsoActive.ifEmpty { listOf(active) }.minOf { abs(index - it) } }
            val step = distance.coerceAtMost(FALLOFF_ALPHA.lastIndex)
            // "Lines ahead": lines further on than that wait out of sight until they're near (not while browsing).
            val hidden = synced && !browsing && active >= 0 && look.ahead < Int.MAX_VALUE && index > active + look.ahead
            val blur by animateDpAsState(
                if (!synced || !canBlur || browsing || isActive) 0.dp else FALLOFF_BLUR[step] * look.blur, tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "blur",
            )
            val alpha by animateFloatAsState(
                when { !synced -> 0.95f; isActive -> 1f; hidden -> 0f; browsing -> 0.8f; else -> FALLOFF_ALPHA[step] }, tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "alpha",
            )
            if (line.isGap) {
                GapDots(line, lines.getOrNull(index + 1)?.timeMs ?: line.endMs, clock, isActive, alpha) { c.player.seekToLyric(line.timeMs) }
                return@itemsIndexed
            }
            val centred = settings.lyricsAlign == com.prism.music.data.prefs.LyricsAlign.CENTER
            val romanLine = roman?.getOrNull(index)
            // "Instead": the line is sung in Latin letters (the original's timings kept); in capitals when that's picked.
            val shown = remember(line, romanLine, romanInstead, look.uppercase) { look.shown(if (romanInstead && romanLine != null) romanLine else line) }
            // Arabic, Hebrew and the like read right to left: such a line starts at the right (its direction comes from
            // its words; left unset, Compose laid every line out left to right, so Arabic sat on the left).
            val rtl = remember(shown) { isRtl(shown.text) }
            // A little more (or less) room inside a wrapped line too, as the line spacing goes.
            val tall = 1f + (look.spacing - 1f) * 0.3f
            val style = (if (synced) TextStyle(fontSize = (34 * scale).sp, lineHeight = (41 * scale * tall).sp) else TextStyle(fontSize = (30 * scale).sp, lineHeight = (38 * scale * tall).sp))
                .copy(
                    // Spectrum: light lines (or the weight picked); the sung line's style makes its own.
                    fontWeight = FontWeight(look.weight),
                    // The lyrics font when one is picked (Settings → Appearance → Text), else the app's.
                    fontFamily = com.prism.music.ui.theme.LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily,
                    textAlign = when { line.alignEnd -> TextAlign.End; centred -> TextAlign.Center; else -> TextAlign.Start },
                    textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                )
            // Calm holds every line at its size; the others breathe back a little.
            val lineScale by animateFloatAsState(
                if (isActive || !synced || look.style == com.prism.music.data.prefs.LyricsStyle.CALM) 1f else 0.98f, tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "scale",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = lineScale; scaleY = lineScale
                        transformOrigin = TransformOrigin(
                            when { centred && !line.alignEnd -> 0.5f; line.alignEnd != rtl -> 1f; else -> 0f }, 0.5f,
                        )
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = synced && !hidden) { c.player.seekToLyric(line.timeMs); browsing = false }
                    .padding(vertical = 9.dp * look.spacing, horizontal = 2.dp),
            ) {
                // Inactive lines sit back (as bright as the setting has them); the sung line is drawn in its style.
                val lineAlpha = if (synced && !isActive) alpha * look.othersAlpha else alpha
                val extraAlpha = if (isActive) 0.8f else lineAlpha * 0.8f
                val textAlign = if (rtl && !centred) TextAlign.End else style.textAlign
                // A translation, as the look sets it.
                val translated = translations?.getOrNull(index)?.takeUnless { it.equals(line.text.trim(), ignoreCase = true) }
                val translationLine = @Composable { padTop: Boolean ->
                    translated?.let { t ->
                        Text(
                            t, style = look.extraStyle(style, 0.52f, 0.56f),
                            color = (if (onCard) Color.Black else look.extraColor).copy(alpha = if (onCard) 0.7f else extraAlpha.coerceAtMost(0.75f)),
                            modifier = Modifier.fillMaxWidth().padding(top = if (padTop) 4.dp else 0.dp, bottom = if (padTop) 0.dp else 4.dp),
                        )
                    }
                }
                // "Under each line": the line in Latin letters, smaller, lit word by word with it (or over it, as picked).
                val romanised = @Composable { padTop: Boolean ->
                    if (!romanInstead && romanLine != null) {
                        val romanStyle = look.extraStyle(style, 0.56f, 0.62f).copy(
                            // Under a right-to-left line it keeps to that line's side.
                            textAlign = textAlign, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr,
                        )
                        val pad = Modifier.fillMaxWidth().padding(top = if (padTop) 4.dp else 0.dp, bottom = if (padTop) 0.dp else 4.dp)
                        val r = look.shown(romanLine)
                        if (isActive && r.isWordSynced) SweptLine(r, clock, romanStyle, pad, dimAlpha = UNSUNG_ALPHA * 0.9f, litBrush = look.sung)
                        else Text(r.text, style = romanStyle, color = (if (onCard) Color.Black else look.extraColor).copy(alpha = if (isActive) 0.85f else lineAlpha * 0.8f), modifier = pad)
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    if (look.extraAbove) { romanised(false); translationLine(false) }
                    if (isActive) SungLine(shown, clock, style, look, axisFamily, Modifier.fillMaxWidth(), onCard = onCard)
                    else RestingLine(shown, style, look, lineAlpha, Modifier.fillMaxWidth())
                    // How far the line has been sung: a thin line of split light under it.
                    if (isActive && synced && !browsing && line.endMs > line.timeMs) LyricProgress(
                        line.timeMs, line.endMs, clock,
                        when { centred && !line.alignEnd -> 0.5f; line.alignEnd != rtl -> 1f; else -> 0f },
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                    )
                    if (!look.extraAbove) romanised(true)
                    // Background vocals, smaller under the line, lighting up word by word in their own time (#66).
                    shown.backgroundLine?.let { bg ->
                        val bgStyle = style.copy(fontSize = style.fontSize * look.backingSize, lineHeight = style.lineHeight * look.backingSize * 1.07f, fontWeight = FontWeight.Normal, shadow = look.shadow)
                        if (isActive) SweptLine(bg, clock, bgStyle, Modifier.fillMaxWidth().padding(top = 3.dp), dimAlpha = UNSUNG_ALPHA * 0.8f, litBrush = look.sung)
                        else Text(bg.text, style = bgStyle, color = look.others.copy(alpha = lineAlpha * 0.75f), modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                    }
                    if (!look.extraAbove) translationLine(true)
                }
            }
        }
        item {
            Text(
                "Lyrics from ${state.lyrics?.source?.label ?: "—"}",
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 20.dp),
            )
            // Japanese kanji were spelled out by Google (no reading data yet): offer the proper readings.
            val needsJapanese by c.romanizer.needsJapanese.collectAsState()
            val models by c.models.states.collectAsState()
            val ja = models[com.prism.music.data.ModelStore.Model.JAPANESE]
            if (roman != null && needsJapanese && ja != com.prism.music.data.ModelStore.State.Ready) {
                val scope = rememberCoroutineScope()
                Text(
                    when (ja) {
                        is com.prism.music.data.ModelStore.State.Downloading -> "Downloading Japanese kanji readings… ${(ja.fraction * 100).toInt()}%"
                        is com.prism.music.data.ModelStore.State.Failed -> "Couldn't download the Japanese kanji readings. Tap to try again"
                        else -> "Better romaji for Japanese: tap to download the reading data (13 MB)"
                    },
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = ja !is com.prism.music.data.ModelStore.State.Downloading) {
                            scope.launch { c.models.download(com.prism.music.data.ModelStore.Model.JAPANESE) }
                        }.padding(vertical = 6.dp),
                )
            }
        }
    }
    // Full-screen lyrics: the bottom of the screen brings the controls back (tap or swipe up).
    if (!controlsOpen) ControlsReveal(Modifier.align(Alignment.BottomCenter), onRevealControls)
    }
}

@Composable
private fun ControlsReveal(modifier: Modifier, onReveal: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(Unit) { detectTapGestures { onReveal() } }
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                    change.consume()
                    pulled += dy
                    if (pulled < -24f) { onReveal(); pulled = 0f }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.padding(bottom = 18.dp).size(38.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun GapDots(line: LyricLine, until: Long, clock: State<Long>, active: Boolean, alpha: Float, onClick: () -> Unit) {
    val swell by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 400 else 350, easing = LYRIC_EASING), label = "gap")
    Box(Modifier.height(54.dp * swell), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(10.dp)
                .size(width = 50.dp, height = 12.dp)
                .graphicsLayer {
                    val g = 0.7f + 0.3f * swell
                    scaleX = g; scaleY = g
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    this.alpha = alpha * swell
                }
                .drawBehind {
                    val span = (until - line.timeMs).coerceAtLeast(1)
                    val through = ((clock.value - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val r = size.height / 2
                    val stride = (size.width - 2 * r) / 2
                    repeat(3) { d ->
                        val lit = (through * 3 - d).coerceIn(0f, 1f)
                        drawCircle(Color.White.copy(alpha = 0.35f + 0.65f * lit), r, Offset(r + d * stride, size.height / 2))
                    }
                },
        )
    }
}

/** "Lyrics from X  Change" — shown above the scrubber while lyrics are open. */
@Composable
fun LyricsStatusWithChange(state: LyricsState, onChange: () -> Unit) {
    val status = when {
        state.loading -> "Looking for lyrics…"
        state.lyrics == null -> "No lyrics found"
        else -> "Lyrics from ${state.lyrics!!.source.label}"
    }
    Row(Modifier.fillMaxWidth().offset(y = 6.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(status, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(
            "Change", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.72f), textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onChange),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsProviderSheet(state: LyricsState, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    // Only the sources turned on in Settings → Lyrics (#51).
    LaunchedEffect(state.song.id) { state.probeAll(settings.lyricsSources) }
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    // The same frosted sheet as the player's ⋯ menu it opens from.
    com.prism.music.ui.components.ActionSheet(state.song.thumbnail, onDismiss) { close ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics source", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // Up top, where it can't scroll out of reach.
            state.source?.let {
                com.prism.music.ui.components.PrismChip(false, { state.redownload(); close {} }, "Re-download", icon = PrismIcons.Refresh)
            }
        }
        state.source?.let { src ->
            Text(
                "Re-download fetches ${src.label}'s lyrics again and replaces the saved copy",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().pane(ui.card, scheme.surfaceContainerHigh)) {
            settings.lyricsSources.forEachIndexed { i, src ->
                val st = state.providers[src] ?: ProviderState.UNKNOWN
                val starred = state.censoredSources[src] == true
                val chosen = state.source == src && state.lyrics != null
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.onSurface.copy(alpha = 0.08f))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = st != ProviderState.NONE) { state.select(src); close {} }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            src.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            color = when { chosen -> scheme.primary; st == ProviderState.NONE -> scheme.onSurface.copy(alpha = 0.4f); else -> scheme.onSurface },
                        )
                        Text(
                            when (st) {
                                ProviderState.LOADING, ProviderState.UNKNOWN -> "Checking…"
                                ProviderState.FOUND_KARAOKE -> "Karaoke · lights up word by word"
                                ProviderState.FOUND_SYNCED -> "Time-synced lines"
                                ProviderState.FOUND_PLAIN -> "Plain text"
                                ProviderState.NONE -> "Not available for this song"
                            } + if (starred && st != ProviderState.NONE) " · Censored" else "",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    when {
                        chosen -> Icon(PrismIcons.Check, "Selected", tint = scheme.primary)
                        st == ProviderState.LOADING -> CircularProgressIndicator(Modifier.size(18.dp), color = scheme.onSurfaceVariant, strokeWidth = 2.dp)
                    }
                }
            }
        }
        SearchAsPane(state, settings.lyricsSources) { close {} }
    }
}

/**
 * "Search with another title" (#63): sources often have a song's lyrics under a title without "(feat. …)", or under
 * its original-language name, while YouTube Music lists it another way. Every source but YouTube Music (which knows
 * the song itself) is then asked by that title and artist, for this song from now on.
 */
@Composable
private fun SearchAsPane(state: LyricsState, sources: List<LyricsSource>, onSearched: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    val current = state.searchAs
    var open by remember(state.song.id) { mutableStateOf(false) }
    var title by remember(state.song.id) { mutableStateOf(current?.first ?: state.song.title) }
    var artist by remember(state.song.id) { mutableStateOf(current?.second?.ifBlank { null } ?: state.song.primaryArtist) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp).fillMaxWidth().pane(ui.card, scheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PrismIcons.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Search with another title", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    current?.let { (t, a) -> "Looking up \"$t\"" + if (a.isNotBlank()) " by $a" else "" }
                        ?: "When the lyrics are listed under a different name",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            // The keyboard covers the pane's foot: its own key goes on to Artist, then searches.
            com.prism.music.ui.components.PrismTextField(
                title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            com.prism.music.ui.components.PrismTextField(
                artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text("Artist") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    if (title.isNotBlank()) { state.searchAgainAs(title, artist, sources); onSearched() }
                }),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) com.prism.music.ui.components.PrismTextButton(onClick = {
                    state.searchAgainAs("", "", sources); onSearched()
                }) { Text("Use the song's own") }
                Spacer(Modifier.weight(1f))
                com.prism.music.ui.components.PrismButton(enabled = title.isNotBlank(), onClick = {
                    state.searchAgainAs(title, artist, sources); onSearched()
                }) { Text("Search") }
            }
        }
    }
}
