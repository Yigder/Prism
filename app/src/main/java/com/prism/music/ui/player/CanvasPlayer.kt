package com.prism.music.ui.player

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.ui.theme.LocalContainer

/**
 * Plays a canvas clip — silent, looping, following the transport — on a
 * TextureView. The video is scaled with an explicit transform (a TextureView
 * otherwise stretches its frames to its own bounds), and nothing is shown
 * until a real frame lands, so a slow or failed clip just leaves the still art.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasPlayer(
    canvas: CanvasArtwork,
    /** The song's cover is saved: what plays is kept on the phone for good. */
    saved: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Share of the height, from the bottom, over which the clip dissolves. */
    bottomFade: Float = 0f,
    /** Extra alpha from the caller (e.g. as the player collapses into a panel). */
    presentationAlpha: () -> Float = { 1f },
    onCoverChanged: (Float) -> Unit = {},
    /** Something covers the player (a sheet): the clip holds its frame meanwhile. */
    hold: Boolean = false,
) {
    val context = LocalContext.current
    val c = LocalContainer.current
    // #384 (1.8.3): one state for every clip this player shows. It was remembered per canvas, but the player's and the
    // video view's listeners are set up once and kept writing the first song's, so the next song's clip never showed.
    val clip = remember { ClipState(canvas) }
    LaunchedEffect(canvas) { clip.show(canvas) }
    val url = clip.url
    var texture by remember { mutableStateOf<TextureView?>(null) }
    // 1.8.6: a loop from a music video takes a moment to start again (0.1 to 0.3 s on the A16, its last frame standing
    // meanwhile). Its first frame, kept as a picture, dissolves in over the last quarter second and goes the moment the
    // loop's real first frame is drawn under it: the end runs into the start with nothing standing still.
    val restart = remember { RestartCover() }
    var still by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val endMessage = remember { arrayOfNulls<androidx.media3.exoplayer.PlayerMessage>(1) }
    DisposableEffect(Unit) { onDispose { endMessage[0]?.cancel(); endMessage[0] = null } }
    LaunchedEffect(restart.shown) { if (restart.shown) { kotlinx.coroutines.delay(RESTART_GIVE_UP_MS); restart.giveUp() } }

    // The player is borrowed from [CanvasPlayers] and handed back when the clip leaves the screen, so opening the
    // player again picks up the same one, clip and decoder (#343: a new player each time made a new video decoder
    // and checked every rendition against the phone's decoders again, many times a minute while it was opened and
    // closed).
    val held = remember { CanvasPlayers.take(context, c, url) }
    val player = held.player
    // Where the picture sits in the view now: for a loop from a music video, by the scene playing (each has its own crop).
    val placed = remember { floatArrayOf(Float.NaN, 0f, 0f, 0f) }
    val place: (TextureView) -> Unit = { tv ->
        val loop = clip.canvas.clip
        val x = loop?.let { it.cropAt(it.startMs + player.currentPosition.coerceAtLeast(0)) } ?: 0.5f
        // Only when something changed: this runs for every frame of a loop with scenes.
        if (placed[0] != x || placed[1] != clip.aspect || placed[2] != tv.width.toFloat() || placed[3] != tv.height.toFloat()) {
            placed[0] = x; placed[1] = clip.aspect; placed[2] = tv.width.toFloat(); placed[3] = tv.height.toFloat()
            tv.cropTo(clip.aspect, loop, x)
        }
    }
    androidx.compose.runtime.SideEffect { held.source.clip = url; held.source.keep = saved }
    DisposableEffect(held) { onDispose { CanvasPlayers.give(held) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                clip.videoSize(videoSize.width * videoSize.pixelWidthHeightRatio, videoSize.height)
                texture?.let(place)
            }

            override fun onPlayerError(error: PlaybackException) {
                CanvasStats.errors++; CanvasStats.lastError = error.errorCodeName
                clip.failed()
            }

            // 1.8.6: a loop from a music video going round ([RestartCover]).
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
                CanvasStats.rounds++
                if (restart.shown) CanvasStats.covered++
                restart.wentRound()
            }

            // Only the clip that is loaded now counts (a late word about the one before it is not this one's first frame).
            override fun onRenderedFirstFrame() { if (CanvasPlayers.playing(player, clip.url)) restart.firstFrameOut() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(url) {
        clip.rendered = false
        still = null
        placed[0] = Float.NaN
        endMessage[0]?.cancel(); endMessage[0] = null
        // Told a quarter second before each end of a video loop (animated covers loop by themselves, cut for it).
        fun watchTheEnd() {
            val loop = clip.canvas.clip ?: return
            endMessage[0] = player.createMessage { _, _ -> restart.nearEnd() }
                .setLooper(android.os.Looper.getMainLooper())
                .setPosition((loop.lengthMs - RESTART_COVER_MS).coerceAtLeast(0L))
                .setDeleteAfterDelivery(false)
                .send()
        }
        // The same clip as last time the player was open: it carries on, no new loading or decoder.
        if (CanvasPlayers.playing(player, url)) {
            val v = player.videoSize
            clip.videoSize(v.width * v.pixelWidthHeightRatio, v.height)
            texture?.let(place)
            // Somewhere in the middle of its loop: the first frame is kept the next time round.
            restart.reset(atStart = false)
            watchTheEnd()
            return@LaunchedEffect
        }
        restart.reset(atStart = clip.canvas.clip != null)
        // A new clip: nothing shows until its own size and first frame are known (not the last clip's).
        clip.aspect = 0f
        val item = MediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        // 1.8.6: a loop from a music video is some seconds of a longer file, from a key frame on.
        clip.canvas.clip?.let { loop ->
            item.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((loop.startMs - loop.fileStartMs).coerceAtLeast(0))
                    .setEndPositionMs((loop.endMs - loop.fileStartMs).coerceAtLeast(1))
                    .build()
            )
        }
        player.setMediaItem(item.build())
        player.prepare()
        watchTheEnd()
    }

    // Only decode while the app is visible; pausing the song keeps the clip moving
    // (like Apple Music) but leaving the app stops it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // 1.8.6: Reduce motion, battery saver and a hot phone hold the picture on its frame (decoding stops, it isn't just
    // hidden); so does a song paused for half a minute. Performance mode never gets here (it switches Canvas off).
    val reduceMotion = com.prism.music.ui.theme.LocalAppSettings.current.reduceMotion
    val strain = rememberPhoneStrain()
    var pausedLong by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        pausedLong = false
        if (!isPlaying) { kotlinx.coroutines.delay(PAUSED_HOLD_MS); pausedLong = true }
    }
    LaunchedEffect(foreground, hold, reduceMotion, strain.value, pausedLong) {
        player.playWhenReady = canvasMoves(foreground, hold, reduceMotion, strain.value.powerSave, strain.value.hot, pausedLong)
    }

    // 1.8.6 (R2): it comes in from the cover over half a second, never pops.
    val alpha by animateFloatAsState(if (clip.rendered) 1f else 0f, tween(if (clip.rendered) 500 else 320), label = "canvasAlpha")
    // In over the loop's last moments; gone at once when the real first frame is under it.
    val stillAlpha by animateFloatAsState(
        if (restart.shown && still != null) 1f else 0f, if (restart.shown) tween(RESTART_DISSOLVE_MS) else androidx.compose.animation.core.snap(), label = "canvasRestart",
    )
    val cover by rememberUpdatedState(onCoverChanged)
    // Held, the still cover shows in the clip's place (the video view is taken off screen, below).
    LaunchedEffect(alpha, hold) { cover(if (hold) 0f else alpha * presentationAlpha()) }
    DisposableEffect(Unit) { onDispose { cover(0f) } }

    AndroidView(
        factory = { ctx ->
            val tv = TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isOpaque = false
                this.alpha = 0f
                player.setVideoTextureView(this)
                val delegate = surfaceTextureListener
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { delegate?.onSurfaceTextureAvailable(s, w, h) }
                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                        delegate?.onSurfaceTextureSizeChanged(s, w, h)
                        place(this@apply)
                    }
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                        clip.rendered = false
                        return delegate?.onSurfaceTextureDestroyed(s) ?: true
                    }
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(s)
                        if (clip.frame()) { place(this@apply); CanvasStats.showing = clip.url }
                        // A loop of several scenes: each has its own crop, changed on the cut.
                        else if (clip.canvas.clip?.crops?.isNotEmpty() == true) place(this@apply)
                        CanvasStats.frames++
                        // The loop's first frame is on screen: keep it as a picture, once, for the restarts to come.
                        if (restart.frameDrawn() && still == null) still = firstFrameOf(this@apply, clip.aspect)
                    }
                }
            }
            texture = tv
            val firstFrame = android.widget.ImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.MATRIX
                this.alpha = 0f
            }
            FadingFrame(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(tv)
                addView(firstFrame)
            }
        },
        update = { frame ->
            val tv = frame.getChildAt(0) as TextureView
            val firstFrame = frame.getChildAt(1) as android.widget.ImageView
            // Under a sheet the video view isn't drawn at all: paused, it was still a live layer (with its fade effect)
            // in the window, and dragging the ⋯ sheet over an animated cover kept sticking.
            frame.visibility = if (hold) android.view.View.INVISIBLE else android.view.View.VISIBLE
            tv.alpha = alpha * presentationAlpha()
            place(tv)
            if (Build.VERSION.SDK_INT >= 31) tv.bottomFade(bottomFade) else frame.fade = bottomFade
            // The kept first frame, placed exactly where the video draws it.
            val picture = still
            if (firstFrame.drawable == null || (firstFrame.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== picture) firstFrame.setImageBitmap(picture)
            firstFrame.alpha = if (picture == null) 0f else stillAlpha * alpha * presentationAlpha()
            firstFrame.visibility = if (picture == null || stillAlpha <= 0f) android.view.View.GONE else android.view.View.VISIBLE
            if (picture != null && tv.width > 0 && clip.aspect > 0f) {
                val t = cropTransform(tv.width.toFloat(), tv.height.toFloat(), clip.aspect, clip.canvas.clip?.bars ?: com.prism.music.data.canvas.Bars.NONE, clip.canvas.clip?.let { it.cropAt(it.startMs) } ?: 0.5f)
                firstFrame.imageMatrix = Matrix().apply {
                    setScale(tv.width.toFloat() / picture.width * t[0], tv.height.toFloat() / picture.height * t[1])
                    postTranslate(t[2], t[3])
                }
                if (Build.VERSION.SDK_INT >= 31) firstFrame.bottomFade(bottomFade)
            }
        },
        modifier = modifier,
    )
}

