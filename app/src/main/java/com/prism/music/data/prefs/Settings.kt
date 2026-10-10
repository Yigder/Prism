package com.prism.music.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("prism_settings")

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class ColorSource(val label: String) { ARTWORK("Now playing"), DYNAMIC("Wallpaper"), CUSTOM("Custom") }
enum class GlassKind(val label: String) { REGULAR("Regular"), CLEAR("Clear") }
enum class AudioQuality(val label: String) { HIGH("High"), NORMAL("Normal"), LOW("Low") }
/** How lyrics show on Android Auto's player. */
enum class CarLyricsStyle(val label: String, val detail: String) {
    KARAOKE("Karaoke", "The line on the cover art, lighting up word by word, and as the title"),
    LINES("Line by line", "The current and next line on the cover art, and as the title"),
    TEXT("Title only", "The current line in place of the song title; the cover stays as it is"),
}

enum class LyricsSource(val label: String) {
    APPLE("Better Lyrics"),
    LYRICSPLUS("LyricsPlus"),
    BINI("Bini"),
    QQ("QQ · Portato"),
    LRCLIB("LRCLIB"),
    NETEASE("NetEase"),
    KUGOU("KuGou"),
    UNISON("Unison"),
    YTMUSIC("YouTube Music"),
    GENIUS("Genius"),
    LYRICSOVH("Lyrics.ovh"),
}

/** Tiles in Home's greeting section. */
enum class HomeShortcut(val label: String) {
    LIKED("Liked songs"), DOWNLOADS("Downloads"), REPLAY("Replay"), LIBRARY("Library"),
    MOODS("Moods & genres"), SEARCH("Search"), EQUALIZER("Equalizer"), TOGETHER("Listen together"),
}

/** A playlist pinned to Home's greeting tiles. */
@Serializable
data class PinnedPlaylist(val id: String, val title: String, val thumbnail: String? = null)

enum class SectionStyle(val label: String) { CAROUSEL("Carousel"), GRID("Grid"), LIST("List"), HERO("Spotlight") }

// ---------------------------------------------------------------- Look & layout

/** The app's typeface. Device fonts are used where the phone has them, with a fallback otherwise. */
enum class FontChoice(val label: String) { SYSTEM("Default"), GOOGLE_SANS("Google Sans"), ROUNDED("Rounded"), CONDENSED("Condensed"), SERIF("Serif"), MONO("Mono") }

/** How round cards, tiles and artwork are. */
enum class Corners(val label: String, val scale: Float) { SHARP("Sharp", 0.2f), SUBTLE("Subtle", 0.6f), ROUND("Round", 1f), EXTRA("Extra", 1.5f) }

/** How much room rows and sections get. */
enum class UiDensity(val label: String, val scale: Float) { COMPACT("Compact", 0.7f), COMFORTABLE("Comfortable", 1f), ROOMY("Roomy", 1.3f) }

/** The big page titles (Library, Replay, Settings…). */
enum class TitleSize(val label: String) { LARGE("Large"), MEDIUM("Medium"), SMALL("Small") }

/** Width of album / playlist cards in carousels. */
enum class CardSize(val label: String, val widthDp: Int) { SMALL("Small", 124), MEDIUM("Medium", 152), LARGE("Large", 184) }

enum class NavBarStyle(val label: String) { FLOATING("Floating"), DOCKED("Docked"), MINIMAL("Minimal") }
enum class NavLabels(val label: String) { ALWAYS("Always"), SELECTED("Selected"), NEVER("Never") }

/** Tabs the bottom bar can hold. */
enum class NavTab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library"), REPLAY("Replay"), SETTINGS("Settings") }

enum class MiniPlayerStyle(val label: String) { CARD("Card"), SLIM("Slim"), PILL("Pill") }
enum class MiniProgress(val label: String) { LINE("Line"), FILL("Fill"), NONE("None") }

/** What's behind the full player. */
enum class PlayerBackground(val label: String) { MESH("Colour mesh"), BLUR("Blurred cover"), THEME("Theme"), BLACK("Black") }
enum class TransportStyle(val label: String) { GLYPHS("Classic"), BUTTON("Play button"), OUTLINE("Rings") }
enum class ScrubberStyle(val label: String) { HAIRLINE("Hairline"), BOLD("Bold"), THUMB("Thumb") }
enum class LyricsAlign(val label: String) { START("Left"), CENTER("Centre") }
enum class LibraryView(val label: String) { GRID("Grid"), LIST("List") }

