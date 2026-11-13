package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.NavTab
import com.prism.music.download.formatGb
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.featurePane
import com.prism.music.ui.theme.screensOnly
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.prism.music.ui.screens.settings.LicensesScreen
import com.prism.music.ui.screens.settings.LinkRow
import com.prism.music.ui.screens.settings.LsStyleRows
import com.prism.music.ui.screens.settings.PickListRow
import com.prism.music.ui.screens.settings.PlayerCoverRows
import com.prism.music.ui.screens.settings.PlayerGroups
import com.prism.music.ui.screens.settings.PlayerLayoutRows
import com.prism.music.ui.screens.settings.PlayerOnItRows
import com.prism.music.ui.screens.settings.PlayerProgressRows
import com.prism.music.ui.screens.settings.PlayerVisualizerRows
import com.prism.music.ui.screens.settings.SettingsGroup
import com.prism.music.ui.screens.settings.SettingsMap
import com.prism.music.ui.screens.settings.SettingsPageContent
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.SettingsRoot
import com.prism.music.ui.screens.settings.SettingsSearchOverlay
import com.prism.music.ui.screens.settings.SettingsTarget
import com.prism.music.ui.screens.settings.TabsMiniPlayerBarRows
import com.prism.music.ui.screens.settings.TabsMiniPlayerMiniRows
import com.prism.music.ui.screens.settings.TabsMiniPlayerPagesRows
import com.prism.music.ui.screens.settings.TextIconsButtonsRows
import com.prism.music.ui.screens.settings.TextIconsShapesRows
import com.prism.music.ui.screens.settings.lookKeys

/**
 * The settings pages of before 1.8.4, by their keys (1.7.6's hub listed what Prism does, with everything about how it
 * looks under Customize Prism, [APPEARANCE], whose page listed the look pages).
 *
 * 1.8.4: the pages are the 17 of the map now ([SettingsPageId], one tap from the root). These keys stay as **aliases**:
 * a link to an old page (the tour, the player's ⋯, prism:// links, "Customize this") opens the page that holds it now
 * ([page], [SettingsMap.resolve]). The titles, icons and summaries are the old pages', still drawn by the old link
 * lists inside the pages that aren't rebuilt yet; nothing new should use them.
 */
