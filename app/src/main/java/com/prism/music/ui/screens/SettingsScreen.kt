package com.prism.music.ui.screens

import android.os.Build
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil3.compose.AsyncImage
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.prefs.CarLyricsStyle
import com.prism.music.data.prefs.CardSize
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.Corners
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.GlassKind
import com.prism.music.data.prefs.LibraryView
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.ScrubberStyle
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.data.prefs.TitleSize
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.data.prefs.UiDensity
import com.prism.music.download.SMART_DOWNLOAD_SIZES
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.download.formatGb
import com.prism.music.download.smartSongEstimate
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.featurePane
import com.prism.music.ui.theme.AccentSwatches
import com.prism.music.ui.theme.AppFonts
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The settings categories, in the order they're listed; each opens its own page. */
enum class SettingsSection(val key: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", "Appearance", Icons.Outlined.Palette),
    INTERFACE("interface", "Layout & navigation", Icons.Outlined.SpaceDashboard),
    PLAYER("player", "Player", Icons.Outlined.PlayCircle),
    SOUND("sound", "Playback & sound", Icons.Outlined.GraphicEq),
    LYRICS("lyrics", "Lyrics", Icons.Outlined.FormatQuote),
    DOWNLOADS("downloads", "Downloads & storage", Icons.Outlined.DownloadForOffline),
    HOME("home", "Home screen", Icons.Outlined.Home),
    LIBRARY("library", "Library & Replay", Icons.Outlined.LibraryMusic),
    ABOUT("about", "About", Icons.Outlined.Info);

    fun summary(s: AppSettings): String = when (this) {
        APPEARANCE -> listOf(s.themeMode.label + " theme", s.fontChoice.label + " font", when { s.liquidGlass -> "Liquid Glass"; s.frostedGlass -> "Frosted Glass"; else -> "Solid bars" }).joinToString(" · ")
        INTERFACE -> listOf(s.navStyle.label + " bar", "${s.navTabs.size} tabs", s.miniPlayerStyle.label + " mini player").joinToString(" · ")
        PLAYER -> listOfNotNull(s.playerBackground.label, if (s.fullBleedArtwork) "Full-bleed artwork" else "Framed artwork", if (s.canvasEnabled) "Animated covers" else null).joinToString(" · ")
        SOUND -> listOfNotNull(if (s.lossless) "Lossless" else s.audioQuality.label + " quality", if (s.spatial) "Prism Spatial" else null, if (s.eq.enabled) "EQ ${s.eq.preset}" else null, if (s.normalize) "Normalized" else null).joinToString(" · ")
        LYRICS -> "${s.lyricsOrder.first().label} first · ${if (s.preferSynced) "synced preferred" else "any lyrics"}"
        DOWNLOADS -> listOfNotNull(if (s.smartDownloads) "Smart downloads ${formatGb(s.smartDownloadGb)}" else null, if (s.downloadWifiOnly) "Wi-Fi only" else "Any network").joinToString(" · ")
        HOME -> "Sections, order and styles"
        LIBRARY -> "${s.libraryView.label} view · ${if (s.replayHours) "hours" else "minutes"}"
        ABOUT -> "Prism ${com.prism.music.BuildConfig.VERSION_NAME}"
    }

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

private val hubGroups = listOf(
    "Look & feel" to listOf(SettingsSection.APPEARANCE, SettingsSection.INTERFACE, SettingsSection.PLAYER),
    "Listening" to listOf(SettingsSection.SOUND, SettingsSection.LYRICS, SettingsSection.DOWNLOADS),
    "Your Prism" to listOf(SettingsSection.HOME, SettingsSection.LIBRARY),
    "" to listOf(SettingsSection.ABOUT),
)

/** Settings: the account, then the categories; [section] shows one category's page. */
@Composable
fun SettingsScreen(bottomPadding: Dp, section: SettingsSection? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    // As a tab, the hub is a root page and has nothing to go back to.
    val isTab = section == null && NavTab.SETTINGS in s.navTabs

    SubPage(
        title = section?.title ?: "Settings",
        bottomPadding = bottomPadding,
        horizontalPadding = 16.dp,
        spacing = 22.dp,
        showBack = !isTab,
    ) {
        when (section) {
            null -> {
                item { AccountCard() }
                hubGroups.forEach { (title, sections) ->
                    item {
                        Group(title) {
                            sections.forEach { sec -> NavRow(sec.icon, sec.title, sec.summary(s)) { nav.go(Routes.settings(sec.key)) } }
                        }
                    }
                }
            }
            SettingsSection.APPEARANCE -> appearance()
            SettingsSection.INTERFACE -> layoutAndNavigation()
            SettingsSection.PLAYER -> item { PlayerSettings() }
            SettingsSection.SOUND -> item { SoundPage() }
            SettingsSection.LYRICS -> item { LyricsSettings() }
            SettingsSection.DOWNLOADS -> item { DownloadSettings() }
            SettingsSection.HOME -> item {
                Group("Home") {
                    Item("Customize Home", "Reorder, hide and restyle Home's sections, shortcuts and greeting", onClick = { nav.go(Routes.CATALOGUE) }) { Chevron() }
                    Toggle("Add new sections automatically", "New shelves from YouTube Music show up on Home as they appear", s.autoAddSections) { c.settings.setAutoAddSections(it) }
                    Toggle("\"Customize Home\" button", "At the very bottom of Home", s.homeCustomizeButton) { c.settings.setHomeCustomizeButton(it) }
                }
            }
            SettingsSection.LIBRARY -> item { LibraryReplaySettings() }
            SettingsSection.ABOUT -> item {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                BackupGroup()
                Group("About") {
                    Item("Prism ${com.prism.music.BuildConfig.VERSION_NAME}", "An unofficial YouTube Music client. Not affiliated with Google or YouTube.", onClick = {})
                    Item("Your data stays here", "History, Replay and settings live only on this phone. Prism has no server.", onClick = {})
                    CheckForUpdates()
                }
                }
            }
        }
    }
}

