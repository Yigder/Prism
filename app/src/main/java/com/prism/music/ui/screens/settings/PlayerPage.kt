package com.prism.music.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BleedFit
import com.prism.music.data.prefs.CoverShape
import com.prism.music.data.prefs.CoverSize
import com.prism.music.data.prefs.DeckPart
import com.prism.music.data.prefs.ImmersiveArt
import com.prism.music.data.prefs.ImmersiveLyrics
import com.prism.music.data.prefs.LockLayout
import com.prism.music.data.prefs.LockPaused
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.PanelPlayer
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.PlayerLayout
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.PlayerTimes
import com.prism.music.data.prefs.PlayerTitleAlign
import com.prism.music.data.prefs.PlayerTitleSize
import com.prism.music.data.prefs.PlayerTitleWeight
import com.prism.music.data.prefs.ScrubberColor
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.ScrubberThumb
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.data.prefs.VisualizerStyle
import com.prism.music.data.prefs.VolumeControl
import com.prism.music.data.prefs.canHide
import com.prism.music.data.prefs.shows
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.screens.ArtistLinksSample
import com.prism.music.ui.screens.KnobSample
import com.prism.music.ui.screens.LayoutSample
import com.prism.music.ui.screens.LyricsSample
import com.prism.music.ui.screens.Needs
import com.prism.music.ui.screens.OrderRow
import com.prism.music.ui.screens.PerformancePaused
import com.prism.music.ui.screens.PlayerBackgroundSample
import com.prism.music.ui.screens.PlayerPartsPreview
import com.prism.music.ui.screens.RowButtons
import com.prism.music.ui.screens.ScrubberStyleSample
import com.prism.music.ui.screens.SettingEntry
import com.prism.music.ui.screens.SettingNeeds
import com.prism.music.ui.screens.SettingsSection
import com.prism.music.ui.screens.ShowOption
import com.prism.music.ui.screens.TimesSample
import com.prism.music.ui.screens.TitleSample
import com.prism.music.ui.screens.VisualizerSample
import com.prism.music.ui.screens.backgroundSample
import com.prism.music.ui.screens.currentLook
import com.prism.music.ui.screens.deckIcon
import com.prism.music.ui.screens.deckPart
import com.prism.music.ui.screens.meshHeldBy
import com.prism.music.ui.screens.meshMovement
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlin.math.roundToInt

private val page = SettingsPageId.PLAYER

/**
 * Settings → Player (1.8.4's map, plan §2.3), rebuilt with the kit: six groups (Layout · Progress bar and times ·
 * Title · Cover · On it · Modes) under the player drawn as it is now. This file is the only place for the page: its
 * composable, its lines in Settings' search ([playerIndex]), its "Reset this page" keys ([playerLookKeys]), its line on
 * the root ([playerValue]) and the old group names it answers to ([playerOldGroups]). The merged controls, the list of
 * the page's settings and its sheets are in `PlayerParts.kt`.
 */
@Composable
fun PlayerPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val changed = playerSettings.filter { it.changed(s) }
    SettingsPageScaffold(
        page.title, bottomPadding, showBack = showBack, onSearch = onSearch,
        reset = PageReset(changed.map { it.line(s) }) { resetPlayerPage(prefs, s) },
        find = find?.let { playerOldGroups[it] ?: it },
        preview = { strip -> PlayerPagePreview(strip) },
    ) {
        // A page of the look says when Performance mode is pausing its effects (a state, not a row).
        if (s.performanceMode) item("performance") { PerformancePaused() }
        group(PlayerGroups.layout) { PlayerLayoutRows() }
        group(PlayerGroups.progress) { PlayerProgressRows() }
        group(PlayerGroups.title) { PlayerTitleRows() }
        group(PlayerGroups.cover) { PlayerCoverRows() }
        group(PlayerGroups.onIt) { PlayerOnItRows() }
        group(PlayerGroups.modes) { PlayerModesRows() }
    }
}

/** Whether the setting stored as [key] differs from how Prism comes (the row's dot). */
@Composable
private fun ch(key: String): Boolean = playerSetting(key).changed(LocalAppSettings.current)

private fun words(vararg parts: String?): String? = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

private fun chipInfo(vararg chips: Pair<String, String>): String = chips.joinToString("\n") { tr(R.string.settings_player_chip_info, it.first, it.second) }

// ---------------------------------------------------------------------------- Layout

