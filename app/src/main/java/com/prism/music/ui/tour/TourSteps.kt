package com.prism.music.ui.tour

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import com.prism.music.BuildConfig
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.PrismIcons

/**
 * One page of Prism's tour.
 *
 * [since] is the versionCode of the release the feature (or its current look) came in. A new install sees every step
 * (the full tour); someone updating sees only the steps newer than the last tour they saw ([Tour.seenVersion]). To
 * introduce a later release's features: add steps with that release's versionCode, and keep the older steps true to
 * the app (new installs still see all of them).
 */
data class TourStep(
    val since: Int,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val points: List<String> = emptyList(),
    /** Small icons beside the big one, for what the step covers. */
    val extras: List<ImageVector> = emptyList(),
    /** "Show me": where it opens (the tour closes; it can be taken again from Settings → About). */
    val showMe: String? = null,
    val kind: Kind = Kind.PLAIN,
) {
    enum class Kind { PLAIN, NOTIFICATIONS }
}

object Tour {
    /** Prism 1.7 (build 39): the redesign and its features. Everyone, new or updating, sees all of these once. */
    private const val V1_7 = 39
    /** Prism 1.7.2 (build 41): color, and the smaller things that came with it. */
    private const val V1_7_2 = 41
    private const val V1_7_3 = 42
    /** Prism 1.7.4 (build 43): the player, your way, and a faster Prism. */
    private const val V1_7_4 = 43
    /** Prism 1.7.5 (build 44): lyrics, your way. */
    private const val V1_7_5 = 44
    /** Prism 1.7.6 (build 45): Home, Library and Search, your way. */
    private const val V1_7_6 = 45

