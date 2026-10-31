package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import com.prism.music.ui.player.drawRibbonStrands
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.PlayerTimes
import com.prism.music.data.prefs.PlayerTitleAlign
import com.prism.music.data.prefs.ScrubberColor
import com.prism.music.data.prefs.SessionButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlin.math.roundToInt

/*
 * Settings → Player, the parts that came with 1.7.4 ("the player, your way"): its layout, its buttons, the order of what's
 * under the title, the title and times, and gestures. Every choice shows what it does as a small example, drawn the way
 * the player is (always dark, white type), and the preview pinned at the top of the page shows the whole player as it'll be.
 */

private val PlayerDark = Color(0xFF17171C)
private val Ink = Color.White.copy(alpha = 0.85f)
private val Faint = Color.White.copy(alpha = 0.28f)

// ---------------------------------------------------------------------------- Layout

@Composable
fun PlayerLayoutGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val layoutInfo = when (s.playerLayout) {
        PlayerLayout.MINIMAL -> tr(R.string.minimal_swipe_the_cover_for_the)
        PlayerLayout.COVER -> tr(R.string.cover_tap_the_cover_for_the)
        else -> ""
    } + tr(R.string.tablets_and_phones_turned_sideways_keep)
    Group(tr(R.string.layout), info = layoutInfo) {
        ChoiceCards(
            PlayerLayout.entries, s.playerLayout,
            title = { it.label }, detail = { it.note },
            onSelect = { prefs.setPlayerLayout(it) },
        ) { LayoutSample(it) }
        if (s.playerLayout == PlayerLayout.VINYL) Toggle(tr(R.string.tonearm), tr(R.string.swings_onto_the_record_while_it), s.vinylTonearm) { prefs.setVinylTonearm(it) }
    }
}

/** A tiny dark phone showing where things sit in [layout]. */
@Composable
private fun LayoutSample(layout: PlayerLayout) {
    val light = Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (layout) {
                PlayerLayout.LYRICS -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                        Spacer(Modifier.width(5.dp))
                        Column { Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(2.dp)); Bar(0.4f, 3.dp, Faint) }
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(0.7f to Faint, 0.85f to Ink, 0.6f to Faint, 0.75f to Faint).forEach { (w, c) -> Bar(w, 4.dp, c, Alignment.Start); Spacer(Modifier.height(6.dp)) }
                }
                PlayerLayout.VINYL -> {
                    Box(Modifier.fillMaxWidth(0.52f).aspectRatio(1f)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF0B0B0D)).border(0.6.dp, Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize(0.66f).clip(CircleShape).border(0.5.dp, Color.White.copy(alpha = 0.07f), CircleShape))
                            Box(Modifier.fillMaxSize(0.42f).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp).size(2.dp, 34.dp).rotate(22f).background(Color(0xFFCFCFD6)))
                    }
                    Spacer(Modifier.height(6.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                PlayerLayout.CASSETTE -> {
                    Spacer(Modifier.height(10.dp))
                    // The tape: the cover as its label, two reels in the window.
                    Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1.56f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E23))) {
                        Box(
                            Modifier.padding(4.dp).fillMaxWidth().fillMaxHeight(0.62f).clip(RoundedCornerShape(2.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(0.62f).clip(CircleShape).background(Color(0xFF0C0C0E)).padding(horizontal = 6.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) { repeat(2) { Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF4A372C)).border(2.dp, Color(0xFFE9E9EC), CircleShape)) } }
                        }
                    }
                    Spacer(Modifier.height(8.dp)); Bar(0.6f, 4.dp, Ink); Spacer(Modifier.height(6.dp)); Transport()
                }
                else -> {
                    val cover = when (layout) { PlayerLayout.COVER -> 0.7f; PlayerLayout.MINIMAL -> 0.52f; else -> 0.46f }
                    Box(Modifier.fillMaxWidth(cover).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
                    Spacer(Modifier.height(6.dp))
                    Bar(0.6f, 4.dp, Ink, Alignment.Start); Spacer(Modifier.height(3.dp)); Bar(0.4f, 3.dp, Faint, Alignment.Start)
                    when (layout) {
                        PlayerLayout.STANDARD -> {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Faint)) { Box(Modifier.fillMaxWidth(0.4f).height(2.dp).background(light)) }
                            Spacer(Modifier.height(5.dp)); Transport()
                        }
                        PlayerLayout.MINIMAL -> { Spacer(Modifier.height(6.dp)); Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White)) }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Previous, play and next, small. */
