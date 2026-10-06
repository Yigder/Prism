package com.prism.music.ui.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.prism.music.playback.AudioEffects
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val PLAYER_GUTTER = 30.dp
private val BOTTOM_ACTION_SIZE = 44.dp
private val PILL_SEGMENT_WIDTH = 64.dp
private val PILL_SEGMENT_WIDTH_TRIPLE = 52.dp

val textShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 4f)

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A hairline capsule with no thumb that thickens under the finger. */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    secondary: Float = 0f,
) {
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        if (dragging) activeHeight else idleHeight,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sliderHeight",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) { p.consume(); break }
                        if (p.positionChanged()) {
                            onValueChange((p.position.x / size.width).coerceIn(0f, 1f))
                            p.consume()
                        }
                    }
                    dragging = false
                    onValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.26f), cornerRadius = r)
            if (secondary > 0f) drawRoundRect(Color.White.copy(alpha = 0.12f), size = Size(size.width * secondary.coerceIn(0f, 1f), size.height), cornerRadius = r)
            val filled = size.width * value.coerceIn(0f, 1f)
            if (filled > 0f) drawRoundRect(
                Color.White.copy(alpha = 0.92f),
                size = Size(filled.coerceAtLeast(size.height).coerceAtMost(size.width), size.height),
                cornerRadius = r,
            )
        }
    }
}

/** Seek bar with elapsed / remaining under it and a centre label between them. */
@Composable
fun PlayerScrubber(positionMs: Long, durationMs: Long, centerLabel: @Composable BoxScope.() -> Unit = {}) {
    val pc = LocalContainer.current.player
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val shown = scrub ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        ThinSlider(
            value = shown,
            onValueChange = { scrub = it },
            onValueChangeFinished = { scrub?.let { pc.seekTo((it * duration).toLong()) }; scrub = null },
        )
        Box(Modifier.fillMaxWidth().offset(y = (-9).dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((shown * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
                Text("-" + formatTime(duration - (shown * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
            }
            centerLabel()
        }
    }
}

/** Previous, play/pause, next — oversized glyphs, no circles. */
@Composable
fun TransportRow(isPlaying: Boolean, isLoading: Boolean, previousEnabled: Boolean, nextEnabled: Boolean, compact: Boolean = false) {
    val pc = LocalContainer.current.player
    val playSize = if (compact) 58.dp else 74.dp
    val playTouch = if (compact) 76.dp else 92.dp
    val skipSize = if (compact) 44.dp else 53.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        TransportGlyph(Icons.Rounded.FastRewind, "Previous", skipSize, 53.dp, 0.85f, previousEnabled) { pc.previous() }
        if (isLoading) {
            Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(if (compact) 30.dp else 38.dp))
            }
        } else {
            TransportGlyph(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", playSize, playTouch) { pc.togglePlay() }
        }
        TransportGlyph(Icons.Rounded.FastForward, "Next", skipSize, 53.dp, 0.85f, nextEnabled) { pc.next() }
    }
}

@Composable
private fun TransportGlyph(
    icon: ImageVector,
    description: String,
    size: Dp,
    touch: Dp,
    heightScale: Float = 1f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.3f, label = "transportAlpha")
    val haptics = rememberHaptics()
    Box(
        Modifier.size(touch).clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(size).graphicsLayer { scaleY = heightScale })
    }
}

/** System media volume, mirrored into a value the bar can drag. */
class PlayerVolume(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
    var level by mutableFloatStateOf(read())
    var dragging by mutableStateOf(false)

    fun read(): Float = (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max

    fun drag(v: Float) {
        dragging = true
        level = v
        am?.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).let { kotlin.math.round(it).toInt() }, 0)
    }

    fun release() { dragging = false }
}

@Composable
fun rememberPlayerVolume(): PlayerVolume {
    val context = LocalContext.current
    val v = remember { PlayerVolume(context) }
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!v.dragging) v.level = v.read()
            delay(500)
        }
    }
    return v
}

val VOLUME_ROW_HEIGHT = 32.dp