@Serializable
data class HomeSectionConfig(
    val key: String,
    val title: String,
    val visible: Boolean = true,
    val style: SectionStyle = SectionStyle.CAROUSEL,
)

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    /** Gains in dB for the 10 ISO bands (31Hz .. 16kHz). */
    val bands: List<Float> = List(10) { 0f },
    val bassBoost: Int = 0,      // 0..1000
    val virtualizer: Int = 0,    // 0..1000
    val loudnessGainMb: Int = 0, // millibels
    val preampDb: Float = 0f,
)

object HomeSections {
    const val GREETING = "local:greeting"
    const val QUICK_PICKS = "local:quick_picks"
    const val RECENT = "local:recent"
    const val LIKED = "local:liked"
    const val DOWNLOADS = "local:downloads"
    const val REPLAY = "local:replay"
    const val GENRES = "local:genres"
    const val MOODS = "yt:moods"
    const val YT_PREFIX = "yt:shelf:"
    /** GREETING's key predates the rename: the section is only the shortcut tiles. */
    const val TILES_TITLE = "Shortcut tiles"

    val defaults = listOf(
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig(RECENT, "Recently played"),
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GENRES, "Your top genres", style = SectionStyle.GRID),
        HomeSectionConfig(MOODS, "Moods & genres", style = SectionStyle.GRID),
        HomeSectionConfig(LIKED, "Liked songs"),
        HomeSectionConfig(DOWNLOADS, "Downloads", visible = false),
    )

    /** What a fresh install's Home shows (and what Reset goes back to). */
    val initial = listOf(
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig("${YT_PREFIX}mixed for you", "Mixed for you"),
    )
}

/** The order lyric sources are tried in on a fresh install. */
val defaultLyricsOrder = listOf(
    LyricsSource.KUGOU, LyricsSource.QQ, LyricsSource.APPLE, LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.YTMUSIC,
    LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.UNISON, LyricsSource.GENIUS, LyricsSource.LYRICSOVH,
)