@Composable
private fun Transport() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Ink))
    }
}

/** A rounded bar [fraction] of the width: a line of text in a sample. */
@Composable
private fun Bar(fraction: Float, height: Dp, color: Color, align: Alignment.Horizontal = Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (align == Alignment.Start) Alignment.CenterStart else Alignment.Center) {
        Box(Modifier.fillMaxWidth(fraction).height(height).clip(CircleShape).background(color))
    }
}

/** The player's dark, for an example to sit on. */
@Composable
private fun SampleScreen(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), PlayerDark))), content = content)
}

// ---------------------------------------------------------------------------- Buttons

@Composable
fun PlayerButtonsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.playerButtons
    Group(tr(R.string.buttons), info = tr(R.string.pick_to_for_the_row_under, PlayerButton.MIN, PlayerButton.MAX)) {
        // The row as it'll be, in a capsule or apart (1.7.8, #155): each as its own card.
        ChoiceCards(listOf(true, false), s.view.buttonsPill, { if (it) tr(R.string.in_a_capsule) else tr(R.string.apart) }, { if (it) tr(R.string.one_glass_capsule_as_now) else tr(R.string.spread_across_the_row_as_in) }, onSelect = { prefs.setButtonsPill(it) }) { pill ->
            SampleScreen(Modifier.padding(6.dp).fillMaxWidth().height(52.dp)) {
                Row(
                    if (pill) Modifier.align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 8.dp, vertical = 6.dp)
                    else Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalArrangement = if (pill) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) { chosen.forEach { Icon(buttonIcon(it), it.label, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.85f)) } }
            }
        }
        // The picked ones, in their order, to drag; then the rest to pick from.
        com.prism.music.ui.components.ReorderList(chosen, { it }, { prefs.setPlayerButtons(it) }) { b, i, held, mod ->
            OrderRow(
                b.label, buttonIcon(b), true,
                canCheck = chosen.size > PlayerButton.MIN,
                onCheck = { v -> if (!v) prefs.setPlayerButtons(chosen - b) },
                onUp = if (i > 0) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < chosen.lastIndex) ({ prefs.setPlayerButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        PlayerButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, buttonIcon(b), false, canCheck = chosen.size < PlayerButton.MAX,
                onCheck = { v -> if (v) prefs.setPlayerButtons(chosen + b) }, onUp = null, onDown = null,
            )
        }
        if (chosen != PlayerButton.DEFAULT) com.prism.music.ui.components.PrismTextButton({ prefs.setPlayerButtons(PlayerButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
            Text(tr(R.string.back_to_lyrics_video_and_up))
        }
    }
}

/** The buttons beside play outside Prism: the notification, the lock screen, Android Auto (Controls & gestures). */
@Composable
fun SessionButtonsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.sessionButtons
    Group(tr(R.string.notification_and_car), info = tr(R.string.your_phone_s_notification_and_lock)) {
        // The notification as it'll look: previous, play, next and the first two beside them.
        SampleScreen(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(56.dp)) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                chosen.getOrNull(0)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
                Icon(PrismIcons.Previous, null, Modifier.size(22.dp), tint = Color.White)
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(PrismIcons.Pause, null, Modifier.size(16.dp), tint = Color.Black) }
                Icon(PrismIcons.Next, null, Modifier.size(22.dp), tint = Color.White)
                chosen.getOrNull(1)?.let { Icon(sessionIcon(it), it.label, Modifier.size(20.dp), tint = Ink) }
            }
        }
        com.prism.music.ui.components.ReorderList(chosen, { it }, { prefs.setSessionButtons(it) }) { b, i, held, mod ->
            OrderRow(
                b.label, sessionIcon(b), true, canCheck = true,
                onCheck = { v -> if (!v) prefs.setSessionButtons(chosen - b) },
                onUp = if (i > 0) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < chosen.lastIndex) ({ prefs.setSessionButtons(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        SessionButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, sessionIcon(b), false, canCheck = true,
                onCheck = { v -> if (v) prefs.setSessionButtons(chosen + b) }, onUp = null, onDown = null,
            )
        }
        if (chosen != SessionButton.DEFAULT) com.prism.music.ui.components.PrismTextButton({ prefs.setSessionButtons(SessionButton.DEFAULT) }, Modifier.padding(start = 12.dp)) {
            Text(tr(R.string.back_to_prism_s_buttons))
        }
    }
}

