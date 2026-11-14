package com.prism.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BleedFit
import com.prism.music.data.prefs.CanvasKinds
import com.prism.music.data.prefs.CoverShape
import com.prism.music.data.prefs.CoverSize
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.ImmersiveArt
import com.prism.music.data.prefs.ImmersiveLyrics
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.LockPaused
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.MenuAction
import com.prism.music.data.prefs.PanelPlayer
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.PlayerTimes
import com.prism.music.data.prefs.PlayerTitleAlign
import com.prism.music.data.prefs.PlayerTitleSize
import com.prism.music.data.prefs.PlayerTitleWeight
import com.prism.music.data.prefs.ScrubberColor
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.ScrubberThumb
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.data.prefs.VisualizerStyle
import com.prism.music.data.prefs.VolumeControl
import com.prism.music.data.prefs.shows
import com.prism.music.tr
import com.prism.music.ui.components.KeepUpwardScroll
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.screens.LookTheme
import com.prism.music.ui.screens.MiniPlayerScreen
import com.prism.music.ui.screens.PreviewPhone
import com.prism.music.ui.screens.SampleScreen
import com.prism.music.ui.screens.buttonIcon
import com.prism.music.ui.screens.worded
import com.prism.music.ui.theme.CoverShapes
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

/*
 * Settings → Player (1.8.4), the parts beside the page itself: the merged controls of plan §2.5 as pure functions, the
 * list of the page's settings (what "Reset this page" lists and puts back, and what PlayerPageTest checks is all
 * there), the sheets its rows open, its pinned preview, and the few examples the old page drew inline.
 */

// ---------------------------------------------------------------------------- Merges (plan §2.5): pure, same stored values

/**
 * The rule of every merge here that has fewer values than stored combinations: a stored combination the control can't
 * tell apart from the one it shows is left as it is ([was]) for as long as the control isn't changed, so nobody's setup
 * changes by opening the page; picking another value stores that value's own combination.
 */
private fun <M, S> keep(merged: M, was: S, toMerged: (S) -> M, own: (M) -> S): S = if (toMerged(was) == merged) was else own(merged)

/** Cover flies into place + Cover flies back too. Stored: (flight, back). "Back" without "flight" does nothing: Off, kept. */
enum class CoverAnimation {
    OFF, OPENING, OPENING_AND_CLOSING;

    val label: String
        get() = when (this) {
            OFF -> tr(R.string.off)
            OPENING -> tr(R.string.settings_player_opening)
            OPENING_AND_CLOSING -> tr(R.string.settings_player_opening_and_closing)
        }

    fun toStored(was: Pair<Boolean, Boolean>): Pair<Boolean, Boolean> = keep(this, was, ::toMerged) {
        when (it) { OFF -> false to false; OPENING -> true to false; OPENING_AND_CLOSING -> true to true }
    }

    companion object {
        fun toMerged(stored: Pair<Boolean, Boolean>): CoverAnimation = when {
            !stored.first -> OFF
            stored.second -> OPENING_AND_CLOSING
            else -> OPENING
        }
    }
}

/** Edge-to-edge cover + Edge-to-edge video. Stored: (cover, video); four values for the four combinations. */
enum class EdgeToEdge(val cover: Boolean, val video: Boolean) {
    OFF(false, false), COVER(true, false), VIDEO(false, true), BOTH(true, true);

    val label: String
        get() = when (this) {
            OFF -> tr(R.string.off)
            COVER -> tr(R.string.cover)
            VIDEO -> tr(R.string.video)
            BOTH -> tr(R.string.both)
        }

    fun toStored(): Pair<Boolean, Boolean> = cover to video

    companion object {
        fun toMerged(stored: Pair<Boolean, Boolean>): EdgeToEdge = entries.first { it.cover == stored.first && it.video == stored.second }
    }
}

/**
 * "Where it shows" (1.8.6), for Canvas's loops (an animated cover always plays in the cover): On the cover / Full screen. Stored:
 * `canvasFillsBackground`, as before ("Fill the player" is Full screen). Whether anything shows at all is the two
 * switches' ([com.prism.music.data.prefs.CanvasKinds]).
 */
internal fun canvasPlaceWords(fills: Boolean): String = if (fills) tr(R.string.full_screen) else tr(R.string.settings_player_on_the_cover)

/** "When a song has both": the animated cover first (as before), or the music video's loop. */
internal fun canvasBothWords(videoFirst: Boolean): String = if (videoFirst) tr(R.string.music_video_3) else tr(R.string.animated_cover)

/** The Canvas row's line: Off while its switch is off, else where it shows. */
internal fun canvasWords(s: AppSettings): String =
    if (CanvasKinds.shown(s).videos) canvasPlaceWords(s.canvasFillsBackground) else tr(R.string.off)