@Composable
internal fun PlayerLayoutRows() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val song by c.player.currentSong.collectAsState()
    val art = song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
    val layoutInfo = when (s.playerLayout) {
        PlayerLayout.MINIMAL -> tr(R.string.minimal_swipe_the_cover_for_the)
        PlayerLayout.COVER -> tr(R.string.cover_tap_the_cover_for_the)
        else -> ""
    } + tr(R.string.tablets_and_phones_turned_sideways_keep)
    PickCardsRow(
        tr(R.string.layout), PlayerLayout.entries, s.playerLayout, { it.label }, info = layoutInfo, changed = ch("player_layout"),
        onPick = { prefs.setPlayerLayout(it) },
    ) { LayoutSample(it) }
    if (s.playerLayout == PlayerLayout.VINYL) SwitchRow(tr(R.string.tonearm), s.vinylTonearm, info = tr(R.string.swings_onto_the_record_while_it), changed = ch("vinyl_tonearm")) { prefs.setVinylTonearm(it) }
    PickCardsRow(
        tr(R.string.background), PlayerBackground.entries, s.playerBackground, { it.label },
        info = tr(R.string.player_background_white_info) + " " + tr(R.string.cover_gradient_info), changed = ch("player_background"), columns = 3,
        onPick = { prefs.setPlayerBackground(it) },
    ) { PlayerBackgroundSample(it, art) }
    // What the picked background can be tuned by takes the place of "Background movement".
    when (s.playerBackground) {
        PlayerBackground.MESH -> SliderRow(
            tr(R.string.background_movement), s.meshSpeed, 0f..1f, ::movementWords,
            // What else holds it still says so (#284).
            info = if (s.meshSpeed > 0f) meshHeldBy(s, LocalContext.current) else null, changed = ch("mesh_speed"),
        ) { v -> meshMovement(v).let { (speed, colors) -> prefs.setMeshSpeed(speed); prefs.setMeshIntensity(colors) } }
        PlayerBackground.GRADIENT -> SliderRow(
            tr(R.string.gradient_depth), s.view.gradientDepth, 0f..1f, ::percent, info = tr(R.string.gradient_depth_info), changed = ch("player_gradient_depth"),
        ) { prefs.setGradientDepth(it) }
        PlayerBackground.BLUR -> {
            ShowBackgroundRow()
            SliderRow(tr(R.string.softness), s.playerBlur, 0.5f..1.5f, ::percent, changed = ch("player_blur")) { prefs.setPlayerBlur(it) }
        }
        PlayerBackground.APPEARANCE -> {
            val own = s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP]
            LinkRow(
                tr(R.string.which_background),
                value = if (own == null) tr(R.string.same_as_home_darkened, com.prism.music.ui.theme.Backdrop.parse(s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name]).first.label)
                else com.prism.music.ui.theme.Backdrop.parse(own).first.label + tr(R.string.darkened_2),
            ) { nav.go(Routes.BACKGROUNDS + "?player=true") }
            ShowBackgroundRow()
        }
        else -> {}
    }
    PickListRow(tr(R.string.controls), TransportStyle.entries, s.transportStyle, { it.label }, changed = ch("transport_style")) { prefs.setTransportStyle(it) }
    SwitchRow(tr(R.string.ring_on_the_play_button), s.view.playRing, info = tr(R.string.the_split_light_around_play_and_pause), changed = ch("play_ring")) { prefs.setPlayRing(it) }
    PickCardsRow(
        tr(R.string.buttons), listOf(true, false), s.view.buttonsPill, { if (it) tr(R.string.in_a_capsule) else tr(R.string.apart) },
        changed = ch("player_buttons_pill"), onPick = { prefs.setButtonsPill(it) },
    ) { ButtonsPillSample(it, s.playerButtons) }
    val video = VideoControls.toMerged(s.view.videoControlsHide to s.view.videoButtonsHide)
    PickListRow(
        tr(R.string.settings_player_controls_over_videos), VideoControls.choices(video), video, { it.label },
        info = words(tr(R.string.while_a_music_video_plays_the), tr(R.string.buttons_on_videos_wait_for_a_tap_info)), changed = ch("video_controls_hide"),
    ) { v -> v.toStored().let { (controls, buttons) -> prefs.setVideoControlsHide(controls); prefs.setVideoButtonsHide(buttons) } }
}

/** How much of a blurred cover or your own background shows (#309: down to a fully dark player). */
@Composable
private fun ShowBackgroundRow() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    SliderRow(
        tr(R.string.show_background), com.prism.music.ui.player.backdropShowOnSlider(s.playerBackdropShow), 0f..1f, ::backdropShowWords,
        info = tr(R.string.show_background_info), changed = ch("player_backdrop_show"),
    ) { prefs.setPlayerBackdropShow(com.prism.music.ui.player.backdropShowFromSlider(it)) }
}

// ---------------------------------------------------------------------------- Progress bar and times

@Composable
internal fun PlayerProgressRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    PickCardsRow(
        tr(R.string.style), ScrubberStyle.entries, s.scrubberStyle, { it.label },
        info = when (s.scrubberStyle) {
            ScrubberStyle.WAVE -> tr(R.string.waveform_each_song_s_own_shape)
            ScrubberStyle.RIBBONS -> tr(R.string.ribbons_flow_only_while_a_song)
            else -> null
        },
        changed = ch("scrubber_style"), onPick = { prefs.setScrubberStyle(it) },
    ) { ScrubberStyleSample(it, s.scrubberColor) }
    ChoiceRow(tr(R.string.progress_bar_color), ScrubberColor.entries, s.scrubberColor, { it.label }, changed = ch("scrubber_color")) { prefs.setScrubberColor(it) }
    SliderRow(
        tr(R.string.progress_bar_thickness), s.scrubberThickness, 0.6f..2f, ::thicknessWords,
        info = if (s.scrubberStyle == ScrubberStyle.WAVE) tr(R.string.the_waveform_keeps_its_own_height) else null, changed = ch("scrubber_thickness"),
    ) { prefs.setScrubberThickness(if (it in 0.93f..1.07f) 1f else it) }
    PickCardsRow(
        tr(R.string.knob_at_the_playhead), ScrubberThumb.entries, s.scrubberThumb, { it.label }, changed = ch("scrubber_thumb"),
        onPick = { prefs.setScrubberThumb(it) },
    ) { KnobSample(it, s.scrubberThickness) }
    SwitchRow(
        tr(R.string.volume_bar_like_the_progress_bar), s.volumeMatchesProgress, info = tr(R.string.same_style_and_thickness), changed = ch("volume_matches_progress"),
    ) { prefs.setVolumeMatchesProgress(it) }
    PickCardsRow(tr(R.string.times), PlayerTimes.entries, s.playerTimes, { it.label }, changed = ch("player_times"), onPick = { prefs.setPlayerTimes(it) }) { TimesSample(it) }
    // 1.8.4: until now only a tap on the time on the player changed it.
    ChoiceRow(
        tr(R.string.settings_player_right_hand_time), listOf(false, true), s.showTrackLength, { if (it) tr(R.string.song_length) else tr(R.string.time_left) },
        info = tr(R.string.settings_player_right_hand_time_info), changed = ch("show_track_length"),
    ) { prefs.setShowTrackLength(it) }
    // The bar only takes the crossfade's shape while Crossfade is on, so the row only shows then.
    if (s.mix) SwitchRow(
        tr(R.string.crossfade_on_the_progress_bar), s.view.crossfadeShape, info = tr(R.string.crossfade_shape_info), changed = ch("crossfade_shape"),
    ) { prefs.setCrossfadeShape(it) }
}

// ---------------------------------------------------------------------------- Title

@Composable
internal fun PlayerTitleRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    PickCardsRow(
        tr(R.string.alignment), PlayerTitleAlign.entries, s.playerTitleAlign, { it.label }, changed = ch("player_title_align"),
        onPick = { prefs.setPlayerTitleAlign(it) },
    ) { TitleSample(it) }
    ChoiceRow(tr(R.string.size), PlayerTitleSize.entries, s.playerTitleSize, { it.label }, changed = ch("player_title_size")) { prefs.setPlayerTitleSize(it) }
    PickCardsRow(
        tr(R.string.weight), PlayerTitleWeight.entries, s.type.playerTitleWeight, { it.label }, info = tr(R.string.light_is_prism_s_own_and),
        changed = ch("player_title_weight"), columns = 3, onPick = { prefs.setPlayerTitleWeight(it) },
    ) { TitleSample(s.playerTitleAlign, it) }
    SwitchRow(
        tr(R.string.scrolling_titles), s.playerTitleScroll, info = tr(R.string.a_long_title_scrolls_across_off), changed = ch("player_title_scroll"),
    ) { prefs.setPlayerTitleScroll(it) }
    PickCardsRow(
        tr(R.string.each_artist_its_own_link), listOf(false, true), s.view.artistLinks, ::artistLinksWords, info = tr(R.string.each_artist_its_own_link_info),
        changed = ch("player_artist_links"), onPick = { prefs.setArtistLinks(it) },
    ) { ArtistLinksSample(it) }
}