/**
 * A tickable row with its icon, and up / down to move it while it's ticked; in a [com.prism.music.ui.components.ReorderList]
 * it also takes the list's [modifier] (hold to carry it) and shows a [grip] to drag it at once.
 */
@Composable
private fun OrderRow(
    label: String, icon: ImageVector, on: Boolean, canCheck: Boolean,
    onCheck: (Boolean) -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?,
    modifier: Modifier = Modifier, grip: Modifier? = null, held: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrismCheck(on, enabled = canCheck, label = label, onChange = onCheck)
        Icon(icon, null, Modifier.size(20.dp), tint = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
        if (on && !held) {
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(PrismIcons.ChevronUp, tr(R.string.move_up_2, label)) }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(PrismIcons.ChevronDown, tr(R.string.move_down_2, label)) }
        } else if (held) Spacer(Modifier.width(96.dp))
        if (grip != null) com.prism.music.ui.components.DragGrip(grip.padding(start = 4.dp, end = 8.dp), tr(R.string.drag, label))
    }
}

fun buttonIcon(b: PlayerButton): ImageVector = when (b) {
    PlayerButton.LYRICS -> PrismIcons.Lyrics
    PlayerButton.VIDEO -> PrismIcons.Video
    PlayerButton.QUEUE -> PrismIcons.Queue
    PlayerButton.OUTPUT -> PrismIcons.Cast
    PlayerButton.SLEEP -> PrismIcons.Moon
    PlayerButton.LIKE -> PrismIcons.Heart
    PlayerButton.DISLIKE -> PrismIcons.ThumbDown
    PlayerButton.ADD_TO_PLAYLIST -> PrismIcons.PlaylistAdd
    PlayerButton.SHARE -> PrismIcons.Share
    PlayerButton.SING -> PrismIcons.Mic
    PlayerButton.DJ -> PrismIcons.Turntable
    PlayerButton.EQUALIZER -> PrismIcons.Equalizer
    PlayerButton.DOWNLOAD -> PrismIcons.Download
    PlayerButton.RADIO -> PrismIcons.Radio
    PlayerButton.SPEED -> PrismIcons.Speed
}

private fun sessionIcon(b: SessionButton): ImageVector = when (b) {
    SessionButton.LIKE -> PrismIcons.Heart
    SessionButton.SHUFFLE -> PrismIcons.Shuffle
    SessionButton.REPEAT -> PrismIcons.Repeat
    SessionButton.CAR_LYRICS -> PrismIcons.Lyrics
    SessionButton.RADIO -> PrismIcons.Radio
    SessionButton.DISLIKE -> PrismIcons.ThumbDown
    SessionButton.BACK_10 -> PrismIcons.Backward
    SessionButton.AHEAD_30 -> PrismIcons.Forward
}

// ---------------------------------------------------------------------------- Arrange

