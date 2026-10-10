package com.prism.music.ui.player

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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

enum class ProviderState { UNKNOWN, LOADING, FOUND_SYNCED, FOUND_PLAIN, NONE }

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

    val lines: List<LyricLine> get() = lyrics?.lines.orEmpty()
    val unavailable: Boolean get() = !loading && lyrics == null

    suspend fun loadAuto() {
        loading = true
        lyrics = try { repo.auto(song) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        source = lyrics?.source
        lyrics?.let { found[it.source] = it; providers[it.source] = it.state() }
        loading = false
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
            synced -> ProviderState.FOUND_SYNCED
            else -> ProviderState.FOUND_PLAIN
        }
    }
}

@Composable
fun rememberLyricsState(song: Song): LyricsState {
    val repo = LocalContainer.current.lyrics
    val scope = rememberCoroutineScope()
    val state = remember(song.id) { LyricsState(repo, song, scope) }
    LaunchedEffect(song.id) { state.loadAuto() }
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
    DisposableEffect(active) {
        if (active && Build.VERSION.SDK_INT >= 35) {
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
        while (true) withFrameNanos { now ->
            val actual = pc.lyricPosition
            if (last < 0) shown = actual.toDouble() else {
                val speed = pc.player?.playbackParameters?.speed ?: 1f
                shown += (now - last) / 1_000_000.0 * speed
                val err = actual - shown
                shown = if (abs(err) > 250) actual.toDouble() else shown + err * 0.06
            }
            last = now
            clock.longValue = shown.toLong()
        }
    }
    return clock
}

private fun List<LyricLine>.indexAt(ms: Long): Int {
    var idx = -1
    for (i in indices) if (this[i].timeMs in 0..ms) idx = i else if (this[i].timeMs > ms) break
    return idx
}

/** Current lyric, one line, directly above the scrubber. */
@Composable
fun CurrentLyricStrip(state: LyricsState, isPlaying: Boolean, onClick: () -> Unit) {
    val lines = state.lines
    val synced = lines.any { it.timeMs >= 0 }
    Box(Modifier.fillMaxWidth().offset(y = 6.dp)) {
        when {
            lines.isNotEmpty() && !synced -> StripRow(onClick, note = true) {
                Text("Open lyrics", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
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
                StripRow(onClick, note = instrumental) {
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
                        if (line != null && !line.isGap && line.isWordSynced) {
                            SweptLine(line, clock, MaterialTheme.typography.titleMedium, maxLines = 1)
                        } else {
                            Text(t, style = MaterialTheme.typography.titleMedium, color = if (instrumental) Color.White.copy(alpha = 0.5f) else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            state.unavailable -> {
                var visible by remember(state.song.id) { mutableStateOf(true) }
                LaunchedEffect(state.song.id) { delay(4000); visible = false }
                val a by animateFloatAsState(if (visible) 0.55f else 0f, tween(900), label = "na")
                Text("Lyrics not available", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(vertical = 4.dp).graphicsLayer { alpha = a })
            }
            else -> Text("Looking for lyrics…", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun StripRow(onClick: () -> Unit, note: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (note) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.weight(1f, fill = false)) { content() }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
    }
}

/**
 * A word-timed line: a dim copy with a bright copy over it, clipped to exactly
 * what has been sung so far — the boundary travels through each word in time.
 */
@Composable
fun SweptLine(
    line: LyricLine,
    clock: State<Long>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    dimAlpha: Float = UNSUNG_ALPHA,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val offsets = remember(line) {
        var acc = 0
        line.words.map { w -> val start = acc; acc += w.text.length; start to acc }
    }
    Box(modifier) {
        // Full width, so centred and right-aligned lines sit where their plain copies do.
        val wide = if (style.textAlign == TextAlign.Center || style.textAlign == TextAlign.End) Modifier.fillMaxWidth() else Modifier
        Text(line.text, wide, style = style, color = Color.White.copy(alpha = dimAlpha), maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
        Text(
            line.text, style = style, color = Color.White, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
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
                        val x = box.left + box.width * (sung - ci)
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = x - feather / 2, endX = x + feather / 2),
                            Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bottom - top), blendMode = BlendMode.DstOut,
                        )
                    }
                },
        )
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
) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val lines = state.lines
    val synced = remember(lines) { lines.any { it.timeMs >= 0 } }
    val clock = rememberLyricClock(isPlaying)
    val active by remember(lines) { derivedStateOf { if (!synced) -1 else lines.indexAt(clock.value) } }
    val lead by remember(lines) { derivedStateOf { if (!synced) -1 else lines.indexAt(clock.value + 300) } }
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
    val controlsOnScroll = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    acc = if ((acc > 0) == (available.y > 0)) acc + available.y else available.y
                    if (acc < -40f) { onHideControls(); acc = 0f } else if (acc > 40f) { onRevealControls(); acc = 0f }
                }
                return Offset.Zero
            }
        }
    }

    if (lines.isEmpty()) {
        Box(modifier.pointerInput(Unit) { detectTapGestures { onRevealControls() } }, contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            else Text("No lyrics for this track", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f))
        }
        return
    }

    val canBlur = Build.VERSION.SDK_INT >= 31 && settings.lyricsBlur
    val scale = settings.lyricsScale
    Box(modifier) {
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
        contentPadding = PaddingValues(top = 30.dp, bottom = 400.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            if (!synced && line.text.startsWith("[") && line.text.endsWith("]")) {
                Box(Modifier.padding(top = if (index == 0) 6.dp else 24.dp, bottom = 8.dp)) {
                    Text(
                        line.text.trim('[', ']').uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 11.dp, vertical = 4.dp),
                    )
                }
                return@itemsIndexed
            }
            if (!synced && line.isGap) { Spacer(Modifier.height(14.dp)); return@itemsIndexed }
            val distance = if (active < 0) 4 else abs(index - active)
            val isActive = synced && index == active
            val step = distance.coerceAtMost(FALLOFF_ALPHA.lastIndex)
            val blur by animateDpAsState(
                if (!synced || !canBlur || browsing || isActive) 0.dp else FALLOFF_BLUR[step], tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "blur",
            )
            val alpha by animateFloatAsState(
                when { !synced -> 0.95f; isActive -> 1f; browsing -> 0.8f; else -> FALLOFF_ALPHA[step] }, tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "alpha",
            )
            if (line.isGap) {
                GapDots(line, lines.getOrNull(index + 1)?.timeMs ?: line.endMs, clock, isActive, alpha) { c.player.seekToLyric(line.timeMs) }
                return@itemsIndexed
            }
            val centred = settings.lyricsAlign == com.prism.music.data.prefs.LyricsAlign.CENTER
            val style = (if (synced) TextStyle(fontSize = (34 * scale).sp, lineHeight = (41 * scale).sp) else TextStyle(fontSize = (30 * scale).sp, lineHeight = (38 * scale).sp))
                .copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    textAlign = when { line.alignEnd -> TextAlign.End; centred -> TextAlign.Center; else -> TextAlign.Start },
                )
            val lineScale by animateFloatAsState(if (isActive || !synced) 1f else 0.98f, tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING), label = "scale")
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = lineScale; scaleY = lineScale
                        transformOrigin = TransformOrigin(if (line.alignEnd) 1f else if (centred) 0.5f else 0f, 0.5f)
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = synced) { c.player.seekToLyric(line.timeMs); browsing = false }
                    .padding(vertical = 9.dp, horizontal = 2.dp),
            ) {
                if (isActive && line.isWordSynced) {
                    SweptLine(line, clock, style, Modifier.fillMaxWidth())
                } else {
                    // Inactive lines sit back; the sung line is full white.
                    val lineAlpha = if (synced && !isActive) alpha * 0.55f else alpha
                    Text(line.text, style = style, color = Color.White.copy(alpha = lineAlpha), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Text(
                "Lyrics from ${state.lyrics?.source?.label ?: "—"}",
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 20.dp),
            )
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
    LaunchedEffect(state.song.id) { state.probeAll(settings.lyricsOrder) }
    val scheme = MaterialTheme.colorScheme
    val ui = com.prism.music.ui.theme.LocalUi.current
    // The same frosted sheet as the player's ⋯ menu it opens from.
    com.prism.music.ui.components.ActionSheet(state.song.thumbnail, onDismiss) { close ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics source", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Up top, where it can't scroll out of reach.
            state.source?.let {
                com.prism.music.ui.components.PrismChip(false, { state.redownload(); close {} }, "Re-download", icon = Icons.Rounded.Refresh)
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
            settings.lyricsOrder.forEachIndexed { i, src ->
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
                                ProviderState.FOUND_SYNCED -> "Time-synced"
                                ProviderState.FOUND_PLAIN -> "Plain text"
                                ProviderState.NONE -> "Not available for this song"
                            } + if (starred && st != ProviderState.NONE) " · Censored" else "",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    when {
                        chosen -> Icon(Icons.Rounded.Check, "Selected", tint = scheme.primary)
                        st == ProviderState.LOADING -> CircularProgressIndicator(Modifier.size(18.dp), color = scheme.onSurfaceVariant, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