/**
 * Hide controls over videos + Video buttons wait for a tap. Stored: (controlsHide, buttonsHide). The plan names three
 * values; the fourth combination (the buttons wait, the controls stay) is [BUTTONS_WAIT], which is only offered while
 * it's the one stored ([choices]), so it stays as it is and is said in words, without a new stored flag.
 */
enum class VideoControls(val controlsHide: Boolean, val buttonsHide: Boolean) {
    ALWAYS(false, false), HIDE_CONTROLS(true, false), HIDE_CONTROLS_AND_BUTTONS(true, true), BUTTONS_WAIT(false, true);

    val label: String
        get() = when (this) {
            ALWAYS -> tr(R.string.always)
            HIDE_CONTROLS -> tr(R.string.settings_player_hide_controls)
            HIDE_CONTROLS_AND_BUTTONS -> tr(R.string.settings_player_hide_controls_and_buttons)
            BUTTONS_WAIT -> tr(R.string.settings_player_buttons_wait_only)
        }

    fun toStored(): Pair<Boolean, Boolean> = controlsHide to buttonsHide

    companion object {
        fun toMerged(stored: Pair<Boolean, Boolean>): VideoControls = entries.first { it.controlsHide == stored.first && it.buttonsHide == stored.second }

        /** What the sheet offers: the plan's three, and the fourth only while it's the stored one. */
        fun choices(now: VideoControls): List<VideoControls> = listOf(ALWAYS, HIDE_CONTROLS, HIDE_CONTROLS_AND_BUTTONS) + listOfNotNull(BUTTONS_WAIT.takeIf { now == it })
    }
}

/** The five queue switches as "In the queue" show-chips: each chip is its own stored switch. */
enum class QueueChip {
    HEADINGS, DJ, LENGTHS, MOVE_PLAYED, TIDIER;

    val label: String
        get() = when (this) {
            HEADINGS -> tr(R.string.settings_player_queue_headings)
            DJ -> tr(R.string.settings_player_queue_dj)
            LENGTHS -> tr(R.string.settings_player_queue_lengths)
            MOVE_PLAYED -> tr(R.string.settings_player_queue_move_played)
            TIDIER -> tr(R.string.settings_player_queue_tidier)
        }

    companion object {
        /** Stored: headings, DJ chip, lengths, played moves, tidy, in [entries]' order. */
        fun toMerged(stored: List<Boolean>): Set<QueueChip> = entries.filterIndexed { i, _ -> stored[i] }.toSet()
        fun toStored(merged: Set<QueueChip>): List<Boolean> = entries.map { it in merged }
    }
}

/** "Lyrics and the queue" show-chips: three switches, each chip its own. */
enum class PanelChip {
    SCROLL_SHOWS_CONTROLS, REOPEN_LAST_PAGE, STEADIER_LYRICS;

    val label: String
        get() = when (this) {
            // Short enough for a chip at large text ("Scrolling up shows the controls" was cut off there).
            SCROLL_SHOWS_CONTROLS -> tr(R.string.settings_player_scroll_for_controls)
            REOPEN_LAST_PAGE -> tr(R.string.reopen_on_the_last_page)
            STEADIER_LYRICS -> tr(R.string.steadier_lyrics)
        }

    companion object {
        fun toMerged(stored: List<Boolean>): Set<PanelChip> = entries.filterIndexed { i, _ -> stored[i] }.toSet()
        fun toStored(merged: Set<PanelChip>): List<Boolean> = entries.map { it in merged }
    }
}

/**
 * Line on the player + Up to two lines as one pick (plan §2.5). Stored: (align, twoLines). The ledger gives this row to
 * Lyrics & Sing → Style, so the Player page draws no row for it; the mapping is here for whichever page does.
 */
data class LineOnPlayer(val align: LyricsAlign, val lines: Int) {
    fun toStored(): Pair<LyricsAlign, Boolean> = align to (lines == 2)

    companion object {
        val all: List<LineOnPlayer> = LyricsAlign.entries.flatMap { a -> listOf(LineOnPlayer(a, 1), LineOnPlayer(a, 2)) }
        fun toMerged(stored: Pair<LyricsAlign, Boolean>): LineOnPlayer = LineOnPlayer(stored.first, if (stored.second) 2 else 1)
    }
}

// ---------------------------------------------------------------------------- The page's settings