/**
 * What the canvases did since Prism started, for Share diagnostics and the tests: times a clip went round, how many of
 * those had the kept first frame over them, and what failed. Counted on the main thread; nothing is stored or sent.
 */
internal object CanvasStats {
    @Volatile var rounds = 0
    @Volatile var covered = 0
    @Volatile var errors = 0
    @Volatile var lastError: String? = null
    /** The clip on screen last: its address (a file for a loop from a music video) and whether a frame of it showed. */
    @Volatile var showing: String? = null
    @Volatile var frames = 0
}

/** How long before a video loop's end its kept first frame starts to dissolve in, and how long that takes. */
internal const val RESTART_COVER_MS = 280L
internal const val RESTART_DISSOLVE_MS = 220
/** A restart that never drew its first frame (the phone held the picture, the clip failed): the kept frame goes anyway. */
internal const val RESTART_GIVE_UP_MS = 1_500L

/**
 * The cover over a video loop's restart (1.8.6): what happens in which order. The loop's first frame is kept as a
 * picture when it is drawn ([frameDrawn] says when); near each end the picture shows ([nearEnd]); the loop goes round
 * ([wentRound]), the decoder puts its first frame out ([firstFrameOut]), and the next frame drawn takes the picture off
 * again. A frame of the old round that is drawn late, between the two, must not take it off: that is the moment it hides.
 */