@Composable
fun VolumeRow(volume: PlayerVolume) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        ThinSlider(volume.level, volume::drag, Modifier.weight(1f), volume::release, idleHeight = 6.dp, activeHeight = 10.dp)
        Spacer(Modifier.width(10.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}

/**
 * Lyrics, the capsule, and the queue. The capsule is Song / Video normally,
 * and shuffle / repeat / autoplay while the queue is up.
 */
@Composable
fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    videoMode: Boolean,
    videoAvailable: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onVideoMode: (Boolean) -> Unit,
) {
    val c = LocalContainer.current
    val pc = c.player
    val shuffle by pc.shuffle.collectAsState()
    val repeat by pc.repeatMode.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val videoLoading by pc.videoLoading.collectAsState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = BOTTOM_ACTION_SIZE * 2 + PILL_SEGMENT_WIDTH * 3 + 2.dp
        val inset = ((maxWidth - widest) / 4).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = inset), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BottomGlyph(Icons.Rounded.FormatQuote, if (lyricsOpen) "Close lyrics" else "Lyrics", lyricsOpen, onToggleLyrics)
            AnimatedContent(
                queueOpen,
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 140)) togetherWith fadeOut(tween(140))).using(SizeTransform(clip = false) { _, _ -> tween(220) })
                },
                label = "pill",
            ) { modes ->
                if (modes) Pill {
                    PillSegment(Icons.Rounded.Shuffle, "Shuffle", shuffle, width = PILL_SEGMENT_WIDTH_TRIPLE) { pc.toggleShuffle() }
                    PillDivider()
                    PillSegment(
                        if (repeat == Player.REPEAT_MODE_ONE) null else Icons.Rounded.Repeat, "Repeat",
                        repeat != Player.REPEAT_MODE_OFF, label = if (repeat == Player.REPEAT_MODE_ONE) "1" else null, width = PILL_SEGMENT_WIDTH_TRIPLE,
                    ) { pc.cycleRepeat() }
                    PillDivider()
                    PillSegment(Icons.Rounded.AllInclusive, "Autoplay", settings.autoplay, width = PILL_SEGMENT_WIDTH_TRIPLE) { c.settings.setAutoplay(!settings.autoplay) }
                } else Pill {
                    PillSegment(Icons.Rounded.MusicNote, "Song", !videoMode && !videoLoading) { if (videoMode || videoLoading) onVideoMode(false) }
                    PillDivider()
                    PillSegment(
                        Icons.Rounded.SmartDisplay, if (videoAvailable) "Music video" else "No music video for this song",
                        videoMode || videoLoading, enabled = videoAvailable || videoMode, loading = videoLoading,
                    ) {
                        if (!videoMode && !videoLoading) onVideoMode(true)
                    }
                }
            }
            BottomGlyph(Icons.AutoMirrored.Rounded.FormatListBulleted, "Up next", queueOpen, onToggleQueue)
        }
    }
}

@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.height(BOTTOM_ACTION_SIZE).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
            .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.20f)))
}

@Composable
private fun PillSegment(
    icon: ImageVector?,
    description: String,
    highlighted: Boolean,
    label: String? = null,
    enabled: Boolean = true,
    width: Dp = PILL_SEGMENT_WIDTH,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        Modifier.width(width).height(BOTTOM_ACTION_SIZE)
            .background(if (highlighted) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val tint = Color.White.copy(alpha = when { !enabled -> 0.25f; highlighted -> 1f; else -> 0.75f })
        Crossfade(Triple(icon, label, loading), animationSpec = tween(200), label = "segment") { (ic, lb, busy) ->
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else if (ic != null) Icon(ic, description, tint = tint, modifier = Modifier.size(24.dp))
            else if (lb != null) Text(lb, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomGlyph(icon: ImageVector, description: String, highlighted: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.size(BOTTOM_ACTION_SIZE).clip(CircleShape)
            .background(if (highlighted) Color.White.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f), modifier = Modifier.size(26.dp))
    }
}

/** Translucent circular button beside the credits (like, menu). */
@Composable
fun CircleGlyph(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val disc by animateFloatAsState(if (active) 0.34f else 0.18f, label = "disc")
    val haptics = rememberHaptics()
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = disc))
            .clickable(remember { MutableInteractionSource() }, indication = null) { haptics.click(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(icon, animationSpec = tween(180), label = "glyph") { Icon(it, description, tint = Color.White, modifier = Modifier.size(19.dp)) }
    }
}

/** The line under the transport: where the sound is going. Tap to pick another output, right in the player. */
@Composable
fun OutputCaption() {
    val context = LocalContext.current
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val preferred by c.player.preferredOutput.collectAsState()
    val phoneName = settings.accountName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { "$it's Phone" } ?: "This Phone"
    var current by remember { mutableStateOf(currentOutput(context, preferred)) }
    LaunchedEffect(preferred) {
        while (isActive) { current = currentOutput(context, preferred); delay(1500) }
    }
    var picking by remember { mutableStateOf(false) }
    Text(
        current?.let { outputLabel(it, phoneName) } ?: phoneName,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(0.65f).clickable { picking = true },
    )
    if (picking) OutputPickerSheet(phoneName) { picking = false }
}

private val OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_DOCK,
)

private fun isBluetooth(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID

/** Outputs music can play through right now: this phone, wired/USB, and connected Bluetooth devices. */
fun outputOptions(context: Context): List<AudioDeviceInfo> {
    val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in OUTPUT_TYPES }
        // One row per physical device (LE Audio and A2DP can both show for the same earbuds).
        .distinctBy { if (isBluetooth(it.type)) "bt:" + (it.address.ifBlank { it.productName.toString() }) else "t:${it.type}:${it.productName}" }
        .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else if (isBluetooth(it.type)) 2 else 1 }
}