data class AppSettings(
    val onboarded: Boolean = false,
    // Account
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val accountName: String = "",
    val accountEmail: String = "",
    val accountAvatar: String = "",
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val colorSource: ColorSource = ColorSource.CUSTOM,
    val accent: Int = 0xFF3A86FF.toInt(),
    val liquidGlass: Boolean = false,
    val glassKind: GlassKind = GlassKind.REGULAR,
    /** Blurred, non-refractive glass. Mutually exclusive with Liquid Glass. */
    val frostedGlass: Boolean = false,
    /** Taps and swipes on player controls give a little buzz. */
    val haptics: Boolean = true,
    /** Artwork runs edge to edge at the top of the player, dissolving into the backdrop. */
    val fullBleedArtwork: Boolean = true,
    /** Animated cover art (motion artwork / video covers) over the sleeve. */
    val canvasEnabled: Boolean = true,
    val canvasOnCellular: Boolean = true,
    val hideVolumeBar: Boolean = true,
    /** "Playing from …" above the artwork in the player. */
    val showPlayingFrom: Boolean = true,
    val lyricsBlur: Boolean = true,
    /** Each screen's background (screen name -> "STYLE" or "PHOTO|path"); missing means the default. */
    val backdrops: Map<String, String> = emptyMap(),
    /** How much the theme colour washes over custom backgrounds (0 = none). */
    val backdropDim: Float = 0.35f,
    val backdropMotion: Boolean = true,
    // Lyrics
    val lyricsOnPlayer: Boolean = true,
    val lyricsOrder: List<LyricsSource> = defaultLyricsOrder,
    val preferSynced: Boolean = true,
    /** Pass over sources whose lyrics star out swear words when another has them in full. */
    val skipCensored: Boolean = true,
    val lyricsScale: Float = 1f,
    /** Lyrics on Android Auto's player (the car's Lyrics button flips this too). */
    val carLyrics: Boolean = true,
    val carLyricsStyle: CarLyricsStyle = CarLyricsStyle.TEXT,
    /** Also on the phone's own media controls (notification, lock screen) when not in the car. */
    val carLyricsOnPhone: Boolean = false,
    // Playback
    val showNerdStats: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val cellularQuality: AudioQuality = AudioQuality.HIGH,
    val autoplay: Boolean = true,
    /** Lossless playback: always the best stream, hi-res output, and the lossless add-on when it's on. */
    val lossless: Boolean = true,
    /** The lossless add-on: play matching FLAC/WAV/AIFF files on the phone instead of the stream. */
    val losslessLocal: Boolean = false,
    val normalize: Boolean = true,
    val skipSilence: Boolean = false,
    val videoMaxHeight: Int = 1080,
    // Storage & downloads
    val cacheSizeMb: Int = 4096,
    val downloadWifiOnly: Boolean = true,
    val downloadLikes: Boolean = true,
    val smartDownloads: Boolean = true,
    /** Space smart downloads may fill, in GB. */
    val smartDownloadGb: Float = 1f,
    // Audio effects
    val eq: EqSettings = EqSettings(bassBoost = 32, virtualizer = 6, loudnessGainMb = 39),
    /** Prism Spatial, the software spatializer. */
    val spatial: Boolean = false,
    val spatialAmount: Float = 0.75f,
    // Home
    val homeLayout: List<HomeSectionConfig> = HomeSections.initial,
    val autoAddSections: Boolean = true,
    /** Replay shows listening time as hours and minutes (20h 4m) instead of minutes (1,204 min). */
    val replayHours: Boolean = true,
    /** Home's headline; blank means good morning / afternoon / evening. */
    val greetingText: String = "",
    val greetingShowName: Boolean = true,
    val homeShortcuts: List<HomeShortcut> = listOf(HomeShortcut.LIKED, HomeShortcut.DOWNLOADS, HomeShortcut.REPLAY),
    /** Recently played albums added after the shortcuts. */
    val homeRecentTiles: Int = 3,
    /** Playlists shown as tiles after the shortcuts. */
    val homePlaylists: List<PinnedPlaylist> = emptyList(),
    val homeCustomizeButton: Boolean = true,
    // Interface
    val fontChoice: FontChoice = FontChoice.CONDENSED,
    val textScale: Float = 1f,
    val corners: Corners = Corners.ROUND,
    val uiDensity: UiDensity = UiDensity.COMFORTABLE,
    val titleSize: TitleSize = TitleSize.LARGE,
    val cardSize: CardSize = CardSize.MEDIUM,
    val reduceMotion: Boolean = false,
    // Navigation
    val navStyle: NavBarStyle = NavBarStyle.DOCKED,
    val navLabels: NavLabels = NavLabels.ALWAYS,
    val navHideOnScroll: Boolean = true,
    val navTabs: List<NavTab> = listOf(NavTab.HOME, NavTab.LIBRARY, NavTab.SEARCH, NavTab.REPLAY),
    val startTab: NavTab = NavTab.HOME,
    val miniPlayerStyle: MiniPlayerStyle = MiniPlayerStyle.CARD,
    val miniSkipButtons: Boolean = true,
    val miniProgress: MiniProgress = MiniProgress.LINE,
    // Player look
    val playerBackground: PlayerBackground = PlayerBackground.MESH,
    val playerArtCorners: Int = 8,
    val transportStyle: TransportStyle = TransportStyle.BUTTON,
    val scrubberStyle: ScrubberStyle = ScrubberStyle.HAIRLINE,
    val artShrinkOnPause: Boolean = true,
    val showOutputDevice: Boolean = true,
    val lyricsAlign: LyricsAlign = LyricsAlign.START,
    // Library & Replay
    val libraryView: LibraryView = LibraryView.LIST,
    val libraryStartTab: String = "PLAYLISTS",
    /** Playlist pages open with their picture edge to edge, like an artist page. */
    val playlistHeroCover: Boolean = true,
    /** Artist, album and playlist pages sit on frosted glass made from their picture (Apple Music's look). */
    val frostedPages: Boolean = true,
    val replayThemeColors: Boolean = false,
) {
    val isLoggedIn: Boolean get() = cookie.contains("SAPISID")
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val cookie = stringPreferencesKey("cookie")
        val visitorData = stringPreferencesKey("visitor_data")
        val dataSyncId = stringPreferencesKey("data_sync_id")
        val accountName = stringPreferencesKey("account_name")
        val accountEmail = stringPreferencesKey("account_email")
        val accountAvatar = stringPreferencesKey("account_avatar")
        val themeMode = stringPreferencesKey("theme_mode")
        val pureBlack = booleanPreferencesKey("pure_black")
        val colorSource = stringPreferencesKey("color_source")
        val accent = intPreferencesKey("accent")
        val liquidGlass = booleanPreferencesKey("liquid_glass")
        val glassKind = stringPreferencesKey("glass_kind")
        val frostedGlass = booleanPreferencesKey("frosted_glass")
        val haptics = booleanPreferencesKey("haptics")
        val fullBleedArtwork = booleanPreferencesKey("full_bleed_artwork")
        val canvasEnabled = booleanPreferencesKey("canvas_enabled")
        val canvasOnCellular = booleanPreferencesKey("canvas_on_cellular")
        val hideVolumeBar = booleanPreferencesKey("hide_volume_bar")
        val showPlayingFrom = booleanPreferencesKey("show_playing_from")
        val spatial = booleanPreferencesKey("soft_spatial")
        val spatialAmount = floatPreferencesKey("soft_spatial_amount")
        val lyricsBlur = booleanPreferencesKey("lyrics_blur")
        val backdrops = stringPreferencesKey("backdrops")
        val backdropDim = floatPreferencesKey("backdrop_dim")
        val backdropMotion = booleanPreferencesKey("backdrop_motion")
        val lyricsOnPlayer = booleanPreferencesKey("lyrics_on_player")
        val lyricsOrder = stringPreferencesKey("lyrics_order")
        val preferSynced = booleanPreferencesKey("prefer_synced")
        val skipCensored = booleanPreferencesKey("skip_censored")
        val lyricsScale = floatPreferencesKey("lyrics_scale")
        val carLyrics = booleanPreferencesKey("car_lyrics")
        val carLyricsStyle = stringPreferencesKey("car_lyrics_style")
        val carLyricsOnPhone = booleanPreferencesKey("car_lyrics_on_phone")
        val showNerdStats = booleanPreferencesKey("nerd_stats")
        val audioQuality = stringPreferencesKey("audio_quality")
        val cellularQuality = stringPreferencesKey("cellular_quality")
        val autoplay = booleanPreferencesKey("autoplay")
        val lossless = booleanPreferencesKey("lossless")
        val losslessLocal = booleanPreferencesKey("lossless_local")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val videoMaxHeight = intPreferencesKey("video_max_height")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val downloadWifiOnly = booleanPreferencesKey("download_wifi_only")
        val downloadLikes = booleanPreferencesKey("download_likes")
        val smartDownloads = booleanPreferencesKey("smart_downloads")
        val smartDownloadGb = floatPreferencesKey("smart_download_gb")
        val eq = stringPreferencesKey("eq")
        val homeLayout = stringPreferencesKey("home_layout")
        val autoAddSections = booleanPreferencesKey("auto_add_sections")
        val replayHours = booleanPreferencesKey("replay_hours")
        val greetingText = stringPreferencesKey("greeting_text")
        val greetingShowName = booleanPreferencesKey("greeting_show_name")
        val homeShortcuts = stringPreferencesKey("home_shortcuts")
        val homeRecentTiles = intPreferencesKey("home_recent_tiles")
        val homePlaylists = stringPreferencesKey("home_playlists")
        val homeCustomizeButton = booleanPreferencesKey("home_customize_button")
        val fontChoice = stringPreferencesKey("font_choice")
        val textScale = floatPreferencesKey("text_scale")
        val corners = stringPreferencesKey("corners")
        val uiDensity = stringPreferencesKey("ui_density")
        val titleSize = stringPreferencesKey("title_size")
        val cardSize = stringPreferencesKey("card_size")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val navStyle = stringPreferencesKey("nav_style")
        val navLabels = stringPreferencesKey("nav_labels")
        val navHideOnScroll = booleanPreferencesKey("nav_hide_on_scroll")
        val navTabs = stringPreferencesKey("nav_tabs")
        val startTab = stringPreferencesKey("start_tab")
        val miniPlayerStyle = stringPreferencesKey("mini_player_style")
        val miniSkipButtons = booleanPreferencesKey("mini_skip_buttons")
        val miniProgress = stringPreferencesKey("mini_progress")
        val playerBackground = stringPreferencesKey("player_background")
        val playerArtCorners = intPreferencesKey("player_art_corners")
        val transportStyle = stringPreferencesKey("transport_style")
        val scrubberStyle = stringPreferencesKey("scrubber_style")
        val artShrinkOnPause = booleanPreferencesKey("art_shrink_on_pause")
        val showOutputDevice = booleanPreferencesKey("show_output_device")
        val lyricsAlign = stringPreferencesKey("lyrics_align")
        val libraryView = stringPreferencesKey("library_view")
        val libraryStartTab = stringPreferencesKey("library_start_tab")
        val playlistHeroCover = booleanPreferencesKey("playlist_hero_cover")
        val frostedPages = booleanPreferencesKey("frosted_pages")
        val replayThemeColors = booleanPreferencesKey("replay_theme_colors")

        /** Everything the "Reset look" button puts back. */
        val look: List<Preferences.Key<*>> by lazy {
            listOf(
                fontChoice, textScale, corners, uiDensity, titleSize, cardSize, reduceMotion, navStyle, navLabels, navHideOnScroll,
                navTabs, startTab, miniPlayerStyle, miniSkipButtons, miniProgress, playerBackground, playerArtCorners, transportStyle,
                scrubberStyle, artShrinkOnPause, showOutputDevice, lyricsAlign, libraryView, playlistHeroCover, frostedPages, replayThemeColors,
            )
        }
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboarded = p[K.onboarded] ?: d.onboarded,
            cookie = p[K.cookie] ?: "",
            visitorData = p[K.visitorData] ?: "",
            dataSyncId = p[K.dataSyncId] ?: "",
            accountName = p[K.accountName] ?: "",
            accountEmail = p[K.accountEmail] ?: "",
            accountAvatar = p[K.accountAvatar] ?: "",
            themeMode = p.enum(K.themeMode, d.themeMode),
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            colorSource = p.enum(K.colorSource, d.colorSource),
            accent = p[K.accent] ?: d.accent,
            liquidGlass = p[K.liquidGlass] ?: d.liquidGlass,
            glassKind = p.enum(K.glassKind, d.glassKind),
            frostedGlass = p[K.frostedGlass] ?: d.frostedGlass,
            haptics = p[K.haptics] ?: d.haptics,
            fullBleedArtwork = p[K.fullBleedArtwork] ?: d.fullBleedArtwork,
            canvasEnabled = p[K.canvasEnabled] ?: d.canvasEnabled,
            canvasOnCellular = p[K.canvasOnCellular] ?: d.canvasOnCellular,
            hideVolumeBar = p[K.hideVolumeBar] ?: d.hideVolumeBar,
            showPlayingFrom = p[K.showPlayingFrom] ?: d.showPlayingFrom,
            spatial = p[K.spatial] ?: d.spatial,
            spatialAmount = p[K.spatialAmount] ?: d.spatialAmount,
            lyricsBlur = p[K.lyricsBlur] ?: d.lyricsBlur,
            backdrops = p[K.backdrops]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: d.backdrops,
            backdropDim = p[K.backdropDim] ?: d.backdropDim,
            backdropMotion = p[K.backdropMotion] ?: d.backdropMotion,
            lyricsOnPlayer = p[K.lyricsOnPlayer] ?: d.lyricsOnPlayer,
            lyricsOrder = p[K.lyricsOrder]?.split(",")?.mapNotNull { n ->
                LyricsSource.entries.firstOrNull { it.name == n }
            }?.let { list ->
                // Sources added in an update slot in at their default position.
                val merged = list.toMutableList()
                LyricsSource.entries.forEachIndexed { i, src -> if (src !in merged) merged.add(i.coerceAtMost(merged.size), src) }
                merged
            } ?: d.lyricsOrder,
            preferSynced = p[K.preferSynced] ?: d.preferSynced,
            skipCensored = p[K.skipCensored] ?: d.skipCensored,
            lyricsScale = p[K.lyricsScale] ?: d.lyricsScale,
            carLyrics = p[K.carLyrics] ?: d.carLyrics,
            carLyricsStyle = p.enum(K.carLyricsStyle, d.carLyricsStyle),
            carLyricsOnPhone = p[K.carLyricsOnPhone] ?: d.carLyricsOnPhone,
            showNerdStats = p[K.showNerdStats] ?: d.showNerdStats,
            audioQuality = p.enum(K.audioQuality, d.audioQuality),
            cellularQuality = p.enum(K.cellularQuality, d.cellularQuality),
            autoplay = p[K.autoplay] ?: d.autoplay,
            lossless = p[K.lossless] ?: d.lossless,
            losslessLocal = p[K.losslessLocal] ?: d.losslessLocal,
            normalize = p[K.normalize] ?: d.normalize,
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            videoMaxHeight = p[K.videoMaxHeight] ?: d.videoMaxHeight,
            cacheSizeMb = p[K.cacheSizeMb] ?: d.cacheSizeMb,
            downloadWifiOnly = p[K.downloadWifiOnly] ?: d.downloadWifiOnly,
            downloadLikes = p[K.downloadLikes] ?: d.downloadLikes,
            smartDownloads = p[K.smartDownloads] ?: d.smartDownloads,
            smartDownloadGb = p[K.smartDownloadGb] ?: d.smartDownloadGb,
            eq = p[K.eq]?.let { runCatching { json.decodeFromString<EqSettings>(it) }.getOrNull() } ?: d.eq,
            homeLayout = p[K.homeLayout]?.let {
                runCatching { json.decodeFromString<List<HomeSectionConfig>>(it) }.getOrNull()
                    // The tiles section was once called "Greeting & shortcuts"; the greeting is the top bar's.
                    ?.map { s -> if (s.key == HomeSections.GREETING) s.copy(title = HomeSections.TILES_TITLE) else s }
            } ?: d.homeLayout,
            autoAddSections = p[K.autoAddSections] ?: d.autoAddSections,
            replayHours = p[K.replayHours] ?: d.replayHours,
            greetingText = p[K.greetingText] ?: d.greetingText,
            greetingShowName = p[K.greetingShowName] ?: d.greetingShowName,
            homeShortcuts = p[K.homeShortcuts]?.let { v ->
                v.split(",").mapNotNull { n -> HomeShortcut.entries.firstOrNull { it.name == n } }
            } ?: d.homeShortcuts,
            homeRecentTiles = p[K.homeRecentTiles] ?: d.homeRecentTiles,
            homePlaylists = p[K.homePlaylists]?.let { runCatching { json.decodeFromString<List<PinnedPlaylist>>(it) }.getOrNull() } ?: d.homePlaylists,
            homeCustomizeButton = p[K.homeCustomizeButton] ?: d.homeCustomizeButton,
            fontChoice = p.enum(K.fontChoice, d.fontChoice),
            textScale = p[K.textScale] ?: d.textScale,
            corners = p.enum(K.corners, d.corners),
            uiDensity = p.enum(K.uiDensity, d.uiDensity),
            titleSize = p.enum(K.titleSize, d.titleSize),
            cardSize = p.enum(K.cardSize, d.cardSize),
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            navStyle = p.enum(K.navStyle, d.navStyle),
            navLabels = p.enum(K.navLabels, d.navLabels),
            navHideOnScroll = p[K.navHideOnScroll] ?: d.navHideOnScroll,
            navTabs = p[K.navTabs]?.split(",")?.mapNotNull { n -> NavTab.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() } ?: d.navTabs,
            startTab = p.enum(K.startTab, d.startTab),
            miniPlayerStyle = p.enum(K.miniPlayerStyle, d.miniPlayerStyle),
            miniSkipButtons = p[K.miniSkipButtons] ?: d.miniSkipButtons,
            miniProgress = p.enum(K.miniProgress, d.miniProgress),
            playerBackground = p.enum(K.playerBackground, d.playerBackground),
            playerArtCorners = p[K.playerArtCorners] ?: d.playerArtCorners,
            transportStyle = p.enum(K.transportStyle, d.transportStyle),
            scrubberStyle = p.enum(K.scrubberStyle, d.scrubberStyle),
            artShrinkOnPause = p[K.artShrinkOnPause] ?: d.artShrinkOnPause,
            showOutputDevice = p[K.showOutputDevice] ?: d.showOutputDevice,
            lyricsAlign = p.enum(K.lyricsAlign, d.lyricsAlign),
            libraryView = p.enum(K.libraryView, d.libraryView),
            libraryStartTab = p[K.libraryStartTab] ?: d.libraryStartTab,
            playlistHeroCover = p[K.playlistHeroCover] ?: d.playlistHeroCover,
            frostedPages = p[K.frostedPages] ?: d.frostedPages,
            replayThemeColors = p[K.replayThemeColors] ?: d.replayThemeColors,
        )
    }

    val flow: StateFlow<AppSettings> = context.dataStore.data.map(::read).stateIn(
        scope, SharingStarted.Eagerly, runBlocking { read(context.dataStore.data.first()) }
    )

    val current: AppSettings get() = flow.value

    private val writeScope = scope

    private fun edit(block: (MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit { block(it) } }
    }

    suspend fun editNow(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }

    suspend fun saveAccount(cookie: String, visitorData: String, dataSyncId: String) = editNow {
        it[K.cookie] = cookie
        it[K.visitorData] = visitorData
        it[K.dataSyncId] = dataSyncId
    }

    fun saveAccountInfo(name: String, email: String?, avatar: String?) = edit {
        it[K.accountName] = name
        it[K.accountEmail] = email ?: ""
        it[K.accountAvatar] = avatar ?: ""
    }

    fun setVisitorData(v: String) = edit { it[K.visitorData] = v }

    fun logout() = edit {
        it.remove(K.cookie); it.remove(K.dataSyncId)
        it.remove(K.accountName); it.remove(K.accountEmail); it.remove(K.accountAvatar)
    }

    fun setThemeMode(v: ThemeMode) = edit { it[K.themeMode] = v.name }
    fun setPureBlack(v: Boolean) = edit { it[K.pureBlack] = v }
    fun setColorSource(v: ColorSource) = edit { it[K.colorSource] = v.name }
    fun setAccent(v: Int) = edit { it[K.accent] = v }
    fun setLiquidGlass(v: Boolean) = edit { it[K.liquidGlass] = v; if (v) it[K.frostedGlass] = false }
    fun setFrostedGlass(v: Boolean) = edit { it[K.frostedGlass] = v; if (v) it[K.liquidGlass] = false }
    fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    fun setGlassKind(v: GlassKind) = edit { it[K.glassKind] = v.name }
    fun setFullBleedArtwork(v: Boolean) = edit { it[K.fullBleedArtwork] = v }
    fun setCanvasEnabled(v: Boolean) = edit { it[K.canvasEnabled] = v }
    fun setCanvasOnCellular(v: Boolean) = edit { it[K.canvasOnCellular] = v }
    fun setHideVolumeBar(v: Boolean) = edit { it[K.hideVolumeBar] = v }
    fun setShowPlayingFrom(v: Boolean) = edit { it[K.showPlayingFrom] = v }
    fun setSpatial(v: Boolean) = edit { it[K.spatial] = v }
    fun setSpatialAmount(v: Float) = edit { it[K.spatialAmount] = v }
    fun setLyricsBlur(v: Boolean) = edit { it[K.lyricsBlur] = v }
    fun setBackdrops(v: Map<String, String>) = edit { it[K.backdrops] = json.encodeToString<Map<String, String>>(v) }
    fun setBackdropDim(v: Float) = edit { it[K.backdropDim] = v }
    fun setBackdropMotion(v: Boolean) = edit { it[K.backdropMotion] = v }
    fun setLyricsOnPlayer(v: Boolean) = edit { it[K.lyricsOnPlayer] = v }
    fun setLyricsOrder(v: List<LyricsSource>) = edit { it[K.lyricsOrder] = v.joinToString(",") { s -> s.name } }
    fun setPreferSynced(v: Boolean) = edit { it[K.preferSynced] = v }
    fun setSkipCensored(v: Boolean) = edit { it[K.skipCensored] = v }
    fun setLyricsScale(v: Float) = edit { it[K.lyricsScale] = v }
    fun setCarLyrics(v: Boolean) = edit { it[K.carLyrics] = v }
    fun setCarLyricsStyle(v: CarLyricsStyle) = edit { it[K.carLyricsStyle] = v.name }
    fun setCarLyricsOnPhone(v: Boolean) = edit { it[K.carLyricsOnPhone] = v }
    fun setShowNerdStats(v: Boolean) = edit { it[K.showNerdStats] = v }
    fun setAudioQuality(v: AudioQuality) = edit { it[K.audioQuality] = v.name }
    fun setCellularQuality(v: AudioQuality) = edit { it[K.cellularQuality] = v.name }
    fun setAutoplay(v: Boolean) = edit { it[K.autoplay] = v }
    fun setLossless(v: Boolean) = edit { it[K.lossless] = v }
    fun setLosslessLocal(v: Boolean) = edit { it[K.losslessLocal] = v }
    fun setNormalize(v: Boolean) = edit { it[K.normalize] = v }
    fun setSkipSilence(v: Boolean) = edit { it[K.skipSilence] = v }
    fun setVideoMaxHeight(v: Int) = edit { it[K.videoMaxHeight] = v }
    fun setCacheSizeMb(v: Int) = edit { it[K.cacheSizeMb] = v }
    fun setDownloadWifiOnly(v: Boolean) = edit { it[K.downloadWifiOnly] = v }
    fun setDownloadLikes(v: Boolean) = edit { it[K.downloadLikes] = v }
    fun setSmartDownloads(v: Boolean) = edit { it[K.smartDownloads] = v }
    fun setSmartDownloadGb(v: Float) = edit { it[K.smartDownloadGb] = v }
    fun setEq(v: EqSettings) = edit { it[K.eq] = json.encodeToString(EqSettings.serializer(), v) }
    fun setHomeLayout(v: List<HomeSectionConfig>) = edit {
        it[K.homeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), v)
    }
    fun setAutoAddSections(v: Boolean) = edit { it[K.autoAddSections] = v }
    fun setReplayHours(v: Boolean) = edit { it[K.replayHours] = v }
    fun setGreetingText(v: String) = edit { it[K.greetingText] = v }
    fun setGreetingShowName(v: Boolean) = edit { it[K.greetingShowName] = v }
    fun setHomeShortcuts(v: List<HomeShortcut>) = edit { it[K.homeShortcuts] = v.joinToString(",") { s -> s.name } }
    fun setHomeRecentTiles(v: Int) = edit { it[K.homeRecentTiles] = v }
    fun setHomePlaylists(v: List<PinnedPlaylist>) = edit { it[K.homePlaylists] = json.encodeToString<List<PinnedPlaylist>>(v) }
    fun setHomeCustomizeButton(v: Boolean) = edit { it[K.homeCustomizeButton] = v }
    fun setFontChoice(v: FontChoice) = edit { it[K.fontChoice] = v.name }
    fun setTextScale(v: Float) = edit { it[K.textScale] = v }
    fun setCorners(v: Corners) = edit { it[K.corners] = v.name }
    fun setUiDensity(v: UiDensity) = edit { it[K.uiDensity] = v.name }
    fun setTitleSize(v: TitleSize) = edit { it[K.titleSize] = v.name }
    fun setCardSize(v: CardSize) = edit { it[K.cardSize] = v.name }
    fun setReduceMotion(v: Boolean) = edit { it[K.reduceMotion] = v }
    fun setNavStyle(v: NavBarStyle) = edit { it[K.navStyle] = v.name }
    fun setNavLabels(v: NavLabels) = edit { it[K.navLabels] = v.name }
    fun setNavHideOnScroll(v: Boolean) = edit { it[K.navHideOnScroll] = v }
    fun setNavTabs(v: List<NavTab>) = edit { it[K.navTabs] = v.joinToString(",") { t -> t.name } }
    fun setStartTab(v: NavTab) = edit { it[K.startTab] = v.name }
    fun setMiniPlayerStyle(v: MiniPlayerStyle) = edit { it[K.miniPlayerStyle] = v.name }
    fun setMiniSkipButtons(v: Boolean) = edit { it[K.miniSkipButtons] = v }
    fun setMiniProgress(v: MiniProgress) = edit { it[K.miniProgress] = v.name }
    fun setPlayerBackground(v: PlayerBackground) = edit { it[K.playerBackground] = v.name }
    fun setPlayerArtCorners(v: Int) = edit { it[K.playerArtCorners] = v }
    fun setTransportStyle(v: TransportStyle) = edit { it[K.transportStyle] = v.name }
    fun setScrubberStyle(v: ScrubberStyle) = edit { it[K.scrubberStyle] = v.name }
    fun setArtShrinkOnPause(v: Boolean) = edit { it[K.artShrinkOnPause] = v }
    fun setShowOutputDevice(v: Boolean) = edit { it[K.showOutputDevice] = v }
    fun setLyricsAlign(v: LyricsAlign) = edit { it[K.lyricsAlign] = v.name }
    fun setLibraryView(v: LibraryView) = edit { it[K.libraryView] = v.name }
    fun setLibraryStartTab(v: String) = edit { it[K.libraryStartTab] = v }
    fun setPlaylistHeroCover(v: Boolean) = edit { it[K.playlistHeroCover] = v }
    fun setFrostedPages(v: Boolean) = edit { it[K.frostedPages] = v }
    fun setReplayThemeColors(v: Boolean) = edit { it[K.replayThemeColors] = v }

    /** Puts every look & layout option back to how Prism ships (colours and backgrounds are left alone). */
    @Suppress("UNCHECKED_CAST")
    fun resetLook() = edit { p -> K.look.forEach { p.remove(it as Preferences.Key<Any>) } }
}
