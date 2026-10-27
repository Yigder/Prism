package com.prism.music.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.looks.Look
import com.prism.music.data.looks.LookCode
import com.prism.music.data.looks.LookSchedule
import com.prism.music.data.looks.LookStore
import com.prism.music.data.looks.SunClock
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ActionGroup
import com.prism.music.ui.components.ActionItem
import com.prism.music.ui.components.ActionSheet
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.frostFill
import com.prism.music.ui.components.runAction
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Appearance → Looks (1.7.3): Prism's own Looks and yours, each a card showing it on three small phones; saving the look
 * you have, sharing a Look as a QR code, text or a file, getting one, a Look for day and one for night, and the history
 * of look changes to go back through.
 */

const val LOOK_PREVIEW_KEY = "look-preview"

/**
 * The live preview at the top of every look page: Home (with the mini player and tab bar), the player and the lyrics,
 * drawn in the app's settings as they are, so it changes as you pick. [pinned]: the small copy held under the bar.
 */
@Composable
fun LookStrip(pinned: Boolean = false) {
    val s = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    if (pinned) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(frostFill(4f)).background(scheme.surface.copy(alpha = 0.82f))
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center,
        ) { LookPhones(s, 48.dp, gap = 10.dp) }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(start = 4.dp)) {
            Text("Your Prism", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light)
            Text("Home, the player and lyrics, as you change them", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = ((maxWidth - 24.dp) / 3).coerceAtMost(132.dp)
            LookPhones(s, w, gap = 12.dp)
        }
    }
}

/** The Looks page. */
fun androidx.compose.foundation.lazy.LazyListScope.looksPage() {
    item(key = LOOK_PREVIEW_KEY) { LookStrip() }
    item(key = "looks") { LooksSettings() }
}