/** The page's groups (their titles are the page's `group(...)` titles and the search lines' groups). */
internal object PlayerGroups {
    val layout get() = tr(R.string.layout)
    val progress get() = tr(R.string.settings_player_progress_and_times)
    val title get() = tr(R.string.title)
    val cover get() = tr(R.string.cover)
    val onIt get() = tr(R.string.settings_player_on_it)
    val modes get() = tr(R.string.settings_player_modes)
}

/**
 * One setting of the page (or the few stored values one control holds): its stored [keys], the [group] and the [row]
 * that holds its control ([sheet]: the row whose sheet that row is in, when it isn't on the page itself), its value in
 * words, and its setter. "Reset this page" and the row's changed dot come from it.
 */
internal class PlayerSetting<T>(
    val keys: List<String>,
    val group: String,
    val row: String,
    val sheet: String?,
    val get: (AppSettings) -> T,
    val words: (T) -> String,
    val set: (SettingsRepository, T) -> Unit,
) {
    fun changed(s: AppSettings): Boolean = get(s) != get(PLAYER_DEFAULTS)
    fun line(s: AppSettings): String = resetLine(if (sheet == null) row else tr(R.string.settings_player_chip_info, sheet, row),words(get(s)), words(get(PLAYER_DEFAULTS)))
    fun reset(prefs: SettingsRepository) = set(prefs, get(PLAYER_DEFAULTS))
}

internal val PLAYER_DEFAULTS = AppSettings()

private fun onOff(v: Boolean) = if (v) tr(R.string.on_2) else tr(R.string.off)
internal fun percent(v: Float) = "${(v * 100).roundToInt()}%"

/** The parts of the player hidden now, as the row says them ("Hidden: Volume"). */
internal fun hiddenPartsWords(s: AppSettings): String {
    val hidden = PlayerPart.entries.filter { !s.shows(it) && it != PlayerPart.NAV_BAND && (s.mix || it !in PlayerPart.CROSSFADE) }
    return if (hidden.isEmpty()) tr(R.string.settings_player_all_shown) else tr(R.string.hidden_s, hidden.joinToString(", ") { it.label })
}

internal fun movementWords(speed: Float): String =
    if (speed < 0.05f) tr(R.string.still) else worded(if (speed < 0.5f) tr(R.string.gentle) else tr(R.string.lively), speed)

internal fun thicknessWords(v: Float): String =
    worded(if (v < 0.85f) tr(R.string.thinner) else if (v <= 1.15f) tr(R.string.as_the_style) else tr(R.string.thicker), v)

internal fun backdropShowWords(at: Float): String {
    val show = com.prism.music.ui.player.backdropShowFromSlider(at)
    return worded(if (at < 0.01f) tr(R.string.dark) else if (show < 0.2f) tr(R.string.subtle) else if (show < 0.7f) tr(R.string.balanced) else tr(R.string.vivid), at)
}