/** The parts under the title, top to bottom: move them, and tick the ones that can be hidden. */
@Composable
fun PlayerArrangeGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val order = s.playerDeck
    Group(tr(R.string.under_the_title), info = tr(R.string.top_to_bottom_as_they_sit)) {
        com.prism.music.ui.components.ReorderList(order, { it }, { prefs.setPlayerDeck(it) }) { part, i, held, mod ->
            val shown = when (part) { DeckPart.STRIP -> s.lyricsOnPlayer; DeckPart.VOLUME -> !s.hideVolumeBar; else -> true }
            val hideable = part == DeckPart.STRIP || part == DeckPart.VOLUME
            OrderRow(
                part.label, deckIcon(part), shown || !hideable, canCheck = hideable,
                onCheck = { v -> if (part == DeckPart.STRIP) prefs.setLyricsOnPlayer(v) else prefs.setHideVolumeBar(!v) },
                onUp = if (i > 0) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }) else null,
                onDown = if (i < order.lastIndex) ({ prefs.setPlayerDeck(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }) else null,
                modifier = mod, grip = Modifier.dragHandle(), held = held,
            )
        }
        if (order != DeckPart.entries) com.prism.music.ui.components.PrismTextButton({ prefs.setPlayerDeck(DeckPart.entries) }, Modifier.padding(start = 12.dp)) {
            Text(tr(R.string.back_to_prism_s_order))
        }
        // GitHub #116: a volume just for Prism, for phones whose volume buttons don't work (or to keep other apps' as they are).
        Label(
            tr(R.string.the_volume_slider_changes),
            info = s.volumeControl.label + ": " + if (s.volumeControl == com.prism.music.data.prefs.VolumeControl.PRISM)
                tr(R.string.only_prism_gets_quieter_or_louder)
            else tr(R.string.like_the_phone_s_volume_buttons),
        )
        Chips(com.prism.music.data.prefs.VolumeControl.entries, s.volumeControl, { it.label }) { v ->
            prefs.setVolumeControl(v)
            // Picking Prism's own volume is picking the slider: show it if it was hidden.
            if (v == com.prism.music.data.prefs.VolumeControl.PRISM && s.hideVolumeBar) prefs.setHideVolumeBar(false)
        }
    }
}

private fun deckIcon(p: DeckPart): ImageVector = when (p) {
    DeckPart.STRIP -> PrismIcons.Lyrics
    DeckPart.SCRUBBER -> PrismIcons.Timer
    DeckPart.TRANSPORT -> PrismIcons.PlayCircle
    DeckPart.VOLUME -> PrismIcons.VolumeUp
    DeckPart.BUTTONS -> PrismIcons.More
}

// ---------------------------------------------------------------------------- Title, times and the progress bar's color

@Composable
fun PlayerTitleGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.title_and_times)) {
        ChoiceCards(
            PlayerTitleAlign.entries, s.playerTitleAlign, title = { it.label }, detail = { if (it == PlayerTitleAlign.START) tr(R.string.under_the_cover_s_left_edge) else tr(R.string.in_the_middle) },
            onSelect = { prefs.setPlayerTitleAlign(it) },
        ) { TitleSample(it) }
        Label(tr(R.string.title_size))
        Chips(com.prism.music.data.prefs.PlayerTitleSize.entries, s.playerTitleSize, { it.label }) { prefs.setPlayerTitleSize(it) }
        Label(tr(R.string.title_weight), info = tr(R.string.light_is_prism_s_own_and))
        ChoiceCards(
            com.prism.music.data.prefs.PlayerTitleWeight.entries, s.type.playerTitleWeight, title = { it.label }, detail = { null },
            onSelect = { prefs.setPlayerTitleWeight(it) },
        ) { TitleSample(s.playerTitleAlign, it) }
        Toggle(tr(R.string.scrolling_titles), tr(R.string.a_long_title_scrolls_across_off), s.playerTitleScroll) { prefs.setPlayerTitleScroll(it) }
        Label(tr(R.string.times_under_the_progress_bar))
        ChoiceCards(
            PlayerTimes.entries, s.playerTimes, title = { it.label }, detail = { null }, onSelect = { prefs.setPlayerTimes(it) },
        ) { TimesSample(it) }
        Label(tr(R.string.progress_bar_color))
        ChoiceCards(
            ScrubberColor.entries, s.scrubberColor, title = { it.label },
            detail = {
                when (it) {
                    ScrubberColor.SPECTRUM -> tr(R.string.the_split_light)
                    ScrubberColor.ACCENT -> tr(R.string.your_accent_color)
                    ScrubberColor.WHITE -> tr(R.string.plain_white)
                    ScrubberColor.COVER -> tr(R.string.the_song_s_cover_s_color)
                }
            },
            onSelect = { prefs.setScrubberColor(it) },
        ) { BarSample(it) }
        // Thickness and the knob (1.7.4): the waveform keeps its own height.
        SliderSetting(
            tr(R.string.progress_bar_thickness), s.scrubberThickness, 0.6f..2f, { worded(if (it < 0.85f) tr(R.string.thinner) else if (it <= 1.15f) tr(R.string.as_the_style) else tr(R.string.thicker), it) },
            info = if (s.scrubberStyle == com.prism.music.data.prefs.ScrubberStyle.WAVE) tr(R.string.the_waveform_keeps_its_own_height) else null,
        ) {
            prefs.setScrubberThickness(if (it in 0.93f..1.07f) 1f else it)
        }
        Label(tr(R.string.knob_at_the_playhead))
        ChoiceCards(
            com.prism.music.data.prefs.ScrubberThumb.entries, s.scrubberThumb, title = { it.label },
            detail = { if (it == com.prism.music.data.prefs.ScrubberThumb.AUTO) tr(R.string.a_dot_with_the_thumb_style) else null },
            onSelect = { prefs.setScrubberThumb(it) },
        ) { KnobSample(it, s.scrubberThickness) }
        // Reddit, 1.7.9.1: the volume bar as thick as the progress bar (its style's height and this thickness).
        Toggle(tr(R.string.volume_bar_like_the_progress_bar), tr(R.string.same_style_and_thickness), s.volumeMatchesProgress) {
            prefs.setVolumeMatchesProgress(it)
        }
    }
}

