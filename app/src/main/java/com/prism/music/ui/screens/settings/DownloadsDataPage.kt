package com.prism.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.songCount
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.LocalSection
import com.prism.music.data.prefs.Preload
import com.prism.music.download.PlaylistDownloads
import com.prism.music.download.SMART_DOWNLOAD_SIZES
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.download.formatGb
import com.prism.music.download.smartSongEstimate
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.SearchField
import com.prism.music.ui.screens.LocalFoldersSheet
import com.prism.music.ui.screens.PrismCheck
import com.prism.music.ui.screens.SectionsEditor
import com.prism.music.ui.screens.SettingEntry
import com.prism.music.ui.screens.SettingNeeds
import com.prism.music.ui.screens.SettingsSection
import com.prism.music.ui.screens.ShowOption
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val page = SettingsPageId.DOWNLOADS_DATA

/**
 * What Prism may use mobile data for: the chips of the one row that took over eight "… on mobile data" switches of
 * before 1.8.4 (plan §2.5). Each chip is one stored switch, so every combination there was is still there; Data saver
 * is a mode, not a use, and stays its own switch beside the row.
 */
enum class MobileUse(val label: String) {
    /** `no_video_on_data`, the other way round: the chip is on while videos may play. Only shown with Data saver on. */
    VIDEOS(tr(R.string.videos)),
    /** `preload_cellular`. */
    LOAD_AHEAD(tr(R.string.load_ahead)),
    /** `smart_downloads_cellular`. Only shown with Smart Downloads on. */
    SMART_DOWNLOADS(tr(R.string.smart_downloads)),
    /** `lyrics_on_mobile_data`. */
    LYRICS(tr(R.string.lyrics)),
    /** `canvas_on_cellular`. Only shown with animated covers on. */
    ANIMATED_COVERS(tr(R.string.animated_covers)),
    /** `canvas_with_downloads_cellular`. Only shown while animated covers are saved with downloads. */
    SAVING_COVERS(tr(R.string.dd_use_saving_covers)),
    /** `mix_tempo_cellular`. Only shown with Normalize volume or Crossfade on. */
    MEASURING(tr(R.string.dd_use_measuring)),
    /** `sing_cellular`. */
    SING(tr(R.string.sing)),
}

/**
 * The stored switches behind "Use mobile data for", as they are stored. Loops from music videos on mobile data
 * (`canvas_videos_cellular`, 1.8.6) are not here: that switch is in Canvas's own sheet on the Player page.
 */
internal data class MobileDataStored(
    val noVideoOnData: Boolean,
    val preloadCellular: Boolean,
    val smartDownloadsCellular: Boolean,
    val lyricsOnMobileData: Boolean,
    val canvasOnCellular: Boolean,
    val canvasWithDownloadsCellular: Boolean,
    val mixTempoOnCellular: Boolean,
    val singOnCellular: Boolean,
) {
    companion object {
        fun of(s: AppSettings) = MobileDataStored(
            s.behavior.noVideoOnData, s.preloadCellular, s.smartDownloadsCellular, s.lyricsOnMobileData,
            s.canvasOnCellular, s.canvasWithDownloadsCellular, s.mixTempoOnCellular, s.singOnCellular,
        )
    }
}

/** The row's ticked chips for the stored switches ("No videos on mobile data" reads the other way round). */
internal fun mobileDataToMerged(stored: MobileDataStored): Set<MobileUse> = buildSet {
    if (!stored.noVideoOnData) add(MobileUse.VIDEOS)
    if (stored.preloadCellular) add(MobileUse.LOAD_AHEAD)
    if (stored.smartDownloadsCellular) add(MobileUse.SMART_DOWNLOADS)
    if (stored.lyricsOnMobileData) add(MobileUse.LYRICS)
    if (stored.canvasOnCellular) add(MobileUse.ANIMATED_COVERS)
    if (stored.canvasWithDownloadsCellular) add(MobileUse.SAVING_COVERS)
    if (stored.mixTempoOnCellular) add(MobileUse.MEASURING)
    if (stored.singOnCellular) add(MobileUse.SING)
}