/** Every setting of the page, in the page's order. */
internal val playerSettings: List<PlayerSetting<*>> by lazy {
    val g = PlayerGroups
    val immersive = tr(R.string.settings_player_immersive_options)
    val lock = tr(R.string.settings_player_lock_options)
    val canvasSheet = tr(R.string.canvas)
    fun <T> ps(keys: List<String>, group: String, row: Int, get: (AppSettings) -> T, words: (T) -> String, sheet: String? = null, set: (SettingsRepository, T) -> Unit) =
        PlayerSetting(keys, group, tr(row), sheet, get, words, set)
    fun flag(keys: List<String>, group: String, row: Int, get: (AppSettings) -> Boolean, sheet: String? = null, set: (SettingsRepository, Boolean) -> Unit) =
        ps(keys, group, row, get, ::onOff, sheet, set)
    listOf(
        // Layout
        ps(listOf("player_layout"), g.layout, R.string.layout, { it.playerLayout }, { it.label }) { p, v -> p.setPlayerLayout(v) },
        flag(listOf("vinyl_tonearm"), g.layout, R.string.tonearm, { it.vinylTonearm }) { p, v -> p.setVinylTonearm(v) },
        ps(listOf("player_background"), g.layout, R.string.background, { it.playerBackground }, { it.label }) { p, v -> p.setPlayerBackground(v) },
        ps(listOf("player_backdrop_show"), g.layout, R.string.show_background, { it.playerBackdropShow }, { backdropShowWords(com.prism.music.ui.player.backdropShowOnSlider(it)) }) { p, v -> p.setPlayerBackdropShow(v) },
        ps(listOf("player_gradient_depth"), g.layout, R.string.gradient_depth, { it.view.gradientDepth }, ::percent) { p, v -> p.setGradientDepth(v) },
        ps(listOf("mesh_speed", "mesh_intensity"), g.layout, R.string.background_movement, { it.meshSpeed to it.meshIntensity }, { movementWords(it.first) }) { p, v -> p.setMeshSpeed(v.first); p.setMeshIntensity(v.second) },
        ps(listOf("player_blur"), g.layout, R.string.softness, { it.playerBlur }, ::percent) { p, v -> p.setPlayerBlur(v) },
        ps(listOf("transport_style"), g.layout, R.string.controls, { it.transportStyle }, { it.label }) { p, v -> p.setTransportStyle(v) },
        flag(listOf("play_ring"), g.layout, R.string.ring_on_the_play_button, { it.view.playRing }) { p, v -> p.setPlayRing(v) },
        ps(listOf("player_buttons_pill"), g.layout, R.string.buttons, { it.view.buttonsPill }, { if (it) tr(R.string.in_a_capsule) else tr(R.string.apart) }) { p, v -> p.setButtonsPill(v) },
        ps(
            listOf("video_controls_hide", "video_buttons_hide"), g.layout, R.string.settings_player_controls_over_videos,
            { it.view.videoControlsHide to it.view.videoButtonsHide }, { VideoControls.toMerged(it).label },
        ) { p, v -> p.setVideoControlsHide(v.first); p.setVideoButtonsHide(v.second) },
        // Progress bar and times
        ps(listOf("scrubber_style"), g.progress, R.string.style, { it.scrubberStyle }, { it.label }) { p, v -> p.setScrubberStyle(v) },
        ps(listOf("scrubber_color"), g.progress, R.string.progress_bar_color, { it.scrubberColor }, { it.label }) { p, v -> p.setScrubberColor(v) },
        ps(listOf("scrubber_thickness"), g.progress, R.string.progress_bar_thickness, { it.scrubberThickness }, ::thicknessWords) { p, v -> p.setScrubberThickness(v) },
        ps(listOf("scrubber_thumb"), g.progress, R.string.knob_at_the_playhead, { it.scrubberThumb }, { it.label }) { p, v -> p.setScrubberThumb(v) },
        flag(listOf("volume_matches_progress"), g.progress, R.string.volume_bar_like_the_progress_bar, { it.volumeMatchesProgress }) { p, v -> p.setVolumeMatchesProgress(v) },
        ps(listOf("player_times"), g.progress, R.string.times, { it.playerTimes }, { it.label }) { p, v -> p.setPlayerTimes(v) },
        ps(listOf("show_track_length"), g.progress, R.string.settings_player_right_hand_time, { it.showTrackLength }, { if (it) tr(R.string.song_length) else tr(R.string.time_left) }) { p, v -> p.setShowTrackLength(v) },
        flag(listOf("crossfade_shape"), g.progress, R.string.crossfade_on_the_progress_bar, { it.view.crossfadeShape }) { p, v -> p.setCrossfadeShape(v) },
        // Title
        ps(listOf("player_title_align"), g.title, R.string.alignment, { it.playerTitleAlign }, { it.label }) { p, v -> p.setPlayerTitleAlign(v) },
        ps(listOf("player_title_size"), g.title, R.string.size, { it.playerTitleSize }, { it.label }) { p, v -> p.setPlayerTitleSize(v) },
        ps(listOf("player_title_weight"), g.title, R.string.weight, { it.type.playerTitleWeight }, { it.label }) { p, v -> p.setPlayerTitleWeight(v) },
        flag(listOf("player_title_scroll"), g.title, R.string.scrolling_titles, { it.playerTitleScroll }) { p, v -> p.setPlayerTitleScroll(v) },
        ps(listOf("player_artist_links"), g.title, R.string.each_artist_its_own_link, { it.view.artistLinks }, ::artistLinksWords) { p, v -> p.setArtistLinks(v) },
        // Cover
        ps(listOf("cover_size"), g.cover, R.string.cover_size, { it.coverSize }, { it.label }) { p, v -> p.setCoverSize(v) },
        ps(listOf("player_art_corners"), g.cover, R.string.cover_corners, { it.playerArtCorners }, { tr(R.string.dp, it) }) { p, v -> p.setPlayerArtCorners(v) },
        ps(listOf("cover_shape_player"), g.cover, R.string.settings_player_cover_shape, { it.shapes.player }, { it.label }) { p, v -> p.setCoverShape("player", v) },
        ps(
            listOf("full_bleed_artwork", "video_full_bleed"), g.cover, R.string.settings_player_edge_to_edge,
            { it.fullBleedArtwork to it.videoFullBleed }, { EdgeToEdge.toMerged(it).label },
        ) { p, v -> p.setFullBleedArtwork(v.first); p.setVideoFullBleed(v.second) },
        ps(listOf("bleed_fit"), g.cover, R.string.how_the_cover_fits, { it.view.bleedFit }, { it.label }) { p, v -> p.setBleedFit(v) },
        flag(listOf("bleed_roomy"), g.cover, R.string.more_room_for_the_picture, { it.view.bleedRoomy }) { p, v -> p.setBleedRoomy(v) },
        flag(listOf("art_shrink_on_pause"), g.cover, R.string.shrink_when_paused, { it.artShrinkOnPause }) { p, v -> p.setArtShrinkOnPause(v) },
        // Owner, 2026-11-13 ("keep these options separate"): Animated covers on the page, Canvas (the loops from music
        // videos) a switch in its own row's sheet; "Canvas on" (canvas_enabled) is stored as either of them on.
        flag(listOf("canvas_enabled", "canvas_covers"), g.cover, R.string.animated_covers, { CanvasKinds.shown(it).covers }) { p, v -> p.setCanvasKinds(covers = v) },
        flag(listOf("canvas_use_videos"), g.cover, R.string.canvas, { CanvasKinds.shown(it).videos }, canvasSheet) { p, v -> p.setCanvasKinds(videos = v) },
        flag(listOf("canvas_videos_cellular"), g.cover, R.string.use_mobile_data, { it.behavior.canvas.videosOnCellular }, canvasSheet) { p, v -> p.setCanvasVideosOnCellular(v) },
        ps(listOf("canvas_video_first"), g.cover, R.string.canvas_both, { it.behavior.canvas.videoFirst }, ::canvasBothWords, canvasSheet) { p, v -> p.setCanvasVideoFirst(v) },
        flag(listOf("canvas_lighter"), g.cover, R.string.canvas_lighter, { it.behavior.canvas.lighter }, canvasSheet) { p, v -> p.setCanvasLighter(v) },
        ps(listOf("canvas_fills_background"), g.cover, R.string.canvas_where, { it.canvasFillsBackground }, ::canvasPlaceWords, canvasSheet) { p, v -> p.setCanvasFillsBackground(v) },
        flag(listOf("canvas_keep_controls"), g.cover, R.string.canvas_keep_controls, { it.behavior.canvas.keepControls }, canvasSheet) { p, v -> p.setCanvasKeepControls(v) },
        // On it
        ps(
            listOf("player_hidden_parts", "player_handle", "show_playing_from", "lyrics_on_player", "show_quality", "best_part_button", "player_shuffle_repeat", "hide_volume_bar", "show_output_device", "player_nav_band", "crossfade_mark", "blend_covers"),
            g.onIt, R.string.what_s_on_the_player,
            { s -> PlayerPart.entries.filter { !s.shows(it) }.toSet() }, { h -> if (h.isEmpty()) tr(R.string.settings_player_all_shown) else tr(R.string.hidden_s, h.joinToString(", ") { it.label }) },
        ) { p, v -> PlayerPart.entries.forEach { part -> p.setPartShown(part, part !in v) } },
        ps(listOf("player_deck"), g.onIt, R.string.under_the_title, { it.playerDeck }, { d -> d.joinToString(" · ") { it.label } }) { p, v -> p.setPlayerDeck(v) },
        ps(
            listOf("player_buttons", "show_video_switch"), g.onIt, R.string.buttons_in_the_row,
            { it.playerButtons to it.showVideoSwitch }, ::rowButtonsWords,
        ) { p, v -> p.setPlayerButtons(v.first); p.setShowVideoSwitch(v.second) },
        ps(listOf("volume_control"), g.onIt, R.string.the_volume_slider_changes, { it.volumeControl }, { it.label }) { p, v -> p.setVolumeControl(v) },
        ps(
            listOf("player_menu_hidden", "player_menu_tiles"), g.onIt, R.string.edit_this_menu,
            { it.view.parts.menuHidden to it.view.parts.menuTiles }, { v ->
                // Its four tiles by name, then what's folded under More.
                val tiles = v.second.joinToString(" · ") { com.prism.music.ui.player.menuLabel(it) }
                if (v.first.isEmpty()) tiles else tr(R.string.settings_player_chip_info, tiles, tr(R.string.hidden_s, v.first.joinToString(", ") { com.prism.music.ui.player.menuLabel(it) }))
            },
        ) { p, _ -> p.resetMenu() },
        ps(listOf("panel_player"), g.onIt, R.string.settings_player_while_panels_open, { it.view.panelPlayer }, { it.label }) { p, v -> p.setPanelPlayer(v) },
        ps(
            listOf("scroll_shows_controls", "player_remembers_page", "lyrics_steady"), g.onIt, R.string.lyrics_and_the_queue,
            { listOf(it.behavior.scrollShowsControls, it.behavior.playerRemembersPage, it.behavior.lyricsSteady) },
            { v -> PanelChip.toMerged(v).joinToString(" · ") { it.label }.ifEmpty { tr(R.string.none) } },
        ) { p, v -> p.setScrollShowsControls(v[0]); p.setPlayerRemembersPage(v[1]); p.setLyricsSteady(v[2]) },
        ps(
            listOf("queue_headings", "queue_dj_chip", "queue_lengths", "queue_played_moves", "queue_tidy"), g.onIt, R.string.settings_player_in_the_queue,
            { listOf(it.view.queueHeadings, it.view.queueDjChip, it.view.queueLengths, it.view.queuePlayedMoves, it.view.queueTidy) },
            { v -> QueueChip.toMerged(v).joinToString(" · ") { it.label }.ifEmpty { tr(R.string.none) } },
        ) { p, v -> p.setQueueHeadings(v[0]); p.setQueueDjChip(v[1]); p.setQueueLengths(v[2]); p.setQueuePlayedMoves(v[3]); p.setQueueTidy(v[4]) },
        // Modes
        ps(listOf("visualizer"), g.modes, R.string.visualizer, { it.visualizer }, { it.label }) { p, v -> p.setVisualizer(v) },
        ps(listOf("visualizer_color"), g.modes, R.string.visualizer_color, { it.visualizerColor }, { it.label }) { p, v -> p.setVisualizerColor(v) },
        // 1.8.6: from Cover, which keeps to seven rows with Animated covers and Canvas apart.
        ps(
            listOf("cover_flight", "cover_flight_back"), g.modes, R.string.settings_player_cover_animation,
            { it.view.coverFlight to it.view.coverFlightBack }, { CoverAnimation.toMerged(it).label },
        ) { p, v -> p.setCoverFlight(v.first); p.setCoverFlightBack(v.second) },
        flag(listOf("immersive_player"), g.modes, R.string.immersive_mode, { it.immersivePlayer }) { p, v -> p.setImmersivePlayer(v) },
        ps(listOf("immersive_art"), g.modes, R.string.artwork, { it.immersiveArt }, { it.label }, immersive) { p, v -> p.setImmersiveArt(v) },
        ps(listOf("immersive_lyrics"), g.modes, R.string.lyrics, { it.immersiveLyrics }, { it.label }, immersive) { p, v -> p.setImmersiveLyrics(v) },
        flag(listOf("immersive_controls"), g.modes, R.string.keep_controls_on_screen, { it.immersiveControls }, immersive) { p, v -> p.setImmersiveControls(v) },
        flag(listOf("immersive_video"), g.modes, R.string.show_the_video, { it.view.immersiveVideo }, immersive) { p, v -> p.setImmersiveVideo(v) },
        ps(listOf("immersive_background"), g.modes, R.string.background, { it.immersiveBackground }, ::immersiveBackgroundWords, immersive) { p, v -> p.setImmersiveBackground(v) },
        flag(listOf("lock_screen_player"), g.modes, R.string.lock_screen_title, { it.behavior.lockScreen }) { p, v -> p.setLockScreen(v) },
        ps(listOf("lock_screen_layout"), g.modes, R.string.lock_screen_layout_label, { it.behavior.lockScreenLayout }, { it.label }, lock) { p, v -> p.setLockScreenLayout(v) },
        flag(listOf("lock_screen_player_look"), g.modes, R.string.settings_player_same_look, { it.view.lockScreenPlayerLook }, lock) { p, v -> p.setLockScreenPlayerLook(v) },
        ps(
            listOf("lock_screen_volume", "lock_screen_like", "lock_screen_output", "lock_screen_repeat", "lock_screen_up_next"), g.modes, R.string.lock_screen_show,
            { b -> b.behavior.let { listOf(it.lockScreenVolume, it.lockScreenLike, it.lockScreenOutput, it.lockScreenRepeat, it.lockScreenUpNext) } },
            { v -> lockShowLabels().filterIndexed { i, _ -> v[i] }.joinToString(" · ").ifEmpty { tr(R.string.none) } }, lock,
        ) { p, v -> p.setLockScreenVolume(v[0]); p.setLockScreenLike(v[1]); p.setLockScreenOutput(v[2]); p.setLockScreenRepeat(v[3]); p.setLockScreenUpNext(v[4]) },
        ps(listOf("lock_screen_paused"), g.modes, R.string.lock_screen_paused_label, { it.behavior.lockScreenPaused }, { it.label }, lock) { p, v -> p.setLockScreenPaused(v) },
        flag(listOf("lock_screen_hide_song"), g.modes, R.string.lock_screen_hide, { it.behavior.lockScreenHideSong }, lock) { p, v -> p.setLockScreenHideSong(v) },
        flag(listOf("desk_mode"), g.modes, R.string.desk_mode, { it.tv.deskMode }) { p, v -> p.setDeskMode(v) },
    )
}