internal class RestartCover {
    /** The kept first frame is over the video. */
    var shown by mutableStateOf(false); private set
    private var kept = false
    private var awaited = false
    private var nextIsFirst = false
    private var round = false
    private var out = false

    /**
     * A clip was loaded ([atStart]: the first frame its decoder puts out is the loop's first) or picked up as it plays.
     * The frames still being drawn from the clip before it are not this one's: on the A16 the last song's frame was kept
     * as the next song's cover and flashed at each of its restarts, so the decoder's word is waited for.
     */
    fun reset(atStart: Boolean) { shown = false; kept = false; awaited = atStart; nextIsFirst = false; round = false; out = false }

    fun nearEnd() { if (kept) shown = true }
    fun wentRound() { round = true; out = false }
    fun firstFrameOut() { if (round) out = true else if (awaited) { awaited = false; nextIsFirst = true } }

    /** A frame reached the screen. True when it is the loop's first and no picture of it is kept yet: keep it now. */
    fun frameDrawn(): Boolean {
        val first = if (round && out) { round = false; out = false; shown = false; true } else if (nextIsFirst) { nextIsFirst = false; true } else false
        if (!first || kept) return false
        kept = true
        return true
    }

    fun giveUp() { shown = false; round = false; out = false }
}

/** The frame on [view] now as a picture of the whole video frame (uncropped), no taller than the view; null if it can't be had. */
private fun firstFrameOf(view: TextureView, aspect: Float): android.graphics.Bitmap? {
    if (aspect <= 0f || view.height <= 0) return null
    val h = view.height.coerceAtMost(1080)
    val w = (h * aspect).toInt().coerceIn(2, 1920)
    return runCatching { view.getBitmap(w, h) }.getOrNull()
}

