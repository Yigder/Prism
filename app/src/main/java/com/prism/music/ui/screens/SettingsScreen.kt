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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil3.compose.AsyncImage
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.prefs.CarLyricsStyle
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.GlassKind
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.download.SMART_DOWNLOAD_SIZES
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.download.formatGb
import com.prism.music.download.smartSongEstimate
import kotlin.math.roundToInt
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.AccentSwatches
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/** The settings categories, in the order they're listed; each opens its own page. */
enum class SettingsSection(val key: String, val title: String, val icon: ImageVector) {
    APPEARANCE("appearance", "Appearance", Icons.Rounded.Palette),
    PLAYER("player", "Player", Icons.Rounded.PlayCircle),
    SOUND("sound", "Playback & sound", Icons.Rounded.GraphicEq),
    LYRICS("lyrics", "Lyrics", Icons.Rounded.FormatQuote),
    DOWNLOADS("downloads", "Downloads & storage", Icons.Rounded.DownloadForOffline),
    HOME("home", "Home screen", Icons.Rounded.Dashboard),
    ABOUT("about", "About", Icons.Rounded.Info);

    fun summary(s: AppSettings): String = when (this) {
        APPEARANCE -> listOf(s.themeMode.label + " theme", when { s.liquidGlass -> "Liquid Glass"; s.frostedGlass -> "Frosted Glass"; else -> "Solid bars" }).joinToString(" · ")
        PLAYER -> listOfNotNull(if (s.fullBleedArtwork) "Full-bleed artwork" else "Framed artwork", if (s.canvasEnabled) "Animated covers" else null, if (s.haptics) "Haptics" else null).joinToString(" · ")
        SOUND -> listOfNotNull(if (s.lossless) "Lossless" else s.audioQuality.label + " quality", if (s.spatial) "Prism Spatial" else null, if (s.eq.enabled) "EQ ${s.eq.preset}" else null, if (s.normalize) "Normalized" else null).joinToString(" · ")
        LYRICS -> "${s.lyricsOrder.first().label} first · ${if (s.preferSynced) "synced preferred" else "any lyrics"}"
        DOWNLOADS -> listOfNotNull(if (s.smartDownloads) "Smart downloads ${formatGb(s.smartDownloadGb)}" else null, if (s.downloadWifiOnly) "Wi-Fi only" else "Any network").joinToString(" · ")
        HOME -> "Sections, order and styles"
        ABOUT -> "Prism ${com.prism.music.BuildConfig.VERSION_NAME}"
    }

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

/** Settings: the account, then a list of categories; [section] shows one category's page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(bottomPadding: Dp, section: SettingsSection? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text(section?.title ?: "Settings") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            scrollBehavior = scroll,
            // See-through until scrolled, so a custom background shows behind the title.
            colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surface),
        )
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (section) {
                null -> {
                    item { AccountCard() }
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 6.dp)) {
                            SettingsSection.entries.forEach { sec ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { nav.go(Routes.settings(sec.key)) }.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                        Icon(sec.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(sec.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(sec.summary(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                SettingsSection.APPEARANCE -> item { AppearanceSettings() }
                SettingsSection.PLAYER -> item { PlayerSettings() }
                SettingsSection.SOUND -> item { SoundPage() }
                SettingsSection.LYRICS -> item { LyricsSettings() }
                SettingsSection.DOWNLOADS -> item { DownloadSettings() }
                SettingsSection.HOME -> item {
                    Group("Home") {
                        Item("Customize Home", "Reorder, hide and restyle Home's sections", onClick = { nav.go(Routes.CATALOGUE) })
                        Toggle("Add new sections automatically", "New shelves from YouTube Music show up on Home as they appear", s.autoAddSections) { c.settings.setAutoAddSections(it) }
                    }
                }
                SettingsSection.ABOUT -> item {
                    Group("About") {
                        Item("Prism ${com.prism.music.BuildConfig.VERSION_NAME}", "An unofficial YouTube Music client. Not affiliated with Google or YouTube.", onClick = {})
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- Account

@Composable
private fun AccountCard() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (s.isLoggedIn) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    if (s.accountAvatar.isNotBlank()) AsyncImage(s.accountAvatar, null, Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.Person, null)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.accountName.ifBlank { "YouTube Music" }, style = MaterialTheme.typography.titleMedium)
                    Text(s.accountEmail.ifBlank { "Signed in" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    c.settings.logout()
                    CookieManager.getInstance().removeAllCookies(null)
                }) { Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out") }
            }
        } else {
            Item("Sign in to YouTube Music", "Sync likes, playlists, history and recommendations", onClick = { nav.go(Routes.LOGIN) }) {
                Icon(Icons.Rounded.Login, null)
            }
        }
    }
}

// ---------------------------------------------------------------------------- Appearance

@Composable
private fun AppearanceSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Group("Theme") {
            Chips(ThemeMode.entries, s.themeMode, { it.label }) { prefs.setThemeMode(it) }
            Toggle("Pure black", "AMOLED-friendly dark theme", s.pureBlack) { prefs.setPureBlack(it) }
        }
        Group("Colour") {
            Chips(ColorSource.entries.filter { it != ColorSource.DYNAMIC || Build.VERSION.SDK_INT >= 31 }, s.colorSource, { it.label }) { prefs.setColorSource(it) }
            if (s.colorSource != ColorSource.DYNAMIC) {
                Text(
                    if (s.colorSource == ColorSource.ARTWORK) "Adapts to the cover of what's playing; this accent is used when nothing is."
                    else "Pick an accent colour",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                AccentPicker(s.accent) { prefs.setAccent(it) }
            }
        }
        Group("Backgrounds") {
            val count = s.backdrops.size
            val nav = LocalNavigator.current
            Item(
                "Screen backgrounds",
                if (count == 0) "Ready-made designs or your own photo for Home, Search, Library and more"
                else s.backdrops.values.map { com.prism.music.ui.theme.Backdrop.parse(it).first.label }.distinct().joinToString(" · ") + " on $count ${if (count == 1) "screen" else "screens"}",
                onClick = { nav.go(Routes.BACKGROUNDS) },
            )
        }
        Group("Glass") {
            Toggle("Liquid Glass", "Refractive glass for the bars, mini player and player controls", s.liquidGlass) { prefs.setLiquidGlass(it) }
            if (s.liquidGlass) {
                Label("Glass style")
                Chips(GlassKind.entries, s.glassKind, { it.label }) { prefs.setGlassKind(it) }
            }
            Toggle("Frosted Glass", "Soft blurred glass instead, without the refraction", s.frostedGlass) { prefs.setFrostedGlass(it) }
        }
    }
}

// ---------------------------------------------------------------------------- Player

@Composable
private fun PlayerSettings() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Group("Artwork") {
            Toggle("Full-bleed artwork", "Cover art runs edge to edge at the top of the player", s.fullBleedArtwork) { prefs.setFullBleedArtwork(it) }
            Toggle("Animated cover art", "Motion artwork and video covers, when a song has one. You can also turn it off for single songs from the player's ⋯ menu", s.canvasEnabled) { prefs.setCanvasEnabled(it) }
            if (s.canvasEnabled) Toggle("Animated covers on mobile data", null, s.canvasOnCellular) { prefs.setCanvasOnCellular(it) }
        }
        Group("On the player") {
            Toggle("Show \"Playing from\"", "The album, playlist or Autoplay label over the artwork", s.showPlayingFrom) { prefs.setShowPlayingFrom(it) }
            Toggle("Current lyric", "The line being sung, just above the scrubber", s.lyricsOnPlayer) { prefs.setLyricsOnPlayer(it) }
            Toggle("Stats for nerds", "Codec, bitrate and sample rate along the bottom of the artwork", s.showNerdStats) { prefs.setShowNerdStats(it) }
            Toggle("Hide volume bar", null, s.hideVolumeBar) { prefs.setHideVolumeBar(it) }
        }
        Group("Feel") {
            Toggle("Haptics", "A light tap when you press player buttons or swipe the mini player and artwork to change songs", s.haptics) { prefs.setHaptics(it) }
        }
    }
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
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { prefs.setLosslessLocal(true); scope.launch { c.localLossless.scan() } }
    }
    LaunchedEffect(s.losslessLocal) { if (s.losslessLocal && found < 0) c.localLossless.scan() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Group("Playback") {
            Toggle("Autoplay", "When a song, album or playlist ends, keep going with similar music", s.autoplay) { prefs.setAutoplay(it) }
            Toggle("Skip silence", "Trims silent gaps at the start and end of songs", s.skipSilence) { prefs.setSkipSilence(it) }
        }
        Group("Quality") {
            Toggle(
                "Lossless playback",
                "Always plays the best audio there is, keeps hi-res files at full resolution, and uses lossless files when the add-on below finds them. YouTube Music itself streams compressed audio (Opus/AAC), so true lossless needs the add-on",
                s.lossless,
            ) { prefs.setLossless(it) }
            if (s.lossless) {
                Toggle(
                    "Lossless add-on",
                    when {
                        !s.losslessLocal -> "Plays FLAC, WAV and AIFF files on your phone instead of the stream whenever they match the song"
                        found < 0 -> "Looking for lossless files…"
                        found == 0 -> "No lossless files found on this phone yet. Add FLAC, WAV or AIFF files to your Music folder"
                        else -> "$found lossless files found · matching songs play from them"
                    },
                    s.losslessLocal,
                ) { on ->
                    if (!on) prefs.setLosslessLocal(false)
                    else if (c.localLossless.hasPermission()) { prefs.setLosslessLocal(true); scope.launch { c.localLossless.scan() } }
                    else askFiles.launch(LocalLossless.permission)
                }
                if (s.losslessLocal) Item("Scan for lossless files again", null, onClick = { scope.launch { c.localLossless.scan() } })
            } else {
                Label("Streaming quality on Wi-Fi")
                Chips(AudioQuality.entries, s.audioQuality, { it.label }) { prefs.setAudioQuality(it) }
                Label("Streaming quality on mobile data")
                Chips(AudioQuality.entries, s.cellularQuality, { it.label }) { prefs.setCellularQuality(it) }
            }
            Label("Music video quality")
            Chips(listOf(480, 720, 1080, 1440), s.videoMaxHeight, { "${it}p" }) { prefs.setVideoMaxHeight(it) }
            Spacer(Modifier.height(4.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Group("Display") {
            Toggle("Prefer synced lyrics", "Skip plain-text results when a later source has time-synced lines", s.preferSynced) { prefs.setPreferSynced(it) }
            Toggle("Focus blur", "Lines further from the one being sung fall out of focus", s.lyricsBlur) { prefs.setLyricsBlur(it) }
            Label("Text size")
            var scale by remember { mutableFloatStateOf(s.lyricsScale) }
            Slider(scale, { scale = it }, Modifier.padding(horizontal = 16.dp), valueRange = 0.75f..1.4f, onValueChangeFinished = { prefs.setLyricsScale(scale) })
        }
        Group("Android Auto") {
            Toggle("Lyrics in the car", "On the car's player, in place of the song title and cover. The Lyrics button there turns them on and off too.", s.carLyrics) { prefs.setCarLyrics(it) }
            if (s.carLyrics) {
                Label("Style")
                Chips(CarLyricsStyle.entries, s.carLyricsStyle, { it.label }) { prefs.setCarLyricsStyle(it) }
                Text(
                    s.carLyricsStyle.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                CarLyricsPreview(s.carLyricsStyle)
                Toggle("On this phone too", "Show them the same way on the lock screen and in the media notification", s.carLyricsOnPhone) { prefs.setCarLyricsOnPhone(it) }
            }
        }
        Group("Sources") {
            Text(
                "Prism tries these in order. You can also switch source on any song from the player's ⋯ menu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            s.lyricsOrder.forEachIndexed { i, src ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(24.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                    Text(src.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Group("Downloads") {
            Item("Manage downloads", "See, sort and delete what's on your phone", onClick = { nav.go(Routes.DOWNLOADS) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                Text(
                    "About ${smartSongEstimate(gb, s.audioQuality)} songs at ${s.audioQuality.label.lowercase()} quality · %.1f GB free on this phone".format(freeGb) +
                        if (gb > freeGb) " — more than is free, so it'll stop when storage runs low" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
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
            Text(
                "Songs you stream are cached so replays start instantly and use no data. Used: %.0f MB".format(cacheBytes / 1_048_576.0),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Label("Cache size (applies after restart)")
            Chips(listOf(512, 1024, 2048, 4096, 8192, 0), s.cacheSizeMb, { if (it == 0) "Unlimited" else if (it >= 1024) "${it / 1024} GB" else "$it MB" }) { prefs.setCacheSizeMb(it) }
            Item("Clear song cache", null, onClick = { c.clearSongCache(); cacheBytes = c.songCacheBytes() })
        }
    }
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp))
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun Item(title: String, subtitle: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(o == selected, { onSelect(o) }, { Text(label(o)) }) }
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
    var lyrics by remember { androidx.compose.runtime.mutableStateOf<com.prism.music.data.lyrics.Lyrics?>(null) }
    var loaded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var bg by remember { androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null) }
    var card by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var line by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
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
        val shape = RoundedCornerShape(14.dp)
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