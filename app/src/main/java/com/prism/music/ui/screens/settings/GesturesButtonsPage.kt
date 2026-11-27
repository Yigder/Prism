package com.prism.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.PipMode
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.data.prefs.SessionButton
import com.prism.music.tr
import com.prism.music.ui.components.ReorderList
import com.prism.music.ui.screens.InfoMark
import com.prism.music.ui.screens.InfoText
import com.prism.music.ui.screens.Needs
import com.prism.music.ui.screens.OrderRow
import com.prism.music.ui.screens.SettingEntry
import com.prism.music.ui.screens.sessionIcon
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlin.math.roundToInt

private val page = SettingsPageId.GESTURES_BUTTONS

/**
 * Where the cover's swipes, double tap and long press work: one row over the two switches of before 1.8.4,
 * "Gestures anywhere on the player" (#259) and "Gestures below the lyrics" (#282). Nothing stored changes.
 *
 * The two switches make four states and the player tells all four apart (`NowPlaying.kt`, `coverGestures`): the fourth,
 * below the lyrics without around the controls, is [COVER_AND_LYRICS]. It is kept as it is for whoever has it, and
 * only offered to them ([gesturesWorkChoices]): everyone else picks among the first three.
 */
enum class GesturesWork(val label: String) {
    /** Neither switch: on the cover only. */
    COVER(tr(R.string.gb_work_cover)),
    /** Both: around the controls, and on the controls below the open lyrics. */
    ANYWHERE(tr(R.string.gb_work_anywhere)),
    /** "Anywhere" alone: around the controls, nothing while the lyrics are open. */
    NOT_LYRICS(tr(R.string.gb_work_not_lyrics)),
    /** "Below the lyrics" alone: the cover, and the controls below the open lyrics. */
    COVER_AND_LYRICS(tr(R.string.gb_work_cover_and_lyrics)),
}

/** The row's value for the two stored switches ([everywhere] = `player_gestures_everywhere`, [underLyrics] = `player_gestures_under_lyrics`). */
internal fun gesturesWorkToMerged(everywhere: Boolean, underLyrics: Boolean): GesturesWork = when {
    everywhere && underLyrics -> GesturesWork.ANYWHERE
    everywhere -> GesturesWork.NOT_LYRICS
    underLyrics -> GesturesWork.COVER_AND_LYRICS
    else -> GesturesWork.COVER
}

/** The two stored switches for the row's value: `everywhere to underLyrics`. */
internal fun gesturesWorkToStored(work: GesturesWork): Pair<Boolean, Boolean> = when (work) {
    GesturesWork.COVER -> false to false
    GesturesWork.ANYWHERE -> true to true
    GesturesWork.NOT_LYRICS -> true to false
    GesturesWork.COVER_AND_LYRICS -> false to true
}

/** What the row's sheet lists: the three choices, and the fourth only while it is what's stored. */
internal fun gesturesWorkChoices(now: GesturesWork): List<GesturesWork> =
    listOf(GesturesWork.COVER, GesturesWork.ANYWHERE, GesturesWork.NOT_LYRICS) + listOfNotNull(now.takeIf { it == GesturesWork.COVER_AND_LYRICS })

/** One of the nine gesture menus: its row's [title], the stored [key], what it does [now] and as Prism comes, and its [choices]. */
private class GestureMenu(val title: String, val key: String, val now: PlayerGesture, val default: PlayerGesture, val choices: List<PlayerGesture>, val info: String? = null)

/** The steps a double tap on the cover's side may seek, in seconds. */
private val DOUBLE_TAP_STEPS = listOf(3, 5, 10, 15)

/**
 * Settings → Gestures & buttons (1.8.4's map, plan §2.3), built with the kit: what swipes, a double tap and a long
 * press do on the player's cover and on the mini player (nine menus, each a list in a sheet), the other swipes and
 * touch, the buttons outside Prism (notification, lock screen, car) with the lyrics shown there, and the screen.
 * This file is the only place for the page: its composable, its lines in Settings' search ([gesturesButtonsIndex]), its
 * "Reset this page" keys ([gesturesButtonsLookKeys]), its line on the root ([gesturesButtonsValue]) and the old group
 * names it answers to ([gesturesButtonsOldGroups]).
 *
 * Nothing here is part of a Look, so "Reset this page" puts each row back through its own setter ([ListeningResets]).
 */