/** The stored switches for the row's ticked chips. */
internal fun mobileDataToStored(uses: Set<MobileUse>) = MobileDataStored(
    noVideoOnData = MobileUse.VIDEOS !in uses,
    preloadCellular = MobileUse.LOAD_AHEAD in uses,
    smartDownloadsCellular = MobileUse.SMART_DOWNLOADS in uses,
    lyricsOnMobileData = MobileUse.LYRICS in uses,
    canvasOnCellular = MobileUse.ANIMATED_COVERS in uses,
    canvasWithDownloadsCellular = MobileUse.SAVING_COVERS in uses,
    mixTempoOnCellular = MobileUse.MEASURING in uses,
    singOnCellular = MobileUse.SING in uses,
)

/**
 * The chips the row shows: as before 1.8.4, one only shows while the thing it is about is on (its switch was hidden
 * otherwise). A chip that isn't shown keeps its stored value.
 */
internal fun mobileUsesShown(s: AppSettings): List<MobileUse> = MobileUse.entries.filter {
    when (it) {
        MobileUse.VIDEOS -> s.dataSaver
        MobileUse.SMART_DOWNLOADS -> s.smartDownloads
        MobileUse.ANIMATED_COVERS -> s.canvasEnabled
        MobileUse.SAVING_COVERS -> s.canvasEnabled && s.canvasWithDownloads
        MobileUse.MEASURING -> s.normalize || s.mix
        MobileUse.LOAD_AHEAD, MobileUse.LYRICS, MobileUse.SING -> true
    }
}

/** The uses in the row's order, in words ("Lyrics · Animated covers"). */
private fun usesText(uses: Set<MobileUse>): String = MobileUse.entries.filter { it in uses }.joinToString(" · ") { it.label }.ifEmpty { tr(R.string.nothing) }

/** What the ⓘ says for each chip shown: the explanations of the switches the chips took over. */
private fun usesInfo(shown: List<MobileUse>): String = tr(R.string.what_prism_may_use_mobile_data) + "\n" + shown.joinToString("\n") { use ->
    tr(
        R.string.settings_player_chip_info, use.label,
        when (use) {
            MobileUse.VIDEOS -> tr(R.string.dd_use_videos_info)
            MobileUse.LOAD_AHEAD -> tr(R.string.off_songs_are_only_loaded_ahead_here)
            MobileUse.SMART_DOWNLOADS -> tr(R.string.about_3_8_mb_a_song)
            MobileUse.LYRICS -> tr(R.string.off_prism_doesn_t_look_up)
            MobileUse.ANIMATED_COVERS -> tr(R.string.saved_covers_play_either_way)
            MobileUse.SAVING_COVERS -> tr(R.string.otherwise_they_wait_for_wi_fi)
            MobileUse.MEASURING -> tr(R.string.normalize_volume_and_crossfade_measure_each)
            MobileUse.SING -> tr(R.string.sing_needs_each_song_whole_on)
        },
    )
}

private fun foldersText(skipped: Set<String>): String = if (skipped.isEmpty()) tr(R.string.every_folder_with_music) else trp(R.plurals.n_folders_left_out, skipped.size)

private fun sectionsText(sections: List<LocalSection>): String = sections.joinToString(" · ") { it.label }

/**
 * Settings → Downloads & data (1.8.4's map, plan §2.3), built with the kit: downloads, the files on the phone that
 * Prism plays and lists, Smart Downloads with its space, saved animated covers, and what is loaded ahead and over
 * mobile data. This file is the only place for the page: its composable, its lines in Settings' search
 * ([downloadsDataIndex]), its "Reset this page" keys ([downloadsDataLookKeys]), its line on the root
 * ([downloadsDataValue]) and the old group names it answers to ([downloadsDataOldGroups]).
 *
 * Nothing here is part of a Look, so "Reset this page" puts each row back through its own setter ([ListeningResets]).
 * The playlists kept downloaded are the listener's own list, not a setting with a default: Reset leaves them.
 */