// ---------------------------------------------------------------------------- Cover

@Composable
internal fun PlayerCoverRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    ChoiceRow(tr(R.string.cover_size), CoverSize.entries, s.coverSize, { it.label }, changed = ch("cover_size")) { prefs.setCoverSize(it) }
    SliderRow(
        tr(R.string.cover_corners), s.playerArtCorners.toFloat(), 0f..32f, { tr(R.string.dp, it.roundToInt()) }, steps = 7, changed = ch("player_art_corners"),
    ) { prefs.setPlayerArtCorners(it.roundToInt()) }
    // The player cover's shape has its one home here (plan §2.6).
    PickCardsRow(
        tr(R.string.settings_player_cover_shape), CoverShape.entries, s.shapes.player, { it.label }, changed = ch("cover_shape_player"), columns = 3,
        thumbnail = { PlayerCoverShapeSample(it, 6, size = 22) }, onPick = { prefs.setCoverShape("player", it) },
    ) { PlayerCoverShapeSample(it, s.playerArtCorners) }
    val edge = EdgeToEdge.toMerged(s.fullBleedArtwork to s.videoFullBleed)
    ChoiceRow(
        tr(R.string.settings_player_edge_to_edge), EdgeToEdge.entries, edge, { it.label },
        info = words(tr(R.string.the_cover_runs_to_the_screen), tr(R.string.a_music_video_runs_to_the)), changed = ch("full_bleed_artwork"),
    ) { v -> prefs.setFullBleedArtwork(v.cover); prefs.setVideoFullBleed(v.video) }
    if (s.fullBleedArtwork) ChoiceRow(tr(R.string.how_the_cover_fits), BleedFit.entries, s.view.bleedFit, { it.label }, changed = ch("bleed_fit")) { prefs.setBleedFit(it) }
    // #293: over a full-bleed picture, smaller controls and a later fade.
    if (s.fullBleedArtwork || s.videoFullBleed) SwitchRow(
        tr(R.string.more_room_for_the_picture), s.view.bleedRoomy, info = tr(R.string.more_room_for_the_picture_info), changed = ch("bleed_roomy"),
    ) { prefs.setBleedRoomy(it) }
    SwitchRow(
        tr(R.string.shrink_when_paused), s.artShrinkOnPause,
        // With an edge-to-edge cover the banner covers the cover that shrinks: a state, so it's said on the row.
        value = if (s.fullBleedArtwork) tr(R.string.not_with_full_bleed_artwork_the_banner_covers_it) else null,
        info = tr(R.string.the_cover_eases_back_while_paused), changed = ch("art_shrink_on_pause"),
    ) { prefs.setArtShrinkOnPause(it) }
    val flight = s.view.coverFlight to s.view.coverFlightBack
    ChoiceRow(
        tr(R.string.settings_player_cover_animation), CoverAnimation.entries, CoverAnimation.toMerged(flight), { it.label },
        info = words(tr(R.string.opening_the_player_the_mini_player), tr(R.string.instead_of_the_whole_player_sliding), tr(R.string.cover_flies_back_too_info)),
        changed = ch("cover_flight"),
    ) { v -> v.toStored(flight).let { (open, back) -> prefs.setCoverFlight(open); prefs.setCoverFlightBack(back) } }
    // 1.8.6: Canvas (was "Animated covers") with its choices in one sheet: the Cover group stays at its seven rows.
    SheetRow(
        tr(R.string.canvas), canvasWords(s),
        info = words(tr(R.string.motion_artwork_and_video_covers_turn), tr(R.string.the_moving_artwork_fills_the_whole)),
        changed = listOf("canvas_enabled", "canvas_use_videos", "canvas_videos_cellular", "canvas_video_first", "canvas_keep_controls", "canvas_lighter").any { ch(it) },
    ) { CanvasOptions() }
}

/**
 * Canvas's sheet (plan-canvas §3.1, §3.6, §11a R1, R6): where it shows, then the rows that only apply to what is picked.
 * Picking Full screen while music videos are off asks whether they may be used; either answer keeps Full screen.
 */
@Composable
private fun CanvasOptions() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val c = s.behavior.canvas
    val stored = s.canvasEnabled to s.canvasFillsBackground
    val now = AnimatedCovers.toMerged(stored)
    var asking by remember { mutableStateOf(false) }
    ChoiceRow(tr(R.string.canvas_where), AnimatedCovers.entries, now, { it.label }, changed = ch("canvas_enabled")) { v ->
        v.toStored(stored).let { (on, fills) -> prefs.setCanvasEnabled(on); prefs.setCanvasFillsBackground(fills) }
        if (AnimatedCovers.asksForVideos(now, v, c.useVideos)) asking = true
    }
    val on = now != AnimatedCovers.OFF
    if (on) SwitchRow(
        tr(R.string.canvas_use_videos), c.useVideos, info = tr(R.string.canvas_use_videos_info), changed = ch("canvas_use_videos"),
    ) { prefs.setCanvasUseVideos(it) }
    // Its mobile-data switch sits with it (owner, 2026-11-13: everything Canvas under Canvas), not among the chips of
    // Downloads & data's "Use mobile data for".
    if (on && c.useVideos) SwitchRow(
        tr(R.string.use_mobile_data), c.videosOnCellular, info = tr(R.string.canvas_from_videos_info), changed = ch("canvas_videos_cellular"),
    ) { prefs.setCanvasVideosOnCellular(it) }
    if (on && c.useVideos) ChoiceRow(
        tr(R.string.canvas_both), listOf(false, true), c.videoFirst, ::canvasBothWords, info = tr(R.string.canvas_both_info), changed = ch("canvas_video_first"),
    ) { prefs.setCanvasVideoFirst(it) }
    if (now == AnimatedCovers.FILL_THE_PLAYER) SwitchRow(
        tr(R.string.canvas_keep_controls), c.keepControls, info = tr(R.string.canvas_keep_controls_info), changed = ch("canvas_keep_controls"),
    ) { prefs.setCanvasKeepControls(it) }
    if (on && c.useVideos) SwitchRow(
        tr(R.string.canvas_lighter), c.lighter, info = tr(R.string.canvas_lighter_info), changed = ch("canvas_lighter"),
    ) { prefs.setCanvasLighter(it) }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(tr(R.string.canvas_ask_title)) },
        text = { Text(tr(R.string.canvas_ask_text)) },
        confirmButton = { PrismTextButton(onClick = { asking = false; prefs.setCanvasUseVideos(true) }) { Text(tr(R.string.canvas_use_videos)) } },
        dismissButton = { PrismTextButton(onClick = { asking = false; prefs.setCanvasUseVideos(false) }) { Text(tr(R.string.not_now)) } },
    )
}