@Composable
private fun KnobSample(knob: com.prism.music.data.prefs.ScrubberThumb, thickness: Float) {
    val light = Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(40.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth().height(16.dp)) {
            val h = 4.dp.toPx() * thickness
            val y = size.height / 2
            val x = size.width * 0.55f
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(x, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val r = 6.dp.toPx()
            when (knob) {
                com.prism.music.data.prefs.ScrubberThumb.DOT, com.prism.music.data.prefs.ScrubberThumb.AUTO ->
                    if (knob == com.prism.music.data.prefs.ScrubberThumb.DOT) drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y))
                com.prism.music.data.prefs.ScrubberThumb.LINE -> drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                com.prism.music.data.prefs.ScrubberThumb.RING -> {
                    drawCircle(Color.Black.copy(alpha = 0.6f), r, androidx.compose.ui.geometry.Offset(x, y))
                    drawCircle(Color.White, r, androidx.compose.ui.geometry.Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                com.prism.music.data.prefs.ScrubberThumb.NONE -> {}
            }
        }
    }
}

// ---------------------------------------------------------------------------- Visualizer

/**
 * The visualizer (1.7.4): off unless picked, since it's the one part of the player that works on every frame. It moves
 * with Prism's own sound (no microphone), only while the player is open and playing.
 */
@Composable
fun PlayerVisualizerGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.visualizer), info = tr(R.string.it_listens_to_prism_s_own)) {
        ChoiceCards(
            com.prism.music.data.prefs.VisualizerStyle.entries, s.visualizer, title = { it.label }, detail = { it.note },
            onSelect = { prefs.setVisualizer(it) },
        ) { VisualizerSample(it, s.visualizerColor) }
        if (s.visualizer != com.prism.music.data.prefs.VisualizerStyle.OFF) {
            Label(tr(R.string.visualizer_color))
            Chips(ScrubberColor.entries, s.visualizerColor, { it.label }) { prefs.setVisualizerColor(it) }
            // Why it doesn't move: a state, so it stays in view.
            if (s.reduceMotion) Note(tr(R.string.reduce_motion_is_on_so_it))
        }
    }
}