@Composable
fun DownloadsDataPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }

    // Smart Downloads as the next setter sees it. "Reset this page" runs its steps one after another while `s` still
    // holds the settings from before (the store catches up after): once a step has turned Smart Downloads off, the
    // steps after it (its space, its mobile data) mustn't start its worker again.
    var smartOn = s.smartDownloads
    var smartCellular = s.smartDownloadsCellular

    // The setters that do more than store (as the switches of the page before did).
    val setLikes: (Boolean) -> Unit = {
        prefs.setDownloadLikes(it)
        if (it) c.downloads.downloadAll(c.library.liked.value)
    }
    val setSmart: (Boolean) -> Unit = {
        prefs.setSmartDownloads(it)
        smartOn = it
        SmartDownloadWorker.schedule(context, it, cellular = smartCellular)
        if (it) SmartDownloadWorker.runNow(context, cellular = smartCellular) else c.trimSongCache()
    }
    val setSpace: (Float) -> Unit = {
        prefs.setOfflineGb(it)
        c.trimSongCache()
        if (smartOn) SmartDownloadWorker.runNow(context, cellular = smartCellular)
    }
    val setCanvasSave: (Boolean) -> Unit = {
        prefs.setCanvasWithDownloads(it)
        if (it) c.downloads.saveCanvases()
    }
    val uses = mobileDataToMerged(MobileDataStored.of(s))
    val setUse: (MobileUse, Boolean) -> Unit = { use, on ->
        when (use) {
            // #281: a video is what uses the most data of all.
            MobileUse.VIDEOS -> prefs.setNoVideoOnData(!on)
            MobileUse.LOAD_AHEAD -> prefs.setPreloadCellular(on)
            MobileUse.SMART_DOWNLOADS -> {
                prefs.setSmartDownloadsCellular(on)
                smartCellular = on
                if (smartOn) {
                    // The worker's network rule is fixed when it's scheduled, so it's scheduled again with the new one.
                    SmartDownloadWorker.schedule(context, true, cellular = on)
                    SmartDownloadWorker.runNow(context, force = false, cellular = on)
                    c.downloads.refreshSmartIds()
                }
            }
            MobileUse.LYRICS -> prefs.setLyricsOnMobileData(on)
            MobileUse.ANIMATED_COVERS -> prefs.setCanvasOnCellular(on)
            MobileUse.SAVING_COVERS -> {
                prefs.setCanvasWithDownloadsCellular(on)
                if (on) c.downloads.saveCanvases()
            }
            MobileUse.MEASURING -> prefs.setMixTempoOnCellular(on)
            MobileUse.SING -> prefs.setSingOnCellular(on)
        }
    }

    // What differs from how Prism comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val wifiChanged = reset.add(tr(R.string.download_on_wi_fi_only), s.downloadWifiOnly, d.downloadWifiOnly, onOff) { prefs.setDownloadWifiOnly(it) }
    val likesChanged = reset.add(tr(R.string.download_liked_songs_automatically), s.downloadLikes, d.downloadLikes, onOff, setLikes)
    val ownChanged = reset.add(tr(R.string.use_your_own_song_files), s.sound.ownLossyFiles, d.sound.ownLossyFiles, onOff) { prefs.setOwnLossyFiles(it) }
    val foldersChanged = reset.add(tr(R.string.folders_in_local_files), s.behavior.localSkipFolders, d.behavior.localSkipFolders, ::foldersText) { prefs.setLocalSkipFolders(it) }
    val sectionsChanged = reset.add(tr(R.string.dd_sections_in_local_files), s.localSections, d.localSections, ::sectionsText) { prefs.setLocalSections(it) }
    val smartChanged = reset.add(tr(R.string.smart_downloads), s.smartDownloads, d.smartDownloads, onOff, setSmart)
    val spaceChanged = reset.add(tr(R.string.space_to_use), s.offlineGb, d.offlineGb, ::formatGb, setSpace)
    val canvasSaveChanged = reset.add(tr(R.string.save_with_downloads), s.canvasWithDownloads, d.canvasWithDownloads, onOff, setCanvasSave)
    val preloadChanged = reset.add(tr(R.string.load_ahead), s.preload, d.preload, { it.label }) { prefs.setPreload(it) }
    val saverChanged = reset.add(tr(R.string.data_saver), s.dataSaver, d.dataSaver, onOff) { prefs.setDataSaver(it) }
    val usesChanged = reset.add(tr(R.string.dd_use_mobile_data_for), uses, mobileDataToMerged(MobileDataStored.of(d)), ::usesText) { back ->
        MobileUse.entries.forEach { use -> if ((use in back) != (use in uses)) setUse(use, use in back) }
    }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.downloads)) {
            val downloads by c.downloadState.collectAsState()
            val count = remember(downloads) { c.downloads.completedSongs().size }
            LinkRow(tr(R.string.manage_downloads), value = if (count == 0) tr(R.string.no_downloads_yet) else songCount(count)) { nav.go(Routes.DOWNLOADS) }
            SwitchRow(tr(R.string.download_on_wi_fi_only), s.downloadWifiOnly, changed = wifiChanged) { prefs.setDownloadWifiOnly(it) }
            SwitchRow(
                tr(R.string.download_liked_songs_automatically), s.downloadLikes,
                info = tr(R.string.including_likes_from_other_devices), changed = likesChanged, onChange = setLikes,
            )
            // #298 (1.8.2).
            var choosingKept by remember { mutableStateOf(false) }
            val kept = s.behavior.keptPlaylists.size
            LinkRow(
                tr(R.string.keep_playlists_downloaded), value = if (kept == 0) tr(R.string.none_chosen) else trp(R.plurals.n_playlists_kept, kept),
                info = tr(R.string.new_songs_in_these_playlists_download),
            ) { choosingKept = true }
            if (choosingKept) DdKeptPlaylistsSheet { choosingKept = false }
        }
        group(tr(R.string.dd_files_on_your_phone)) {
            DdOwnSongFiles(ownChanged)
            // #207, #249 (1.8.2): which folders Local files lists.
            var choosingFolders by remember { mutableStateOf(false) }
            LinkRow(
                tr(R.string.folders_in_local_files), value = foldersText(s.behavior.localSkipFolders),
                info = tr(R.string.untick_a_folder_to_leave_it_out), changed = foldersChanged,
            ) { choosingFolders = true }
            // #29: which sections Local files shows, in what order. Before 1.8.4 only Local files' own Edit chip opened this.
            var editingSections by remember { mutableStateOf(false) }
            LinkRow(tr(R.string.dd_sections_in_local_files), value = sectionsText(s.localSections), changed = sectionsChanged) { editingSections = true }
            if (editingSections) SectionsEditor(s.localSections, onFolders = { choosingFolders = true }) { editingSections = false }
            if (choosingFolders) LocalFoldersSheet { choosingFolders = false }
        }
        // Not "Smart Downloads": that is its first row's name (§2.7).
        group(tr(R.string.dd_downloaded_for_you)) {
            SwitchRow(tr(R.string.smart_downloads), s.smartDownloads, info = tr(R.string.one_space_for_music_on_your), changed = smartChanged, onChange = setSmart)
            var cacheBytes by remember { mutableLongStateOf(c.songCacheBytes()) }
            val picks by c.downloads.smartIds.collectAsState()
            val downloads by c.downloadState.collectAsState()
            val used = picks.sumOf { downloads[it]?.bytes ?: 0L } + cacheBytes
            val usedText = if (used < 1_073_741_824L) "${used / 1_048_576} MB" else tr(R.string.t_1f_gb).format(used / 1_073_741_824.0)
            val sizes = SMART_DOWNLOAD_SIZES
            fun size(i: Float) = sizes[i.roundToInt().coerceIn(0, sizes.lastIndex)]
            val freeGb = remember { context.filesDir.usableSpace / 1_073_741_824f }
            // How many songs that is, what is used and what the phone has free: figures, behind the ⓘ.
            val room = smartSongEstimate(s.offlineGb, s.audioQuality).let { n ->
                trp(R.plurals.about_n_songs_used, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "%,d".format(n), usedText)
            }.format(freeGb) + if (s.offlineGb > freeGb) tr(R.string.it_stops_when_storage_runs_low) else ""
            SliderRow(
                tr(R.string.space_to_use), sizes.indexOfFirst { it >= s.offlineGb }.let { if (it < 0) sizes.lastIndex else it }.toFloat(),
                0f..sizes.lastIndex.toFloat(), { formatGb(size(it)) }, steps = sizes.size - 2, info = room, changed = spaceChanged,
            ) { setSpace(size(it)) }
            LinkRow(
                tr(R.string.clear_smart_downloads), value = tr(R.string.dd_space_used, usedText),
                info = tr(R.string.removes_the_songs_it_downloaded_ahead) + "\n" + tr(R.string.your_own_downloads_stay), action = true,
            ) {
                c.downloads.clearSmartDownloads()
                cacheBytes = c.songCacheBytes()
            }
        }
        if (s.canvasEnabled) group(tr(R.string.animated_covers)) {
            SwitchRow(
                tr(R.string.save_with_downloads), s.canvasWithDownloads,
                info = tr(R.string.a_downloaded_song_s_animated_cover), changed = canvasSaveChanged, onChange = setCanvasSave,
            )
            DdCanvasStorage()
        }
        // What is loaded ahead, and what may go over mobile data (1.7.6 gathered the "on mobile data" choices here;
        // streaming quality on mobile data is on Sound & quality).
        group(tr(R.string.mobile_data)) {
            ChoiceRow(
                tr(R.string.load_ahead), Preload.entries, s.preload, { it.label },
                info = (if (s.preload == Preload.START) tr(R.string.now_the_next_song_s_start) else tr(R.string.now_whole_songs_so_they_play)) + "\n" +
                    (if (s.preload == Preload.START) tr(R.string.the_next_song_s_start_about) else tr(R.string.whole_songs_about_3_8_mb)),
                changed = preloadChanged,
            ) { prefs.setPreload(it) }
            SwitchRow(tr(R.string.data_saver), s.dataSaver, info = tr(R.string.on_mobile_data_pictures_at_the), changed = saverChanged) { prefs.setDataSaver(it) }
            val shown = mobileUsesShown(s)
            ShowChipsRow(
                tr(R.string.dd_use_mobile_data_for), shown.map { use -> ShowOption(use.label, use in uses) { setUse(use, it) } },
                info = usesInfo(shown), changed = usesChanged,
            )
        }
    }
}