// ---------------------------------------------------------------------------- On it

@Composable
internal fun PlayerOnItRows() {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    var editMenu by remember { mutableStateOf(false) }
    // The player drawn as it is: every part a tap to hide or show it.
    SheetRow(
        tr(R.string.what_s_on_the_player), hiddenPartsWords(s), info = tr(R.string.tap_a_part_of_the_player_to_hide_or_show_it),
        changed = ch("player_hidden_parts"),
    ) {
        val now = LocalAppSettings.current
        PlayerPartsPreview(now, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { part -> prefs.setPartShown(part, !now.shows(part)) }
    }
    SheetRow(
        tr(R.string.under_the_title), s.playerDeck.joinToString(" · ") { it.label }, info = tr(R.string.top_to_bottom_as_they_sit), changed = ch("player_deck"),
    ) { DeckOrder() }
    if (s.shows(PlayerPart.BUTTONS)) SheetRow(
        tr(R.string.buttons_in_the_row), rowButtonsWords(s.playerButtons to s.showVideoSwitch), changed = ch("player_buttons"), sheetTitle = null,
    ) { RowButtons() }
    // The ⋯ menu's tiles and actions: its one row in Settings (the menu itself has the same editor).
    LinkRow(
        tr(R.string.edit_this_menu), value = tr(R.string.the_player_s_menu_its_tiles_and_actions), icon = PrismIcons.More, changed = ch("player_menu_hidden"),
    ) { editMenu = true }
    if (editMenu) com.prism.music.ui.player.MenuEditorSheet { editMenu = false }
    PickCardsRow(
        tr(R.string.settings_player_while_panels_open), PanelPlayer.entries, s.view.panelPlayer, { it.label }, info = tr(R.string.the_player_s_controls_while_the),
        changed = ch("panel_player"), onPick = { prefs.setPanelPlayer(it) },
    ) { PlayerPanelSample(it) }
    val autoHide = s.view.panelPlayer == PanelPlayer.AUTO_HIDE
    ShowChipsRow(
        tr(R.string.lyrics_and_the_queue),
        listOfNotNull(
            // Only "Hide after a moment" hides controls for a scroll to bring back.
            if (autoHide) ShowOption(PanelChip.SCROLL_SHOWS_CONTROLS.label, s.behavior.scrollShowsControls) { prefs.setScrollShowsControls(it) } else null,
            ShowOption(PanelChip.REOPEN_LAST_PAGE.label, s.behavior.playerRemembersPage) { prefs.setPlayerRemembersPage(it) },
            ShowOption(PanelChip.STEADIER_LYRICS.label, s.behavior.lyricsSteady) { prefs.setLyricsSteady(it) },
        ),
        info = chipInfo(
            PanelChip.SCROLL_SHOWS_CONTROLS.label to tr(R.string.off_scrolling_back_up_to_an),
            PanelChip.REOPEN_LAST_PAGE.label to tr(R.string.reopen_on_the_last_page_info),
            PanelChip.STEADIER_LYRICS.label to tr(R.string.steadier_lyrics_info),
        ),
        changed = ch("scroll_shows_controls"),
    )
    ShowChipsRow(
        tr(R.string.settings_player_in_the_queue),
        listOf(
            ShowOption(QueueChip.HEADINGS.label, s.view.queueHeadings) { prefs.setQueueHeadings(it) },
            ShowOption(QueueChip.DJ.label, s.view.queueDjChip) { prefs.setQueueDjChip(it) },
            ShowOption(QueueChip.LENGTHS.label, s.view.queueLengths) { prefs.setQueueLengths(it) },
            ShowOption(QueueChip.MOVE_PLAYED.label, s.view.queuePlayedMoves) { prefs.setQueuePlayedMoves(it) },
            ShowOption(QueueChip.TIDIER.label, s.view.queueTidy) { prefs.setQueueTidy(it) },
        ),
        info = chipInfo(
            QueueChip.HEADINGS.label to tr(R.string.off_the_queue_is_one_list),
            QueueChip.DJ.label to tr(R.string.dj_in_the_queue_info),
            QueueChip.MOVE_PLAYED.label to tr(R.string.move_songs_you_have_played_info),
            QueueChip.TIDIER.label to tr(R.string.tidier_queue_info),
        ),
        changed = ch("queue_headings"),
    )
    // GitHub #116: a volume just for Prism; only while the volume bar is on the player.
    if (s.shows(PlayerPart.VOLUME)) ChoiceRow(
        tr(R.string.the_volume_slider_changes), VolumeControl.entries, s.volumeControl, { it.label },
        info = words(tr(R.string.like_the_phone_s_volume_buttons), tr(R.string.only_prism_gets_quieter_or_louder)), changed = ch("volume_control"),
    ) { prefs.setVolumeControl(it) }
}

/** The parts under the title, top to bottom as they sit on the player, to drag (and tick, where a part can hide). */
@Composable
private fun DeckOrder() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val order = s.playerDeck
    com.prism.music.ui.components.ReorderList(order, { it }, { prefs.setPlayerDeck(it) }) { deck, _, _, mod ->
        val part = deckPart(deck)
        OrderRow(
            deck.label, deckIcon(deck), part == null || s.shows(part), canCheck = part != null && s.canHide(part),
            onCheck = { v -> if (part != null) prefs.setPartShown(part, v) },
            modifier = mod, grip = Modifier.dragHandle(),
        )
    }
    if (order != DeckPart.entries) com.prism.music.ui.components.PrismTextButton({ prefs.setPlayerDeck(DeckPart.entries) }, Modifier.padding(start = 12.dp)) {
        Text(tr(R.string.back_to_prism_s_order))
    }
}