@Composable
fun GesturesButtonsPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val seconds: (Int) -> String = { ListeningResets.seconds(it) }
    val holdText: (Int) -> String = { if (it == 0) tr(R.string.off) else seconds(it) }

    val coverTitle = tr(R.string.gb_on_the_cover)
    val miniTitle = tr(R.string.gb_on_the_mini_player)
    val cover = listOf(
        GestureMenu(tr(R.string.swipe_left), "cover_swipe_left", s.gestures.coverSwipeLeft, d.gestures.coverSwipeLeft, PlayerGesture.choices()),
        GestureMenu(tr(R.string.swipe_right), "cover_swipe_right", s.gestures.coverSwipeRight, d.gestures.coverSwipeRight, PlayerGesture.choices()),
        GestureMenu(tr(R.string.swipe_up), "cover_swipe_up", s.gestures.coverSwipeUp, d.gestures.coverSwipeUp, PlayerGesture.choices(), info = tr(R.string.on_the_player_s_cover_swiping)),
        GestureMenu(tr(R.string.double_tap), "cover_double_tap", s.gestures.coverDoubleTap, d.gestures.coverDoubleTap, PlayerGesture.choices(double = true)),
        GestureMenu(tr(R.string.long_press), "cover_long_press", s.gestures.coverLongPress, d.gestures.coverLongPress, PlayerGesture.choices()),
    )
    val mini = listOf(
        GestureMenu(tr(R.string.swipe_left), "mini_swipe_left", s.gestures.miniSwipeLeft, d.gestures.miniSwipeLeft, PlayerGesture.choices(mini = true)),
        GestureMenu(tr(R.string.swipe_right), "mini_swipe_right", s.gestures.miniSwipeRight, d.gestures.miniSwipeRight, PlayerGesture.choices(mini = true)),
        GestureMenu(tr(R.string.swipe_up), "mini_swipe_up", s.gestures.miniSwipeUp, d.gestures.miniSwipeUp, PlayerGesture.choices(mini = true)),
        GestureMenu(tr(R.string.pull_down), "mini_swipe_down", s.gestures.miniSwipeDown, d.gestures.miniSwipeDown, PlayerGesture.choices(mini = true)),
    )
    val seeksBySide = s.gestures.coverDoubleTap == PlayerGesture.SEEK_SIDES
    val work = gesturesWorkToMerged(s.behavior.gesturesEverywhere, s.behavior.gesturesUnderLyrics)
    val setWork: (GesturesWork) -> Unit = { w ->
        val (everywhere, underLyrics) = gesturesWorkToStored(w)
        if (everywhere != s.behavior.gesturesEverywhere) prefs.setGesturesEverywhere(everywhere)
        if (underLyrics != s.behavior.gesturesUnderLyrics) prefs.setGesturesUnderLyrics(underLyrics)
    }

    // What differs from how Prism comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    // "Swipe left" is a row of both the cover and the mini player: the reset's line names which.
    fun menuChanged(group: String, m: GestureMenu) = reset.add(group + " · " + m.title, m.now, m.default, { it.label }) { prefs.setGesture(m.key, it) }
    val coverChanged = cover.map { menuChanged(coverTitle, it) }
    val stepChanged = reset.add(tr(R.string.gb_double_tap_seeks), s.doubleTapSeekSeconds, d.doubleTapSeekSeconds, seconds) { prefs.setDoubleTapSeekSeconds(it) }
    val workChanged = reset.add(tr(R.string.gb_gestures_work), work, gesturesWorkToMerged(d.behavior.gesturesEverywhere, d.behavior.gesturesUnderLyrics), { it.label }) {
        val (everywhere, underLyrics) = gesturesWorkToStored(it)
        prefs.setGesturesEverywhere(everywhere); prefs.setGesturesUnderLyrics(underLyrics)
    }
    val miniChanged = mini.map { menuChanged(miniTitle, it) }
    val holdChanged = reset.add(tr(R.string.hold_previous_next_to_seek), s.holdSeekSeconds, d.holdSeekSeconds, holdText) { prefs.setHoldSeekSeconds(it) }
    val hapticsChanged = reset.add(tr(R.string.haptics), s.haptics, d.haptics, onOff) { prefs.setHaptics(it) }
    val panelChanged = reset.add(tr(R.string.swipe_up_for_the_queue), s.behavior.panelSwipes, d.behavior.panelSwipes, onOff) { prefs.setPanelSwipes(it) }
    val tabsChanged = reset.add(tr(R.string.swipe_between_tabs), s.swipeTabs, d.swipeTabs, onOff) { prefs.setSwipeTabs(it) }
    val rowsChanged = reset.add(tr(R.string.gb_row_swipes), s.behavior.rowSwipes, d.behavior.rowSwipes, onOff) { prefs.setRowSwipes(it) }
    val rowLeftChanged = reset.add(tr(R.string.swipe_left), s.behavior.rowSwipeLeft, d.behavior.rowSwipeLeft, { it.label }) { prefs.setRowSwipeLeft(it) }
    val rowRightChanged = reset.add(tr(R.string.swipe_right), s.behavior.rowSwipeRight, d.behavior.rowSwipeRight, { it.label }) { prefs.setRowSwipeRight(it) }
    val coverPlaysChanged = reset.add(tr(R.string.gb_lyrics_cover_plays), s.behavior.lyricsCoverPlays, d.behavior.lyricsCoverPlays, onOff) { prefs.setLyricsCoverPlays(it) }
    val buttonsChanged = reset.add(tr(R.string.buttons), s.sessionButtons, d.sessionButtons, ::buttonsText) { prefs.setSessionButtons(it) }
    val notificationLyricsChanged = reset.add(tr(R.string.gb_lyrics_in_notification), s.carLyricsOnPhone, d.carLyricsOnPhone, onOff) { prefs.setCarLyricsOnPhone(it) }
    val portraitChanged = reset.add(tr(R.string.lock_to_portrait), s.behavior.lockPortrait, d.behavior.lockPortrait, onOff) { prefs.setLockPortrait(it) }
    val pipChanged = reset.add(tr(R.string.picture_in_picture), s.behavior.pip, d.behavior.pip, { it.label }) { prefs.setPipMode(it) }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(coverTitle) {
            cover.forEachIndexed { i, m ->
                PickListRow(m.title, m.choices, m.now, { it.label }, info = m.info, changed = coverChanged[i]) {
                    prefs.setGesture(m.key, it)
                    // Seek by side needs a step; it used to be switched off by setting none.
                    if (it == PlayerGesture.SEEK_SIDES && s.doubleTapSeekSeconds <= 0) prefs.setDoubleTapSeekSeconds(10)
                }
                // #79: how far each double tap on a side goes, right under the menu that asks for it.
                if (m.key == "cover_double_tap" && seeksBySide) ChoiceRow(
                    tr(R.string.gb_double_tap_seeks), DOUBLE_TAP_STEPS, s.doubleTapSeekSeconds.coerceAtLeast(DOUBLE_TAP_STEPS.first()), seconds, changed = stepChanged,
                ) { prefs.setDoubleTapSeekSeconds(it) }
            }
            // #259 and #282 as one row. A list in a sheet: three chips this long don't fit a 360 dp phone.
            PickListRow(
                tr(R.string.gb_gestures_work), gesturesWorkChoices(work), work, { it.label },
                info = tr(R.string.gb_gestures_work_info), changed = workChanged, onPick = setWork,
            )
        }
        group(miniTitle) {
            mini.forEachIndexed { i, m ->
                PickListRow(m.title, m.choices, m.now, { it.label }, changed = miniChanged[i]) { prefs.setGesture(m.key, it) }
            }
        }
        group(tr(R.string.gb_swipes_and_touch)) {
            // 1.8.0: one slider, Off at its start. Same stored value.
            SliderRow(
                tr(R.string.hold_previous_next_to_seek), s.holdSeekSeconds.toFloat(), 0f..20f, { holdText(it.roundToInt()) }, steps = 19,
                info = tr(R.string.a_tap_still_changes_songs), changed = holdChanged,
            ) { prefs.setHoldSeekSeconds(it.roundToInt()) }
            SwitchRow(tr(R.string.haptics), s.haptics, info = tr(R.string.a_light_tap_on_buttons_and), changed = hapticsChanged) { prefs.setHaptics(it) }
            SwitchRow(tr(R.string.swipe_up_for_the_queue), s.behavior.panelSwipes, info = tr(R.string.swipe_up_on_the_lower_half_all), changed = panelChanged) { prefs.setPanelSwipes(it) }
            // From Tabs & mini player (§2.3): a swipe, so it is looked for here.
            SwitchRow(tr(R.string.swipe_between_tabs), s.swipeTabs, info = tr(R.string.a_sideways_swipe_on_a_tab), changed = tabsChanged) { prefs.setSwipeTabs(it) }
            // #406 (1.8.4): on is how Prism was.
            SwitchRow(tr(R.string.gb_row_swipes), s.behavior.rowSwipes, info = tr(R.string.gb_row_swipes_info), changed = rowsChanged) { prefs.setRowSwipes(it) }
            if (s.behavior.rowSwipes) {
                ChoiceRow(tr(R.string.swipe_left), com.prism.music.data.prefs.RowSwipeAction.entries, s.behavior.rowSwipeLeft, { it.label }, changed = rowLeftChanged) {
                    prefs.setRowSwipeLeft(it)
                }
                ChoiceRow(tr(R.string.swipe_right), com.prism.music.data.prefs.RowSwipeAction.entries, s.behavior.rowSwipeRight, { it.label }, changed = rowRightChanged) {
                    prefs.setRowSwipeRight(it)
                }
            }
            // #464 (1.8.5): off is how Prism was (a tap closes the lyrics).
            SwitchRow(tr(R.string.gb_lyrics_cover_plays), s.behavior.lyricsCoverPlays, info = tr(R.string.gb_lyrics_cover_plays_info), changed = coverPlaysChanged) { prefs.setLyricsCoverPlays(it) }
        }
        group(tr(R.string.notification_and_car)) {
            GbSessionButtons(buttonsChanged)
            // #392 (1.8.4): its own switch, with or without the car's ("On this phone too" under Lyrics in the car before).
            SwitchRow(
                tr(R.string.gb_lyrics_in_notification), s.carLyricsOnPhone, value = tr(R.string.lock_screen_and_notification),
                info = tr(R.string.gb_lyrics_in_notification_info), changed = notificationLyricsChanged,
            ) { prefs.setCarLyricsOnPhone(it) }
        }
        group(tr(R.string.screen)) {
            SwitchRow(tr(R.string.lock_to_portrait), s.behavior.lockPortrait, info = tr(R.string.on_a_phone_prism_no_longer), changed = portraitChanged) { prefs.setLockPortrait(it) }
            ChoiceRow(
                tr(R.string.picture_in_picture), PipMode.entries, s.behavior.pip, { it.label },
                info = tr(R.string.when_a_playing_music_video_floats), changed = pipChanged,
            ) { prefs.setPipMode(it) }
        }
    }
}

