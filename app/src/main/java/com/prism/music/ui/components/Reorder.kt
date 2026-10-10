package com.prism.music.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A list to rearrange: hold a row and drag it up or down; [onReorder] gets the new order when
 * it's let go. [row] draws each one (held is true while it's being dragged) and must apply the
 * modifier it's given, which carries the drag.
 */
@Composable
fun <T> ReorderList(
    items: List<T>,
    keyOf: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, index: Int, held: Boolean, modifier: Modifier) -> Unit,
) {
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var dragOrder by remember { mutableStateOf<List<T>?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val current by rememberUpdatedState(items)
    val haptics = LocalHapticFeedback.current
    // While a row is held the list is reordered here, and saved when it's let go.
    val rows = dragOrder ?: items
    Column(modifier) {
        rows.forEachIndexed { i, item ->
            val k = keyOf(item)
            key(k) {
                val held = dragKey == k
                var height by remember { mutableIntStateOf(0) }
                row(
                    item, i, held,
                    Modifier
                        .onSizeChanged { height = it.height }
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer {
                            if (held) { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12.dp.toPx() }
                        }
                        .pointerInput(k) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragOrder = current; dragKey = k; dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val list = dragOrder ?: return@detectDragGesturesAfterLongPress
                                    dragOffset += amount.y
                                    val at = list.indexOfFirst { keyOf(it) == k }
                                    val to = when {
                                        dragOffset > height / 2f && at < list.lastIndex -> at + 1
                                        dragOffset < -height / 2f && at > 0 -> at - 1
                                        else -> at
                                    }
                                    if (to != at) {
                                        dragOrder = list.toMutableList().apply { add(to, removeAt(at)) }
                                        dragOffset -= (to - at) * height
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = { dragOrder?.let { if (it != current) onReorder(it) }; dragKey = null; dragOrder = null; dragOffset = 0f },
                                onDragCancel = { dragKey = null; dragOrder = null; dragOffset = 0f },
                            )
                        },
                )
            }
        }
    }
}