/** A paused song's canvas keeps moving this long, then holds its frame (plan-canvas §11a R7). */
internal const val PAUSED_HOLD_MS = 30_000L

/**
 * Whether a canvas moves now (1.8.6). It holds its frame while the app is away or a sheet covers it (as before), with
 * Reduce motion on (until 1.8.6 animated covers ignored it), in battery saver, while the phone is hot, and once the song
 * has been paused for [PAUSED_HOLD_MS].
 */
internal fun canvasMoves(foreground: Boolean, hold: Boolean, reduceMotion: Boolean, powerSave: Boolean, hot: Boolean, pausedLong: Boolean): Boolean =
    foreground && !hold && !reduceMotion && !powerSave && !hot && !pausedLong

/** The phone asking to be spared: battery saver on, or running hot (Android 10+ says so; "severe" and up counts). */
internal data class PhoneStrain(val powerSave: Boolean = false, val hot: Boolean = false)

internal fun isHot(thermalStatus: Int): Boolean = thermalStatus >= 3 // PowerManager.THERMAL_STATUS_SEVERE

@Composable
private fun rememberPhoneStrain(): androidx.compose.runtime.State<PhoneStrain> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(PhoneStrain()) }
    DisposableEffect(context) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        fun read() {
            state.value = PhoneStrain(
                powerSave = power?.isPowerSaveMode == true,
                hot = Build.VERSION.SDK_INT >= 29 && power != null && isHot(power.currentThermalStatus),
            )
        }
        read()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) = read()
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val thermal = if (Build.VERSION.SDK_INT >= 29 && power != null) android.os.PowerManager.OnThermalStatusChangedListener { read() }.also { power.addThermalStatusListener(it) } else null
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= 29 && thermal != null) power?.removeThermalStatusListener(thermal)
        }
    }
    return state
}