/** Prism also checks on its own from Home; tapping this asks GitHub right now. */
@Composable
private fun CheckForUpdates() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Item(
        "Check for updates",
        when {
            checking -> "Asking GitHub…"
            result != null -> result
            else -> "Prism looks for new versions on its own and says so on Home"
        },
        onClick = {
            if (!checking) scope.launch {
                checking = true
                result = runCatching { c.updates.checkNow() }.fold(
                    { v -> if (v != null) "Prism $v is out · install it from the banner on Home" else "You're up to date" },
                    { "Couldn't reach GitHub · try again in a moment" },
                )
                checking = false
            }
        },
    ) {
        if (checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------- Account

@Composable
private fun AccountCard() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    // A pane of frosted glass on a frosted page, the accent's gradient otherwise.
    val frosted = com.prism.music.ui.components.LocalFrosted.current
    val ink = com.prism.music.ui.components.featureInk()
    Row(
        Modifier.fillMaxWidth().featurePane(ui.card)
            .then(if (s.isLoggedIn) Modifier else Modifier.clickable { nav.go(Routes.LOGIN) })
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(if (frosted) com.prism.music.ui.components.frostFill(1.6f) else scheme.surface.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (s.isLoggedIn && s.accountAvatar.isNotBlank()) AsyncImage(s.accountAvatar, null, Modifier.fillMaxSize())
            else Icon(if (s.isLoggedIn) Icons.Rounded.Person else Icons.Rounded.Login, null, tint = if (frosted) scheme.primary else ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (s.isLoggedIn) s.accountName.ifBlank { "YouTube Music" } else "Sign in to YouTube Music",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s.isLoggedIn) s.accountEmail.ifBlank { "Signed in" } else "Sync likes, playlists, history and recommendations",
                style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.isLoggedIn) OutlinedButton(onClick = {
            c.settings.logout()
            CookieManager.getInstance().removeAllCookies(null)
        }) { Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out") }
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink)
    }
}

// ---------------------------------------------------------------------------- Appearance

private fun androidx.compose.foundation.lazy.LazyListScope.appearance() {
    item(key = "theme") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Theme") {
            Chips(ThemeMode.entries, s.themeMode, { it.label }) { prefs.setThemeMode(it) }
            Toggle("Pure black", "AMOLED-friendly dark theme", s.pureBlack) { prefs.setPureBlack(it) }
        }
    }
    item(key = "colour") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Colour") {
            Chips(ColorSource.entries.filter { it != ColorSource.DYNAMIC || Build.VERSION.SDK_INT >= 31 }, s.colorSource, { it.label }) { prefs.setColorSource(it) }
            if (s.colorSource != ColorSource.DYNAMIC) {
                Note(
                    if (s.colorSource == ColorSource.ARTWORK) "Adapts to the cover of what's playing; this accent is used when nothing is."
                    else "Pick an accent colour"
                )
                AccentPicker(s.accent) { prefs.setAccent(it) }
            }
        }
    }
    item(key = "type") { TypeSettings() }
    item(key = "shape") { ShapeSettings() }
    item(key = "backgrounds") {
        val s = LocalAppSettings.current
        val nav = LocalNavigator.current
        Group("Backgrounds") {
            val count = s.backdrops.size
            Item(
                "Screen backgrounds",
                if (count == 0) "Ready-made designs or your own photo for Home, Search, Library and more"
                else s.backdrops.values.map { com.prism.music.ui.theme.Backdrop.parse(it).first.label }.distinct().joinToString(" · ") + " on $count ${if (count == 1) "screen" else "screens"}",
                onClick = { nav.go(Routes.BACKGROUNDS) },
            ) { Chevron() }
        }
    }
    item(key = "glass") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Glass") {
            Toggle("Liquid Glass", "Refractive glass for the bars, mini player and player controls", s.liquidGlass) { prefs.setLiquidGlass(it) }
            if (s.liquidGlass) {
                Label("Glass style")
                Chips(GlassKind.entries, s.glassKind, { it.label }) { prefs.setGlassKind(it) }
            }
            Toggle("Frosted Glass", "Soft blurred glass instead, without the refraction", s.frostedGlass) { prefs.setFrostedGlass(it) }
            Toggle("Frosted pages", "Artist, album and playlist pages sit on frosted glass made from their picture, and take its colour; Home, Library, Search and Settings on frost from what's playing", s.frostedPages) { prefs.setFrostedPages(it) }
        }
    }
    item(key = "motion") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Motion") {
            Toggle("Reduce motion", "Calmer animations: no drifting backgrounds, no artwork bounce, quicker transitions", s.reduceMotion) { prefs.setReduceMotion(it) }
        }
    }
    item(key = "reset") { ResetLook() }
}