// ---------------------------------------------------------------------------- Modes

@Composable
internal fun PlayerModesRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val context = LocalContext.current
    val b = s.behavior
    PlayerVisualizerRows()

    SwitchRow(
        tr(R.string.immersive_mode), s.immersivePlayer, info = words(tr(R.string.the_cover_fills_the_screen_lyrics), tr(R.string.like_a_lyric_video_tap_the)),
        changed = ch("immersive_player"),
    ) { prefs.setImmersivePlayer(it) }
    // Immersive's options (kept, plan §2.6) and its background (its one home) show only while it's on, in one sheet.
    if (s.immersivePlayer) SheetRow(
        tr(R.string.settings_player_immersive_options),
        listOf(s.immersiveArt.label, s.immersiveLyrics.label, immersiveBackgroundWords(s.immersiveBackground)).joinToString(" · "),
        changed = listOf("immersive_art", "immersive_lyrics", "immersive_controls", "immersive_video", "immersive_background").any { ch(it) },
    ) { ImmersiveOptions() }

    val gate = com.prism.music.ui.lockscreen.LockScreenGate
    val resumed = com.prism.music.ui.components.rememberResumeCount()
    // Read again on coming back from Android's page.
    val canStart = remember(resumed, b.lockScreen) { gate.canStartDirectly(context) }
    val refused = remember(resumed, b.lockScreen) { com.prism.music.ui.lockscreen.LockScreenRefusals.lately(context) }
    SwitchRow(
        tr(R.string.lock_screen_title), b.lockScreen,
        // What stands in its way is a state, said on the row; the steps are in its options.
        value = when {
            !b.lockScreen -> null
            !canStart -> tr(R.string.lock_screen_needs_permission)
            refused -> tr(R.string.lock_screen_refused)
            else -> null
        },
        info = words(tr(R.string.lock_screen_detail), tr(R.string.lock_screen_info)), changed = ch("lock_screen_player"),
    ) { on ->
        prefs.setLockScreen(on)
        // From Android 13 an app installed from a file only gets "App was denied access" on that page: the steps come first.
        if (on && !gate.canStartDirectly(context) && !gate.mayBeRestricted) gate.openPermissionPage(context)
    }
    // With the lock screen on its options would be 8 to 11 rows: they open in a sheet from this row (plan §2.11).
    if (b.lockScreen) SheetRow(
        tr(R.string.settings_player_lock_options),
        listOf(b.lockScreenLayout.label, b.lockScreenPaused.label).joinToString(" · "),
        changed = playerSettings.any { it.sheet == tr(R.string.settings_player_lock_options) && it.changed(s) },
    ) { LockScreenOptions(canStart) }

    // #265: the TV's ambient screen on a phone at its stand.
    SwitchRow(tr(R.string.desk_mode), s.tv.deskMode, info = words(tr(R.string.desk_mode_detail), tr(R.string.desk_mode_info)), changed = ch("desk_mode")) { prefs.setDeskMode(it) }
}

/** The visualizer's rows, the first of Modes: on the page, and in "Customize this" for the player. */
@Composable
internal fun PlayerVisualizerRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val visualizerOn = s.visualizer != VisualizerStyle.OFF
    PickCardsRow(
        tr(R.string.visualizer), VisualizerStyle.entries, s.visualizer, { it.label },
        // Why it doesn't move is a state: it's said with the explanation.
        info = words(tr(R.string.it_listens_to_prism_s_own), if (visualizerOn && s.reduceMotion) tr(R.string.reduce_motion_is_on_so_it) else null),
        changed = ch("visualizer"), onPick = { prefs.setVisualizer(it) },
    ) { VisualizerSample(it, s.visualizerColor) }
    if (visualizerOn) ChoiceRow(tr(R.string.visualizer_color), ScrubberColor.entries, s.visualizerColor, { it.label }, changed = ch("visualizer_color")) { prefs.setVisualizerColor(it) }
}

@Composable
private fun ImmersiveOptions() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    ChoiceRow(tr(R.string.artwork), ImmersiveArt.entries, s.immersiveArt, { it.label }, changed = ch("immersive_art")) { prefs.setImmersiveArt(it) }
    ChoiceRow(tr(R.string.lyrics), ImmersiveLyrics.entries, s.immersiveLyrics, { it.label }, changed = ch("immersive_lyrics")) { prefs.setImmersiveLyrics(it) }
    SwitchRow(
        tr(R.string.keep_controls_on_screen), s.immersiveControls, info = tr(R.string.previous_play_and_next_stay_up), changed = ch("immersive_controls"),
    ) { prefs.setImmersiveControls(it) }
    SwitchRow(
        tr(R.string.show_the_video), s.view.immersiveVideo, info = words(tr(R.string.a_playing_music_video_behind_the), tr(R.string.when_a_music_video_is_playing)),
        changed = ch("immersive_video"),
    ) { prefs.setImmersiveVideo(it) }
    PickCardsRow(
        tr(R.string.background), LyricsBackground.entries, s.immersiveBackground, ::immersiveBackgroundWords, changed = ch("immersive_background"),
        onPick = { prefs.setImmersiveBackground(it) },
    ) { LyricsSample(currentLook().withoutCard(s.lyricsOthersAlpha), Modifier.fillMaxWidth(), ground = backgroundSample(it, immersive = true), size = 0.7f, lines = 2) }
}