/** The buttons in their order, in words ("Like · Shuffle · Repeat"). */
private fun buttonsText(buttons: List<SessionButton>): String = buttons.joinToString(" · ") { it.label }

/**
 * The buttons beside previous / play / next outside Prism (the notification, the lock screen, Android Auto), in the
 * listener's order: tick one to show it, hold or drag one to move it. As it was on the old page, under a small picture
 * of the notification with the first two. "Reset this page" puts Prism's own buttons back.
 *
 * A list in a group: the kit has no row type for it, so it carries its own tag and says the buttons as its value; it
 * counts as one row.
 */
@Composable
private fun GbSessionButtons(changed: Boolean) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val chosen = s.sessionButtons
    val title = tr(R.string.buttons)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("gesturesButtons:buttons").semantics {
            settingsRowValue = buttonsText(chosen)
            settingsRowChanged = changed
        },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (changed) ChangedDot()
            InfoMark(open, title) { open = !open }
        }
        InfoText(open, tr(R.string.your_phone_s_notification_and_lock), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        // The notification as it'll look: previous, play, next and the first two beside them. Dark, as a notification's
        // player is over a cover.
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF211A33)),
            contentAlignment = Alignment.Center,
        ) {
            val ink = Color.White.copy(alpha = 0.85f)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                chosen.getOrNull(0)?.let { Icon(sessionIcon(it), null, Modifier.size(20.dp), tint = ink) }
                Icon(PrismIcons.Previous, null, Modifier.size(22.dp), tint = Color.White)
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(PrismIcons.Pause, null, Modifier.size(16.dp), tint = Color.Black) }
                Icon(PrismIcons.Next, null, Modifier.size(22.dp), tint = Color.White)
                chosen.getOrNull(1)?.let { Icon(sessionIcon(it), null, Modifier.size(20.dp), tint = ink) }
            }
        }
        ReorderList(chosen, { it }, { prefs.setSessionButtons(it) }) { b, _, _, mod ->
            OrderRow(
                b.label, sessionIcon(b), true, canCheck = true,
                onCheck = { v -> if (!v) prefs.setSessionButtons(chosen - b) },
                modifier = mod, grip = Modifier.dragHandle(),
            )
        }
        SessionButton.entries.filter { it !in chosen }.forEach { b ->
            OrderRow(
                b.label, sessionIcon(b), false, canCheck = true,
                onCheck = { v -> if (v) prefs.setSessionButtons(chosen + b) },
            )
        }
        Spacer(Modifier.heightIn(min = 6.dp))
    }
}

