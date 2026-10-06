package com.prism.music.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AvTimer
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset as offsetConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.prism.music.R
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueKind
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private val DISMISS_STRIP = 32.dp
private val ART_BOX_TOP_PAD = 8.dp
private const val HERO_FADE = 0.42f

@Composable
fun rememberPosition(fast: Boolean): Long {
    val pc = LocalContainer.current.player
    var pos by remember { mutableLongStateOf(pc.position) }
    LaunchedEffect(fast) {
        while (isActive) {
            pos = pc.position
            delay(if (fast) 60 else 250)
        }
    }
    return pos
}

private fun Modifier.measuredSquare(side: () -> Dp): Modifier = layout { measurable, constraints ->
    val px = side().roundToPx()
    val p = measurable.measure(constraints.constrain(Constraints.fixed(px, px)))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

/**
 * Now Playing, modelled on BitChord v1.7 (itself after Apple Music): artwork
 * that shrinks while paused, a hairline scrubber, oversized transport glyphs, a
 * volume capsule, and lyrics / Song·Video / queue along the bottom. Lyrics and
 * the queue live inside the player; the sleeve collapses into a header for them.
 */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val view = LocalView.current
    val settings by c.settings.flow.collectAsState()
    val song = pc.currentSong.collectAsState().value ?: return
    val isPlaying by pc.isPlaying.collectAsState()
    val buffering by pc.buffering.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val videoAvailable by pc.videoAvailable.collectAsState()
    val hasNext by pc.hasNext.collectAsState()
    val hasPrevious by pc.hasPrevious.collectAsState()
    val liked by c.library.likedIds.collectAsState()
    val source by c.queue.source.collectAsState()

    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsControlsOpen by remember { mutableStateOf(true) }
    var queueControlsOpen by remember { mutableStateOf(true) }
    var showProviders by remember { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showTiming by remember { mutableStateOf(false) }
    var fullscreenVideo by rememberSaveable { mutableStateOf(false) }
    var videoFill by rememberSaveable { mutableStateOf(true) }

    val lyricsState = rememberLyricsState(song)
    val position = rememberPosition(fast = false)
    val volume = rememberPlayerVolume()
    val scope = rememberCoroutineScope()

    // The player always owns light status-bar icons.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) ctl.isAppearanceLightStatusBars = previous }
    }
    DisposableEffect(lyricsOpen) {
        view.keepScreenOn = lyricsOpen
        onDispose { view.keepScreenOn = false }
    }

    val openLyrics = { lyricsControlsOpen = true; lyricsOpen = true; queueOpen = false; statsOpen = false }
    val closeLyrics = { lyricsControlsOpen = false; lyricsOpen = false }
    val toggleQueue = {
        val opening = !queueOpen
        queueOpen = opening
        if (opening) { closeLyrics(); statsOpen = false; queueControlsOpen = true }
    }
    val toggleStats = {
        val opening = !statsOpen
        statsOpen = opening
        if (opening) { closeLyrics(); queueOpen = false }
    }
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, volume.dragging) {
        if (lyricsOpen && lyricsControlsOpen && !volume.dragging) { delay(5000); lyricsControlsOpen = false }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BackHandler {
        when {
            fullscreenVideo -> fullscreenVideo = false
            lyricsOpen -> closeLyrics()
            queueOpen -> queueOpen = false
            statsOpen -> statsOpen = false
            else -> onCollapse()
        }
    }
    if (videoMode && (fullscreenVideo || landscape)) {
        FullscreenVideo(song, forceLandscape = fullscreenVideo, onExit = { fullscreenVideo = false })
        return
    }

    // ---------------------------------------------------------------- Motion
    val collapse = animateFloatAsState(if (lyricsOpen || queueOpen || statsOpen) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "collapse")
    val p: () -> Float = { collapse.value }
    val collapsePastHalf = collapse.value >= 0.5f
    val panelsSettled = collapse.value >= 1f
    val panelFade by animateFloatAsState(if (panelsSettled) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "panelFade")
    val artScale by animateFloatAsState(
        if (isPlaying || !settings.artShrinkOnPause || settings.reduceMotion) 1f else 0.86f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale",
    )
    val swipe = remember { Animatable(0f) }
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    val dismiss = remember { Animatable(0f) }

    // ---------------------------------------------------------------- Canvas
    val metered = remember { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }
    val savedCanvases by c.canvas.saved.collectAsState()
    val canvasSaved = savedCanvases.has(song.id, com.prism.music.data.canvas.CanvasRepository.albumKey(song))
    val songCanvas by produceState<CanvasArtwork?>(c.canvas.cached(song), song.id, song.album?.title, settings.canvasEnabled) {
        // Saved clips play from the phone, so mobile data doesn't matter for them.
        if (!settings.canvasEnabled || (metered && !settings.canvasOnCellular && !c.canvas.isSaved(song))) { value = null; return@produceState }
        val known = c.canvas.cached(song)
        value = known
        if (known == null) delay(500)
        value = c.canvas.canvasFor(song)
    }
    // Animated artwork can be switched off per song from the song options.
    val canvasHidden by c.canvas.hidden.collectAsState()
    val canvas = songCanvas.takeIf { !videoMode && song.id !in canvasHidden }
    var canvasCover by remember(canvas) { mutableFloatStateOf(0f) }

    // ---------------------------------------------------------------- Artwork
    val artUrl = hiRes(song.thumbnail, 1200)
    var artLoaded by remember(artUrl) { mutableStateOf(false) }
    val heroMode = settings.fullBleedArtwork && !videoMode
    var heroHeight by remember { mutableStateOf(0.dp) }
    val heroT = animateFloatAsState(if (heroMode && (artLoaded || canvasCover > 0f)) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "hero")
    val heroVisible: () -> Float = { heroT.value * (1f - p()) }
    val mesh = rememberArtworkMesh(song.thumbnail)
    val blurImage = rememberFullArtworkBlur(song.thumbnail)
    val fullBlur by animateFloatAsState(if (lyricsOpen || queueOpen || statsOpen) 1f else 0f, tween(360, easing = FastOutSlowInEasing), label = "fullBlur")
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Collapsed into the header thumbnail, the sleeve keeps gentle corners whatever the setting.
    val artShape = RoundedCornerShape(lerp(settings.playerArtCorners.dp, 8.dp, collapse.value))

    val originText = when {
        source.kind == QueueKind.SINGLE -> "Playing from Autoplay"
        source.title.isNotBlank() -> "Playing from ${source.title}"
        song.album?.title != null -> "Playing from ${song.album.title}"
        else -> "Playing from Queue"
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dismiss.value.roundToInt()) }
            .graphicsLayer {
                val f = (dismiss.value / 1800f).coerceIn(0f, 1f)
                scaleX = 1f - f * 0.06f; scaleY = 1f - f * 0.06f
                shape = RoundedCornerShape((f * 140).dp); clip = f > 0f
            }
            .background(PlayerFallbackBackdrop),
    ) {
        // ------------------------------------------------ Backdrop
        when (settings.playerBackground) {
            com.prism.music.data.prefs.PlayerBackground.MESH -> {
                if (fullBlur < 1f) ArtworkMeshBackdrop(mesh, Modifier.graphicsLayer { alpha = 1f - fullBlur }, seam = if (heroMode) heroHeight else 0.dp)
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            com.prism.music.data.prefs.PlayerBackground.BLUR -> FullArtworkBlurBackdrop(blurImage)
            com.prism.music.data.prefs.PlayerBackground.THEME -> ThemeBackdrop()
            com.prism.music.data.prefs.PlayerBackground.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // ------------------------------------------------ Full-bleed banner (+ canvas)
        if (heroMode && heroHeight > 0.dp && !(collapsePastHalf && heroVisible() < 0.001f)) {
            AsyncImage(
                artUrl, null, contentScale = ContentScale.Crop,
                onState = { if (it is AsyncImagePainter.State.Success) artLoaded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .graphicsLayer {
                        alpha = heroVisible() * (1f - canvasCover)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height * (1f - HERO_FADE), endY = size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                CanvasPlayer(
                    clip, canvasSaved, isPlaying,
                    Modifier.fillMaxWidth().height(heroHeight),
                    bottomFade = HERO_FADE,
                    presentationAlpha = { heroT.value * (1f - 2f * p()).coerceIn(0f, 1f) },
                    onCoverChanged = { canvasCover = it },
                )
            }
        }
        // Status-bar scrim
        Box(Modifier.fillMaxWidth().height(statusTop + DISMISS_STRIP).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))

        val dismissGesture = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { scope.launch { if (dismiss.value > 220f) onCollapse() else dismiss.animateTo(0f, spring()) } },
                onDragCancel = { scope.launch { dismiss.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                scope.launch { dismiss.snapTo((dismiss.value + amount).coerceAtLeast(0f)) }
            }
        }
        // Drag on the sleeve: down closes the player, up pulls the queue in.
        val sleeveGesture = Modifier.pointerInput(lyricsOpen, queueOpen, statsOpen) {
            if (lyricsOpen || queueOpen || statsOpen) return@pointerInput
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    scope.launch {
                        when {
                            dismiss.value > 220f -> onCollapse()
                            total < -90f -> { dismiss.snapTo(0f); toggleQueue() }
                            else -> dismiss.animateTo(0f, spring())
                        }
                    }
                },
            ) { change, amount ->
                change.consume()
                total += amount
                if (total > 0) scope.launch { dismiss.snapTo(total.coerceAtLeast(0f)) }
            }
        }
        val skipGesture = Modifier.pointerInput(lyricsOpen, queueOpen, statsOpen) {
            if (lyricsOpen || queueOpen || statsOpen) return@pointerInput
            var total = 0f
            val threshold = 72.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                onDragEnd = {
                    if (total <= -threshold) { haptics.gestureEnd(); pc.next() }
                    else if (total >= threshold) { haptics.gestureEnd(); pc.previous() }
                    scope.launch { swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                },
            ) { _, d ->
                if ((kotlin.math.abs(total) < threshold) != (kotlin.math.abs(total + d) < threshold)) haptics.threshold()
                total += d
                scope.launch { swipe.snapTo(total * 0.35f) }
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().then(skipGesture), horizontalAlignment = Alignment.CenterHorizontally) {
            // ------------------------------------------------ Handle + caption
            Box(Modifier.fillMaxWidth().height(DISMISS_STRIP).then(dismissGesture)) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, lerp(6.dp, (DISMISS_STRIP - 5.dp) / 2, p()).roundToPx()) }
                        .size(38.dp, 5.dp)
                        .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.70f)),
                )
                if (collapse.value < 0.999f && settings.showPlayingFrom) PlaybackOriginCaption(
                    originText,
                    Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - p() },
                ) {
                    song.album?.id?.let { onCollapse(); nav.go(Routes.album(it)) }
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PLAYER_GUTTER), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = ART_BOX_TOP_PAD, bottom = 18.dp)) {
                    val fullArt = minOf(maxWidth, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT).coerceAtLeast(THUMB_SIZE)
                    val groupTop = ((maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT) / 2).coerceAtLeast(0.dp)
                    val bannerBottom = statusTop + DISMISS_STRIP + ART_BOX_TOP_PAD + groupTop + fullArt + ART_TITLE_GAP / 2
                    if (!lyricsOpen && !queueOpen && !statsOpen && bannerBottom != heroHeight) SideEffect { heroHeight = bannerBottom }
                    val sleeveMaxWidth = maxWidth
                    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                    fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                    fun artStart(): Dp = lerp((maxWidth - fullArt) / 2, 0.dp, p())
                    fun titleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

                    // ---- Music video: spans the full screen width, filling the artwork slot.
                    if (videoMode && !collapsePastHalf) {
                        Box(
                            Modifier
                                // requiredWidth wider than the column overflows equally on
                                // both sides, so this already reaches both screen edges.
                                .offset { IntOffset(0, artTop().roundToPx()) }
                                .requiredWidth(sleeveMaxWidth + PLAYER_GUTTER * 2)
                                .height(fullArt + ART_TITLE_GAP / 2)
                                .graphicsLayer { alpha = 1f - 2f * p() }
                                .then(sleeveGesture),
                        ) {
                            MusicVideoSurface(Modifier.fillMaxSize(), fill = videoFill)
                            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                VideoChip(Icons.Rounded.AspectRatio, if (videoFill) "Fit video" else "Fill") { videoFill = !videoFill }
                                VideoChip(Icons.Rounded.Fullscreen, "Full screen") { fullscreenVideo = true }
                            }
                        }
                    }

                    // ---- The sleeve (collapses to a header thumbnail)
                    Box(
                        Modifier
                            .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                            .measuredSquare(::artSize)
                            .then(if (!videoMode) sleeveGesture else Modifier)
                            .graphicsLayer {
                                val idle = artScale + (1f - artScale) * p()
                                scaleX = idle; scaleY = idle
                                translationX = swipe.value * (1f - p())
                            }
                            .then(
                                if (lyricsOpen || queueOpen || statsOpen) Modifier.clickable { queueOpen = false; statsOpen = false; closeLyrics() } else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = when {
                                        videoMode -> (2f * p() - 1f).coerceIn(0f, 1f)
                                        heroMode && artLoaded -> 1f - heroVisible()
                                        else -> 1f
                                    }
                                }
                                .shadow(if (artLoaded) 10.dp else 0.dp, artShape)
                                .clip(artShape)
                                .background(Color.Black.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!artLoaded) Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
                            AsyncImage(
                                artUrl, null, contentScale = ContentScale.Crop,
                                onState = { if (it is AsyncImagePainter.State.Success) artLoaded = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (!heroMode && !videoMode) canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                                CanvasPlayer(clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), onCoverChanged = { canvasCover = it })
                            }
                        }
                        if (!collapsePastHalf && !videoMode) SleeveNerdStats(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).graphicsLayer { alpha = 1f - p() * 2f },
                        )
                    }

                    // ---- Credits: title, artist, like, menu
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, (titleTop() - lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())).roundToPx()) }
                            .layout { measurable, constraints ->
                                val start = titleStart().roundToPx()
                                val placeable = measurable.measure(constraints.offsetConstraints(horizontal = -start))
                                layout(constraints.constrainWidth(placeable.width + start), constraints.constrainHeight(placeable.height)) {
                                    placeable.placeRelative(start, 0)
                                }
                            }
                            .height(HEADER_HEIGHT)
                            .then(sleeveGesture),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f).graphicsLayer {
                                val s = 1f - 0.2f * p()
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                        ) {
                            val scrolls = collapse.value < 0.01f
                            PlayerMarquee(
                                song.title, MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), Color.White, scrolls,
                                Modifier.clickable(enabled = song.album?.id != null) { song.album?.id?.let { onCollapse(); nav.go(Routes.album(it)) } },
                            )
                            PlayerMarquee(
                                song.artistText, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W500, fontSize = 20.sp), Color.White.copy(alpha = 0.55f), scrolls,
                                Modifier.clickable(enabled = song.artists.firstOrNull()?.id != null) { song.artists.firstOrNull()?.id?.let { onCollapse(); nav.go(Routes.artist(it)) } },
                                delayMs = 5500,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val isLiked = song.id in liked
                        CircleGlyph(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from liked" else "Like", isLiked) {
                            c.library.toggleLike(song)
                        }
                        Spacer(Modifier.width(8.dp))
                        CircleGlyph(Icons.Rounded.MoreHoriz, "Song options") { showActions = true }
                    }

                    // ---- Lyrics and queue panels, under the collapsed header
                    if (lyricsOpen && panelsSettled) {
                        LyricsPanel(
                            lyricsState, isPlaying, lyricsControlsOpen,
                            onRevealControls = { lyricsControlsOpen = true },
                            onHideControls = { lyricsControlsOpen = false },
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT).graphicsLayer { alpha = panelFade },
                        )
                    }
                    if (statsOpen && panelsSettled) {
                        NerdStatsPanel(
                            Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                alpha = panelFade
                                translationY = (1f - panelFade) * 26.dp.toPx()
                            },
                        )
                    }
                    if (queueOpen && panelsSettled) {
                        InlineQueue(
                            onRevealPlayer = { queueControlsOpen = true },
                            onHidePlayer = { queueControlsOpen = false },
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                alpha = panelFade
                                translationY = (1f - panelFade) * 26.dp.toPx()
                            },
                        )
                    }
                }

                // ------------------------------------------------ The deck
                AnimatedVisibility(
                    (!lyricsOpen || lyricsControlsOpen) && (!queueOpen || queueControlsOpen),
                    enter = expandVertically(tween(420, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(420)),
                    exit = shrinkVertically(tween(420, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(300)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyricsOpen) LyricsStatusWithChange(lyricsState) { showProviders = true }
                        else if (settings.lyricsOnPlayer) CurrentLyricStrip(lyricsState, isPlaying, openLyrics)
                        else Text(" ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
                        PlayerScrubber(position, pc.duration) {
                            QualityLabel(Modifier.align(Alignment.Center), selected = statsOpen) { toggleStats() }
                        }
                        Spacer(Modifier.height(8.dp))
                        TransportRow(isPlaying, buffering, previousEnabled = hasPrevious || position > 3000, nextEnabled = hasNext)
                        Spacer(Modifier.height(12.dp))
                        if (!settings.hideVolumeBar) VolumeRow(volume) else Spacer(Modifier.height(VOLUME_ROW_HEIGHT))
                        Spacer(Modifier.height(6.dp))
                        PlayerActionRow(
                            lyricsOpen, queueOpen, videoMode, videoAvailable,
                            onToggleLyrics = { if (lyricsOpen) closeLyrics() else openLyrics() },
                            onToggleQueue = toggleQueue,
                            onVideoMode = { pc.setVideoMode(it) },
                        )
                        Spacer(Modifier.height(18.dp))
                        if (settings.showOutputDevice) OutputCaption() else Spacer(Modifier.height(14.dp))
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }

    if (showProviders) LyricsProviderSheet(lyricsState) { showProviders = false }
    if (showActions) PlayerOptionsSheet(
        song,
        hasCanvas = songCanvas != null,
        statsShown = statsOpen,
        hasNext = hasNext,
        onLyricsSource = { showProviders = true },
        onLyricsTiming = { showTiming = true },
        onRedownloadLyrics = { lyricsState.redownload() },
        onToggleStats = { toggleStats() },
        onSleepTimer = { showSleep = true },
        onCollapse = onCollapse,
    ) { showActions = false }
    if (showSleep) SleepTimerDialog { showSleep = false }
    if (showTiming) LyricsTimingSheet { showTiming = false }
}

/** The "Theme" player background: a deep wash of the accent colour, lighter at the top. */
@Composable
private fun ThemeBackdrop() {
    val scheme = MaterialTheme.colorScheme
    fun Color.deep(f: Float) = Color(red * f, green * f, blue * f, 1f)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(scheme.primary.deep(0.55f), scheme.tertiary.deep(0.28f), Color.Black.copy(alpha = 0.92f).compositeOver(scheme.primary.deep(0.1f))))
        ),
    )
}