@Composable
private fun VisualizerSample(style: com.prism.music.data.prefs.VisualizerStyle, color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    // A still frame of each, from a made-up spectrum.
    val bands = remember { FloatArray(20) { i -> (0.85f - i * 0.025f) * (0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 1.7f))) } }
    SampleScreen(Modifier.fillMaxWidth().height(96.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val cover = size.height * 0.5f
            val cx = size.width / 2
            val coverTop = size.height * 0.06f
            fun coverRect() = drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59))),
                androidx.compose.ui.geometry.Offset(cx - cover / 2, coverTop), androidx.compose.ui.geometry.Size(cover, cover), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
            when (style) {
                com.prism.music.data.prefs.VisualizerStyle.GLOW -> drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2), cover * 1.3f),
                    cover * 1.3f, androidx.compose.ui.geometry.Offset(cx, coverTop + cover / 2),
                )
                com.prism.music.data.prefs.VisualizerStyle.RING -> for (k in 3 downTo 1) {
                    val g = 3.dp.toPx() * k
                    drawRoundRect(
                        fill, androidx.compose.ui.geometry.Offset(cx - cover / 2 - g, coverTop - g), androidx.compose.ui.geometry.Size(cover + 2 * g, cover + 2 * g),
                        androidx.compose.ui.geometry.CornerRadius(4.dp.toPx() + g), alpha = 0.35f / k, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                else -> {}
            }
            coverRect()
            val barY = size.height * 0.78f
            when (style) {
                com.prism.music.data.prefs.VisualizerStyle.BARS -> {
                    val w = size.width / bands.size
                    bands.forEachIndexed { i, v ->
                        val h = size.height * 0.4f * v
                        drawRoundRect(fill, androidx.compose.ui.geometry.Offset(i * w + w * 0.15f, size.height - h), androidx.compose.ui.geometry.Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.35f), alpha = 0.5f)
                    }
                }
                com.prism.music.data.prefs.VisualizerStyle.WAVE -> {
                    val path = androidx.compose.ui.graphics.Path()
                    val mid = size.height * 0.68f
                    val step = size.width / (bands.size - 1)
                    bands.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, mid - v * 10.dp.toPx()) else path.lineTo(i * step, mid - v * 10.dp.toPx()) }
                    bands.indices.reversed().forEach { i -> path.lineTo(i * step, mid + bands[i] * 10.dp.toPx()) }
                    path.close()
                    drawPath(path, fill, alpha = 0.35f)
                }
                else -> {}
            }
            // The progress bar (thicker and glowing for Pulse).
            val pulse = style == com.prism.music.data.prefs.VisualizerStyle.PULSE
            val h = if (pulse) 4.dp.toPx() else 2.dp.toPx()
            if (pulse) drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY - h * 1.5f), androidx.compose.ui.geometry.Size(size.width * 0.45f, h * 4), androidx.compose.ui.geometry.CornerRadius(h * 2), alpha = 0.25f)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, barY), androidx.compose.ui.geometry.Size(size.width * 0.45f, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
    }
}

@Composable
private fun TitleSample(align: PlayerTitleAlign, weight: com.prism.music.data.prefs.PlayerTitleWeight = LocalAppSettings.current.type.playerTitleWeight) {
    SampleScreen(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (align == PlayerTitleAlign.CENTER) Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (align == PlayerTitleAlign.CENTER) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    tr(R.string.northern_lights), color = Color.White, fontSize = 12.sp, maxLines = 1, textAlign = if (align == PlayerTitleAlign.CENTER) TextAlign.Center else TextAlign.Start,
                    // Prism's own big titles are light; at this size their own weight wouldn't show it.
                    fontWeight = weight.title ?: androidx.compose.ui.text.font.FontWeight.Light,
                )
                Text(tr(R.string.prism_sample), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, fontWeight = weight.artist)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(Faint))
        }
    }
}

