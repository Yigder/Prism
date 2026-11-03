package com.prism.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A slider along the right edge of a long list (1.7.9, Reddit: "a slider on the right to scroll, it's hard to swipe that
 * long"): it shows while the list moves, and dragging its thumb jumps straight to that part of the list, with a bubble
 * saying where ([label] for how far down the thumb is, 0..1: e.g. the song's number). Only for lists longer than [minItems].
 */
@Composable
fun FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    minItems: Int = 60,
    description: String,
    label: (Float) -> String?,
) {
    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total < minItems) return
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // Shown while the list scrolls or the thumb is held, then fades away after a moment.
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) shown = true
        else { delay(1_500); shown = false }
    }
    val scope = rememberCoroutineScope()
    val fraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo.size.coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / (info.totalItemsCount - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    AnimatedVisibility(shown, modifier.fillMaxHeight(), enter = fadeIn(), exit = fadeOut()) {
        // Wide enough for the bubble beside the strip; only the strip itself takes touches.
        BoxWithConstraints(Modifier.fillMaxHeight().width(160.dp)) {
            val density = LocalDensity.current
            val thumbHeight = 48.dp
            val travel = with(density) { (maxHeight - thumbHeight).toPx() }
            var dragFraction by remember { mutableStateOf(0f) }
            val at = if (dragging) dragFraction else fraction
            val y = (travel * at).roundToInt()
            Box(
                Modifier.align(Alignment.TopEnd).fillMaxHeight().width(36.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(travel) {
                        detectVerticalDragGestures(
                            onDragStart = { o -> dragging = true; dragFraction = (o.y / travel).coerceIn(0f, 1f) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            dragFraction = (change.position.y / travel).coerceIn(0f, 1f)
                            val target = (dragFraction * (state.layoutInfo.totalItemsCount - 1)).roundToInt()
                            scope.launch { state.scrollToItem(target) }
                        }
                    },
            ) {
                // The thumb: a slim pill, wider while held.
                Box(
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 4.dp)
                        .size(width = if (dragging) 8.dp else 5.dp, height = thumbHeight)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.85f else 0.45f), RoundedCornerShape(50)),
                )
            }
            if (dragging) {
                label(dragFraction)?.let { text ->
                    Box(
                        Modifier.align(Alignment.TopEnd).offset { IntOffset(with(density) { (-44).dp.roundToPx() }, y) }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