enum class SettingsSection(val key: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", tr(R.string.customize_prism), PrismIcons.Palette),
    SOUND("sound", tr(R.string.playback_sound), PrismIcons.Equalizer),
    LYRICS("lyrics", tr(R.string.lyrics_sing), PrismIcons.Lyrics),
    CONTROLS("controls", tr(R.string.controls_gestures), PrismIcons.Touch),
    LIBRARY("library", tr(R.string.recommendations_replay), PrismIcons.Library),
    DOWNLOADS("downloads", tr(R.string.downloads_storage_data), PrismIcons.DownloadCircle),
    DEVICES("devices", tr(R.string.devices_connections), PrismIcons.Car),
    LANGUAGE("language", tr(R.string.language), PrismIcons.Globe),
    ABOUT("about", tr(R.string.backup_about), PrismIcons.Info),
    /** Not on the hub: Devices & connections' own pages. */
    CONNECT("connect", tr(R.string.speakers_tvs_browser), PrismIcons.Cast),
    DISCORD("discord", tr(R.string.discord), PrismIcons.Groups),
    LASTFM("lastfm", "Last.fm", PrismIcons.Replay),
    /** 1.8.1: Spotify through the listener's own developer app. */
    SPOTIFY("spotify", tr(R.string.spotify), PrismIcons.Library),
    /** Not on the hub: opened from About. */
    LICENSES("licenses", tr(R.string.open_source_licenses), PrismIcons.Info),
    /** Not on the hub: Customize Prism's pages. */
    LOOKS("looks", tr(R.string.looks), PrismIcons.Looks),
    COLOR("color", tr(R.string.color_2), PrismIcons.Palette),
    TEXT("text", tr(R.string.text), PrismIcons.Signature),
    ICONS("icons", tr(R.string.icons_shapes), PrismIcons.Category),
    GLASS("glass", tr(R.string.glass_backgrounds), PrismIcons.Photo),
    INTERFACE("interface", tr(R.string.tabs_mini_player), PrismIcons.Customize),
    HOME("home", tr(R.string.home_library), PrismIcons.Home),
    PLAYER("player", tr(R.string.player), PrismIcons.PlayCircle),
    LYRICS_LOOK("lyrics_look", tr(R.string.lyrics), PrismIcons.Lyrics);

    fun summary(s: AppSettings, dark: Boolean = true): String = when (this) {
        APPEARANCE -> tr(R.string.looks_colors_fonts_the_player_and)
        INTERFACE -> listOf(tr(R.string.nav_bar_x, s.navStyle.label), com.prism.music.trp(R.plurals.n_tabs, s.navTabs.size), tr(R.string.mini_player_x, s.miniPlayerStyle.label)).joinToString(" · ")
        PLAYER -> com.prism.music.ui.screens.settings.playerValue(s, dark)
        SOUND -> listOfNotNull(if (s.lossless) tr(R.string.lossless) else tr(R.string.audio_quality_x, s.audioQuality.label), if (s.mix) tr(R.string.crossfade) else null, if (s.normalize) tr(R.string.normalized) else null, if (s.spatial) tr(R.string.prism_spatial) else null, if (s.eq.enabled) "EQ ${com.prism.music.playback.EqPresets.label(s.eq.preset)}" else null).joinToString(" · ")
        LYRICS -> listOfNotNull(tr(R.string.first, s.lyricsSources.first().label), if (s.lyricsTranslate) tr(R.string.translated) else null, tr(R.string.sing)).joinToString(" · ")
        LYRICS_LOOK -> tr(R.string.style_2, s.lyricsStyle.label, s.lyricsPosition.label)
        CONTROLS -> tr(R.string.swipes_and_taps_on_the_player)
        // Smart Downloads named only while it's on (a new install has it off: "5 GB" read as if it were on).
        DOWNLOADS -> listOfNotNull(if (s.smartDownloads) tr(R.string.smart_downloads_2, formatGb(s.offlineGb)) else null, if (s.downloadWifiOnly) tr(R.string.wi_fi_only) else tr(R.string.any_network), if (s.dataSaver) tr(R.string.data_saver) else null).joinToString(" · ")
        DEVICES -> tr(R.string.android_auto_speakers_tvs_discord_last)
        // 1.8.4: the pages' own lines (short enough for one line on the root).
        CONNECT -> com.prism.music.ui.screens.settings.speakersTvsBrowserValue(s, dark)
        HOME -> tr(R.string.home_s_sections_and_top_library)
        // Only what's on the page itself (1.8.4: it showed the content country, which is on Language).
        LIBRARY -> listOfNotNull(tr(R.string.your_artists_new_releases), if (com.prism.music.data.HistoryPause.paused(s.behavior)) tr(R.string.hist_paused_title) else null, tr(R.string.replay_2)).joinToString(" · ")
        DISCORD -> if (s.discordPresence) tr(R.string.showing_what_you_play_on_your) else tr(R.string.show_what_you_re_playing_on)
        LASTFM -> tr(R.string.scrobble_what_you_play_to_your)
        SPOTIFY -> tr(R.string.spot_summary)
        LANGUAGE -> java.util.Locale.getDefault().let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase() }
        ABOUT -> com.prism.music.ui.screens.settings.backupAboutValue(s, dark)
        LICENSES -> tr(R.string.gpl_3_0_or_later)
        COLOR -> listOfNotNull(
            // The light's name (White shows as Black in light mode).
            if (s.lightStyle == com.prism.music.data.prefs.LightStyle.WHITE) tr(R.string.light_2, whiteName(dark))
            else com.prism.music.ui.theme.SpectrumPresets.label(s.spectrumPreset).let { if (it == com.prism.music.ui.theme.SpectrumPresets.COVER_LABEL) tr(R.string.light_from_the_cover) else tr(R.string.light_2, it) },
            when (s.colorSource) { ColorSource.CUSTOM -> tr(R.string.accent_x, com.prism.music.ui.theme.ColorMath.toHex(s.accent)); ColorSource.ARTWORK -> tr(R.string.accent_from_the_cover); ColorSource.DYNAMIC -> tr(R.string.accent_from_the_wallpaper) },
        ).joinToString(" · ")
        TEXT -> listOfNotNull(tr(R.string.font_x, s.fontChoice.label), if (s.textScale != 1f) tr(R.string.size_2, (s.textScale * 100).roundToInt()) else null).joinToString(" · ")
        ICONS -> tr(R.string.icon_pack_and_weight_app_icon)
        GLASS -> listOfNotNull(when { s.liquidGlass -> tr(R.string.liquid_glass); s.frostedGlass -> tr(R.string.frosted_glass); else -> tr(R.string.solid_bars) }, if (s.backdrops.screensOnly.isEmpty()) null else tr(R.string.backgrounds), if (s.reduceMotion) tr(R.string.reduced_motion) else null).joinToString(" · ")
        LOOKS -> com.prism.music.ui.screens.settings.looksValue(s, dark)
    }

    /** 1.8.4: the page of the map that holds what this old page held ([SettingsMap.aliases]). */
    val page: SettingsPageId get() = SettingsMap.aliases.getValue(key).page

    /** The look settings its page's "Reset this page" puts back (1.8.4: each page's own file lists them). */
    val lookKeys: List<String> get() = page.lookKeys

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