@Composable
private fun TypeSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Text") {
        Label("Font")
        FontPicker(s.fontChoice) { prefs.setFontChoice(it) }
        SliderSetting("Text size", s.textScale, 0.85f..1.25f, { "${(it * 100).roundToInt()}%" }, steps = 7) { prefs.setTextScale(it) }
        Note("On top of your phone's own text size.")
    }
}

/** Each font's name, set in that font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontPicker(current: FontChoice, onPick: (FontChoice) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ui = LocalUi.current
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FontChoice.entries.forEach { f ->
            val on = f == current
            Column(
                Modifier.width(96.dp).clip(ui.tile)
                    .background(if (on) scheme.primaryContainer else com.prism.music.ui.components.quietFill())
                    .border(if (on) 2.dp else 0.dp, if (on) scheme.primary else Color.Transparent, ui.tile)
                    .clickable { onPick(f) }.padding(10.dp),
            ) {
                Text("Aa", style = TextStyle(fontFamily = AppFonts.family(f), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = if (on) scheme.onPrimaryContainer else scheme.onSurface)
                Text(f.label, style = MaterialTheme.typography.labelMedium.copy(fontFamily = AppFonts.family(f)), color = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ShapeSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val scheme = MaterialTheme.colorScheme
    Group("Shape") {
        Label("Corners")
        Chips(Corners.entries, s.corners, { it.label }) { prefs.setCorners(it) }
        // A peek at what the corners look like.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ui = LocalUi.current
            listOf(scheme.primary, scheme.tertiary, scheme.secondary).forEach { col ->
                Box(Modifier.weight(1f).aspectRatio(1.3f).clip(ui.tile).background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.55f)))))
            }
        }
    }
}

@Composable
private fun ResetLook() {
    val prefs = LocalContainer.current.settings
    var ask by remember { mutableStateOf(false) }
    TextButton(onClick = { ask = true }, Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reset look & layout to defaults")
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Reset look & layout?") },
        text = { Text("Font, text size, corners, spacing, navigation bar, tabs, mini player and player style go back to how Prism ships. Your colours, backgrounds and everything else stay as they are.") },
        confirmButton = { TextButton(onClick = { prefs.resetLook(); ask = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------- Layout & navigation

private fun androidx.compose.foundation.lazy.LazyListScope.layoutAndNavigation() {
    item(key = "nav") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Navigation bar") {
            Label("Style")
            Chips(NavBarStyle.entries, s.navStyle, { it.label }) { prefs.setNavStyle(it) }
            Label("Labels")
            Chips(NavLabels.entries, s.navLabels, { it.label }) { prefs.setNavLabels(it) }
            Toggle("Shrink while scrolling", "The bar slims down as you scroll a page and comes back when you scroll up", s.navHideOnScroll) { prefs.setNavHideOnScroll(it) }
        }
    }
    item(key = "tabs") { TabsEditor() }
    item(key = "mini") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Mini player") {
            Chips(MiniPlayerStyle.entries, s.miniPlayerStyle, { it.label }) { prefs.setMiniPlayerStyle(it) }
            Toggle("Previous & next buttons", "Swiping the mini player sideways changes songs either way", s.miniSkipButtons) { prefs.setMiniSkipButtons(it) }
            Label("Progress")
            Chips(MiniProgress.entries, s.miniProgress, { it.label }) { prefs.setMiniProgress(it) }
        }
    }
    item(key = "density") {
        val s = LocalAppSettings.current
        val prefs = LocalContainer.current.settings
        Group("Pages") {
            Label("Spacing")
            Chips(UiDensity.entries, s.uiDensity, { it.label }) { prefs.setUiDensity(it) }
            Label("Page titles")
            Chips(TitleSize.entries, s.titleSize, { it.label }) { prefs.setTitleSize(it) }
            Label("Album & playlist cards")
            Chips(CardSize.entries, s.cardSize, { it.label }) { prefs.setCardSize(it) }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Which tabs the bar holds, in what order, and which one Prism opens on. */