/**
 * #314 (1.8.1): your own MP3/AAC/Ogg files in place of the stream, beside lossless sync's files in Downloads. Off by
 * default; it needs file access, not lossless playback. On, the row says what was found, and "Scan again" follows it.
 */
@Composable
private fun DdOwnSongFiles(changed: Boolean) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val scope = rememberCoroutineScope()
    val found by c.localLossless.ownFound.collectAsState()
    val synced by c.losslessSync.songs.collectAsState()
    val syncing by c.losslessSync.working.collectAsState()
    val inDownloads = remember(synced) { synced.values.count { it.second.lossy } }
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prefs.setOwnLossyFiles(true) // lossless sync scans when this turns on
    }
    val on = s.sound.ownLossyFiles
    SwitchRow(
        tr(R.string.use_your_own_song_files), on,
        value = when {
            !on -> null
            found < 0 -> tr(R.string.own_files_looking)
            found == 0 -> tr(R.string.own_files_none)
            syncing -> tr(R.string.own_files_adding, found)
            else -> tr(R.string.own_files_status, found, inDownloads)
        },
        info = tr(R.string.own_files_info), changed = changed,
    ) { want ->
        if (!want) prefs.setOwnLossyFiles(false)
        else if (c.localLossless.hasPermission()) prefs.setOwnLossyFiles(true)
        else askFiles.launch(LocalLossless.permission)
    }
    if (on) LinkRow(tr(R.string.scan_your_song_files_again), info = tr(R.string.also_checked_every_few_hours), action = true) { scope.launch { c.losslessSync.sync() } }
}