/**
 * Settings (1.8.4): the root ([SettingsRoot]: search, the account, Performance mode, the map's 17 pages in 5 groups),
 * or, with a [key], one page. [key] is a page's own key (`SettingsPageId.key`) or a key of before 1.8.4
 * (`SettingsSection.key`), which opens the page that holds it now; [find] is the group to open the page at
 * ([SettingsMap.resolve]). On a wide window the root sits down the side with the picked page beside it.
 */
@Composable
fun SettingsScreen(bottomPadding: Dp, key: String? = null, find: String? = null) {
    // The switches show what the listener chose, also while Performance mode draws Prism lighter.
    StoredSettings { SettingsHost(bottomPadding, SettingsMap.resolve(key, find)) }
}

/** [content] with the settings as stored, not as Performance mode draws them (pages that change settings). */
@Composable
fun StoredSettings(content: @Composable () -> Unit) {
    val stored by LocalContainer.current.settings.stored.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalAppSettings provides stored, content = content)
}

@Composable
private fun SettingsHost(bottomPadding: Dp, target: SettingsTarget?) {
    val nav = LocalNavigator.current
    if (target != null) {
        SettingsDestination(bottomPadding, target, showBack = true, onSearch = null)
        return
    }
    // As a tab, the root is a root page and has nothing to go back to.
    val showBack = NavTab.SETTINGS !in LocalAppSettings.current.navTabs
    if (!com.prism.music.ui.theme.LocalWindowLayout.current.listDetail) {
        SettingsRoot(bottomPadding, showBack) { page, group -> nav.go(Routes.settings(page.key, group)) }
        return
    }
    // A wide window: the root down the side (400 dp), the picked page beside it.
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPageId.entries.first().key) }
    var openFind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(400.dp).fillMaxHeight()) {
            SettingsRoot(bottomPadding, showBack, selected = SettingsPageId.of(open), searchFocus = searchFocus) { page, group -> open = page.key; openFind = group }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.runtime.key(open, openFind) {
                SettingsDestination(
                    bottomPadding, SettingsTarget(SettingsPageId.of(open) ?: SettingsPageId.entries.first(), openFind), showBack = false,
                    // The search is the root's, right beside the page.
                    onSearch = { runCatching { searchFocus.requestFocus() } },
                )
            }
        }
    }
}

/**
 * One page of the map (or the licenses, a screen of Backup & about). Its bar's search button opens Settings search:
 * [onSearch] where the root is beside it, else the same search laid over the page.
 */
@Composable
private fun SettingsDestination(bottomPadding: Dp, target: SettingsTarget, showBack: Boolean, onSearch: (() -> Unit)?) {
    var searching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val search = onSearch ?: { searching = true }
    Box(Modifier.fillMaxSize()) {
        if (target.licenses) LicensesScreen(bottomPadding, showBack, search)
        else SettingsPageContent(target.page, bottomPadding, target.find, showBack, search)
        if (searching) SettingsSearchOverlay(bottomPadding) { searching = false }
    }
}

/** What Performance mode pauses, in plain words (its ⓘ and the pages' notice). */
internal val PERFORMANCE_PAUSES =
    tr(R.string.blur_and_frosted_glass_the_frost_all)


/** At the top of a page whose effects Performance mode is pausing. */
@Composable
internal fun PerformancePaused() {
    val c = LocalContainer.current
    Group("") {
        Item(
            tr(R.string.performance_mode_is_on), tr(R.string.some_of_these_effects_are_paused),
            info = tr(R.string.paused_you_can_still_change_them, PERFORMANCE_PAUSES.lowercase()),
            icon = PrismIcons.Speed, onClick = { c.settings.setPerformanceMode(false) },
        ) { PrismTextButton(onClick = { c.settings.setPerformanceMode(false) }) { Text(tr(R.string.turn_off)) } }
    }
}