internal fun playerSetting(key: String): PlayerSetting<*> = playerSettings.first { key in it.keys }

internal fun artistLinksWords(separate: Boolean) = if (separate) tr(R.string.each_name_opens_its_artist) else tr(R.string.one_tap_asks_which)

internal fun immersiveBackgroundWords(b: LyricsBackground) = if (b == LyricsBackground.PLAYER) tr(R.string.the_cover) else b.label

internal fun lockShowLabels() = listOf(tr(R.string.volume), tr(R.string.lock_screen_show_like), tr(R.string.lock_screen_show_output), tr(R.string.repeat), tr(R.string.up_next))

/** The row's buttons in words; the Video button only counts while the music video is on. */
internal fun rowButtonsWords(v: Pair<List<PlayerButton>, Boolean>): String =
    v.first.filter { it != PlayerButton.VIDEO || v.second }.joinToString(" · ") { it.label }

/**
 * "Reset this page": the look settings go back by their keys ([playerLookKeys]); the page's settings that aren't part
 * of a Look (the lock screen, desk mode, Immersive…), and the two whose keys another page's reset still lists, by
 * their setters.
 */
internal fun resetPlayerPage(prefs: SettingsRepository, s: AppSettings) {
    val rest = playerSettings.filter { it.changed(s) && !playerLookKeys.containsAll(it.keys) }
    prefs.resetLookKeys(playerLookKeys)
    rest.forEach { it.reset(prefs) }
}