/** Animated covers kept on the phone: the ones saved from the player, and the recently played ones. A tap empties one. */
@Composable
private fun DdCanvasStorage() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val saved by c.canvas.saved.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val sizes by produceState(0L to 0L, version, saved) {
        // 1.8.6: loops cut from music videos count with the animated covers (Canvas is both).
        value = withContext(Dispatchers.IO) {
            (c.canvasStore.savedBytes + c.clipFiles.savedBytes) to (c.canvasStore.recentBytes + c.clipFiles.recentBytes)
        }
    }
    fun mb(bytes: Long) = tr(R.string.t_0f_mb).format(bytes / 1_048_576f)
    LinkRow(
        tr(R.string.saved_animated_covers),
        value = if (saved.count == 0) tr(R.string.none_yet) else tr(R.string.tap_to_remove_all, saved.count, mb(sizes.first)),
        info = tr(R.string.save_one_from_the_player_s), action = true,
    ) {
        if (saved.count > 0) scope.launch {
            withContext(Dispatchers.IO) { c.canvasStore.clearSaved(); c.clipFiles.clearSaved() }
            version++
        }
    }
    LinkRow(tr(R.string.recently_played_covers), value = tr(R.string.tap_to_clear, mb(sizes.second)), info = tr(R.string.kept_up_to_400_mb_so), action = true) {
        scope.launch {
            withContext(Dispatchers.IO) { c.canvasStore.clearRecent(); c.clipFiles.clearRecent() }
            version++
        }
    }
}