/**
 * What a [CanvasPlayer] knows about the clip it shows now (#384, 1.8.3): one object for every clip, so the listeners set
 * up once (the player's, the video view's) always reach the current clip's state.
 */
internal class ClipState(first: CanvasArtwork) {
    var canvas by mutableStateOf(first); private set
    var url by mutableStateOf(first.url); private set
    var rendered by mutableStateOf(false)
    var aspect by mutableFloatStateOf(0f)

    /** The song's clip changed: start over with it. */
    fun show(next: CanvasArtwork) {
        if (next == canvas) return
        // The same clip under another song (an album's one video for all its tracks): it plays on as it is; resetting
        // here would hide it for good, since nothing loads again to show it.
        if (next.url == canvas.url) { canvas = next; return }
        canvas = next; url = next.url; rendered = false; aspect = 0f
    }

    fun videoSize(width: Float, height: Int) { aspect = if (width > 0 && height > 0) width / height else 0f }

    /** A frame landed: the clip shows once its size is known. True when it has just become visible. */
    fun frame(): Boolean {
        if (rendered || aspect <= 0f) return false
        rendered = true
        return true
    }

    /** The clip couldn't play: its other rendition, else the still cover stays. */
    fun failed() {
        val alt = canvas.fallbackUrl
        if (alt != null && alt != url) url = alt else rendered = false
    }
}

/**
 * The animated covers' players, kept between uses (#343, 1.8.1.1): a clip leaving the screen (the player closing) hands
 * its player back paused and without a surface, and the next one to show takes it, with its clip and decoder still
 * there. One kept at a time, let go after [IDLE_RELEASE_MS] unused. Main thread only.
 */
@OptIn(UnstableApi::class)
internal object CanvasPlayers {
    /** What the player's data source reads, set by whichever [CanvasPlayer] holds it now. */
    class Source { @Volatile var clip: String = ""; @Volatile var keep: Boolean = false }
    class Held(val player: ExoPlayer, val source: Source)

    private const val IDLE_RELEASE_MS = 30_000L
    /** The player can show the clip twice at once (behind everything and in the lyrics' ground): two kept at most. */
    private const val KEPT = 2
    private val parked = ArrayList<Held>(KEPT)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val letGo = Runnable { parked.forEach { it.player.release() }; parked.clear() }

    /** A kept player, the one already on [url] if there is one, else a new one. */
    fun take(context: Context, c: com.prism.music.AppContainer, url: String): Held {
        (parked.firstOrNull { it.source.clip == url } ?: parked.lastOrNull())?.let { parked.remove(it); return it }
        val source = Source()
        // Clips come from the phone when they've played before (or were saved), else stream and are kept.
        val sources = DefaultMediaSourceFactory(c.canvasStore.playerSourceFactory({ source.clip }) { source.keep })
        val side = com.prism.music.data.canvas.canvasMaxSide(context)
        val player = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(sources)
            // A clip of a few seconds, looping, mostly read from the phone: a few seconds ahead is plenty. With the
            // player's defaults (50 s, up to 128 MB for video) it kept loading further loops of the same clip and
            // held ~50 MB of it in memory (heap dump on the budget emulator, 1.7.6).
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 10_000, 1_000, 2_000)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                // Always the same rendition (H.264, the best up to canvasMaxSide), so what was kept last
                // time, or saved ahead by CanvasStore (which picks the same one), is what plays.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(side, side)
                    .clearViewportSizeConstraints()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setForceHighestSupportedBitrate(true)
                    .build()
            }
        return Held(player, source)
    }

    fun give(held: Held) {
        held.player.playWhenReady = false
        held.player.clearVideoSurface()
        if (parked.size >= KEPT) { held.player.release(); return }
        parked += held
        // Let go once nothing has taken them back for a while (each hand-back starts the wait again).
        main.removeCallbacks(letGo)
        main.postDelayed(letGo, IDLE_RELEASE_MS)
    }

    /** The player has [url] loaded (not failed or stopped): showing it again needs nothing new. */
    fun playing(player: Player, url: String): Boolean = loadedAlready(player.currentMediaItem?.localConfiguration?.uri?.toString(), player.playbackState, player.playerError != null, url)

    internal fun loadedAlready(current: String?, state: Int, failed: Boolean, url: String): Boolean =
        current == url && !failed && state != Player.STATE_IDLE
}