/** Prism's own lock screen (1.8.2): the permission steps a phone still needs, then its layout and what it shows. */
@Composable
private fun LockScreenOptions(canStart: Boolean) {
    val s = LocalAppSettings.current
    val c = LocalContainer.current
    val prefs = c.settings
    val context = LocalContext.current
    val b = s.behavior
    val gate = com.prism.music.ui.lockscreen.LockScreenGate
    val maker = remember { com.prism.music.ui.lockscreen.LockScreenMaker.of() }
    val song by c.player.currentSong.collectAsState()
    if (!canStart) {
        if (gate.mayBeRestricted) {
            LinkRow(tr(R.string.lock_screen_app_info), value = tr(R.string.lock_screen_app_info_detail), info = tr(R.string.lock_screen_app_info_info), external = true) { gate.openAppInfo(context) }
            LinkRow(tr(R.string.lock_screen_allow_step), value = tr(R.string.lock_screen_allow_detail), info = tr(R.string.lock_screen_allow_info), external = true) { gate.openPermissionPage(context) }
        } else {
            LinkRow(tr(R.string.lock_screen_allow), value = tr(R.string.lock_screen_allow_detail), info = tr(R.string.lock_screen_allow_info), external = true) { gate.openPermissionPage(context) }
        }
    }
    // D43: vivo and Xiaomi add permissions of their own that Android can't read: one more step there.
    maker?.let { m ->
        val detail = if (m == com.prism.music.ui.lockscreen.LockScreenMaker.XIAOMI) R.string.lock_screen_maker_xiaomi_detail else R.string.lock_screen_maker_vivo_detail
        LinkRow(tr(R.string.lock_screen_maker_step), value = tr(detail), info = tr(R.string.lock_screen_maker_info), external = true) { com.prism.music.ui.lockscreen.LockScreenMaker.openPage(context, m) }
    }
    PickCardsRow(
        tr(R.string.lock_screen_layout_label), LockLayout.entries, b.lockScreenLayout, { it.label },
        info = if (s.performanceMode) tr(R.string.lock_screen_performance) else null, changed = ch("lock_screen_layout"),
        onPick = { prefs.setLockScreenLayout(it) },
    ) { com.prism.music.ui.lockscreen.LockScreenSample(it, song) }
    // 1.8.4 (D53): the lock screen in the player's look.
    SwitchRow(
        tr(R.string.settings_player_same_look), s.view.lockScreenPlayerLook, info = tr(R.string.settings_player_same_look_info), changed = ch("lock_screen_player_look"),
    ) { prefs.setLockScreenPlayerLook(it) }
    val labels = lockShowLabels()
    ShowChipsRow(
        tr(R.string.lock_screen_show),
        listOf(
            ShowOption(labels[0], b.lockScreenVolume) { prefs.setLockScreenVolume(it) },
            ShowOption(labels[1], b.lockScreenLike) { prefs.setLockScreenLike(it) },
            ShowOption(labels[2], b.lockScreenOutput) { prefs.setLockScreenOutput(it) },
            ShowOption(labels[3], b.lockScreenRepeat) { prefs.setLockScreenRepeat(it) },
            ShowOption(labels[4], b.lockScreenUpNext) { prefs.setLockScreenUpNext(it) },
        ),
        info = tr(R.string.lock_screen_show_info), changed = ch("lock_screen_volume"),
    )
    ChoiceRow(
        tr(R.string.lock_screen_paused_label), LockPaused.entries, b.lockScreenPaused, { it.label }, info = tr(R.string.lock_screen_paused_info), changed = ch("lock_screen_paused"),
    ) { prefs.setLockScreenPaused(it) }
    SwitchRow(
        tr(R.string.lock_screen_hide), b.lockScreenHideSong, info = words(tr(R.string.lock_screen_hide_detail), tr(R.string.lock_screen_hide_info)), changed = ch("lock_screen_hide_song"),
    ) { prefs.setLockScreenHideSong(it) }
}

// ---------------------------------------------------------------------------- The page's parts for the map

/** The root's line for Player: its layout and background. */
fun playerValue(s: AppSettings, dark: Boolean): String =
    // 1.8.4: its layout and background only, so it fits one line on the root (with animated covers it ran to "…").
    listOf(tr(R.string.player_layout_x, s.playerLayout.label), s.playerBackground.label).joinToString(" · ")

/**
 * The look settings "Reset this page" puts back by their keys: every setting of the page that is part of a Look. (The
 * page's other settings go back by their setters, see `resetPlayerPage`; no key may be in two pages' lists.)
 */
val playerLookKeys: List<String> = listOf(
    "player_layout", "vinyl_tonearm", "player_background", "player_backdrop_show", "player_gradient_depth", "mesh_speed", "mesh_intensity",
    "player_blur", "transport_style", "play_ring", "player_buttons_pill", "video_controls_hide", "video_buttons_hide",
    "scrubber_style", "scrubber_color", "scrubber_thickness", "scrubber_thumb", "crossfade_shape", "volume_matches_progress", "player_times",
    "player_title_align", "player_title_size", "player_title_weight", "player_title_scroll", "player_artist_links",
    "cover_size", "player_art_corners", "full_bleed_artwork", "video_full_bleed", "bleed_fit", "bleed_roomy", "art_shrink_on_pause",
    "cover_flight", "cover_flight_back", "canvas_fills_background",
    "player_hidden_parts", "player_handle", "show_playing_from", "player_shuffle_repeat", "hide_volume_bar", "show_output_device",
    "player_nav_band", "crossfade_mark", "blend_covers", "player_deck", "player_buttons",
    "panel_player", "queue_headings", "queue_dj_chip", "queue_lengths", "queue_played_moves", "queue_tidy",
    "visualizer", "visualizer_color", "immersive_art", "immersive_lyrics", "immersive_controls", "immersive_video",
    // Immersive's background has its one home here (it came from Lyrics → Background).
    "immersive_background", "lock_screen_player_look",
    // The player's cover shape came from Text & icons, which let go of it.
    "cover_shape_player",
)

/** Old group titles → this page's groups, so links and tour steps of before the rework land where the setting is now. */
val playerOldGroups: Map<String, String> by lazy {
    val g = PlayerGroups
    mapOf(
        tr(R.string.look) to g.layout,
        tr(R.string.progress_bar) to g.progress,
        tr(R.string.title_and_times) to g.title,
        tr(R.string.animated_covers) to g.cover,
        tr(R.string.what_s_on_the_player) to g.onIt,
        tr(R.string.lyrics_and_the_queue) to g.onIt,
        tr(R.string.visualizer) to g.modes,
        tr(R.string.immersive_mode) to g.modes,
        tr(R.string.lock_screen_title) to g.modes,
        tr(R.string.desk_mode) to g.modes,
    )
}