    /** Every step, in the order they're shown. */
    val steps: List<TourStep> = listOf(
        TourStep(
            V1_7, PrismIcons.Home, "Home",
            "Your starting point, made from what you play.",
            listOf(
                "The Replay card at the top: your year so far (pick its look in Settings → Customize Prism → Home & Library)",
                "Quick picks: songs around the artists you play, swipe across for more",
                "Made for you: Daily Mixes, your time-of-day mix and Release Radar",
                "The grid button at the top arranges Home's sections",
                "\"Continue …\" picks up the album you were in",
            ),
            listOf(PrismIcons.StarFilled, PrismIcons.Customize),
        ),
        TourStep(
            V1_7, PrismIcons.PlayCircle, "The player",
            "Tap the mini player (or swipe it up) to open it; swipe down to close.",
            listOf(
                "Best part jumps to where listeners replay most; hold it to pick your own",
                "Double-tap the cover's sides to skip back or ahead",
                "Hold previous or next to seek",
                "⋯ has the sleep timer, stats, downloads and Player settings",
            ),
            listOf(PrismIcons.Flame, PrismIcons.Moon),
        ),
        TourStep(
            V1_7, PrismIcons.Lyrics, "Lyrics",
            "Open them from the button under the controls. Many songs light up word by word.",
            listOf(
                "Tap a line to jump there",
                "Their source is at the bottom: tap it to pick another, or fix the timing from ⋯",
                "Translate them, or read other scripts in Latin letters (Settings → Lyrics & Sing)",
            ),
            listOf(PrismIcons.Translate, PrismIcons.Romanize),
        ),
        TourStep(
            V1_7, PrismIcons.Mic, "Sing",
            "Karaoke with the song's own music: Prism takes the voice out, on your phone.",
            listOf(
                "With the lyrics open, tap Sing at the bottom",
                "Leave a little of the voice in to sing along, or none",
                "Move the key up or down to suit your voice",
            ),
        ),
        TourStep(
            V1_7, PrismIcons.Equalizer, "DJ mode and Crossfade",
            "Let Prism mix your music: songs blend into each other, in key and on the beat.",
            listOf(
                "In the queue, tap DJ, then pick Steady, Build up or Wind down",
                "Long-press an album or playlist → Play as a DJ set",
                "Crossfade (Settings → Playback & sound) blends any two songs",
            ),
            listOf(PrismIcons.Queue, PrismIcons.Shuffle),
        ),
        TourStep(
            V1_7, PrismIcons.StarFilled, "Made for you",
            "Mixes made on your phone from what you play. Nothing about your listening leaves it.",
            listOf(
                "Daily Mixes group the artists you play by what goes together",
                "Release Radar finds new music from the artists you follow",
                "Made for you also has mixes from your own music, and On repeat: they come from your plays alone and work offline",
                "When the queue ends, Prism's radio picks from your music (Settings → Playback & sound → Autoplay from)",
                "Tap shuffle twice on a playlist for Smart Shuffle: new songs slip in",
                "\"Not for me\" and \"Leave out of my taste\" keep things out",
            ),
            listOf(PrismIcons.SmartShuffle, PrismIcons.Block),
        ),
        TourStep(
            V1_7, PrismIcons.Search, "Search and Recognize",
            "Find anything in Prism Music, or name the song playing around you.",
            listOf(
                "Filters for songs, videos, albums, artists, podcasts and live",
                "\"On this phone\" searches your own music files",
                "The waveform button in the search box listens for a song",
                "Add the Recognize widget or Quick Settings tile to do it from anywhere",
                "Podcasts (the button at the top of Home) has its own search for shows and episodes",
            ),
            listOf(PrismIcons.Recognize),
        ),
        TourStep(
            V1_7, PrismIcons.LibraryFilled, "Your library",
            "Everything you keep, with or without a YouTube Music account.",
            listOf(
                "Liked songs, Downloads and the music files on your phone",
                "Make playlists and save albums: without an account they're kept on this phone",
                "A Prism Account (Settings, at the top) is your name and picture (a photo or one of Prism's), all on this phone; it can keep a copy of your YouTube Music library",
                "Blend makes a playlist of two tastes with a friend: scan each other's code and both phones get it",
                "Listen together plays in step with friends on the same Wi-Fi",
            ),
            listOf(PrismIcons.Blend, PrismIcons.Groups),
        ),
        TourStep(
            V1_7, PrismIcons.Stats, "Replay and Rewind",
            "Your listening, recapped on your phone.",
            listOf(
                "Replay: your top songs, artists and genres for any stretch of time",
                "Rewind: your year in music, as a story, each December",
                "Share any slide as a picture",
            ),
            listOf(PrismIcons.History),
        ),
        TourStep(
            V1_7, PrismIcons.Cast, "Prism Connect",
            "Music beyond your phone.",
            listOf(
                "Play on speakers, TVs and Chromecasts: tap the device line under the controls",
                "Control Prism from a browser on your computer",
                "Hand what's playing to another phone or tablet with Prism",
            ),
            listOf(PrismIcons.Tv, PrismIcons.Handoff),
            showMe = Routes.settings("connect"),
        ),
        TourStep(
            V1_7, PrismIcons.Palette, "Make it yours",
            "Prism takes its color from what's playing. Change as much of it as you like.",
            listOf(
                "Appearance: the Spectrum's colors (or plain white), your accent, fonts, icons and backgrounds",
                "Layout: the tab bar, your tabs and the mini player",
                "Player: its background, the cover and what shows on it",
                "Search Settings from the box at the top of Settings",
            ),
            listOf(PrismIcons.Customize, PrismIcons.Settings),
            showMe = Routes.settings("appearance"),
        ),
        TourStep(
            V1_7_2, PrismIcons.Palette, "Color, your way",
            "Settings → Customize Prism → Color: every choice shows what it looks like before you pick it.",
            listOf(
                "The light: 12 presets, White (Black in light mode), or your own colors",
                "Your accent: any color, from a wheel, a code or the eyedropper",
                "The page background: plain, tinted with your accent, or a color of your own",
                "Each group says everything it changes in the app",
            ),
            listOf(PrismIcons.Customize),
            showMe = Routes.settings("color"),
        ),
        TourStep(
            V1_7_2, PrismIcons.LibraryFilled, "Also new",
            "Smaller things that make Prism yours.",
            listOf(
                "Hold Prism's icon for Search, Recognize, Liked songs and Downloads",
                "Library: pick its tiles, or put your playlists first (the edit button at the top of Library)",
                "Pin Prism playlists to Home (Customize Home → Pinned playlists)",
                "Song credits in any song's menu",
                "See which downloads failed, and why",
            ),
            listOf(PrismIcons.Edit),
        ),
        TourStep(
            V1_7_3, PrismIcons.Looks, "Looks",
            "Settings → Customize Prism → Looks: your whole look, saved, switched and shared in one tap.",
            listOf(
                "Try Prism's own: Paper, Midnight, Neon, Soft, Studio and Vinyl",
                "Save yours, update it when you change it, and share it as a QR code, a code or a file",
                "Browse Looks people shared, under From the community",
                "One Look by day and another by night, at sunrise and sunset",
                "Every look page shows Home, the player and lyrics as you change them; Undo takes a change back",
                "Customize by tapping: hold the Settings tab, or turn it on in Appearance, then tap the mini player, tab bar, player or a Home section",
            ),
            listOf(PrismIcons.Sun, PrismIcons.Moon, PrismIcons.Undo),
            showMe = Routes.settings("looks"),
        ),
        TourStep(
            V1_7_4, PrismIcons.PlayCircle, "The player, your way",
            "Settings → Customize Prism → Player, or ⋯ → Player settings on the player itself.",
            listOf(
                "Layouts: Standard, a big Cover, Lyrics first, Minimal, Vinyl or Cassette (they turn as it plays)",
                "A visualizer if you'd like one: bars, a wave, a ring, a pulse or a glow (off unless you pick it)",
                "Pick the buttons under the controls (speed is one), and the two beside play in your notification and car",
                "Put the lyric line, progress bar, controls and volume in your order",
                "A waveform progress bar, its color, the title centered or bigger, and which times show",
                "Choose what swipes, a double tap and a long press do on the cover and the mini player",
            ),
            listOf(PrismIcons.Turntable, PrismIcons.Customize),
            showMe = Routes.settings("player"),
        ),
        TourStep(
            V1_7_4, PrismIcons.Speed, "Faster",
            "Prism scrolls, searches and opens the player more smoothly, most of all on older phones.",
            listOf(
                "Prism tells Android which of its parts to get ready ahead, and updates from the banner come ready, so they no longer run slow",
                "Less work while lists scroll, while you type in Search, and while a song plays",
                "If something still stutters, Settings → Backup & about → Diagnostics notes where it happened, to share",
            ),
        ),
        TourStep(
            V1_7_5, PrismIcons.Lyrics, "Lyrics, your way",
            "Settings → Customize Prism → Lyrics: how your lyrics look, with an example of every choice.",
            listOf(
                "Six styles: Prism, Classic karaoke, Bold card, Glow, Typewriter and Calm",
                "The sung line and the others in white, your light, the accent, the cover's color or your own",
                "Weight, line spacing, how dim the other lines are, blur strength, capitals and a shadow",
                "The sung line near the top, a third down or in the middle, and how many lines to come show",
                "Translations over or under each line, their size, color and italics",
                "A background of their own: plain dark, the cover blurred or moving, its color, or yours; immersive mode too",
            ),
            listOf(PrismIcons.Lyrics, PrismIcons.Palette),
            showMe = Routes.settings("lyrics_look"),
        ),
        TourStep(
            V1_7_5, PrismIcons.History, "Also new",
            "A few things you asked for.",
            listOf(
                "Library → History: what you played, by day, on this phone or on your account",
                "Liked a song and its video? They show once, as the song",
                "Prism's own volume, apart from the phone's (Settings → Customize Prism → Player)",
                "Hide the status bar (Customize Prism → Tabs & mini player)",
                "Settings pages are tidier: tap ⓘ beside a setting for the details",
                "Library's tiles: the edit button at the top of Library",
            ),
            listOf(PrismIcons.History, PrismIcons.HeartFilled),
        ),
        TourStep(
            V1_7_6, PrismIcons.Home, "Home, your way",
            "Customize Home (the button at the top of Home) has more in it.",
            listOf(
                "Hold any album, playlist, artist or mix → Pin to Home: a section of its own",
                "Sections from your own listening: Liked this month, Downloads you haven't played, Most played in a year",
                "Rename any section; leave YouTube Music's video or podcast sections off",
                "The top of Home: the greeting, just the buttons, or the greeting over a picture or the playing cover",
                "Home shows at once when Prism opens, while the new one loads",
            ),
            listOf(PrismIcons.Home, PrismIcons.Pin),
            showMe = Routes.CATALOGUE,
        ),
        TourStep(
            V1_7_6, PrismIcons.Library, "Library and tabs, your way",
            "Folders, pins, and tabs of your own.",
            listOf(
                "Hold a playlist → Move to a folder; hold anything in Library → Pin to the top",
                "Playlists, Albums and Artists each a list or a grid; 2, 3 or 4 covers across",
                "Make a cover for a playlist, or use your own photo of an artist",
                "Keep YouTube playlists and copy them as Prism playlists, no account needed",
                "Tabs: Downloads, Liked songs or Podcasts as tabs, your own names, words only, swipe between them",
            ),
            listOf(PrismIcons.Folder, PrismIcons.Library),
            showMe = Routes.settings("interface"),
        ),
        TourStep(
            V1_7_6, PrismIcons.Search, "Search, redesigned",
            "Search starts from your music.",
            listOf(
                "Type a line of a song's lyrics to find it",
                "What you opened lately, your artists, and moods drawn by Prism",
                "Artists and songs with their pictures while you type",
                "Results already in your library, right after the top one",
            ),
            listOf(PrismIcons.Search, PrismIcons.Recognize),
            showMe = Routes.SEARCH,
        ),
        TourStep(
            V1_7_6, PrismIcons.Settings, "Settings, easier to find",
            "Settings are sorted by what they do.",
            listOf(
                "Everything about how Prism looks is under Customize Prism",
                "Gestures and buttons in Controls & gestures",
                "Every mobile data choice on one page",
                "Android Auto, speakers and Discord in Car & devices",
                "Performance mode, at the top, for slower phones",
            ),
            listOf(PrismIcons.Settings, PrismIcons.Speed),
            showMe = Routes.SETTINGS,
        ),
        TourStep(
            V1_7, PrismIcons.Update, "Stay in the loop",
            "Let Prism tell you when something needs you.",
            listOf(
                "Download progress",
                "New releases from artists you star",
                "Your Rewind, ready on December 1",
            ),
            kind = TourStep.Kind.NOTIFICATIONS,
        ),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("tour", Context.MODE_PRIVATE)

    /** The versionCode of the last tour finished or skipped; 0 for none (a new install, or one from before tours). */
    fun seenVersion(context: Context): Int = prefs(context).getInt("seen_version", 0)

    /** The tour is done for this version: later only newer steps are shown. */
    fun markSeen(context: Context) = prefs(context).edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply()

    /** The steps someone who last saw the tour at [seen] hasn't seen: all of them for 0. */
    fun stepsAfter(seen: Int): List<TourStep> = steps.filter { it.since > seen }

    /** Whether it's the whole tour (never seen one) or only what's new since the last one. */
    fun isFullTour(seen: Int) = seen == 0
}
