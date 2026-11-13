package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.SearchRank
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.index
import com.prism.music.ui.screens.settings.rootIndex

/**
 * One line of what Settings' search looks through (#73): a setting by its title, the page and group it sits in, and
 * other words people may look for it by.
 *
 * 1.8.4: each page's lines live in that page's own file (`ui/screens/settings/<Page>Page.kt`, its `…Index` list), and
 * [SettingsIndex] puts them together. [SettingsIndexTest] checks that each setting row and group on the pages is listed,
 * so a new setting needs a line in its page's list.
 */
data class SettingEntry(
    /** The page it's on; null for the Settings root itself (the account, Performance mode). */
    val page: SettingsPageId?,
    /** The group it's in, as the page names it (the result opens the page there); blank: the page has no such group. */
    val group: String,
    val title: String,
    val keywords: String = "",
    /** How a result reads when the title alone wouldn't say what it is ("Everywhere"). */
    val shown: String = title,
    /** The switch it only shows under (Crossfade's Length needs Crossfade on); the result says to turn that on first. */
    val needs: Needs? = null,
    /**
     * For a setting on a page that isn't one of Settings' own (Customize Home): that page's route, which the result opens
     * in place of [page], and its title ([routeTitle]) for the result's line.
     */
    val route: String? = null,
    val routeTitle: String? = null,
) {
    /** The name of the page the result names and opens: [routeTitle]'s, or [page]'s, or Settings itself. */
    val pageTitle: String get() = routeTitle ?: page?.title ?: tr(R.string.settings)

    /** Where a tap on the result goes; null for the root's own (the account), which is found in place. */
    val opens: String? get() = route ?: page?.let { com.prism.music.ui.Routes.settings(it.key, group.ifBlank { null }) }

    /** The line for a page itself (found by its name, and by the names of the old pages it took over). */
    val isPage: Boolean get() = page != null && route == null && group.isBlank() && title == page.title
}

/** A setting that only shows while [on] holds; [hint] says what to change first ("Turn on Crossfade songs first"). */
class Needs(val hint: String, val on: (com.prism.music.data.prefs.AppSettings) -> Boolean)

/** The [Needs] more than one line (or more than one page) uses. A page's own go in its own file. */
object SettingNeeds {
    fun turnOn(name: String, on: (com.prism.music.data.prefs.AppSettings) -> Boolean) = Needs(tr(R.string.turn_on_first, name), on)
    val immersive = turnOn(tr(R.string.immersive_mode)) { it.immersivePlayer }
    // 1.8.6: the setting is called Canvas now.
    val canvas = turnOn(tr(R.string.canvas)) { it.canvasEnabled }
    val canvasSave = turnOn(tr(R.string.animated_covers_and_save_with_downloads)) { it.canvasEnabled && it.canvasWithDownloads }
    val holdSeek = turnOn(tr(R.string.hold_previous_next_to_seek)) { it.holdSeekSeconds > 0 }
    val songsOnly = turnOn(tr(R.string.songs_not_videos)) { it.radioSongsOnly }
    val crossfade = turnOn(tr(R.string.crossfade_songs)) { it.mix }
    val manualLength = Needs(tr(R.string.turn_on_crossfade_songs_and_turn)) { it.mix && !it.mixAuto }
    val lossless = turnOn(tr(R.string.lossless_playback)) { it.lossless }
    val notLossless = Needs(tr(R.string.lossless_playback_always_plays_the_best)) { !it.lossless }
    val saver = turnOn(tr(R.string.lossless_playback_and_data_saver)) { it.lossless && it.dataSaver }
    val measuring = turnOn(tr(R.string.normalize_volume_or_crossfade_songs)) { it.normalize || it.mix }
    val translate = turnOn(tr(R.string.translate_lyrics)) { it.lyricsTranslate }
    val romanize = Needs(tr(R.string.pick_a_romanize_lyrics_option_first)) { it.romanize != com.prism.music.data.prefs.RomanizeMode.OFF }
    val smart = turnOn(tr(R.string.smart_downloads)) { it.smartDownloads }
    val webRemote = turnOn(tr(R.string.control_from_a_browser)) { it.webRemote }
    val ownFiles = turnOn(tr(R.string.use_your_own_song_files)) { it.sound.ownLossyFiles }
    val lockScreen = turnOn(tr(R.string.lock_screen_title)) { it.behavior.lockScreen }
}

/**
 * What Settings' search looks through: the root's own lines, a line for each page (its name, with the names of the old
 * pages it took over as search words, so old places are still found), then every page's own list.
 */
object SettingsIndex {
    /** A line per page of the map: found by its name, the old pages' names and what those pages were looked for by. */
    private fun pageEntry(page: SettingsPageId) = SettingEntry(page, "", page.title, page.oldNames + " " + page.words)

    val entries: List<SettingEntry> by lazy {
        rootIndex + SettingsPageId.entries.map { pageEntry(it) } + SettingsPageId.entries.flatMap { it.index }
    }

    /**
     * Settings matching [query]: every word typed has to turn up in the title, group, page (or an old page's name) or
     * keywords. Titles that start with the query come first, then titles that contain it, then pages found by another
     * name, then the rest.
     */
    fun search(query: String): List<SettingEntry> {
        val q = SearchRank.norm(query)
        if (q.isBlank()) return emptyList()
        val words = q.split(' ')
        return prepared.filter { p -> words.all { it in p.hay } }
            .sortedBy { p -> when { p.title.startsWith(q) -> 0; q in p.title -> 1; p.entry.isPage -> 2; else -> 3 } }
            .map { it.entry }
    }

    /** An entry's words as [search] compares them, read once: it runs while composing, for every letter typed (#355). */
    private class Prepared(val entry: SettingEntry, val title: String, val hay: String)

    private val prepared: List<Prepared> by lazy {
        entries.map { e ->
            // A page's old names count for everything on it ("playback & sound equalizer" still finds the Equalizer).
            val pageWords = e.routeTitle ?: e.page?.let { it.title + " " + it.oldNames } ?: tr(R.string.settings)
            Prepared(e, SearchRank.norm(e.title), SearchRank.norm("${e.title} ${e.group} $pageWords ${e.keywords}"))
        }
    }

    /** Reads every entry's words now, so that a first letter doesn't. For a background thread. */
    fun prepare() { prepared.size }
}