/** Which playlists stay downloaded as they grow (#298; this list's shape is stuntsnakee's, from PR #304). */
@Composable
private fun DdKeptPlaylistsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val library by c.library.playlists.collectAsState()
    val prismLists by c.prismLibrary.lists.collectAsState()
    val savedLists by c.prismLibrary.playlists.collectAsState()
    val signedIn = s.isLoggedIn
    val mine = remember(library, prismLists, savedLists, signedIn) {
        (prismLists.map { it.item() } + savedLists.map { it.item() } + if (signedIn) library.filterIsInstance<PlaylistItem>() else emptyList())
            .filter { PlaylistDownloads.canKeep(it.id) && !it.isMix }
            .distinctBy { PlaylistDownloads.keepKey(it.id) }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(mine, query) { if (query.isBlank()) mine else mine.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    PrismSheet(onDismiss = onDismiss) {
        Text(tr(R.string.keep_playlists_downloaded), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        if (mine.size > 8) SearchField(query, tr(R.string.find_a_playlist), { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (mine.isEmpty()) Text(tr(R.string.no_playlists_yet), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
            items(shown.size, key = { shown[it].id }) { i ->
                val p = shown[i]
                val on = PlaylistDownloads.keepKey(p.id) in s.behavior.keptPlaylists
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(c.covers.art(p), Modifier.size(46.dp), LocalUi.current.smallArt, size = 120, placeholderIcon = PrismIcons.QueueMusic)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.subtitle.isNotBlank()) Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PrismCheck(on, label = p.title) { c.keptPlaylists.set(p.id, it) }
                }
            }
        }
    }
}

/** The root's line for Downloads & data: Smart Downloads' room, Wi-Fi only or any network, Data saver when on. */
fun downloadsDataValue(s: AppSettings, dark: Boolean): String = SettingsSection.DOWNLOADS.summary(s, dark)

/** The look settings "Reset this page" puts back: none (downloads and data aren't part of a look; the page resets its rows itself). */
val downloadsDataLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: "Your own song files" and "Local files" are Files on your phone, "Smart
 * Downloads" is the group around the row of that name, and "Load ahead" is the first row of Mobile data. "Downloads",
 * "Animated covers" and "Mobile data" are still called that.
 */
val downloadsDataOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.your_own_song_files) to tr(R.string.dd_files_on_your_phone),
        tr(R.string.local_files) to tr(R.string.dd_files_on_your_phone),
        tr(R.string.smart_downloads) to tr(R.string.dd_downloaded_for_you),
        tr(R.string.load_ahead) to tr(R.string.mobile_data),
    )
}