@Composable
internal fun Chevron() = Icon(PrismIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------- Account

/** The root's account card: the Prism Account, then YouTube Music (sign in, sign out, switch). */
@Composable
internal fun AccountCard() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    // A pane of frosted glass on a frosted page, the accent's gradient otherwise.
    val frosted = com.prism.music.ui.components.LocalFrosted.current
    val ink = com.prism.music.ui.components.featureInk()
    var switching by remember { mutableStateOf(false) }
    if (switching) AccountSwitcher { switching = false }
    val profile by c.account.profile.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    fun signOut() {
        c.settings.logout()
        // The account's library kept for offline goes with it (copies in Prism stay: they're the Prism Account's).
        c.library.forgetAccount()
        CookieManager.getInstance().removeAllCookies(null)
    }
    // With a Prism Account, signing out can keep the YouTube Music library in Prism first.
    if (confirmSignOut) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(tr(R.string.sign_out_of_youtube_music)) },
        text = { Text(tr(R.string.keep_a_copy_of_its_playlists)) },
        confirmButton = {
            PrismTextButton({
                confirmSignOut = false
                c.scope.launch {
                    val r = runCatching { c.account.bringOver() }.getOrNull()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        signOut()
                        android.widget.Toast.makeText(context, if (r != null) tr(R.string.signed_out_playlists_and_albums_kept, trp(R.plurals.n_playlists, r.playlists), trp(R.plurals.n_albums, r.albums)) else tr(R.string.signed_out), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(tr(R.string.keep_a_copy)) }
        },
        dismissButton = { PrismTextButton({ confirmSignOut = false; signOut() }) { Text(tr(R.string.just_sign_out)) } },
    )
    Column(Modifier.fillMaxWidth().featurePane(ui.card)) {
    // The Prism Account: who the listener is in Prism, on this phone.
    Row(
        Modifier.fillMaxWidth().clickable { nav.go(Routes.ACCOUNT) }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile != null || s.isLoggedIn) com.prism.music.ui.screens.ProfilePicture(Modifier.size(56.dp))
        else Box(Modifier.size(56.dp).clip(CircleShape).background(if (frosted) com.prism.music.ui.components.frostFill(1.6f) else scheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Icon(PrismIcons.Add, null, tint = if (frosted) scheme.primary else ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.name ?: tr(R.string.make_a_prism_account),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (profile != null) tr(R.string.prism_account_on_this_phone) else tr(R.string.your_name_and_picture_kept_on),
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(PrismIcons.ChevronRight, null, tint = ink)
    }
    // YouTube Music: optional, for the account's library, history and recommendations.
    Row(
        Modifier.fillMaxWidth()
            .then(if (s.isLoggedIn) Modifier else Modifier.clickable { nav.go(Routes.LOGIN) })
            .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (frosted) com.prism.music.ui.components.frostFill(1.6f) else scheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (s.isLoggedIn && s.accountAvatar.isNotBlank()) AsyncImage(s.accountAvatar, null, Modifier.fillMaxSize())
            else Icon(PrismIcons.Login, null, Modifier.size(18.dp), tint = if (frosted) scheme.primary else ink)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (s.isLoggedIn) tr(R.string.youtube_music, s.accountName.ifBlank { tr(R.string.signed_in) }) else tr(R.string.sign_in_to_youtube_music),
                style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s.isLoggedIn) s.accountEmail.ifBlank { tr(R.string.signed_in_2) } else tr(R.string.optional_your_library_history_and_recommendation),
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.isLoggedIn) PrismOutlinedButton(onClick = { if (profile != null) confirmSignOut = true else signOut() }) {
            Icon(PrismIcons.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.sign_out))
        }
        else Icon(PrismIcons.ChevronRight, null, tint = ink)
    }
    // Another channel of the same Google account (brand accounts), or another Google account altogether.
    if (s.isLoggedIn) Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
        PrismTextButton(onClick = { switching = true }) {
            Icon(PrismIcons.Person, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(6.dp)); Text(tr(R.string.switch_account), color = ink)
        }
        PrismTextButton(onClick = { nav.go(Routes.LOGIN_ADD) }) {
            Icon(PrismIcons.Add, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(6.dp)); Text(tr(R.string.add_a_google_account), color = ink)
        }
    }
    // #372, #373, #377: YouTube's own answers say it isn't taking Prism's requests as this account.
    val accountSeen by c.innerTube.seen.collectAsState()
    if (s.isLoggedIn && accountSeen?.signedIn == false) Text(
        tr(R.string.youtube_no_longer_takes_this_sign_in), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = ink,
    )
    }
}

