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
import com.prism.music.data.canvas.CANVAS_MAX_SIDE
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
) {
    val context = LocalContext.current
    val c = LocalContainer.current
    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    var aspect by remember(canvas) { mutableFloatStateOf(0f) }
    var texture by remember { mutableStateOf<TextureView?>(null) }

    val keep by rememberUpdatedState(saved)
    val clip by rememberUpdatedState(url)
    val player = remember {
        // Clips come from the phone when they've played before (or were saved), else stream and are kept.
        val sources = DefaultMediaSourceFactory(c.canvasStore.dataSourceFactory({ clip }) { keep })
        ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            // Always the same rendition (H.264, the best up to CANVAS_MAX_SIDE), so what was kept last
            // time, or saved ahead by CanvasStore (which picks the same one), is what plays.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(CANVAS_MAX_SIDE, CANVAS_MAX_SIDE)
                .clearViewportSizeConstraints()
                .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                .setForceHighestSupportedBitrate(true)
                .build()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                aspect = if (w > 0 && videoSize.height > 0) w / videoSize.height else 0f
                texture?.cropTo(aspect)
            }

            override fun onPlayerError(error: PlaybackException) {
                val alt = canvas.fallbackUrl
                if (alt != null && alt != url) url = alt else rendered = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(url) {
        rendered = false
        val item = MediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
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
    LaunchedEffect(foreground, isPlaying) { player.playWhenReady = foreground }

    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(320), label = "canvasAlpha")
    val cover by rememberUpdatedState(onCoverChanged)
    LaunchedEffect(alpha) { cover(alpha * presentationAlpha()) }
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
                        cropTo(aspect)
                    }
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                        rendered = false
                        return delegate?.onSurfaceTextureDestroyed(s) ?: true
                    }
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(s)
                        if (!rendered && aspect > 0f) {
                            cropTo(aspect)
                            rendered = true
                        }
                    }
                }
            }
            texture = tv
            FadingFrame(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(tv)
            }
        },
        update = { frame ->
            val tv = frame.getChildAt(0) as TextureView
            tv.alpha = alpha * presentationAlpha()
            tv.cropTo(aspect)
            if (Build.VERSION.SDK_INT >= 31) tv.bottomFade(bottomFade) else frame.fade = bottomFade
        },
        modifier = modifier,
    )
}

/** Centre-crop the video into the view (a TextureView stretches by default). */
internal fun TextureView.cropTo(clipAspect: Float) {
    if (width <= 0 || height <= 0 || clipAspect <= 0f) { setTransform(Matrix()); return }
    val viewAspect = width.toFloat() / height
    val m = Matrix()
    if (clipAspect > viewAspect) m.setScale(clipAspect / viewAspect, 1f, width / 2f, height / 2f)
    else m.setScale(1f, viewAspect / clipAspect, width / 2f, height / 2f)
    setTransform(m)
}

/** Dissolve the bottom edge on the TextureView's own node (a Compose mask can't reach video frames). */
@RequiresApi(31)
private fun TextureView.bottomFade(fraction: Float) {
    if (fraction <= 0.001f || height <= 0) { setRenderEffect(null); return }
    val h = height.toFloat()
    val gradient = LinearGradient(0f, h * (1f - fraction), 0f, h, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
    setRenderEffect(RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), RenderEffect.createShaderEffect(gradient), BlendMode.DST_IN))
}

/** Pre-API-31 bottom fade: draw the child into a layer and mask it. */
private class FadingFrame(context: Context) : FrameLayout(context) {
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