@Composable
private fun LooksSettings() {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val mine by c.looks.mine.collectAsState()
    val current by c.looks.currentId.collectAsState()
    val pending by c.looks.pending.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Look?>(null) }
    var sharing by remember { mutableStateOf<Look?>(null) }
    var renaming by remember { mutableStateOf<Look?>(null) }

    fun apply(look: Look) = scope.launch { c.looks.apply(look) }
    fun take(look: Look?) {
        if (look == null) Toast.makeText(context, "That isn't a Prism Look", Toast.LENGTH_SHORT).show() else c.looks.pending.value = look
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { c.looks.readFile(it) } } }
            r.getOrNull()?.let { c.looks.pending.value = it }
                ?: Toast.makeText(context, r.exceptionOrNull()?.message ?: "That file isn't a Prism Look", Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // What you have now, and keeping it.
        val now = current?.let(c.looks::find)
        // One of yours, changed since it was saved: it can be updated with the changes.
        val edited by c.looks.edited.collectAsState()
        val app = LocalAppSettings.current
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    edited != null -> "You're on ${edited!!.name}, with changes"
                    now != null -> "You're on ${now.name}"
                    else -> "You're on your own mix of settings"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                edited?.let { e -> remember(e, app) { c.looks.edits(e) }?.let { "$it since you saved it. Update it to keep them in ${e.name}." } }
                    ?: "A Look keeps colors, type, icons, backgrounds, the player and lyrics' look together. Pick one and everything changes at once; Undo puts it back.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                edited?.let { e ->
                    PrismButton({ scope.launch { c.looks.update(e) } }) { Icon(PrismIcons.Update, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update") }
                    PrismOutlinedButton({ saving = true }) { Icon(PrismIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save as new") }
                } ?: PrismButton({ saving = true }) { Icon(PrismIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save as a Look") }
                if (now != null && edited == null) PrismOutlinedButton({ sharing = now }) { Icon(PrismIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share") }
            }
            edited?.let { e -> PrismTextButton({ apply(e) }) { Icon(PrismIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Go back to ${e.name} as saved", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            // Icons are drawn as Prism starts: a Look with other icons shows them after a restart.
            if (c.looks.iconsNeedRestart()) PrismTextButton({
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Runtime.getRuntime().exit(0)
                }
            }) { Icon(PrismIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Restart Prism to see its icons") }
        }

        Group("Prism's Looks") {
            LookGrid(BuiltInLooks.all, current, onPick = ::apply, onMore = { acting = it })
        }
        Group("Your Looks") {
            if (mine.isEmpty()) Note("Looks you save show here. Save one above, or get one from a friend below.")
            else LookGrid(mine, current, onPick = ::apply, onMore = { acting = it })
        }
        CommunityGroup(current)
        Group("Get a Look", info = "You see a Look first, and choose to try it or keep it.") {
            Item("Scan a Look's QR code", "From a friend's Prism", icon = PrismIcons.Camera, onClick = { nav.go(Routes.LOOK_SCAN) }) { Chevron() }
            Item("Paste a Look code", "A code someone sent you", icon = PrismIcons.Copy, onClick = { take(c.looks.decode(clipboard.getText()?.text)) })
            Item("Open a Look file", "A .prismlook file, with its photos and font", icon = PrismIcons.Photo, onClick = { openFile.launch(arrayOf("application/zip", "application/octet-stream")) })
        }
        ScheduleGroup()
        HistoryGroup()
    }

    if (saving) NameDialog("Save as a Look", "Name", "My Look", onDismiss = { saving = false }) { name ->
        saving = false
        scope.launch {
            val look = c.looks.saveCurrent(name)
            Toast.makeText(context, "Saved \"${look.name}\"", Toast.LENGTH_SHORT).show()
        }
    }
    renaming?.let { look ->
        NameDialog("Rename", "Name", look.name, onDismiss = { renaming = null }) { name -> renaming = null; c.looks.rename(look.id, name) }
    }
    acting?.let { look ->
        LookActions(look, onDismiss = { acting = null }, onApply = { apply(look) }, onShare = { sharing = look }, onRename = { renaming = look })
    }
    sharing?.let { look -> ShareLookSheet(look) { sharing = null } }
    pending?.let { look -> PendingLookDialog(look) }
}

/** What the community gallery is showing: nothing asked yet, loading, its Looks, or why it couldn't load. */
private sealed interface GalleryState {
    data object Closed : GalleryState
    data object Loading : GalleryState
    data class Shown(val looks: List<com.prism.music.data.looks.GalleryLook>) : GalleryState
    data class Failed(val why: String) : GalleryState
}

/** Kept while Prism runs, so the list doesn't close every time the page is left. */
private val galleryState = mutableStateOf<GalleryState>(GalleryState.Closed)

/**
 * Looks people shared on GitHub (themes/ in Prism's repository, added by pull request). Nothing is downloaded until
 * the listener asks; a gallery Look opens as a preview first, like any shared one.
 */
@Composable
private fun CommunityGroup(current: String?) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var state by galleryState
    var opening by remember { mutableStateOf<String?>(null) }
    fun load(refresh: Boolean) = scope.launch {
        state = GalleryState.Loading
        state = runCatching { c.lookGallery.looks(refresh) }.fold(
            { GalleryState.Shown(it) },
            { GalleryState.Failed(if (it is java.net.UnknownHostException) "You're offline" else it.message ?: "Couldn't reach GitHub") },
        )
    }
    Group("From the community") {
        when (val s = state) {
            GalleryState.Closed -> Item(
                "Browse community Looks", "Looks people shared on GitHub", icon = PrismIcons.Globe,
                info = "The list is downloaded from Prism's GitHub repository when you open it (GitHub sees the download, nothing else is sent). Each Look shows as a preview first, and can only change how Prism looks.",
                onClick = { load(false) },
            ) { Chevron() }
            GalleryState.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                com.prism.music.ui.components.PrismSpinner(Modifier.size(28.dp))
            }
            is GalleryState.Failed -> Item("Couldn't load the gallery", "${s.why} · tap to try again", onClick = { load(true) })
            is GalleryState.Shown -> {
                if (s.looks.isEmpty()) Note("No community Looks yet. Yours could be the first.")
                else LookGrid(
                    s.looks.map { it.look }, current,
                    onPick = { look ->
                        val g = s.looks.first { it.look.id == look.id }
                        if (opening == null) scope.launch {
                            opening = g.entry.id
                            runCatching { c.lookGallery.fetch(g, c.looks) }
                                .onSuccess { c.looks.pending.value = it }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't download that Look", Toast.LENGTH_SHORT).show() }
                            opening = null
                        }
                    },
                    onMore = null,
                )
                if (opening != null) Note("Downloading its photos…")
                Item("Refresh the list", "From GitHub", onClick = { load(true) })
            }
        }
        Item(
            "Add your Look", "By pull request on GitHub", icon = PrismIcons.OpenInNew,
            info = "Save your Look, share it as text, and add its code to themes/index.json in Prism's repository. The page says how.",
            onClick = { runCatching { uri.openUri(com.prism.music.data.looks.LookGallery.ADD_YOURS) } },
        )
    }
}

/** [looks] two to a row, each a card with the Look on two small phones; [current] is ringed. */
@Composable
private fun LookGrid(looks: List<Look>, current: String?, onPick: (Look) -> Unit, onMore: ((Look) -> Unit)?) {
    val edited by LocalContainer.current.looks.edited.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        looks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { look ->
                    LookCard(look, look.id == current, Modifier.weight(1f), { onPick(look) }, onMore?.let { m -> { m(look) } }, changed = look.id == edited?.id)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A Look as a card: its Home and player, its name and what it is, a ⋯ for more. Tapping it switches to it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LookCard(look: Look, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onMore: (() -> Unit)? = null, changed: Boolean = false) {
    val c = LocalContainer.current
    val app = LocalAppSettings.current
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val s = remember(look, app) { c.looks.preview(look) }
    val song by c.player.currentSong.collectAsState()
    Column(
        modifier.clip(ui.tile).background(frostFill(if (selected) 2.6f else 1f))
            .then(if (selected) Modifier.border(2.dp, PrismTokens.spectrum, ui.tile) else Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.08f), ui.tile))
            .semantics { role = Role.RadioButton; this.selected = selected }
            // A long press opens the card's ⋯ too, as every other card in Prism does.
            .combinedClickable(onClickLabel = "Use ${look.name}", onLongClickLabel = onMore?.let { "More for ${look.name}" }, onLongClick = onMore, onClick = onClick)
            .padding(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = ((maxWidth - 8.dp) / 2).coerceAtMost(80.dp)
            val dark = com.prism.music.ui.theme.isAppDark(s)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewPhone(w) { LookTheme(s, dark) { colors -> MiniHome(colors, song) } }
                PreviewPhone(w) { LookTheme(s, true) { colors -> MiniPlayerScreen(colors, song) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(look.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(PrismIcons.CheckCircle, "In use", Modifier.size(18.dp))
            // Yours, in use with changes not saved into it yet.
            if (changed) Icon(PrismIcons.Edit, "In use, changed since saved", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            if (onMore != null) Icon(
                PrismIcons.More, "More for ${look.name}",
                Modifier.padding(start = 2.dp).size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(6.dp),
                tint = scheme.onSurfaceVariant,
            )
        }
        if (look.note.isNotBlank()) Text(look.note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2, maxLines = 3)
    }
}

/** A Look's ⋯: switch to it, share it, and (yours) rename, copy or delete it; Prism's own can be copied to make your own. */
@Composable
private fun LookActions(look: Look, onDismiss: () -> Unit, onApply: () -> Unit, onShare: () -> Unit, onRename: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    ActionSheet(null, onDismiss) { close ->
        Text(look.name, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
        if (look.note.isNotBlank()) Text(look.note, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val edited by c.looks.edited.collectAsState()
        val changed = edited?.id == look.id
        val changes = remember(look, changed) { if (changed) c.looks.edits(look)?.let { "$it since you saved it" } else c.looks.changes(look) }
        Text(
            changes ?: "You're on it now", Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        )
        val items = listOfNotNull(
            // Yours: save the app's look as it is now into it (your changes since, or any settings you've made since).
            if (!look.builtIn && changes != null) ActionItem(
                PrismIcons.Update, if (changed) "Update with your changes" else "Update to your settings now",
                onClick = { scope.launch { c.looks.update(look) } },
            ) else null,
            ActionItem(PrismIcons.Check, if (changed) "Go back to it as saved" else "Use this Look", onClick = onApply),
            ActionItem(PrismIcons.Share, "Share", onClick = onShare),
            if (!look.builtIn) ActionItem(PrismIcons.Edit, "Rename", onClick = onRename) else null,
            ActionItem(PrismIcons.Copy, if (look.builtIn) "Save a copy to change" else "Duplicate", onClick = {
                scope.launch { c.looks.keep(look, if (look.builtIn) look.name else "${look.name} copy") }
            }),
            if (!look.builtIn) ActionItem(PrismIcons.Delete, "Delete", danger = true, onClick = { c.looks.delete(look.id) }) else null,
        )
        ActionGroup(null, items) { runAction(it, close) }
    }
}

/** A name to type ([initial] picked), for saving or renaming a Look. */
@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.prism.music.ui.components.PrismTextField(
                name, { name = it.take(Look.MAX_NAME) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
            )
        },
        confirmButton = { PrismTextButton({ onDone(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { PrismTextButton(onDismiss) { Text("Cancel") } },
    )
}

/**
 * Sharing a Look: its QR code to scan, its code as text, or a .prismlook file. Codes leave out photos and your own font;
 * a file can carry them, if you tick them.
 */
@Composable
private fun ShareLookSheet(look: Look, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember(look) { c.looks.code(look) }
    val hasPhotos = look.photoAssets.isNotEmpty()
    val hasFont = look.font != null
    var withPhotos by remember { mutableStateOf(hasPhotos) }
    var withFont by remember { mutableStateOf(false) }
    ActionSheet(null, onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Share ${look.name}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(12.dp))
            if (code.length <= LookCode.MAX_QR_CHARS) {
                Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
                    QrCode(code, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Text("Scan it in Prism: Settings → Customize Prism → Looks → Scan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else Text("Too much for a QR code: send it as text or a file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasPhotos || hasFont) Text(
                "Codes leave out photos and your own font; a file can carry them.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton({
                    val text = "Here's my Prism Look, \"${look.name}\". Share this message to Prism to see it:\n\n$code"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send ${look.name}"))
                }) { Text("Send as text") }
                PrismOutlinedButton({
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                val name = look.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "Look" }
                                val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve("$name.${LookStore.FILE_EXTENSION}")
                                file.outputStream().use { c.looks.writeFile(look, it, withPhotos, withFont) }
                                file
                            }
                        }
                        r.onSuccess { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            send.clipData = android.content.ClipData.newRawUri(null, uri)
                            context.startActivity(Intent.createChooser(send, "Send ${look.name}"))
                        }.onFailure { Toast.makeText(context, it.message ?: "Couldn't make the file", Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Send as a file") }
            }
        }
        if (hasPhotos || hasFont) Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            if (hasPhotos) Toggle("Include its photos", "Your background photos go in the file", withPhotos) { withPhotos = it }
            if (hasFont) Toggle("Include its font", "Only if the font's license lets you share it", withFont) { withFont = it }
        }
    }
}

/** A Look someone shared, shown before anything changes: try it, keep it, or not. */
@Composable
private fun PendingLookDialog(look: Look) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember(look) { c.looks.preview(look) }
    val changes = remember(look) { c.looks.changes(look) }
    fun done() { c.looks.pending.value = null }
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text(look.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LookPhones(s, 72.dp, gap = 8.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    (changes?.let { "$it. " } ?: "It looks just like yours now. ") + "A Look only changes how Prism looks, nothing else, and Undo puts yours back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                PrismTextButton({
                    done()
                    scope.launch { c.looks.keep(look); Toast.makeText(context, "Saved to your Looks", Toast.LENGTH_SHORT).show() }
                }) { Text("Save") }
                PrismTextButton({ done(); scope.launch { c.looks.apply(look) } }) { Text("Use it") }
            }
        },
        dismissButton = { PrismTextButton(::done) { Text("Not now") } },
    )
}

/** A Look by day and another by night, switched at sunrise and sunset or at set times. */
@Composable
private fun ScheduleGroup() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val sch by c.looks.schedule.collectAsState()
    val mine by c.looks.mine.collectAsState()
    val all = BuiltInLooks.all + mine
    fun set(s: LookSchedule) = c.looks.setSchedule(s)
    fun time(minutes: Int): String = android.text.format.DateFormat.getTimeFormat(context)
        .format(java.util.Date(java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    fun pickTime(at: Int, done: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> done(h * 60 + m) }, at / 60, at % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    Group("Day and night") {
        Toggle(
            "Day and night Looks", "One Look by day, another by night", sch.on,
            info = "Like Theme: System, for whole Looks. Sunrise and sunset are worked out from your time zone, without your location. A Look you pick in between stays until the next switch.",
        ) { set(sch.copy(on = it)) }
        if (sch.on) {
            Label("By day")
            Chips(all.map { it.id }, sch.day, { id -> all.firstOrNull { it.id == id }?.name ?: id }) { set(sch.copy(day = it)) }
            Label("By night")
            Chips(all.map { it.id }, sch.night, { id -> all.firstOrNull { it.id == id }?.name ?: id }) { set(sch.copy(night = it)) }
            Label("Switch")
            Chips(listOf(true, false), sch.sun, { if (it) "Sunrise & sunset" else "Set times" }) { set(sch.copy(sun = it)) }
            if (!sch.sun) {
                Item("Day starts at", time(sch.dayAt), onClick = { pickTime(sch.dayAt) { set(sch.copy(dayAt = it)) } })
                Item("Night starts at", time(sch.nightAt), onClick = { pickTime(sch.nightAt) { set(sch.copy(nightAt = it)) } })
            }
            val next = remember(sch) { c.looks.nextTurn(sch) }
            val nextLook = all.firstOrNull { it.id == (if (next.to == SunClock.Part.DAY) sch.day else sch.night) }?.name ?: ""
            val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(next.at.toEpochMilli()))
            Note("Next: $nextLook at $at")
        }
    }
}

/** The last changes to the look, newest first: tap one to go back to before it. */
@Composable
private fun HistoryGroup() {
    val c = LocalContainer.current
    val steps by c.looks.steps.collectAsState()
    Group("History") {
        if (steps.isEmpty()) Note("Your last ${LookStore.MAX_STEPS} changes to the look show here, to go back through.")
        steps.forEachIndexed { i, step ->
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(step.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            Item(
                step.label, "$ago · ${if (i == 0) "tap to undo" else "tap to go back to before this"}",
                icon = PrismIcons.Undo, onClick = { c.looks.goBack(i) },
            )
        }
    }
}