@Composable
private fun VideoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/** The main player's video, drawn on a TextureView so it composes with the layout. */
@OptIn(UnstableApi::class)
@Composable
fun MusicVideoSurface(modifier: Modifier, fill: Boolean) {
    val pc = LocalContainer.current.player
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.texture_player, null) as PlayerView).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                player = pc.player
            }
        },
        update = {
            it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (it.player !== pc.player) it.player = pc.player
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** Edge-to-edge landscape video with tap-to-show controls. */
@Composable
private fun FullscreenVideo(song: Song, forceLandscape: Boolean, onExit: () -> Unit) {
    val pc = LocalContainer.current.player
    val view = LocalView.current
    val activity = view.context as? Activity
    val isPlaying by pc.isPlaying.collectAsState()
    var controls by remember { mutableStateOf(true) }
    val position = rememberPosition(fast = false)
    DisposableEffect(forceLandscape) {
        val window = activity?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousOrientation = activity?.requestedOrientation
        if (forceLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (forceLandscape) activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(controls, isPlaying) { if (controls && isPlaying) { delay(3500); controls = false } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { pc.togglePlay() }) },
    ) {
        MusicVideoSurface(Modifier.fillMaxSize(), fill = false)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(song.artistText, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onExit),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.FullscreenExit, "Exit full screen", tint = Color.White) }
                Box(
                    Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { pc.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(44.dp)) }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp)) {
                    PlayerScrubber(position, pc.duration)
                }
            }
        }
    }
}