/** The root's line for Gestures & buttons: what a double tap on the cover does, and haptics when on ("Double tap: Seek by side · Haptics"). */
fun gesturesButtonsValue(s: AppSettings, dark: Boolean): String = listOfNotNull(
    tr(R.string.gb_value_double_tap, s.gestures.coverDoubleTap.label),
    if (s.haptics) tr(R.string.haptics) else null,
).joinToString(" · ")

/** The look settings "Reset this page" puts back: none (what touches do isn't part of a look; the page resets its rows itself). */
val gesturesButtonsLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: "Gestures" began with the cover's (the mini player's were under a label in
 * it; search opens those in their own group), and "Buttons and touch" is Swipes and touch. "Notification and car" and
 * "Screen" are still called that.
 */
val gesturesButtonsOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.gestures) to tr(R.string.gb_on_the_cover),
        tr(R.string.buttons_and_touch) to tr(R.string.gb_swipes_and_touch),
    )
}

/** The page's lines in Settings' search (each names the group it is drawn in). */
val gesturesButtonsIndex: List<SettingEntry> by lazy {
    val cover = tr(R.string.gb_on_the_cover)
    val mini = tr(R.string.gb_on_the_mini_player)
    val touch = tr(R.string.gb_swipes_and_touch)
    val outside = tr(R.string.notification_and_car)
    val screen = tr(R.string.screen)
    val gestureWords = tr(R.string.swipe_double_tap_long_press_cover)
    listOf(
        SettingEntry(page, cover, tr(R.string.swipe_left), gestureWords),
        SettingEntry(page, cover, tr(R.string.swipe_right), gestureWords),
        SettingEntry(page, cover, tr(R.string.swipe_up), gestureWords),
        SettingEntry(page, cover, tr(R.string.double_tap), tr(R.string.seek_skip_seconds_fast_forward_rewind)),
        SettingEntry(
            page, cover, tr(R.string.gb_double_tap_seeks), tr(R.string.seek_skip_seconds_fast_forward_rewind),
            needs = Needs(tr(R.string.gb_double_tap_seeks_needs)) { it.gestures.coverDoubleTap == PlayerGesture.SEEK_SIDES },
        ),
        SettingEntry(page, cover, tr(R.string.long_press), tr(R.string.hold_cover)),
        // The two switches it took over stay as search words.
        SettingEntry(
            page, cover, tr(R.string.gb_gestures_work),
            tr(R.string.gestures_everywhere_keywords) + " " + tr(R.string.gestures_anywhere_on_the_player) + " " + tr(R.string.gestures_under_the_lyrics),
        ),
        SettingEntry(page, mini, tr(R.string.swipe_left), tr(R.string.mini_player)),
        SettingEntry(page, mini, tr(R.string.swipe_right), tr(R.string.mini_player)),
        SettingEntry(page, mini, tr(R.string.swipe_up), tr(R.string.swipe_the_mini_player_up) + " " + tr(R.string.mini_player_swipe_up_open_player)),
        SettingEntry(page, mini, tr(R.string.pull_down), tr(R.string.mini_player_stop)),
        SettingEntry(page, touch, tr(R.string.hold_previous_next_to_seek), tr(R.string.fast_forward_rewind) + " " + tr(R.string.each_step)),
        SettingEntry(page, touch, tr(R.string.haptics), tr(R.string.vibration_vibrate)),
        SettingEntry(page, touch, tr(R.string.swipe_up_for_the_queue), tr(R.string.gesture_one_hand_queue_lyrics_swipe)),
        SettingEntry(page, touch, tr(R.string.swipe_between_tabs), tr(R.string.tab_bar_swipe_gesture_sideways)),
        SettingEntry(page, touch, tr(R.string.gb_row_swipes), tr(R.string.gb_row_swipes_keywords) + " " + tr(R.string.add_to_playlist)),
        SettingEntry(page, touch, tr(R.string.gb_lyrics_cover_plays), tr(R.string.gb_lyrics_cover_plays_keywords)),
        SettingEntry(page, outside, tr(R.string.buttons), tr(R.string.notification_lock_screen_android_auto_car)),
        SettingEntry(page, outside, tr(R.string.gb_lyrics_in_notification), tr(R.string.gb_lyrics_in_notification_keywords)),
        SettingEntry(page, screen, tr(R.string.lock_to_portrait), tr(R.string.rotation_orientation_landscape_upright)),
        SettingEntry(page, screen, tr(R.string.picture_in_picture), tr(R.string.pip_video_float_window_leave)),
    )
}