/** The page's lines in Settings' search (each names the group it is drawn in). */
val downloadsDataIndex: List<SettingEntry> by lazy {
    val downloads = tr(R.string.downloads)
    val files = tr(R.string.dd_files_on_your_phone)
    val smart = tr(R.string.dd_downloaded_for_you)
    val covers = tr(R.string.animated_covers)
    val data = tr(R.string.mobile_data)
    // The eight switches the chips took over stay as search words, with what each was found by.
    val useWords = listOf(
        R.string.no_videos_on_mobile_data, R.string.no_videos_on_mobile_data_keywords, R.string.load_ahead_on_mobile_data, R.string.preload_cellular,
        R.string.smart_downloads_on_mobile_data, R.string.lyrics_on_mobile_data, R.string.cellular_data_saver_metered_load_lyrics,
        R.string.animated_covers_on_mobile_data, R.string.canvas_cellular_play, R.string.save_animated_covers_on_mobile_data,
        R.string.measure_songs_on_mobile_data, R.string.get_songs_ready_for_sing_on, R.string.karaoke_cellular_sing, R.string.dd_use_keywords,
    ).joinToString(" ") { tr(it) }
    with(SettingNeeds) {
        listOf(
            SettingEntry(page, downloads, tr(R.string.manage_downloads), tr(R.string.offline_delete)),
            SettingEntry(page, downloads, tr(R.string.download_on_wi_fi_only), tr(R.string.cellular_mobile_data)),
            SettingEntry(page, downloads, tr(R.string.download_liked_songs_automatically), tr(R.string.offline_likes)),
            SettingEntry(page, downloads, tr(R.string.keep_playlists_downloaded), tr(R.string.keep_playlists_keywords)),
            // #314 (1.8.1).
            SettingEntry(page, files, tr(R.string.use_your_own_song_files), tr(R.string.own_files_keywords)),
            SettingEntry(page, files, tr(R.string.scan_your_song_files_again), tr(R.string.own_files_keywords), needs = ownFiles),
            SettingEntry(page, files, tr(R.string.folders_in_local_files), tr(R.string.local_folders_keywords)),
            SettingEntry(page, files, tr(R.string.dd_sections_in_local_files), tr(R.string.dd_sections_keywords)),
            SettingEntry(page, smart, tr(R.string.smart_downloads), tr(R.string.offline_cache_storage_space) + " " + tr(R.string.songs_you_ll_likely_play_ready)),
            SettingEntry(page, smart, tr(R.string.space_to_use), tr(R.string.storage_cache_size_gb)),
            SettingEntry(page, smart, tr(R.string.clear_smart_downloads), tr(R.string.cache_delete_free_space)),
            SettingEntry(page, covers, tr(R.string.save_with_downloads), tr(R.string.canvas_offline), needs = canvas),
            SettingEntry(page, covers, tr(R.string.saved_animated_covers), tr(R.string.canvas_storage), needs = canvas),
            SettingEntry(page, covers, tr(R.string.recently_played_covers), tr(R.string.canvas_storage_cache), needs = canvas),
            SettingEntry(page, data, tr(R.string.load_ahead), tr(R.string.preload_buffer)),
            SettingEntry(page, data, tr(R.string.data_saver), tr(R.string.cellular_low_data)),
            SettingEntry(page, data, tr(R.string.dd_use_mobile_data_for), MobileUse.entries.joinToString(" ") { it.label } + " " + useWords),
        )
    }
}
