package com.prism.music.ui.screens.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.prism.music.BuildConfig
import com.prism.music.CrashLog
import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.playback.AudioDiagnostics
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.screens.LicensesPage
import com.prism.music.ui.screens.SOURCE_URL
import com.prism.music.ui.screens.SettingEntry
import com.prism.music.ui.screens.SettingNeeds
import com.prism.music.ui.screens.SettingsSection
import com.prism.music.ui.screens.UpdateBanner
import com.prism.music.ui.screens.rememberBackupFlow
import com.prism.music.ui.screens.rememberRestoreFlow
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val page = SettingsPageId.BACKUP_ABOUT

/** The page's foot line (Prism is unofficial: not a row, and always drawn). */
internal const val BACKUP_ABOUT_FOOT = "backupAbout:foot"

/** The update itself, under the Updates group while there is one (not a row either: Home's card). */
internal const val BACKUP_ABOUT_UPDATE = "backupAbout:update"

/**
 * Settings → Backup & about (1.8.4's map, plan §2.3), built with the kit: back up and restore, the update check (with
 * the update itself under it when there is one), Prism's version and source, the tour, the licenses, and diagnostics
 * as a group of its own (a fold before 1.8.4). At the page's foot, outside the groups, the line that Prism is
 * unofficial. This file is the only place for the page: its composable, its lines in Settings' search
 * ([backupAboutIndex]), its "Reset this page" keys ([backupAboutLookKeys]), its line on the root ([backupAboutValue])
 * and the old group names it answers to ([backupAboutOldGroups]). The licenses' screen, opened from its About group, is
 * here too ([LicensesScreen]).
 *
 * Nothing here sends anything by itself: the update check asks GitHub for the public release (as before), and the
 * diagnostics leave the phone only through Android's share sheet, when the listener taps Share.
 */
@Composable
fun BackupAboutPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackupAboutContent(
        bottomPadding, find, showBack, onSearch,
        checkNow = { c.updates.checkNow() },
        shareDiagnostics = {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    AudioDiagnostics.export(context, AudioDiagnostics.about(c) + "\n" + AudioDiagnostics.canvas(c))
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                context.startActivity(Intent.createChooser(diagnosticsShareIntent(uri), tr(R.string.share_diagnostics)))
            }
        },
    )
}

/**
 * What goes to Android's share sheet for "Share diagnostics" and "Last crash": the exported text file, readable by
 * the app the listener picks and by nothing else.
 */
internal fun diagnosticsShareIntent(uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, tr(R.string.prism_playback_diagnostics))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return send
}

/** Where a tap on "Check for updates" has got to. */
internal sealed interface UpdateCheck {
    data object Asking : UpdateCheck
    data object Current : UpdateCheck
    data class Newer(val version: String) : UpdateCheck
    data object Unreachable : UpdateCheck
}

/**
 * The value line of "Check for updates": what the last tap found, and before any tap the update Prism already knows
 * of ([offered]) or else the installed version.
 */
internal fun updateCheckValue(check: UpdateCheck?, offered: String?): String = when (check) {
    UpdateCheck.Asking -> tr(R.string.asking_github)
    UpdateCheck.Current -> tr(R.string.you_re_up_to_date)
    is UpdateCheck.Newer -> tr(R.string.prism_is_out_tap_below_to, check.version)
    UpdateCheck.Unreachable -> tr(R.string.couldn_t_reach_github_try_again)
    null -> if (offered != null) tr(R.string.prism_is_available, offered) else tr(R.string.prism, BuildConfig.VERSION_NAME)
}

/**
 * The page itself. [checkNow] asks GitHub for the latest release (the newer version, null when Prism is current, or it
 * throws); [shareDiagnostics] exports the log and opens the share sheet. Both are the page's only ways out of the
 * phone, handed in so that tests draw the page without either.
 */