/**
 * Crop the video into the view (a TextureView stretches by default): centred for an animated cover; for a loop from a
 * music video ([loop]) its black bars are cut off first and the crop sits where the loop says ([VideoClip.cropX]).
 */
internal fun TextureView.cropTo(clipAspect: Float, loop: com.prism.music.data.canvas.VideoClip? = null, cropX: Float? = null) {
    if (width <= 0 || height <= 0 || clipAspect <= 0f) { setTransform(Matrix()); return }
    val t = cropTransform(width.toFloat(), height.toFloat(), clipAspect, loop?.bars ?: com.prism.music.data.canvas.Bars.NONE, cropX ?: loop?.cropX ?: 0.5f)
    val m = Matrix()
    m.setScale(t[0], t[1])
    m.postTranslate(t[2], t[3])
    setTransform(m)
}

/**
 * Scale (x, y) and shift (x, y) that take a frame stretched over the whole view (what a TextureView draws) to one that
 * keeps its shape and covers the view with the picture inside [bars]. Across, the part that doesn't fit is shared out
 * by [cropX] (0 keeps the picture's left edge, 0.5 its middle, 1 its right edge); up and down it is centred.
 */
internal fun cropTransform(viewW: Float, viewH: Float, aspect: Float, bars: com.prism.music.data.canvas.Bars, cropX: Float): FloatArray {
    val across = (1f - bars.left - bars.right).takeIf { it > 0.1f } ?: 1f
    val down = (1f - bars.top - bars.bottom).takeIf { it > 0.1f } ?: 1f
    val left = if (across == 1f) 0f else bars.left
    val top = if (down == 1f) 0f else bars.top
    val pictureAspect = aspect * across / down
    // The whole frame's size on screen, so that the picture inside the bars just covers the view.
    val frameH: Float
    val frameW: Float
    if (pictureAspect > viewW / viewH) { frameH = viewH / down; frameW = frameH * aspect } else { frameW = viewW / across; frameH = frameW / aspect }
    val spareX = (across * frameW - viewW).coerceAtLeast(0f)
    val spareY = (down * frameH - viewH).coerceAtLeast(0f)
    return floatArrayOf(frameW / viewW, frameH / viewH, -(left * frameW + spareX * cropX.coerceIn(0f, 1f)), -(top * frameH + spareY / 2f))
}

/** Dissolve the bottom edge on the view's own node (a Compose mask can't reach video frames). */
@RequiresApi(31)
internal fun android.view.View.bottomFade(fraction: Float) {
    if (fraction <= 0.001f || height <= 0) { setRenderEffect(null); return }
    val h = height.toFloat()
    val gradient = LinearGradient(0f, h * (1f - fraction), 0f, h, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
    setRenderEffect(RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), RenderEffect.createShaderEffect(gradient), BlendMode.DST_IN))
}

/** Pre-API-31 bottom fade: draw the child into a layer and mask it. */
internal class FadingFrame(context: Context) : FrameLayout(context) {
    /** The fade asked for (Android 12+ draws it as a render effect instead of [fade]). */
    var wanted = 0f
    var fade = 0f
        set(v) { if (v != field) { field = v; invalidate() } }
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    override fun dispatchDraw(canvas: Canvas) {
        if (fade <= 0.001f) { super.dispatchDraw(canvas); return }
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, h * (1f - fade), 0f, h, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), h, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), h, paint)
        canvas.restoreToCount(layer)
    }
}