@Composable
private fun TabsEditor() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val tabs = s.navTabs
    Group("Tabs") {
        Note("Tick the tabs you want and use the arrows to order them. Keep Home, Library or Settings so Settings stays in reach.")
        val ordered = tabs + NavTab.entries.filter { it !in tabs }
        // Settings opens from Home and Library, or is a tab itself: one of them always stays.
        fun allowed(next: List<NavTab>) = next.size >= 2 && next.any { it == NavTab.HOME || it == NavTab.LIBRARY || it == NavTab.SETTINGS }
        ordered.forEach { t ->
            val on = t in tabs
            val i = tabs.indexOf(t)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { v ->
                    val next = if (v) tabs + t else tabs - t
                    if (allowed(next)) {
                        prefs.setNavTabs(next)
                        if (!v && s.startTab == t) prefs.setStartTab(next.first())
                    }
                }, enabled = !on || allowed(tabs - t))
                Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (on) {
                    IconButton(onClick = { prefs.setNavTabs(tabs.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { prefs.setNavTabs(tabs.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < tabs.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                }
            }
        }
        Label("Open Prism on")
        Chips(tabs, if (s.startTab in tabs) s.startTab else tabs.first(), { it.label }) { prefs.setStartTab(it) }
        Note("Takes effect the next time Prism starts.")
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------- Player

@Composable
private fun PlayerSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group("Look") {
            Label("Background")
            Chips(PlayerBackground.entries, s.playerBackground, { it.label }) { prefs.setPlayerBackground(it) }
            Label("Controls")
            Chips(TransportStyle.entries, s.transportStyle, { it.label }) { prefs.setTransportStyle(it) }
            Label("Seek bar")
            Chips(ScrubberStyle.entries, s.scrubberStyle, { it.label }) { prefs.setScrubberStyle(it) }
            SliderSetting("Artwork corners", s.playerArtCorners.toFloat(), 0f..32f, { "${it.roundToInt()} dp" }, steps = 7) { prefs.setPlayerArtCorners(it.roundToInt()) }
        }
        Group("Artwork") {
            Toggle("Full-bleed artwork", "Cover art runs edge to edge at the top of the player", s.fullBleedArtwork) { prefs.setFullBleedArtwork(it) }
            Toggle("Shrink when paused", "The cover eases back a little while the music is paused", s.artShrinkOnPause) { prefs.setArtShrinkOnPause(it) }
            Toggle("Animated cover art", "Motion artwork and video covers, when a song has one. You can also turn it off for single songs from the player's ⋯ menu", s.canvasEnabled) { prefs.setCanvasEnabled(it) }
            if (s.canvasEnabled) Toggle("Animated covers on mobile data", "Saved covers play from the phone either way", s.canvasOnCellular) { prefs.setCanvasOnCellular(it) }
            if (s.canvasEnabled) CanvasStorage()
        }
        Group("On the player") {
            Toggle("Show \"Playing from\"", "The song's album over the artwork; tap it to open the album", s.showPlayingFrom) { prefs.setShowPlayingFrom(it) }
            Toggle("Current lyric", "The line being sung, just above the scrubber", s.lyricsOnPlayer) { prefs.setLyricsOnPlayer(it) }
            Toggle("Stats for nerds", "Codec, bitrate and sample rate along the bottom of the artwork", s.showNerdStats) { prefs.setShowNerdStats(it) }
            Toggle("Output device", "Where the sound is going, under the controls (tap it to switch)", s.showOutputDevice) { prefs.setShowOutputDevice(it) }
            Toggle("Hide volume bar", null, s.hideVolumeBar) { prefs.setHideVolumeBar(it) }
        }
        Group("Feel") {
            Toggle("Haptics", "A light tap when you press player buttons or swipe the mini player and artwork to change songs", s.haptics) { prefs.setHaptics(it) }
        }
    }
}

/** Animated covers kept on the phone: the ones saved from the player, and the recently played ones. */
@Composable
private fun CanvasStorage() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val saved by c.canvas.saved.collectAsState()
    var version by remember { mutableStateOf(0) }
    val sizes by androidx.compose.runtime.produceState(0L to 0L, version, saved) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.savedBytes to c.canvasStore.recentBytes }
    }
    fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_048_576f)
    Item(
        "Saved animated covers",
        if ((saved.count == 0)) "None yet · save one from the player's ⋯ menu so it never has to load again"
        else "${saved.count} saved · ${mb(sizes.first)} · tap to remove them all",
        onClick = {
            if ((saved.count > 0)) scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.clearSaved() }
                version++
            }
        },
    )
    Item(
        "Recently played covers",
        "${mb(sizes.second)} kept so they start instantly next time (up to 400 MB) · tap to clear",
        onClick = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.canvasStore.clearRecent() }
                version++
            }
        },
    )
}