@Composable
private fun TimesSample(times: PlayerTimes) {
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Faint)) {
                Box(Modifier.fillMaxWidth(0.35f).height(3.dp).background(Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.ELAPSED) "1:12" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text(if (times == PlayerTimes.BOTH || times == PlayerTimes.LEFT) "−2:31" else "", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
    }
}

/** The progress bar's styles as cards, each drawn as it looks on the player (1.7.7, with Ribbons, #151). */
@Composable
fun ScrubberStyleCards() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    ChoiceCards(
        com.prism.music.data.prefs.ScrubberStyle.entries, s.scrubberStyle, title = { it.label },
        detail = {
            when (it) {
                com.prism.music.data.prefs.ScrubberStyle.HAIRLINE -> tr(R.string.a_thin_line)
                com.prism.music.data.prefs.ScrubberStyle.BOLD -> tr(R.string.a_thicker_bar)
                com.prism.music.data.prefs.ScrubberStyle.THUMB -> tr(R.string.a_line_and_a_knob)
                com.prism.music.data.prefs.ScrubberStyle.WAVE -> tr(R.string.each_song_s_own_shape)
                com.prism.music.data.prefs.ScrubberStyle.RIBBONS -> tr(R.string.strands_flow_along_while_it_plays)
            }
        },
        onSelect = { prefs.setScrubberStyle(it) },
    ) { ScrubberStyleSample(it, s.scrubberColor) }
}

@Composable
private fun ScrubberStyleSample(style: com.prism.music.data.prefs.ScrubberStyle, color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    val paths = remember { List(3) { androidx.compose.ui.graphics.Path() } }
    SampleScreen(Modifier.fillMaxWidth().height(44.dp)) {
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth().height(8.dp)) {
            val played = size.width * 0.58f
            val h = when (style) {
                com.prism.music.data.prefs.ScrubberStyle.HAIRLINE -> 2.5.dp; com.prism.music.data.prefs.ScrubberStyle.BOLD -> 5.dp
                com.prism.music.data.prefs.ScrubberStyle.THUMB -> 2.dp; com.prism.music.data.prefs.ScrubberStyle.WAVE -> 0.dp
                com.prism.music.data.prefs.ScrubberStyle.RIBBONS -> 3.5.dp
            }.toPx()
            val y = size.height / 2
            if (style == com.prism.music.data.prefs.ScrubberStyle.WAVE) {
                val n = 34
                val step = size.width / n
                for (i in 0 until n) {
                    val v = 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))
                    val bh = size.height * 1.6f * v
                    val x = i * step + step * 0.2f
                    val at = androidx.compose.ui.geometry.Offset(x, y - bh / 2)
                    val sz = androidx.compose.ui.geometry.Size(step * 0.6f, bh)
                    if (x < played) drawRoundRect(fill, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                    else drawRoundRect(Faint, at, sz, androidx.compose.ui.geometry.CornerRadius(step * 0.3f))
                }
                return@Canvas
            }
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(Faint, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
            drawRoundRect(fill, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(played, h), r)
            if (style == com.prism.music.data.prefs.ScrubberStyle.RIBBONS) {
                // The same strands as the player's, held still here.
                translate(top = y - h / 2) { drawRibbonStrands(paths, played, 0.35f, fill, barHeight = h) }
            }
            if (style == com.prism.music.data.prefs.ScrubberStyle.THUMB || style == com.prism.music.data.prefs.ScrubberStyle.RIBBONS) {
                drawCircle(Color.White, 5.dp.toPx(), androidx.compose.ui.geometry.Offset(played, y))
            }
        }
    }
}

@Composable
private fun BarSample(color: ScrubberColor) {
    val fill = com.prism.music.ui.player.scrubberFill(color, LocalContainer.current.player.currentSong.value?.thumbnail)
        ?: Brush.horizontalGradient(*PrismTokens.spectrumColors(true).toTypedArray())
    SampleScreen(Modifier.fillMaxWidth().height(36.dp)) {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 12.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Faint)) {
            Box(Modifier.fillMaxWidth(0.55f).height(5.dp).background(fill))
        }
    }
}

// ---------------------------------------------------------------------------- Background

