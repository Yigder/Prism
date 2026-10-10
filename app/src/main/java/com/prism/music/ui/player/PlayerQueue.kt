package com.prism.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.playback.QueueEntry
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The queue, inside the player: now playing, what's next, then autoplay. */
@Composable
fun InlineQueue(onRevealPlayer: () -> Unit, onHidePlayer: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val pc = c.player
    val queue by pc.queue.collectAsState()
    val index by pc.currentIndex.collectAsState()
    val autoplayStart by c.queue.autoplayStart.collectAsState()
    val source by c.queue.source.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(index) { if (listState.firstVisibleItemIndex != 0) listState.animateScrollToItem(0) }

    val now = queue.getOrNull(index)
    val autoplayFrom = if (autoplayStart > 0) autoplayStart else queue.size
    val upNext by pc.upNext.collectAsState()
    val shuffled by pc.shuffle.collectAsState()
    // In play order, so with shuffle on this is the shuffled order.
    val order = upNext.mapNotNull { queue.getOrNull(it) }
    val upcoming = order.filter { it.index < autoplayFrom }
    val autoplay = order.filter { it.index >= autoplayFrom }

    val controlsOnScroll = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    acc = if ((acc > 0) == (available.y > 0)) acc + available.y else available.y
                    if (acc < -40f) { onHidePlayer(); acc = 0f } else if (acc > 40f) { onRevealPlayer(); acc = 0f }
                }
                return Offset.Zero
            }
        }
    }

    var saving by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (saving) com.prism.music.ui.components.NewPlaylistDialog(onDismiss = { saving = false }) { name, privacy ->
        saving = false
        val songs = (listOfNotNull(now) + order).map { it.song.id }.distinct().take(300)
        c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val id = runCatching { c.ytm.createPlaylist(name, songs, privacy = privacy) }.getOrNull()
            android.widget.Toast.makeText(context, if (id != null) "Saved ${songs.size} songs to $name" else "Couldn't save the queue", android.widget.Toast.LENGTH_SHORT).show()
            if (id != null) c.library.refreshCollections()
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            if (settings.isLoggedIn && queue.size > 1) QueueChip("Save") { saving = true }
            if (order.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                QueueChip("Clear") { pc.clearUpcoming() }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().nestedScroll(controlsOnScroll), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (now != null) {
                item(key = "h-now") { Heading("Now Playing", Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                item(key = "now-" + now.uid) { QueueRow(now.song, isCurrent = true, onClick = {}, onRemove = null) }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "h-next") { Heading("Next from: ${source.title.ifBlank { now?.song?.album?.title ?: "Queue" }}", Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                queueSection(upcoming, reorder = !shuffled) { from, to -> pc.move(from, to) }
            }
            if (settings.autoplay || autoplay.isNotEmpty()) {
                item(key = "h-auto") { AutoplayHeading(autoplay.isNotEmpty()) }
                queueSection(autoplay, reorder = !shuffled) { from, to -> pc.move(from, to) }
            }
        }
    }
}

/** [reorder] is off while shuffled: dragging moves songs in the list, not in the shuffled order. */
private fun LazyListScope.queueSection(rows: List<QueueEntry>, reorder: Boolean = true, onMove: (Int, Int) -> Unit) {
    items(rows, key = { it.uid }) { entry ->
        val pc = LocalContainer.current.player
        val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        var position by remember(entry.index) { mutableStateOf(entry.index) }
        QueueRow(
            entry.song,
            isCurrent = false,
            onClick = { pc.skipTo(entry.index) },
            onRemove = { pc.removeAt(entry.index) },
            modifier = Modifier.offset { IntOffset(0, drag.roundToInt()) },
            dragHandle = if (!reorder) Modifier else Modifier.pointerInput(entry.uid) {
                detectDragGestures(
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount.y
                    // Trade places with a neighbour each time the row crosses one.
                    if (drag > rowPx * 0.6f) { onMove(position, position + 1); position += 1; drag -= rowPx }
                    else if (drag < -rowPx * 0.6f && position > 0) { onMove(position, position - 1); position -= 1; drag += rowPx }
                }
            },
        )
    }
}

/** A small action at the top of the queue ("Save", "Clear"). */
@Composable
private fun QueueChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Heading(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth())
}

@Composable
private fun AutoplayHeading(hasTracks: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AllInclusive, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Autoplay", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                if (hasTracks) "Similar music plays next" else "Similar music will play after your queue",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    dragHandle: Modifier? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp).offset(x = (-4).dp).then(dragHandle))
            Spacer(Modifier.width(4.dp))
        }
        AsyncImage(hiRes(song.thumbnail, 226), null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isCurrent) {
            Icon(Icons.Rounded.GraphicEq, "Now playing", tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        if (onRemove != null) Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Remove", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
}