// ---------------------------------------------------------------------------- Playback & sound

@Composable
private fun SoundPage() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val found by c.localLossless.found.collectAsState()
    val synced by c.losslessSync.songs.collectAsState()
    val syncing by c.losslessSync.working.collectAsState()
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Lossless still works without file access; it just can't use files on the phone.
        prefs.setLossless(true)
        if (granted) prefs.setLosslessLocal(true) // lossless sync scans when this turns on
    }
    LaunchedEffect(s.losslessLocal) { if (s.losslessLocal && found < 0) c.losslessSync.sync() }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group("Playback") {
            Toggle("Autoplay", "When a song, album or playlist ends, keep going with similar music", s.autoplay) { prefs.setAutoplay(it) }
            Toggle("Skip silence", "Trims silent gaps at the start and end of songs", s.skipSilence) { prefs.setSkipSilence(it) }
        }
        Group("Quality") {
            Toggle(
                "Lossless playback",
                "Always plays the best audio there is, keeps hi-res files at full resolution, and plays matching FLAC, WAV and AIFF files on your phone instead of the stream" + when {
                    !s.lossless -> ""
                    !s.losslessLocal -> " · allow file access to use lossless files"
                    found < 0 -> " · looking for lossless files…"
                    found == 0 -> " · no lossless files found yet (add them to your Music folder)"
                    syncing -> " · $found lossless files found, adding them to Downloads…"
                    else -> " · $found lossless files found, ${synced.size} in Downloads"
                },
                s.lossless,
            ) { on ->
                if (!on) { prefs.setLossless(false); prefs.setLosslessLocal(false) }
                else if (c.localLossless.hasPermission()) {
                    prefs.setLossless(true); prefs.setLosslessLocal(true)
                } else askFiles.launch(LocalLossless.permission)
            }
            if (s.lossless) {
                if (s.losslessLocal) Item(
                    "Scan for lossless files again",
                    "New files are added to Downloads as soon as they're on your phone, and Prism checks again every few hours",
                    onClick = { scope.launch { c.losslessSync.sync() } },
                )
                else Item("Allow access to lossless files", null, onClick = { askFiles.launch(LocalLossless.permission) })
            } else {
                Label("Streaming quality on Wi-Fi")
                Chips(AudioQuality.entries, s.audioQuality, { it.label }) { prefs.setAudioQuality(it) }
                Label("Streaming quality on mobile data")
                Chips(AudioQuality.entries, s.cellularQuality, { it.label }) { prefs.setCellularQuality(it) }
            }
            Label("Music video quality")
            Chips(listOf(480, 720, 1080, 1440), s.videoMaxHeight, { "${it}p" }) { prefs.setVideoMaxHeight(it) }
            Spacer(Modifier.height(6.dp))
        }
        Group("Sound") {
            Toggle("Normalize volume", "Evens out loudness between songs: loud ones come down, quiet ones come up (with a limiter so nothing clips). Works offline too", s.normalize) { prefs.setNormalize(it) }
            SoundSettings(onOpenEq = { nav.go(Routes.EQ) })
        }
    }
}

