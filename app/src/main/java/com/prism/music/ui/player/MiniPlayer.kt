package com.prism.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val pc = LocalContainer.current.player
    val song by pc.currentSong.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    val s = song ?: return
    val settings = LocalAppSettings.current
    val ui = LocalUi.current
    val position = rememberPosition(fast = false)
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    val style = settings.miniPlayerStyle
    var menu by remember { mutableStateOf(false) }
    if (menu) com.prism.music.ui.components.SongActionsSheet(s) { menu = false }

    val height = when (style) { MiniPlayerStyle.CARD -> 64.dp; MiniPlayerStyle.SLIM -> 54.dp; MiniPlayerStyle.PILL -> 56.dp }
    val shape = when (style) {
        MiniPlayerStyle.CARD -> ui.shape(22.dp)
        MiniPlayerStyle.SLIM -> ui.shape(16.dp)
        MiniPlayerStyle.PILL -> RoundedCornerShape(50)
    }
    val artSize = when (style) { MiniPlayerStyle.CARD -> 48.dp; MiniPlayerStyle.SLIM -> 40.dp; MiniPlayerStyle.PILL -> 44.dp }
    val artShape = if (style == MiniPlayerStyle.PILL) CircleShape else ui.shape(if (style == MiniPlayerStyle.SLIM) 9.dp else 12.dp)
    val fraction = (position.toFloat() / pc.duration.coerceAtLeast(1)).coerceIn(0f, 1f)

    GlassSurface(
        modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                drag.value < -140 -> { haptics.gestureEnd(); pc.next() }
                                drag.value > 140 -> { haptics.gestureEnd(); pc.previous() }
                            }
                            drag.animateTo(0f)
                        }
                    },
                ) { _, d ->
                    val before = drag.value
                    val after = before + d
                    if ((kotlin.math.abs(before) < 140f) != (kotlin.math.abs(after) < 140f)) haptics.threshold()
                    scope.launch { drag.snapTo(after) }
                }
            },
        shape = shape,
    ) {
        // Fill: the played part of the song tints the whole card.
        if (settings.miniProgress == MiniProgress.FILL) Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction).background(scheme.primary.copy(alpha = 0.16f)),
        )
        Row(
            // Holding it brings up the song's actions (like, add to playlist, share…) without opening the player.
            Modifier.fillMaxSize().combinedClickable(onClick = onOpen, onLongClick = { haptics.click(); menu = true })
                .padding(start = if (style == MiniPlayerStyle.PILL) 6.dp else 8.dp, end = if (style == MiniPlayerStyle.PILL) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(s.thumbnail, Modifier.size(artSize), artShape, size = 226)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                s,
                transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) },
                modifier = Modifier.weight(1f),
                label = "mini",
            ) { song ->
                Column {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (style != MiniPlayerStyle.SLIM || song.artistText.isNotBlank()) Text(
                        song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (settings.miniSkipButtons && style == MiniPlayerStyle.CARD) {
                IconButton(onClick = { haptics.click(); pc.previous() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            }
            if (style == MiniPlayerStyle.PILL) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(scheme.onSurface).clickable { haptics.click(); pc.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(24.dp), tint = scheme.surface) }
            } else {
                IconButton(onClick = { haptics.click(); pc.togglePlay() }, modifier = Modifier.size(44.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", Modifier.size(if (style == MiniPlayerStyle.SLIM) 26.dp else 30.dp))
                }
            }
            if (settings.miniSkipButtons) {
                IconButton(onClick = { haptics.click(); pc.next() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
        }
        if (settings.miniProgress == MiniProgress.LINE) {
            Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter).padding(horizontal = if (style == MiniPlayerStyle.PILL) 28.dp else 18.dp)) {
                drawLine(scheme.onSurface.copy(alpha = 0.12f), Offset(0f, 0f), Offset(size.width, 0f), 4f, StrokeCap.Round)
                drawLine(scheme.primary, Offset(0f, 0f), Offset(size.width * fraction, 0f), 4f, StrokeCap.Round)
            }
        }
    }
}