// ---------------------------------------------------------------------------- Sheets

/**
 * A Link row that opens a sheet of its own: the kit's [RowsSheet] titled [sheetTitle]. [sheetTitle] null: the content
 * brings its own heading ([UntitledSheet]).
 */
@Composable
internal fun SheetRow(
    title: String,
    value: String,
    info: String? = null,
    changed: Boolean = false,
    icon: ImageVector? = null,
    sheetTitle: String? = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title, "player-sheet") { mutableStateOf(false) }
    LinkRow(title, value = value, icon = icon, info = info, changed = changed) { open = true }
    if (open) {
        if (sheetTitle != null) RowsSheet(sheetTitle, { open = false }, info, content)
        else UntitledSheet({ open = false }, content)
    }
}

/**
 * [RowsSheet] without its title, for an editor that heads itself (`RowButtons`: its "Buttons in the row" label carries
 * its own ⓘ). Kit gap: [RowsSheet] always draws a title. The same grab handle, height, scrolling and tag as the kit's.
 */
@Composable
private fun UntitledSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    PrismSheet(onDismiss, containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface, dragHandle = null) { _ ->
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.3f)))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f).testTag(SettingsTags.SHEET)
                .nestedScroll(KeepUpwardScroll).verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------- The pinned preview

/**
 * The page's pinned preview: the player as the settings draw it now (the Looks page's small player: its background,
 * cover, title, progress bar and controls) with the layout and background in words beside it; as a strip, smaller.
 */