// ---------------------------------------------------------------------------- Lyrics

@Composable
private fun LyricsSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group("Display") {
            Toggle("Prefer synced lyrics", "Skip plain-text results when a later source has time-synced lines", s.preferSynced) { prefs.setPreferSynced(it) }
            Toggle("Skip censored lyrics", "Pass over a source that stars out swear words (f**k) when another has the full lyrics", s.skipCensored) { prefs.setSkipCensored(it) }
            Toggle("Focus blur", "Lines further from the one being sung fall out of focus", s.lyricsBlur) { prefs.setLyricsBlur(it) }
            Label("Alignment")
            Chips(LyricsAlign.entries, s.lyricsAlign, { it.label }) { prefs.setLyricsAlign(it) }
            SliderSetting("Text size", s.lyricsScale, 0.75f..1.4f, { "${(it * 100).roundToInt()}%" }) { prefs.setLyricsScale(it) }
        }
        Group("Android Auto") {
            Toggle("Lyrics in the car", "On the car's player, in place of the song title and cover. The Lyrics button there turns them on and off too.", s.carLyrics) { prefs.setCarLyrics(it) }
            if (s.carLyrics) {
                Label("Style")
                Chips(CarLyricsStyle.entries, s.carLyricsStyle, { it.label }) { prefs.setCarLyricsStyle(it) }
                Note(s.carLyricsStyle.detail)
                CarLyricsPreview(s.carLyricsStyle)
                Toggle("On this phone too", "Also show them on the lock screen and in the media notification when you're not driving", s.carLyricsOnPhone) { prefs.setCarLyricsOnPhone(it) }
            }
        }
        Group("Sources") {
            Note("Prism tries these in order. You can also switch source on any song from the player's ⋯ menu.")
            s.lyricsOrder.forEachIndexed { i, src ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", color = if (i == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(src.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { prefs.setLyricsOrder(s.lyricsOrder.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Up")
                    }
                    IconButton(onClick = { prefs.setLyricsOrder(s.lyricsOrder.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < s.lyricsOrder.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Down")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- Downloads & storage

@Composable
private fun DownloadSettings() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var cacheBytes by remember { mutableLongStateOf(c.songCacheBytes()) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group("Downloads") {
            Item("Manage downloads", "See, sort and delete what's on your phone", onClick = { nav.go(Routes.DOWNLOADS) }) { Chevron() }
            Toggle("Download on Wi-Fi only", null, s.downloadWifiOnly) { prefs.setDownloadWifiOnly(it) }
            Toggle("Download liked songs automatically", "Every song you like is saved for offline, including likes synced from other devices", s.downloadLikes) {
                prefs.setDownloadLikes(it)
                if (it) c.downloads.downloadAll(c.library.liked.value)
            }
        }
        Group("Smart downloads") {
            Toggle("Smart downloads", "Keeps the songs you're most likely to play offline, learned on your phone from what you play, finish, skip and like. Fills the space you choose gradually over a few days on Wi-Fi, and swaps songs out as your taste changes", s.smartDownloads) {
                prefs.setSmartDownloads(it)
                SmartDownloadWorker.schedule(context, it)
                if (it) SmartDownloadWorker.runNow(context)
            }
            if (s.smartDownloads) {
                val sizes = SMART_DOWNLOAD_SIZES
                var i by remember { mutableFloatStateOf(sizes.indexOfFirst { it >= s.smartDownloadGb }.coerceAtLeast(0).toFloat()) }
                val gb = sizes[i.roundToInt().coerceIn(0, sizes.lastIndex)]
                val freeGb = remember { context.filesDir.usableSpace / 1_073_741_824f }
                Label("Space to use: ${formatGb(gb)}")
                Note(
                    "About ${smartSongEstimate(gb, s.audioQuality)} songs at ${s.audioQuality.label.lowercase()} quality · %.1f GB free on this phone".format(freeGb) +
                        if (gb > freeGb) " — more than is free, so it'll stop when storage runs low" else ""
                )
                Slider(
                    i, { i = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0f..sizes.lastIndex.toFloat(), steps = sizes.size - 2,
                    onValueChangeFinished = {
                        prefs.setSmartDownloadGb(gb)
                        SmartDownloadWorker.runNow(context)
                    },
                )
            }
        }
        Group("Song cache") {
            Note("Songs you stream are cached so replays start instantly and use no data. Used: %.0f MB".format(cacheBytes / 1_048_576.0))
            Label("Cache size (applies after restart)")
            Chips(listOf(512, 1024, 2048, 4096, 8192, 0), s.cacheSizeMb, { if (it == 0) "Unlimited" else if (it >= 1024) "${it / 1024} GB" else "$it MB" }) { prefs.setCacheSizeMb(it) }
            Item("Clear song cache", null, onClick = { c.clearSongCache(); cacheBytes = c.songCacheBytes() })
        }
    }
}

// ---------------------------------------------------------------------------- Library & Replay

private val libraryTabs = listOf("PLAYLISTS" to "Playlists", "SONGS" to "Songs", "ALBUMS" to "Albums", "ARTISTS" to "Artists", "DOWNLOADS" to "Downloads")

@Composable
private fun LibraryReplaySettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group("Library") {
            Label("Albums, artists & playlists as")
            Chips(LibraryView.entries, s.libraryView, { it.label }) { prefs.setLibraryView(it) }
            Label("Open Library on")
            Chips(libraryTabs.map { it.first }, s.libraryStartTab, { k -> libraryTabs.first { it.first == k }.second }) { prefs.setLibraryStartTab(it) }
            Spacer(Modifier.height(6.dp))
            Toggle("Full-width playlist pictures", "Playlists open with their picture edge to edge, like an artist page", s.playlistHeroCover) { prefs.setPlaylistHeroCover(it) }
        }
        Group("Replay") {
            Toggle("Show time in hours", if (s.replayHours) "Listening time reads like \"20h 4m\"" else "Listening time reads like \"1,204 min\"", s.replayHours) { prefs.setReplayHours(it) }
            Toggle("Use theme colours", "Replay's cards follow your accent instead of Prism's own gradient", s.replayThemeColors) { prefs.setReplayThemeColors(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(current: Int, onPick: (Int) -> Unit) {
    var hue by remember {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(current, hsl); mutableFloatStateOf(hsl[0])
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentSwatches.forEach { col ->
                val sel = col.toArgb() == current
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(col)
                        .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { onPick(col.toArgb()) },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(
                Brush.horizontalGradient((0..6).map { Color(ColorUtils.HSLToColor(floatArrayOf(it * 60f, 0.75f, 0.6f))) })
            )
        )
        Slider(hue, { hue = it }, valueRange = 0f..359f, onValueChangeFinished = {
            onPick(ColorUtils.HSLToColor(floatArrayOf(hue, 0.72f, 0.6f)))
        })
    }
}

/** What the car shows right now (or would), drawn by the same code that draws it for the car. */
@Composable
private fun CarLyricsPreview(style: CarLyricsStyle) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val song by c.player.currentSong.collectAsState()
    var lyrics by remember { mutableStateOf<com.prism.music.data.lyrics.Lyrics?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var bg by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var card by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song?.id) {
        lyrics = null; card = null; line = null; loaded = false
        val s = song ?: return@LaunchedEffect
        lyrics = runCatching { c.lyrics.auto(s) }.getOrNull()?.takeIf { it.synced }
        loaded = true
        bg = runCatching { com.prism.music.playback.CarLyricsCard.background(context, s.thumbnail) }.getOrNull()
    }
    LaunchedEffect(lyrics, style, bg) {
        val l = lyrics ?: return@LaunchedEffect
        while (true) {
            val pos = c.player.lyricPosition
            var idx = com.prism.music.playback.CarLyricsCard.lineAt(l.lines, pos)
            if (idx < 0 || l.lines[idx].isGap) idx = l.lines.indexOfFirst { !it.isGap && it.text.isNotBlank() && it.timeMs >= pos }.takeIf { it >= 0 } ?: idx
            val ln = l.lines.getOrNull(idx)
            if (ln != null && !ln.isGap) {
                val sung = if (style == CarLyricsStyle.KARAOKE && ln.isWordSynced) ln.words.count { it.startMs <= pos } else -1
                line = ln.text
                card = if (style == CarLyricsStyle.TEXT) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.prism.music.playback.CarLyricsCard.render(bg, l.lines, idx, sung).asImageBitmap()
                }
            }
            kotlinx.coroutines.delay(300)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = LocalUi.current.shape(14.dp)
        val img = card
        if (img != null) androidx.compose.foundation.Image(img, "Lyric card", Modifier.size(132.dp).clip(shape))
        else com.prism.music.ui.components.Artwork(song?.thumbnail, Modifier.size(132.dp), shape, size = 300)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            val s = song
            when {
                s == null -> Text("Play a song to see how its lyrics look in the car.", style = MaterialTheme.typography.bodyMedium)
                loaded && lyrics == null -> {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("No synced lyrics for this song, so the car shows it as usual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(line ?: s.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    Text("${s.title} · ${s.artistText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