/** The color mesh's motion and strength, and how soft the blurred cover is (under Look → Background). */
@Composable
fun PlayerBackgroundTuning() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    if (s.playerBackground == com.prism.music.data.prefs.PlayerBackground.MESH) {
        SliderSetting(tr(R.string.motion), s.meshSpeed, 0f..1f, { if (it < 0.05f) tr(R.string.still) else worded(if (it < 0.5f) tr(R.string.slow_drift) else tr(R.string.drifting), it) }) { prefs.setMeshSpeed(if (it < 0.05f) 0f else it) }
        if (s.reduceMotion && s.meshSpeed > 0f) Note(tr(R.string.reduce_motion_is_on_so_it))
        SliderSetting(tr(R.string.colors), s.meshIntensity, 0.4f..1.6f, { worded(if (it < 0.8f) tr(R.string.soft) else if (it <= 1.2f) tr(R.string.as_the_cover) else tr(R.string.rich), it) }) { prefs.setMeshIntensity(it) }
    }
    if (s.playerBackground == com.prism.music.data.prefs.PlayerBackground.MESH || s.playerBackground == com.prism.music.data.prefs.PlayerBackground.BLUR) {
        SliderSetting(tr(R.string.softness), s.playerBlur, 0.5f..1.5f, { "${(it * 100).roundToInt()}%" }) { prefs.setPlayerBlur(it) }
    }
}

// ---------------------------------------------------------------------------- Gestures

/** What swipes, a double tap and a long press do on the player's cover, and swipes on the mini player. */
@Composable
fun PlayerGesturesGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group(tr(R.string.gestures), info = tr(R.string.on_the_player_s_cover_swiping)) {
        GestureRow(tr(R.string.swipe_left), s.coverSwipeLeft, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_left", it) }
        GestureRow(tr(R.string.swipe_right), s.coverSwipeRight, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_right", it) }
        GestureRow(tr(R.string.swipe_up), s.coverSwipeUp, PlayerGesture.choices()) { prefs.setGesture("cover_swipe_up", it) }
        GestureRow(tr(R.string.double_tap), s.coverDoubleTap, PlayerGesture.choices(double = true)) {
            prefs.setGesture("cover_double_tap", it)
            // Seek by side needs a step; it used to be switched off by setting none.
            if (it == PlayerGesture.SEEK_SIDES && s.doubleTapSeekSeconds <= 0) prefs.setDoubleTapSeekSeconds(10)
        }
        if (s.coverDoubleTap == PlayerGesture.SEEK_SIDES) Chips(listOf(3, 5, 10, 15), s.doubleTapSeekSeconds.coerceAtLeast(3), { tr(R.string.s_a_tap, it) }) { prefs.setDoubleTapSeekSeconds(it) }
        GestureRow(tr(R.string.long_press), s.coverLongPress, PlayerGesture.choices()) { prefs.setGesture("cover_long_press", it) }
        // #259 (1.7.9.2): the same gestures around the controls too.
        Toggle(
            tr(R.string.gestures_anywhere_on_the_player), tr(R.string.not_only_on_the_cover_around_the_controls_too), s.behavior.gesturesEverywhere,
            info = tr(R.string.your_swipes_double_tap_and_long_press_work_below_the_title_too),
        ) { prefs.setGesturesEverywhere(it) }
        Label(tr(R.string.mini_player))
        GestureRow(tr(R.string.swipe_left), s.miniSwipeLeft, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_left", it) }
        GestureRow(tr(R.string.swipe_right), s.miniSwipeRight, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_right", it) }
        GestureRow(tr(R.string.swipe_up), s.miniSwipeUp, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_up", it) }
        GestureRow(tr(R.string.pull_down), s.miniSwipeDown, PlayerGesture.choices(mini = true)) { prefs.setGesture("mini_swipe_down", it) }
    }
}

/** A gesture and what it does; tapping it lists the choices. */
@Composable
private fun GestureRow(title: String, value: PlayerGesture, choices: List<PlayerGesture>, onPick: (PlayerGesture) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = tr(R.string.change_what_does, title)) { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(PrismIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            choices.forEach { g ->
                DropdownMenuItem(
                    { Text(g.label) }, { open = false; onPick(g) },
                    trailingIcon = if (g == value) ({ Icon(PrismIcons.Check, null) }) else null,
                )
            }
        }
    }
}