@Composable
internal fun PlayerPagePreview(strip: Boolean) {
    val s = LocalAppSettings.current
    val song by LocalContainer.current.player.currentSong.collectAsState()
    Row(
        Modifier.fillMaxSize().padding(horizontal = if (strip) 14.dp else 18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (strip) 12.dp else 18.dp, Alignment.CenterHorizontally),
    ) {
        // Performance mode (1.8.5): a still phone, cheap to open (no live player, blur or motion); the same words beside it.
        if (s.performanceMode) PlainPlayerPhone(if (strip) 28.dp else 86.dp)
        else PreviewPhone(if (strip) 28.dp else 86.dp, Modifier.testTag(PlayerPreviewTags.LIVE)) { LookTheme(s, true) { colors -> MiniPlayerScreen(colors, song) } }
        Column(Modifier.weight(1f, fill = false)) {
            Text(song?.title ?: tr(R.string.northern_lights), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playerValue(s, true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (strip) 1 else 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Which phone [PlayerPagePreview] drew, for tests. */
internal object PlayerPreviewTags {
    const val LIVE = "settings:player:preview:live"
    const val PLAIN = "settings:player:preview:plain"
}

/**
 * The player drawn plainly, [width] wide (as [PreviewPhone]): a cover, the title's lines, a progress bar and the
 * controls in one still drawing, without the player's background, artwork, fonts or an offscreen layer.
 */
@Composable
private fun PlainPlayerPhone(width: androidx.compose.ui.unit.Dp) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(width * 0.12f)
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val hues = com.prism.music.ui.screens.standIns[0]
    androidx.compose.foundation.Canvas(
        Modifier.size(width, width * 2.05f).testTag(PlayerPreviewTags.PLAIN).clip(shape).background(Color(0xFF121212)).border(1.dp, edge, shape),
    ) {
        val w = size.width
        val h = size.height
        val side = w * 0.12f
        val cover = w - side * 2
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.05f)
        drawRoundRect(Brush.linearGradient(hues, androidx.compose.ui.geometry.Offset(side, h * 0.14f), androidx.compose.ui.geometry.Offset(side + cover, h * 0.14f + cover)),
            androidx.compose.ui.geometry.Offset(side, h * 0.14f), androidx.compose.ui.geometry.Size(cover, cover), r)
        val line = h * 0.022f
        val lr = androidx.compose.ui.geometry.CornerRadius(line / 2)
        var y = h * 0.14f + cover + h * 0.05f
        drawRoundRect(Color.White.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(side, y), androidx.compose.ui.geometry.Size(cover * 0.7f, line), lr)
        y += line * 2
        drawRoundRect(Color.White.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(side, y), androidx.compose.ui.geometry.Size(cover * 0.45f, line * 0.8f), lr)
        y += line * 3
        drawRoundRect(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(side, y), androidx.compose.ui.geometry.Size(cover, line * 0.4f), lr)
        drawRoundRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(side, y), androidx.compose.ui.geometry.Size(cover * 0.35f, line * 0.4f), lr)
        y += line * 4
        for (i in -1..1) drawCircle(Color.White.copy(alpha = if (i == 0) 0.95f else 0.6f), if (i == 0) w * 0.08f else w * 0.045f, androidx.compose.ui.geometry.Offset(w / 2 + i * w * 0.25f, y))
    }
}

// ---------------------------------------------------------------------------- Examples the old page drew inline

/** The bottom row in a capsule or apart (as `ButtonsPillCards` drew each card). */
@Composable
internal fun ButtonsPillSample(pill: Boolean, chosen: List<PlayerButton>) {
    SampleScreen(Modifier.padding(6.dp).fillMaxWidth().height(52.dp)) {
        Row(
            if (pill) Modifier.align(Alignment.Center).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 8.dp, vertical = 6.dp)
            else Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalArrangement = if (pill) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) { chosen.forEach { Icon(buttonIcon(it), it.label, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.85f)) } }
    }
}