/**
 * Every channel Prism can use on this sign-in: each Google account's own and its brand accounts (one email can have
 * several YouTube channels). Picking one switches the library, likes and recommendations to it.
 */
@Composable
private fun AccountSwitcher(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts by androidx.compose.runtime.produceState<List<com.prism.music.data.model.YtAccount>?>(null) {
        value = runCatching { c.ytm.accounts() }.getOrDefault(emptyList())
    }
    com.prism.music.ui.components.ActionSheet(s.accountAvatar.ifBlank { null }, onDismiss) { close ->
        Text(
            tr(R.string.accounts_and_channels), Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
        )
        val list = accounts
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { com.prism.music.ui.components.PrismSpinner(Modifier.size(30.dp)) }
            list.isEmpty() -> Text(
                tr(R.string.youtube_didn_t_list_any_other),
                Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            )
            else -> list.groupBy { it.email }.forEach { (email, channels) ->
                if (email != null) Text(
                    email, Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                channels.forEach { a ->
                    val current = a.authUser == s.authUser && a.dataSyncId.substringBefore("||") == s.dataSyncId.substringBefore("||")
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !current) {
                            close {
                                c.scope.launch {
                                    c.library.switchAccount(a)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, tr(R.string.switched_to, a.name), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(2f)), contentAlignment = Alignment.Center) {
                            if (a.avatar != null) AsyncImage(a.avatar, null, Modifier.fillMaxSize()) else Icon(PrismIcons.Person, null)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            a.byline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        if (current) Icon(PrismIcons.Check, tr(R.string.in_use), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Each font's name, set in that font, with the typeface it is. */
/**
 * [text] on up to [maxLines] lines in [style], made smaller a step at a time (down to about half) until every line ends
 * between words or after a hyphen: a word too wide for the room is otherwise split mid-word. Judged from its own layout,
 * not measured ahead (BoxWithConstraints can't sit in the font cards' row, which is sized by its contents).
 */
@Composable
internal fun WholeWords(text: String, style: TextStyle, color: Color, maxLines: Int = 2, modifier: Modifier = Modifier) {
    var fitted by remember(text, style) { mutableStateOf(style) }
    Text(
        text, modifier, style = fitted, color = color, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { l ->
            val whole = !l.hasVisualOverflow && (0 until l.lineCount - 1).all { i -> text.getOrNull(l.getLineEnd(i) - 1).let { it == ' ' || it == '-' } }
            if (!whole && fitted.fontSize.value > style.fontSize.value * 0.5f) fitted = fitted.copy(fontSize = fitted.fontSize * 0.9f)
        },
    )
}

/** "Reset this page" for the page that holds what [section]'s old page held (the old pages' own call). */
@Composable
internal fun ResetPage(section: SettingsSection) = ResetPage(section.page.title, section.lookKeys)

/**
 * "Reset this page" (1.7.3), the old pages' button: the look settings [lookKeys] back to how Prism comes, after asking
 * (the dialog names [title]). [also]: what else the reset puts back (the icon style, kept apart from the settings).
 * A page rebuilt with the kit has this under ⋯ instead (`PageReset`).
 */
@Composable
internal fun ResetPage(title: String, lookKeys: List<String>, also: () -> Unit = {}) {
    val c = LocalContainer.current
    var ask by remember { mutableStateOf(false) }
    PrismTextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.reset_this_page))
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(tr(R.string.reset_2, title)) },
        text = { Text(tr(R.string.this_page_s_look_goes_back)) },
        confirmButton = {
            PrismTextButton(onClick = {
                ask = false
                c.settings.resetLookKeys(lookKeys)
                also()
            }) { Text(tr(R.string.reset)) }
        },
        dismissButton = { PrismTextButton(onClick = { ask = false }) { Text(tr(R.string.cancel)) } },
    )
}

/**
 * "Customize this" (1.7.3): the settings for the element tapped, in a sheet, with a way to the whole page. A Home
 * section also gets its own style.
 *
 * 1.8.4: the groups are the rebuilt pages' own rows (the same composables the pages call), so a setting reads and works
 * here as on its page: the mini player's group and the tab bar's of Tabs & mini player; for the player, Player's
 * Layout, Progress bar and times, Cover and On it, its visualizer, and Lyrics & Sing's Style; for a Home section, Tabs &
 * mini player's Pages and Text & icons' Shapes and Buttons.
 */
@Composable
fun CustomizeSheet(target: com.prism.music.ui.components.CustomizeTarget, key: String?, onLeave: () -> Unit = {}, onDismiss: () -> Unit) {
    StoredSettings { CustomizeSheetContent(target, key, onLeave, onDismiss) }
}

@Composable
private fun CustomizeSheetContent(target: com.prism.music.ui.components.CustomizeTarget, key: String?, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val page = when (target) {
        com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER, com.prism.music.ui.components.CustomizeTarget.TAB_BAR -> SettingsSection.INTERFACE
        com.prism.music.ui.components.CustomizeTarget.PLAYER -> SettingsSection.PLAYER
        com.prism.music.ui.components.CustomizeTarget.HOME_SECTION -> SettingsSection.HOME
    }
    val section = key?.let { k -> s.homeLayout.firstOrNull { it.key == k } }
    com.prism.music.ui.components.ActionSheet(null, onDismiss) { close ->
        Text(
            section?.titled() ?: target.title, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
        )
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val asPrismComes = remember { AppSettings() }
            when (target) {
                com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER -> SettingsGroup(tr(R.string.mini_player)) { TabsMiniPlayerMiniRows() }
                // "Swipe between tabs" isn't here: its one home is Gestures & buttons.
                com.prism.music.ui.components.CustomizeTarget.TAB_BAR -> SettingsGroup(tr(R.string.navigation_bar)) { TabsMiniPlayerBarRows() }
                com.prism.music.ui.components.CustomizeTarget.PLAYER -> {
                    SettingsGroup(PlayerGroups.layout) { PlayerLayoutRows() }
                    SettingsGroup(PlayerGroups.progress) { PlayerProgressRows() }
                    // Cover animation is in Modes on the page (1.8.6); here, where Modes isn't, it stays with the cover.
                    SettingsGroup(PlayerGroups.cover) { PlayerCoverRows(); com.prism.music.ui.screens.settings.PlayerCoverAnimationRow() }
                    SettingsGroup(PlayerGroups.onIt) { PlayerOnItRows() }
                    SettingsGroup(tr(R.string.visualizer)) { PlayerVisualizerRows() }
                    // "Lyrics" and not "Style": here the group's name has to say whose.
                    SettingsGroup(tr(R.string.lyrics)) { LsStyleRows(asPrismComes) }
                }
                com.prism.music.ui.components.CustomizeTarget.HOME_SECTION -> {
                    if (section != null) SettingsGroup(tr(R.string.this_section)) {
                        // As Customize Home's menu: a style shows the section in it, so a pin drawn as one tile stops being one.
                        val tiled = section.tile && OwnSections.canTile(section.key)
                        val restyle: (com.prism.music.data.prefs.SectionStyle) -> Unit = { st ->
                            c.settings.setHomeLayout(s.homeLayout.map { if (it.key == section.key) it.copy(style = st, visible = true, tile = false) else it })
                        }
                        // Five styles: a pick with its list (chips are for two to four short words).
                        PickListRow(
                            tr(R.string.style), com.prism.music.data.prefs.SectionStyle.entries, section.style, { it.label },
                            value = if (tiled) tr(R.string.one_tile) else section.style.label, searchable = false,
                            onRepick = if (tiled) ({ restyle(section.style) }) else null, onPick = restyle,
                        )
                        LinkRow(tr(R.string.hide_this_section), value = tr(R.string.bring_it_back_from_customize_home), action = true) {
                            c.settings.setHomeLayout(s.homeLayout.map { if (it.key == section.key) it.copy(visible = false) else it })
                            close {}
                        }
                    }
                    SettingsGroup(tr(R.string.pages)) { TabsMiniPlayerPagesRows() }
                    SettingsGroup(tr(R.string.text_icons_shapes)) { TextIconsShapesRows() }
                    SettingsGroup(tr(R.string.buttons)) { TextIconsButtonsRows() }
                }
            }
            // The Undo bar sits under the sheet: a change made here can be taken back here.
            val steps by c.looks.steps.collectAsState()
            val openedAt = remember { System.currentTimeMillis() }
            steps.firstOrNull()?.takeIf { it.at >= openedAt }?.let { step ->
                PrismTextButton({ c.looks.undo() }, Modifier.fillMaxWidth()) {
                    Icon(PrismIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(tr(R.string.undo_2, step.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PrismTextButton({ close { onLeave(); nav.go(Routes.settings(page.key)) } }, Modifier.fillMaxWidth()) {
                Text(tr(R.string.all_settings, page.title)); Spacer(Modifier.width(6.dp)); Chevron()
            }
        }
    }
}