/** Where audio is going: the device picked in Prism if still there, otherwise the system's media route. */
fun currentOutput(context: Context, preferred: AudioDeviceInfo?): AudioDeviceInfo? {
    val options = outputOptions(context)
    if (preferred != null) options.firstOrNull { it.id == preferred.id }?.let { return it }
    val am = context.getSystemService(AudioManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 33 && am != null) {
        runCatching {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routed ->
                options.firstOrNull { it.type == routed.type && (routed.address.isBlank() || it.address == routed.address) }
                    ?.let { return it }
            }
        }
    }
    return options.maxByOrNull { if (isBluetooth(it.type)) 3 else if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else 2 }
}

fun outputLabel(d: AudioDeviceInfo, phoneName: String): String = when (d.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> phoneName
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headphones"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    else -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        ?: if (isBluetooth(d.type)) "Bluetooth device" else "USB audio"
}

private fun outputIcon(d: AudioDeviceInfo): ImageVector = when {
    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Rounded.PhoneAndroid
    d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || d.type == AudioDeviceInfo.TYPE_HDMI || d.type == AudioDeviceInfo.TYPE_DOCK -> Icons.Rounded.Speaker
    isBluetooth(d.type) -> Icons.Rounded.Headphones
    d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET -> Icons.Rounded.Usb
    else -> Icons.Rounded.Headphones
}

/** Audio output picker inside the player — no trip to the system Bluetooth page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputPickerSheet(phoneName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pc = LocalContainer.current.player
    val preferred by pc.preferredOutput.collectAsState()
    var options by remember { mutableStateOf(outputOptions(context)) }
    var current by remember { mutableStateOf(currentOutput(context, preferred)) }
    LaunchedEffect(preferred) {
        while (isActive) {
            options = outputOptions(context)
            current = currentOutput(context, preferred)
            delay(1000)
        }
    }
    com.prism.music.ui.components.PrismSheet(onDismiss, containerColor = Color(0xFF1C1C1E), contentColor = Color.White) {
        Text("Play on", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.padding(bottom = 28.dp)) {
            options.forEach { d ->
                val selected = current?.id == d.id
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { pc.setOutput(d) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = if (selected) 0.95f else 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(outputIcon(d), null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(outputLabel(d, phoneName), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                                isBluetooth(d.type) -> "Bluetooth"
                                d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                                else -> "Wired"
                            } + if (selected) " · Playing" else "",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                    if (selected) Icon(Icons.Rounded.Check, "Selected", tint = Color.White)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) Row(
                Modifier.fillMaxWidth()
                    .clickable { if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cast, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Other devices", style = MaterialTheme.typography.bodyLarge)
                    Text("Paired devices that aren't connected, and cast speakers", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
                }
            }
        }
    }
}

@Composable
fun PlaybackOriginCaption(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(shadow = textShadow),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = PLAYER_GUTTER, vertical = 1.dp),
    )
}

/** Title / artist line that scrolls only when it doesn't fit, after a rest. */
@Composable
fun PlayerMarquee(text: String, style: TextStyle, color: Color, enabled: Boolean, modifier: Modifier = Modifier, delayMs: Int = 2500) {
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.then(
            if (enabled) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = delayMs, repeatDelayMillis = 5000, velocity = 26.dp)
            else Modifier
        ),
        overflow = if (enabled) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Quality badge between the timestamps; tap for the full stats panel. */
@Composable
fun QualityLabel(modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val stats by LocalContainer.current.nerdStats.collectAsState()
    val codec = stats.codec.takeIf { it.isNotBlank() }?.let(::codecName).orEmpty()
    val lossless = stats.source.startsWith("Lossless") ||
        listOf("flac", "alac", "wav", "pcm", "aiff").any { stats.codec.contains(it, true) }
    val text = when {
        stats.source == "Downloaded" -> "Downloaded"
        lossless -> "Lossless"
        stats.streamBitrateKbps > 0 -> "$codec ${stats.streamBitrateKbps} kbps".trim()
        else -> codec
    }
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = if (selected) 0.95f else 0.55f),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(CircleShape)
            .background(Color.White.copy(alpha = if (selected) 0.16f else 0f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** One line of stats on the foot of the sleeve, when Stats for nerds is on. */
@Composable
fun SleeveNerdStats(modifier: Modifier = Modifier) {
    val settings by LocalContainer.current.settings.flow.collectAsState()
    if (!settings.showNerdStats) return
    val s by LocalContainer.current.nerdStats.collectAsState()
    // Just the basics: codec, bitrate, sample rate.
    val parts = listOfNotNull(
        s.codec.takeIf { it.isNotBlank() }?.let(::codecName),
        s.streamBitrateKbps.takeIf { it > 0 }?.let { "$it kbps" },
        s.sampleRate.takeIf { it > 0 }?.let(::sampleRateText),
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall.copy(shadow = textShadow),
        color = Color.White.copy(alpha = 0.65f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