/** The player's cover in [shape], with the cover's own corners. */
@Composable
internal fun PlayerCoverShapeSample(shape: CoverShape, corners: Int, size: Int = 56) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).clip(CoverShapes.of(shape, corners.dp)).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF7A59)))))
    }
}

/**
 * The player with its lyrics open, drawn small: lines of lyrics, and the controls under them as [mode] leaves them.
 * (As `PanelPlayerSample` in OldPlayer.kt, which is private there and not to be edited.)
 */
@Composable
internal fun PlayerPanelSample(mode: PanelPlayer) {
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 18.dp, vertical = 6.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(ink.copy(alpha = 0.06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
        val controls = mode != PanelPlayer.HIDDEN
        val small = mode == PanelPlayer.SMALL
        val linesEnd = if (small) h * 0.76f else if (controls) h * 0.62f else h * 0.92f
        var y = h * 0.12f
        var i = 0
        while (y < linesEnd) {
            drawRoundRect(
                ink.copy(alpha = if (i == 1) 0.85f else 0.3f), androidx.compose.ui.geometry.Offset(w * 0.12f, y),
                androidx.compose.ui.geometry.Size(w * (if (i % 2 == 0) 0.62f else 0.74f), h * 0.07f), androidx.compose.ui.geometry.CornerRadius(h * 0.035f),
            )
            y += h * 0.14f; i++
        }
        if (small) {
            drawRect(ink.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.83f), androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.02f))
            listOf(0.4f, 0.5f, 0.6f).forEachIndexed { k, x ->
                drawCircle(ink.copy(alpha = 0.85f), if (k == 1) h * 0.05f else h * 0.03f, androidx.compose.ui.geometry.Offset(w * x, h * 0.92f))
            }
        } else if (controls) {
            val a = if (mode == PanelPlayer.AUTO_HIDE) 0.3f else 0.85f
            drawRect(ink.copy(alpha = a * 0.6f), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.72f), androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.025f))
            listOf(0.32f, 0.5f, 0.68f).forEachIndexed { k, x ->
                drawCircle(ink.copy(alpha = a), if (k == 1) h * 0.08f else h * 0.05f, androidx.compose.ui.geometry.Offset(w * x, h * 0.86f))
            }
        }
    }
}
