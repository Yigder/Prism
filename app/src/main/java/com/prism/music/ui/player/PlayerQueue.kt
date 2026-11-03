package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import androidx.compose.foundation.border
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
fun InlineQueue(onRevealPlayer: () -> Unit, onHidePlayer: () -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val pc = c.player
    val queue by pc.queue.collectAsState()
    val index by pc.currentIndex.collectAsState()
    val autoplayStart by c.queue.autoplayStart.collectAsState()
    val source by c.queue.source.collectAsState()
    val settings by c.settings.flow.collectAsState()
    // Songs already played sit above Now Playing (#68), a scroll up; the list opens at Now Playing.
    val playedIdx by pc.played.collectAsState()
    val played = playedIdx.takeLast(MAX_PLAYED).mapNotNull { queue.getOrNull(it) }
    val nowItem = if (played.isEmpty()) 0 else played.size + 1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = nowItem)
    LaunchedEffect(index) { if (listState.firstVisibleItemIndex != nowItem) listState.animateScrollToItem(nowItem) }

    val now = queue.getOrNull(index)
    val autoplayFrom = if (autoplayStart > 0) autoplayStart else queue.size
    val upNext by pc.upNext.collectAsState()
    val shuffled by pc.shuffle.collectAsState()
    // In play order, so with shuffle on this is the shuffled order.
    val order = upNext.mapNotNull { queue.getOrNull(it) }
    val userQueued by c.queue.userQueued.collectAsState()
    // What the listener added (Play next, Add to queue) comes first, under its own heading.
    val queued = order.filter { it.song.id in userQueued }
    val upcoming = order.filter { it.index < autoplayFrom && it.song.id !in userQueued }
    val autoplay = order.filter { it.index >= autoplayFrom && it.song.id !in userQueued }

    val controlsOnScroll = remember { ControlsOnScroll(onHide = onHidePlayer, onReveal = onRevealPlayer) }
    controlsOnScroll.reveals = scrollReveals(settings)

    // A song taken out (swiped away or its ×, #149) can be put back for a few seconds.
    var undo by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { kotlinx.coroutines.delay(8_000); undo = null } }
    val remove: (QueueEntry) -> Unit = { entry -> pc.removeForUndo(entry.index)?.let { undo = entry.song.title to it } }

    // A queue made for editing (R11, #159): hold a song to pick several and move or take them out together; each song's
    // ⋯ has the same for one; "Lock" keeps the order still (no drag handles, no swiping away) while you scroll.
    val locked = settings.behavior.queueLocked
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val selecting = selected != null
    val toggle: (QueueEntry) -> Unit = { e -> selected = (selected ?: emptySet()).let { if (e.uid in it) it - e.uid else it + e.uid } }
    val startSelecting: (QueueEntry) -> Unit = { e -> onHidePlayer(); selected = setOf(e.uid) }
    androidx.activity.compose.BackHandler(selecting) { selected = null }
    fun picked(): List<Int> = selected.orEmpty().let { uids -> queue.filter { it.uid in uids }.map { it.index } }
    var menuFor by remember { mutableStateOf<QueueEntry?>(null) }
    var adding by remember { mutableStateOf<List<Song>?>(null) }
    // The current song can be picked too (to add to a playlist), but it never moves or goes.
    menuFor?.let { e ->
        val isNow = e.index == index
        com.prism.music.ui.components.SongActionsSheet(
            e.song,
            extra = if (isNow) listOf(
                com.prism.music.ui.components.SheetItem(PrismIcons.Delete, tr(R.string.clear_everything_after_this), danger = true) { pc.clearAfter(e.index) },
            ) else listOf(
                com.prism.music.ui.components.SheetItem(PrismIcons.PlaylistPlay, tr(R.string.move_up_next)) { pc.moveNext(listOf(e.index)) },
                com.prism.music.ui.components.SheetItem(PrismIcons.QueueMusic, tr(R.string.move_to_the_end)) { pc.moveToEnd(listOf(e.index)) },
                com.prism.music.ui.components.SheetItem(PrismIcons.Close, tr(R.string.remove_from_the_queue)) { remove(e) },
                com.prism.music.ui.components.SheetItem(PrismIcons.Delete, tr(R.string.clear_everything_after_this), danger = true) { pc.clearAfter(e.index) },
            ),
        ) { menuFor = null }
    }
    adding?.let { songs -> com.prism.music.ui.components.PlaylistPicker(songs, tr(R.string.from_your_queue)) { adding = null } }

    // DJ mode: its switch, energy curve and how far the scans have got; each song's key and tempo as badges.
    val dj by c.dj.on.collectAsState()
    val djProgress by c.dj.progress.collectAsState()
    val djRevision by c.dj.revision.collectAsState()
    // Smart Shuffle's picks are marked ✦.
    val smartPicks by c.queue.smartPicks.collectAsState()
    val badge: (Song) -> String? = remember(dj, djRevision, smartPicks) {
        { song ->
            when {
                dj -> if (!c.mixTempo.keyed(song.id)) "…" else djBadge(c.mixTempo.key(song.id), c.mixTempo.djBpm(song.id))
                song.id in smartPicks -> tr(R.string.pick)
                else -> null
            }
        }
    }

    var saving by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (saving) com.prism.music.ui.components.NewPlaylistDialog(onDismiss = { saving = false }, local = !settings.isLoggedIn) { name, privacy ->
        saving = false
        // Signed out, the queue becomes a Prism playlist on the phone (H7).
        if (!settings.isLoggedIn) {
            val list = (listOfNotNull(now) + order).map { it.song }.filter { !it.isLive && !it.isEpisode }.distinctBy { it.id }
            c.prismLibrary.create(name, list)
            android.widget.Toast.makeText(context, tr(R.string.saved_to, com.prism.music.data.model.songCount(list.size), name), android.widget.Toast.LENGTH_SHORT).show()
            return@NewPlaylistDialog
        }
        val songs = (listOfNotNull(now) + order).map { it.song.id }.distinct().take(300)
        c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val id = runCatching { c.ytm.createPlaylist(name, songs, privacy = privacy) }.getOrNull()
            android.widget.Toast.makeText(context, if (id != null) tr(R.string.saved_to, com.prism.music.data.model.songCount(songs.size), name) else tr(R.string.couldn_t_save_the_queue), android.widget.Toast.LENGTH_SHORT).show()
            if (id != null) c.library.refreshCollections()
        }
    }

    Column(modifier.fillMaxWidth()) {
        // Pulled down by its title row, the queue closes (#149); the list itself keeps scrolling as before.
        val pullToClose = if (onClose == null) Modifier else Modifier.pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total > 90f) onClose() },
            ) { change, amount -> change.consume(); total += amount }
        }
        if (selecting) {
            val n = selected.orEmpty().size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trp(R.plurals.n_songs_picked, n), style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
                QueueChip(tr(R.string.done), selected = true) { selected = null }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                val enabled = n > 0
                QueueChip(tr(R.string.move_up_next)) { if (enabled) { pc.moveNext(picked()); selected = null } }
                QueueChip(tr(R.string.move_to_the_end)) { if (enabled) { pc.moveToEnd(picked()); selected = null } }
                QueueChip(tr(R.string.add_to_playlist)) { if (enabled) adding = pc.songsAt(picked()).filter { !it.isLive && !it.isEpisode } }
                QueueChip(tr(R.string.remove)) { if (enabled) { pc.removeMany(picked()); selected = null } }
            }
        } else Row(pullToClose, verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.queue), style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            if (order.size > 1) {
                QueueChip("DJ", selected = dj) { pc.setDjMode(!dj) }
                Spacer(Modifier.width(8.dp))
            }
            if (queue.size > 1) QueueChip(tr(R.string.save)) { saving = true }
            if (order.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                QueueChip(tr(R.string.clear)) { pc.clearUpcoming() }
            }
            Spacer(Modifier.width(8.dp))
            // The lock: the order stays put while you scroll (#159).
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (locked) Color.White else Color.White.copy(alpha = 0.12f))
                    .clickable(onClickLabel = if (locked) tr(R.string.unlock_the_order) else tr(R.string.lock_the_order)) { c.settings.setQueueLocked(!locked) },
                contentAlignment = Alignment.Center,
            ) { Icon(PrismIcons.Lock, if (locked) tr(R.string.order_locked) else tr(R.string.lock_the_order), tint = if (locked) Color.Black else Color.White, modifier = Modifier.size(17.dp)) }
        }
        if (dj) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (flow in com.prism.music.playback.DjFlow.entries) {
                    QueueChip(flow.label, selected = settings.djFlow == flow) { c.settings.setDjFlow(flow) }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // Only an order it has: how far the scans are, songs waiting for Wi-Fi (with mobile data offered), or how many are in order.
            val (status, offerData) = com.prism.music.playback.DjStatus.of(djProgress)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f, fill = false))
                if (offerData) {
                    Spacer(Modifier.width(8.dp))
                    // For this DJ set only (until Prism closes): "Measure songs on mobile data" stays as the listener set it.
                    QueueChip(tr(R.string.use_mobile_data)) { c.dj.rescan(allowData = true) }
                }
            }
        }
        undo?.let { (title, putBack) ->
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f))
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.removed, title), style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    tr(R.string.undo), style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { putBack(); undo = null }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // "Queue headings" off (#197): one continuous list of songs (Autoplay keeps its heading: it holds the switch).
        val headings = settings.view.queueHeadings
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().nestedScroll(controlsOnScroll), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!headings) item(key = "h-gap") { Spacer(Modifier.height(8.dp)) }
            if (played.isNotEmpty()) {
                if (headings) item(key = "h-played") { Heading(tr(R.string.played), Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                items(played, key = { "played-" + it.uid }) { entry ->
                    QueueRow(
                        entry.song, isCurrent = false, onClick = { if (selecting) toggle(entry) else pc.skipTo(entry.index) },
                        onRemove = if (selecting) null else { { remove(entry) } },
                        modifier = Modifier.alpha(0.6f),
                        pick = selected?.let { entry.uid in it }, onLongClick = { startSelecting(entry) }, onMore = { menuFor = entry }, swipeable = !locked,
                    )
                }
            }
            if (now != null) {
                if (headings) item(key = "h-now") { Heading(tr(R.string.now_playing), Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                item(key = "now-" + now.uid) {
                    QueueRow(
                        now.song, isCurrent = true, onClick = { if (selecting) toggle(now) }, onRemove = null, badge = badge(now.song),
                        pick = selected?.let { now.uid in it }, onLongClick = { startSelecting(now) }, onMore = { menuFor = now },
                    )
                }
            }
            val rows = SectionRows(selected, toggle, startSelecting, { menuFor = it }, locked, onDragStart = onHidePlayer)
            if (queued.isNotEmpty()) {
                if (headings) item(key = "h-queued") { Heading(tr(R.string.next_in_queue), Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                queueSection(queued, shuffled, badge = badge, onRemove = remove, rows = rows)
            }
            if (upcoming.isNotEmpty()) {
                if (headings) item(key = "h-next") { Heading(tr(R.string.next_from, source.title.ifBlank { now?.song?.album?.title ?: tr(R.string.queue) }), Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                queueSection(upcoming, shuffled, badge = badge, onRemove = remove, rows = rows)
            }
            if (settings.autoplay || autoplay.isNotEmpty()) {
                item(key = "h-auto") { AutoplayHeading(autoplay.isNotEmpty()) }
                queueSection(autoplay, shuffled, badge = badge, onRemove = remove, rows = rows)
            }
        }
    }
}

/** How many played songs the queue lists above Now Playing (the latest ones). */
private const val MAX_PLAYED = 100

/**
 * A section of the queue whose songs can be dragged: in the list itself, or while [shuffled] (the queue then shows the
 * shuffled play order) in the play order, beside the song shown next to it in this section.
 */
/** How a section's rows take part in editing the queue: picking several, each one's ⋯, the lock, a drag starting. */
private class SectionRows(
    val selected: Set<String>?,
    val toggle: (QueueEntry) -> Unit,
    val startSelecting: (QueueEntry) -> Unit,
    val onMore: (QueueEntry) -> Unit,
    val locked: Boolean,
    val onDragStart: () -> Unit,
)

private fun LazyListScope.queueSection(
    entries: List<QueueEntry>,
    shuffled: Boolean,
    badge: (Song) -> String? = { null },
    onRemove: (QueueEntry) -> Unit,
    rows: SectionRows,
) {
    val selecting = rows.selected != null
    items(entries, key = { it.uid }) { entry ->
        val pc = LocalContainer.current.player
        val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        var position by remember(entry.index) { mutableStateOf(entry.index) }
        // Read at each step of a drag, not as they were when it started (the rows move under the finger).
        val section by androidx.compose.runtime.rememberUpdatedState(entries)
        val current by androidx.compose.runtime.rememberUpdatedState(entry)
        val shuffleOn by androidx.compose.runtime.rememberUpdatedState(shuffled)
        /** Moves the song one place down ([down]) or up; false at the section's end. */
        fun step(down: Boolean): Boolean {
            if (!shuffleOn) {
                if (!down && position == 0) return false
                val to = if (down) position + 1 else position - 1
                pc.move(position, to); position = to
                return true
            }
            val at = section.indexOfFirst { it.uid == current.uid }
            val beside = section.getOrNull(if (down) at + 1 else at - 1) ?: return false
            pc.moveInPlayOrder(current.index, beside.index, after = down)
            return true
        }
        QueueRow(
            entry.song,
            isCurrent = false,
            onClick = { if (selecting) rows.toggle(entry) else pc.skipTo(entry.index) },
            onRemove = if (selecting) null else { { onRemove(entry) } },
            badge = badge(entry.song),
            modifier = Modifier.offset { IntOffset(0, drag.roundToInt()) },
            pick = rows.selected?.let { entry.uid in it },
            onLongClick = { rows.startSelecting(entry) },
            onMore = { rows.onMore(entry) },
            swipeable = !rows.locked,
            // Locked (or picking songs), there's nothing to grab: scrolling can't move a song.
            dragHandle = if (rows.locked || selecting) null else Modifier.pointerInput(entry.uid) {
                detectDragGestures(
                    // The player's controls go while a song is moved, so the list has the room (R11).
                    onDragStart = { rows.onDragStart() },
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount.y
                    // Trade places with a neighbour each time the row crosses one.
                    if (drag > rowPx * 0.6f) { if (step(down = true)) drag -= rowPx else drag = rowPx * 0.6f }
                    else if (drag < -rowPx * 0.6f) { if (step(down = false)) drag += rowPx else drag = -rowPx * 0.6f }
                }
            },
        )
    }
}

/** A small action at the top of the queue ("Save", "Clear"; [selected] for a switch like "DJ"). */
@Composable
private fun QueueChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** A scanned song's key on the Camelot wheel and its tempo ("8A · 124"), as DJ mode sees them (what's clear of them). */
private fun djBadge(key: com.prism.music.playback.MusicKey?, bpmValue: Double?): String? {
    val k = key?.takeIf { it.sure }?.camelot
    val bpm = bpmValue?.roundToInt()?.toString()
    return listOfNotNull(k, bpm).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Heading(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth())
}

@Composable
private fun AutoplayHeading(hasTracks: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(PrismIcons.Autoplay, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(tr(R.string.autoplay), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                if (hasTracks) tr(R.string.similar_music_plays_next) else tr(R.string.similar_music_will_play_after_your),
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
    badge: String? = null,
    /** Picking songs: whether this one is picked; null when not picking. */
    pick: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    /** The song's ⋯ (its menu with the queue's own actions). */
    onMore: (() -> Unit)? = null,
    /** Swiping it away is on (off while the order is locked). */
    swipeable: Boolean = true,
) {
    // Swiped left past a third of the row, it slides out and goes (#149); short of that it springs back. Moved in the
    // drawing layer, so a swipe doesn't lay the row out again each frame.
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    // The swipe outlives a recomposition (it's keyed on the song), so it calls the latest onRemove: the row's place
    // in the queue changes when a song above it goes, and an old one would take out its neighbour.
    val removeNow by androidx.compose.runtime.rememberUpdatedState(onRemove)
    val swipe = if (onRemove == null || !swipeable) Modifier else Modifier.pointerInput(song.id) {
        val goAt = size.width / 3f
        detectHorizontalDragGestures(
            onDragEnd = {
                scope.launch {
                    if (slide.value <= -goAt) {
                        haptics.gestureEnd()
                        slide.animateTo(-size.width.toFloat(), androidx.compose.animation.core.tween(160))
                        removeNow?.invoke()
                    } else slide.animateTo(0f)
                }
            },
            onDragCancel = { scope.launch { slide.animateTo(0f) } },
        ) { change, d ->
            change.consume()
            val after = (slide.value + d).coerceAtMost(0f)
            if ((slide.value > -goAt) != (after > -goAt)) haptics.threshold()
            scope.launch { slide.snapTo(after) }
        }
    }
    Row(
        modifier.fillMaxWidth().then(swipe)
            .graphicsLayer {
                translationX = slide.value
                alpha = 1f - (-slide.value / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.7f)
            }
            .clip(RoundedCornerShape(8.dp))
            .then(if (pick == true) Modifier.background(Color.White.copy(alpha = 0.1f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pick != null) {
            if (pick) Icon(PrismIcons.CheckCircle, tr(R.string.picked), tint = Color.White, modifier = Modifier.size(22.dp))
            else Box(Modifier.size(22.dp).padding(2.dp).border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape))
            Spacer(Modifier.width(8.dp))
        } else if (dragHandle != null) {
            Icon(PrismIcons.DragHandle, tr(R.string.drag_to_reorder), tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp).offset(x = (-4).dp).then(dragHandle))
            Spacer(Modifier.width(4.dp))
        }
        AsyncImage(hiRes(song.thumbnail, 226), null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) {
            Text(
                badge, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (isCurrent) {
            Icon(PrismIcons.Equalizer, tr(R.string.widget_now_playing), tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        if (onMore != null && pick == null) Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.More, tr(R.string.more_for, song.title), tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
        if (onRemove != null) Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Close, tr(R.string.remove), tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
}