@Composable
internal fun BackupAboutContent(
    bottomPadding: Dp,
    find: String?,
    showBack: Boolean,
    onSearch: (() -> Unit)?,
    checkNow: suspend () -> String?,
    shareDiagnostics: () -> Unit,
) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val nav = LocalNavigator.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = remember { AppSettings() }
    // The flows keep their dialogs here, not in a row: a row scrolled away would take its dialog with it.
    val backup = rememberBackupFlow()
    val restore = rememberRestoreFlow()
    // Prism also checks on its own (at most hourly), as Home does; pre-releases are never offered (UpdateChecker).
    val offered by c.updates.available.collectAsState()
    LaunchedEffect(Unit) { c.updates.check() }
    var check by remember { mutableStateOf<UpdateCheck?>(null) }
    // A crash kept on the phone (CrashLog): there to share whether or not diagnostics are on.
    var crashAt by remember { mutableStateOf(CrashLog.lastAt(context)) }

    val reset = ListeningResets()
    val diagnosticsChanged = reset.add(tr(R.string.playback_diagnostics), s.audioDiagnostics, d.audioDiagnostics, { ListeningResets.onOff(it) }) {
        c.settings.setAudioDiagnostics(it)
    }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.backup)) {
            LinkRow(tr(R.string.back_up), info = tr(R.string.settings_library_replay_sign_in_you), action = true, onClick = backup)
            LinkRow(tr(R.string.restore_from_a_backup), info = tr(R.string.replaces_what_the_backup_holds_then), action = true, onClick = restore)
        }
        group(tr(R.string.about_updates)) {
            LinkRow(
                tr(R.string.check_for_updates), value = updateCheckValue(check, offered?.version),
                info = tr(R.string.prism_also_checks_on_its_own), enabled = check != UpdateCheck.Asking, action = true,
            ) {
                scope.launch {
                    check = UpdateCheck.Asking
                    check = runCatching { checkNow() }.fold({ v -> if (v != null) UpdateCheck.Newer(v) else UpdateCheck.Current }, { UpdateCheck.Unreachable })
                }
            }
        }
        // The update itself, installed from here (#201): the same card as Home's, which needs Home to be a tab.
        if (offered != null) item(BACKUP_ABOUT_UPDATE) {
            // The card brings its own 16 dp gutters; the page already has them.
            Box(
                Modifier.testTag(BACKUP_ABOUT_UPDATE).layout { measurable, constraints ->
                    val gutter = 16.dp.roundToPx()
                    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + 2 * gutter))
                    layout(constraints.maxWidth, p.height) { p.place(-gutter, 0) }
                },
            ) { UpdateBanner(inSettings = true) }
        }
        // "Your data stays here" was a row that did nothing: its words are the group's ⓘ (§2.7).
        group(tr(R.string.about_3), info = tr(R.string.about_data_info)) {
            // The installed version is the Updates row's value line (and the root's); this one is where Prism comes from.
            LinkRow(tr(R.string.source_code), value = tr(R.string.open_on_github), external = true) { runCatching { uri.openUri(SOURCE_URL) } }
            LinkRow(tr(R.string.take_the_tour), info = tr(R.string.a_minute_s_walk_through_prism)) { c.tourRequests.tryEmit(Unit) }
            LinkRow(tr(R.string.open_source_licenses), value = tr(R.string.gpl_3_0_or_later)) { nav.go(Routes.settings(SettingsSection.LICENSES.key)) }
            // Debug builds only, so it's left out of Settings search (named title: SettingsIndexTest skips it).
            if (BuildConfig.DEBUG) LinkRow(title = tr(R.string.design_gallery), info = tr(R.string.every_component_of_the_redesign_in)) { nav.go(Routes.DESIGN_GALLERY) }
        }
        // Playback diagnostics: a log of what the audio did, kept on the phone, for chasing stutters. A group of its
        // own since 1.8.4 (the "More options" fold is gone); Share and Clear only show while the log is kept.
        group(tr(R.string.diagnostics)) {
            SwitchRow(
                tr(R.string.playback_diagnostics), s.audioDiagnostics, info = tr(R.string.keeps_a_log_on_this_phone), changed = diagnosticsChanged,
            ) { c.settings.setAudioDiagnostics(it) }
            crashAt?.let { at ->
                LinkRow(
                    tr(R.string.last_crash),
                    value = tr(R.string.tap_to_share_its_details, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))),
                    info = tr(R.string.prism_closed_unexpectedly_what_went_wrong), action = true, onClick = shareDiagnostics,
                )
                LinkRow(tr(R.string.forget_crashes), action = true) { CrashLog.clear(context); crashAt = null }
            }
            if (s.audioDiagnostics) {
                LinkRow(tr(R.string.share_diagnostics), info = tr(R.string.the_log_and_audio_settings_as), action = true, onClick = shareDiagnostics)
                LinkRow(tr(R.string.clear_diagnostics), info = tr(R.string.starts_the_log_again), action = true) {
                    scope.launch(Dispatchers.IO) { AudioDiagnostics.clear { AudioDiagnostics.about(c) } }
                    Toast.makeText(context, tr(R.string.diagnostics_cleared), Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Not an explanation but what Prism has to say about itself: always on the page, under the groups.
        item(BACKUP_ABOUT_FOOT) {
            Text(
                tr(R.string.unofficial_not_affiliated_with_google_or), Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(BACKUP_ABOUT_FOOT),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The open-source licenses (`settings/licenses`, the old key): a screen of this page, not a page of the map, opened
 * from About. [LicensesPage] as it was, inside the kit's scaffold.
 */
@Composable
fun LicensesScreen(bottomPadding: Dp, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    SettingsPageScaffold(tr(R.string.open_source_licenses), bottomPadding, showBack = showBack, onSearch = onSearch) {
        item("licenses") { LicensesPage() }
    }
}

/** The root's line for Backup & about: Prism's version, and what the page holds, in one short line. */
fun backupAboutValue(s: AppSettings, dark: Boolean): String = tr(R.string.root_backup_short, com.prism.music.BuildConfig.VERSION_NAME)

/** The look settings "Reset this page" puts back: none (the page's own reset turns the diagnostics log off again). */
val backupAboutLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups. Backup, About and Diagnostics kept their names; nothing is renamed. ("Check
 * for updates" left About for the new Updates group: search opens it there.)
 */
val backupAboutOldGroups: Map<String, String> = emptyMap()

/** The page's lines in Settings' search (each names the group it is drawn in). */
val backupAboutIndex: List<SettingEntry> by lazy {
    val backup = tr(R.string.backup)
    val updates = tr(R.string.about_updates)
    val about = tr(R.string.about_3)
    val diagnostics = tr(R.string.diagnostics)
    val logOn = SettingNeeds.turnOn(tr(R.string.playback_diagnostics)) { it.audioDiagnostics }
    listOf(
        SettingEntry(page, backup, tr(R.string.back_up), tr(R.string.backup_export_save_move_new_phone)),
        SettingEntry(page, backup, tr(R.string.restore_from_a_backup), tr(R.string.import_move_new_phone)),
        SettingEntry(page, updates, tr(R.string.check_for_updates), tr(R.string.update_new_version)),
        // "Your data stays here" is the group's ⓘ now: its words still find About.
        SettingEntry(page, about, tr(R.string.source_code), tr(R.string.app_name) + " " + tr(R.string.version_github) + " privacy " + tr(R.string.your_data_stays_here)),
        SettingEntry(page, about, tr(R.string.take_the_tour), tr(R.string.tutorial_walkthrough_help_guide_what_s)),
        SettingEntry(page, about, tr(R.string.open_source_licenses), tr(R.string.license_gpl)),
        SettingEntry(page, diagnostics, tr(R.string.playback_diagnostics), tr(R.string.stutter_judder_skip_glitch_lag_bluetooth) + " " + tr(R.string.a_log_for_tracking_down_stutters)),
        // The two crash rows show while a crash is kept on the phone (not a setting to turn on).
        SettingEntry(page, diagnostics, tr(R.string.last_crash), tr(R.string.crash_closed_unexpectedly_error_report_stack)),
        SettingEntry(page, diagnostics, tr(R.string.forget_crashes), tr(R.string.crash_delete_clear)),
        SettingEntry(page, diagnostics, tr(R.string.share_diagnostics), tr(R.string.log_send_report), needs = logOn),
        SettingEntry(page, diagnostics, tr(R.string.clear_diagnostics), tr(R.string.log_reset), needs = logOn),
    )
}