/** The page's lines in Settings' search: one per row, with the words of the rows it took over. */
val playerIndex: List<SettingEntry> by lazy {
    with(SettingNeeds) {
        val g = PlayerGroups
        fun bg(name: Int, vararg which: PlayerBackground) = Needs(tr(R.string.turn_on_first, tr(name))) { it.playerBackground in which }
        val volumeShown = turnOn(tr(R.string.volume)) { it.shows(PlayerPart.VOLUME) }
        val edge = turnOn(tr(R.string.settings_player_edge_to_edge)) { it.fullBleedArtwork || it.videoFullBleed }
        val canvasOn = turnOn(tr(R.string.canvas)) { it.canvasEnabled }
        val canvasVideos = turnOn(tr(R.string.canvas_use_videos)) { it.canvasEnabled && it.behavior.canvas.useVideos }
        listOf(
            // Layout
            SettingEntry(page, g.layout, tr(R.string.layout), tr(R.string.player_layout_standard_cover_lyrics_minimal)),
            SettingEntry(page, g.layout, tr(R.string.tonearm), tr(R.string.vinyl_record_turntable_arm_needle), needs = turnOn(tr(R.string.vinyl_layout)) { it.playerLayout == PlayerLayout.VINYL }),
            SettingEntry(page, g.layout, tr(R.string.background), tr(R.string.player_blur_cover_color) + " " + tr(R.string.cover_gradient) + " " + tr(R.string.look)),
            SettingEntry(page, g.layout, tr(R.string.show_background), needs = bg(R.string.blurred_cover, PlayerBackground.BLUR, PlayerBackground.APPEARANCE)),
            SettingEntry(page, g.layout, tr(R.string.gradient_depth), tr(R.string.gradient_depth_info), needs = bg(R.string.cover_gradient, PlayerBackground.GRADIENT)),
            // 1.8.0: Motion and Colors are one slider (old names still find it).
            SettingEntry(
                page, g.layout, tr(R.string.background_movement),
                tr(R.string.mesh_background_drift_animate_move_still) + " " + tr(R.string.mesh_background_saturation_vivid_soft_rich) + " " + tr(R.string.motion) + " " + tr(R.string.colors),
                needs = Needs(tr(R.string.turn_on_first, PlayerBackground.MESH.label)) { it.playerBackground == PlayerBackground.MESH },
            ),
            SettingEntry(page, g.layout, tr(R.string.softness), tr(R.string.blur_background_strength_soft_clear), needs = bg(R.string.blurred_cover, PlayerBackground.BLUR)),
            SettingEntry(
                page, g.layout, tr(R.string.which_background), tr(R.string.player_background_own_design_photo_pattern),
                needs = Needs(tr(R.string.turn_on_first, PlayerBackground.APPEARANCE.label)) { it.playerBackground == PlayerBackground.APPEARANCE },
            ),
            SettingEntry(page, g.layout, tr(R.string.controls), tr(R.string.buttons_transport)),
            SettingEntry(page, g.layout, tr(R.string.ring_on_the_play_button), tr(R.string.the_split_light_around_play_and_pause)),
            SettingEntry(page, g.layout, tr(R.string.buttons), tr(R.string.player_bottom_row_buttons_lyrics_video) + " " + tr(R.string.in_a_capsule) + " " + tr(R.string.apart)),
            SettingEntry(
                page, g.layout, tr(R.string.settings_player_controls_over_videos),
                tr(R.string.hide_controls_over_videos) + " " + tr(R.string.music_video_controls_hide_tap_full) + " " + tr(R.string.buttons_on_videos_wait_for_a_tap) + " " + tr(R.string.buttons_on_videos_keywords),
            ),
            // Progress bar and times
            SettingEntry(page, g.progress, tr(R.string.style), tr(R.string.progress_bar) + " " + tr(R.string.scrubber_seek_bar)),
            SettingEntry(page, g.progress, tr(R.string.progress_bar_color), tr(R.string.scrubber_color_accent_white_cover_spectrum)),
            SettingEntry(page, g.progress, tr(R.string.progress_bar_thickness), tr(R.string.scrubber_thick_thin_height_bold)),
            SettingEntry(page, g.progress, tr(R.string.knob_at_the_playhead), tr(R.string.scrubber_thumb_knob_dot_line_ring)),
            SettingEntry(page, g.progress, tr(R.string.volume_bar_like_the_progress_bar), tr(R.string.volume_bar_keywords)),
            SettingEntry(page, g.progress, tr(R.string.times), tr(R.string.times_under_the_progress_bar) + " " + tr(R.string.elapsed_remaining_time_left_none) + " " + tr(R.string.duration_time_left_remaining)),
            SettingEntry(page, g.progress, tr(R.string.settings_player_right_hand_time), tr(R.string.time_left) + " " + tr(R.string.song_length) + " " + tr(R.string.duration_time_left_remaining)),
            // 1.8.1: the bar shaped by the crossfade (drawn only while Crossfade is on).
            SettingEntry(page, g.progress, tr(R.string.crossfade_on_the_progress_bar), tr(R.string.crossfade_shape_keywords), needs = crossfade),
            // Title
            SettingEntry(page, g.title, tr(R.string.alignment), tr(R.string.title_and_times) + " " + tr(R.string.title_alignment_centered_centre_left)),
            SettingEntry(page, g.title, tr(R.string.size), tr(R.string.title_size) + " " + tr(R.string.big_small_large_font)),
            SettingEntry(page, g.title, tr(R.string.weight), tr(R.string.title_weight) + " " + tr(R.string.bold_heavy_thick_light_regular_font)),
            SettingEntry(page, g.title, tr(R.string.scrolling_titles), tr(R.string.marquee_scroll_long_title_ellipsis)),
            SettingEntry(page, g.title, tr(R.string.each_artist_its_own_link), tr(R.string.each_artist_its_own_link_keywords)),
            // Cover
            SettingEntry(page, g.cover, tr(R.string.cover_size), tr(R.string.large_full_width)),
            SettingEntry(page, g.cover, tr(R.string.cover_corners), tr(R.string.corners) + " " + tr(R.string.cover_rounded)),
            SettingEntry(page, g.cover, tr(R.string.settings_player_cover_shape), tr(R.string.player_s_cover) + " " + tr(R.string.cover_shape_circle_squircle_flower_cookie)),
            SettingEntry(
                page, g.cover, tr(R.string.settings_player_edge_to_edge),
                tr(R.string.edge_to_edge_cover) + " " + tr(R.string.edge_to_edge_video) + " " + tr(R.string.full_bleed_artwork) + " " + tr(R.string.full_bleed_video) + " " + tr(R.string.music_video_edge_to_edge_fill),
            ),
            SettingEntry(page, g.cover, tr(R.string.how_the_cover_fits), tr(R.string.how_the_cover_fits_keywords), needs = turnOn(tr(R.string.edge_to_edge_cover)) { it.fullBleedArtwork }),
            SettingEntry(page, g.cover, tr(R.string.more_room_for_the_picture), tr(R.string.more_room_for_the_picture_keywords), needs = edge),
            SettingEntry(page, g.cover, tr(R.string.shrink_when_paused)),
            SettingEntry(
                page, g.cover, tr(R.string.settings_player_cover_animation),
                tr(R.string.cover_flies_into_place) + " " + tr(R.string.cover_flies_back_too) + " " + tr(R.string.animation_transition_open_player_cover_mini),
            ),
            // 1.8.6: "Animated covers" is Canvas now (the old name and "Fill the player" still find it), with its sheet's rows.
            SettingEntry(
                page, g.cover, tr(R.string.canvas),
                tr(R.string.canvas_keywords) + " " + tr(R.string.animated_covers) + " " + tr(R.string.canvas_motion_video_cover) + " " + tr(R.string.fill_the_player) + " " +
                    tr(R.string.full_screen) + " " + tr(R.string.canvas_background_full_screen_behind),
            ),
            SettingEntry(page, g.cover, tr(R.string.canvas_where)),
            SettingEntry(page, g.cover, tr(R.string.canvas_use_videos), tr(R.string.canvas_use_videos_keywords), needs = canvasOn),
            SettingEntry(page, g.cover, tr(R.string.use_mobile_data), tr(R.string.canvas_from_videos) + " " + tr(R.string.cellular_mobile_data) + " " + tr(R.string.canvas), needs = canvasVideos),
            SettingEntry(page, g.cover, tr(R.string.canvas_both), tr(R.string.canvas_both_keywords), needs = canvasVideos),
            SettingEntry(
                page, g.cover, tr(R.string.canvas_keep_controls), tr(R.string.canvas_keep_controls_keywords),
                needs = turnOn(tr(R.string.full_screen)) { it.canvasEnabled && it.canvasFillsBackground },
            ),
            SettingEntry(page, g.cover, tr(R.string.canvas_lighter), tr(R.string.canvas_lighter_keywords), needs = canvasVideos),
            // On it: one editor for what's on the player (found by each part's name too).
            SettingEntry(
                page, g.onIt, tr(R.string.what_s_on_the_player),
                tr(R.string.what_s_on_the_player_keywords) + " " + PlayerPart.entries.joinToString(" ") { it.label } + " " + tr(R.string.album_caption_playing_from) + " " +
                    tr(R.string.codec_bitrate_nerd_stats) + " " + tr(R.string.skip_chorus_hook_highlight_most_replayed) + " " + tr(R.string.bluetooth_speaker_headphones) + " " +
                    tr(R.string.player_handle_keywords) + " " + tr(R.string.nav_band_keywords) + " " + tr(R.string.crossfade_songs) + " " + tr(R.string.lyrics_line),
            ),
            SettingEntry(page, g.onIt, tr(R.string.under_the_title), tr(R.string.order_arrange_rearrange_move_current_lyric)),
            // One line for the row (1.8.4: it was listed twice), with both lines' search words.
            SettingEntry(
                page, g.onIt, tr(R.string.buttons_in_the_row), tr(R.string.player_bottom_row_buttons_lyrics_video) + " " + tr(R.string.music_video_audio_only),
                needs = turnOn(tr(R.string.buttons)) { it.shows(PlayerPart.BUTTONS) },
            ),
            SettingEntry(page, g.onIt, tr(R.string.edit_this_menu), tr(R.string.edit_this_menu_keywords)),
            SettingEntry(page, g.onIt, tr(R.string.settings_player_while_panels_open), tr(R.string.player_controls_hide_full_screen_lyrics)),
            SettingEntry(
                page, g.onIt, tr(R.string.lyrics_and_the_queue),
                PanelChip.entries.joinToString(" ") { it.label } + " " + tr(R.string.scrolling_up_shows_the_controls) + " " + tr(R.string.scroll_swipe_down_lyrics_seek_bar) + " " + tr(R.string.reopen_on_the_last_page_keywords) + " " + tr(R.string.steadier_lyrics_keywords),
            ),
            SettingEntry(
                page, g.onIt, tr(R.string.settings_player_in_the_queue),
                listOf(R.string.queue_headings, R.string.played_now_playing_up_next_headings, R.string.dj_in_the_queue, R.string.dj_in_the_queue_keywords, R.string.song_lengths_in_the_queue,
                    R.string.song_lengths_in_the_queue_keywords, R.string.move_songs_you_have_played, R.string.move_songs_you_have_played_keywords, R.string.tidier_queue, R.string.tidier_queue_keywords,
                ).joinToString(" ") { tr(it) },
            ),
            SettingEntry(page, g.onIt, tr(R.string.the_volume_slider_changes), tr(R.string.volume_independent_app_volume_prism_s), needs = volumeShown),
            // Modes
            SettingEntry(page, g.modes, tr(R.string.visualizer), tr(R.string.visualiser_bars_wave_ring_pulse_glow)),
            SettingEntry(page, g.modes, tr(R.string.visualizer_color), tr(R.string.visualiser_colour_accent_white_cover), needs = turnOn(tr(R.string.visualizer)) { it.visualizer != VisualizerStyle.OFF }),
            SettingEntry(page, g.modes, tr(R.string.immersive_mode), tr(R.string.lyric_video_full_screen)),
            SettingEntry(page, g.modes, tr(R.string.settings_player_immersive_options), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.artwork), tr(R.string.immersive_mode), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.lyrics), tr(R.string.immersive_mode), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.keep_controls_on_screen), tr(R.string.play_pause_skip_next_previous_buttons), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.show_the_video), tr(R.string.immersive_music_video_behind_lyrics), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.background), tr(R.string.immersive_mode), needs = immersive),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_title), tr(R.string.lock_screen_keywords)),
            SettingEntry(page, g.modes, tr(R.string.settings_player_lock_options), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_allow), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_app_info), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_allow_step), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_layout_label), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.settings_player_same_look), tr(R.string.lock_screen_keywords) + " " + tr(R.string.look_lock_screen_like_player), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_show), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_paused_label), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.lock_screen_hide), tr(R.string.lock_screen_keywords), needs = lockScreen),
            SettingEntry(page, g.modes, tr(R.string.desk_mode), tr(R.string.desk_mode_keywords)),
        )
    }
}
