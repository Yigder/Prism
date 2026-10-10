package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.HomeShortcut
import com.prism.music.data.prefs.PinnedPlaylist
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.components.PrismChip
import com.prism.music.ui.components.RoundAction
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

private fun SectionStyle.icon(): ImageVector = when (this) {
    SectionStyle.CAROUSEL -> Icons.Rounded.ViewCarousel
    SectionStyle.GRID -> Icons.Rounded.GridView
    SectionStyle.LIST -> Icons.AutoMirrored.Rounded.ViewList
    SectionStyle.HERO -> Icons.Rounded.ViewDay
}

/**
 * Customize Home: the greeting, the sections Home shows (in order — hold and drag to move — each
 * with a look), the shortcut tiles, and a couple of options. Everything less common sits behind a row's ⋮ menu or a sheet.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CatalogueScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val ytShelves by homeShelfCache.collectAsState()
    val layout = settings.homeLayout
    // Every section Prism can show — built-in and every shelf YouTube Music has offered — that isn't on Home yet.
    val available = (HomeSections.defaults + ytShelves.filterNot { it.title.equals("Quick picks", true) }.map { HomeSectionConfig(shelfKey(it.title), it.title) })
        .filter { cfg -> layout.none { it.key == cfg.key } }
        .distinctBy { it.key }
    var adding by remember { mutableStateOf(false) }
    var pinning by remember { mutableStateOf(false) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOrder by remember { mutableStateOf<List<HomeSectionConfig>?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // The tiles only show while their section is on Home.
    val tilesOn = layout.any { it.key == HomeSections.GREETING && it.visible }
    val currentLayout by rememberUpdatedState(layout)

    fun save(list: List<HomeSectionConfig>) = c.settings.setHomeLayout(list)

    SubPage(
        "Customize Home", bottomPadding,
        horizontalPadding = 16.dp,
        spacing = 22.dp,
        actions = { RoundAction(Icons.Rounded.Restore, "Reset", glass = true) { save(HomeSections.initial) } },
    ) {
        item(key = "greeting") {
            Group("Greeting") {
                GreetingField()
                Toggle("Show your name", null, settings.greetingShowName) { c.settings.setGreetingShowName(it) }
            }
        }
        item(key = "sections") {
            Group("Sections") {
                Note("Hold and drag a section to move it")
                // While a row is held, the list is reordered here and saved when it's let go.
                val rows = dragOrder ?: layout
                val haptics = LocalHapticFeedback.current
                val step = with(LocalDensity.current) { 1.dp.toPx() } // the divider between rows
                rows.forEachIndexed { i, cfg ->
                    key(cfg.key) {
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        val held = dragKey == cfg.key
                        var height by remember { mutableIntStateOf(0) }
                        SectionRow(
                            cfg,
                            canUp = i > 0,
                            canDown = i < rows.lastIndex,
                            onMove = { by -> save(layout.toMutableList().apply { add(i + by, removeAt(i)) }) },
                            onChange = { new -> save(layout.map { if (it.key == cfg.key) new else it }) },
                            onRemove = { save(layout.filterNot { it.key == cfg.key }) },
                            held = held,
                            modifier = Modifier
                                .onSizeChanged { height = it.height }
                                .zIndex(if (held) 1f else 0f)
                                .graphicsLayer {
                                    if (held) { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12.dp.toPx() }
                                }
                                .then(if (held) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
                                .pointerInput(cfg.key) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragOrder = currentLayout; dragKey = cfg.key; dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val list = dragOrder ?: return@detectDragGesturesAfterLongPress
                                            dragOffset += amount.y
                                            val at = list.indexOfFirst { it.key == cfg.key }
                                            val h = height + step
                                            val to = when {
                                                dragOffset > h / 2 && at < list.lastIndex -> at + 1
                                                dragOffset < -h / 2 && at > 0 -> at - 1
                                                else -> at
                                            }
                                            if (to != at) {
                                                dragOrder = list.toMutableList().apply { add(to, removeAt(at)) }
                                                dragOffset -= (to - at) * h
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = { dragOrder?.let { if (it != currentLayout) save(it) }; dragKey = null; dragOrder = null; dragOffset = 0f },
                                        onDragCancel = { dragKey = null; dragOrder = null; dragOffset = 0f },
                                    )
                                },
                        )
                    }
                }
                if (layout.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Add a section", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
        item(key = "tiles") {
            // Greyed out (still editable) while the Shortcut tiles section is hidden or removed.
            Column(Modifier.alpha(if (tilesOn) 1f else 0.45f)) { Group(HomeSections.TILES_TITLE) {
                Note(if (tilesOn) "Shortcuts appear in the order you pick them" else "Show the ${HomeSections.TILES_TITLE} section to see these on Home")
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeShortcut.entries.forEach { sc ->
                        val on = sc in settings.homeShortcuts
                        PrismChip(
                            on, { c.settings.setHomeShortcuts(if (on) settings.homeShortcuts - sc else settings.homeShortcuts + sc) },
                            if (on) "${settings.homeShortcuts.indexOf(sc) + 1}  ${sc.label}" else sc.label,
                        )
                    }
                }
                Item(
                    "Pinned playlists",
                    settings.homePlaylists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.title } ?: "None",
                    onClick = { pinning = true },
                )
                Item("Recently played albums", null, onClick = { c.settings.setHomeRecentTiles((settings.homeRecentTiles + 1) % 7) }) {
                    Stepper(settings.homeRecentTiles, 0..6) { c.settings.setHomeRecentTiles(it) }
                }
            } }
        }
        item(key = "options") {
            Group("Options") {
                Toggle("Add new sections automatically", "New shelves from YouTube Music appear at the bottom", settings.autoAddSections) { c.settings.setAutoAddSections(it) }
                Toggle("Customize button on Home", null, settings.homeCustomizeButton) { c.settings.setHomeCustomizeButton(it) }
            }
        }
    }

    if (adding) AddSectionSheet(available, ytShelves.isEmpty(), { adding = false }) { cfg -> save(layout + cfg.copy(visible = true)) }
    if (pinning) PinPlaylistsSheet { pinning = false }
}

/** One section on Home: tap to show or hide it; ⋮ moves, restyles or removes it. */
@Composable
private fun SectionRow(
    cfg: HomeSectionConfig,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onChange: (HomeSectionConfig) -> Unit,
    onRemove: () -> Unit,
    held: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val fg = if (cfg.visible) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        // No tap while held, so letting go of a drag doesn't also hide the section.
        modifier.fillMaxWidth().clickable(enabled = !held, onClickLabel = if (cfg.visible) "Hide" else "Show") { onChange(cfg.copy(visible = !cfg.visible)) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (cfg.visible) cfg.style.icon() else Icons.Rounded.VisibilityOff, null, Modifier.size(22.dp), tint = if (cfg.visible) scheme.primary else fg)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(cfg.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (cfg.visible) cfg.style.label else "Hidden",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.DragHandle, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant.copy(alpha = 0.6f))
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Rounded.MoreVert, "Options for ${cfg.title}", tint = scheme.onSurfaceVariant)
            DropdownMenu(menu, { menu = false }) {
                SectionStyle.entries.forEach { st ->
                    DropdownMenuItem(
                        { Text(st.label) }, { onChange(cfg.copy(style = st, visible = true)); menu = false },
                        leadingIcon = { Icon(st.icon(), null) },
                        trailingIcon = { if (st == cfg.style) Icon(Icons.Rounded.Check, null) },
                    )
                }
                HorizontalDivider()
                if (canUp) DropdownMenuItem({ Text("Move up") }, { onMove(-1); menu = false }, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) })
                if (canDown) DropdownMenuItem({ Text("Move down") }, { onMove(1); menu = false }, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null) })
                DropdownMenuItem(
                    { Text(if (cfg.visible) "Hide" else "Show") }, { onChange(cfg.copy(visible = !cfg.visible)); menu = false },
                    leadingIcon = { Icon(if (cfg.visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) },
                )
                DropdownMenuItem({ Text("Remove") }, { onRemove(); menu = false }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
            }
        }
    }
}

/** The headline, edited in place; empty follows the time of day. */
@Composable
private fun GreetingField() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    var text by remember { mutableStateOf(settings.greetingText) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Headline", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        BasicTextField(
            text, { text = it; c.settings.setGreetingText(it) },
            Modifier.fillMaxWidth().padding(top = 2.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Good morning / afternoon / evening", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            },
        )
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Remove, "Fewer") }
        Text(if (value == 0) "Off" else "$value", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, "More") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSectionSheet(available: List<HomeSectionConfig>, notLoaded: Boolean, onDismiss: () -> Unit, onAdd: (HomeSectionConfig) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Add a section", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        val (prism, youtube) = available.partition { !it.key.startsWith(HomeSections.YT_PREFIX) }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (prism.isNotEmpty()) item { SheetLabel("From Prism") }
            items(prism, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
            item { SheetLabel("From YouTube Music") }
            if (youtube.isEmpty()) item {
                Text(
                    if (notLoaded) "Open Home once to load YouTube Music's sections" else "Everything's already on Home",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(youtube, key = { it.key }) { cfg -> SheetRow(cfg.title) { onAdd(cfg); onDismiss() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinPlaylistsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val library by c.library.playlists.collectAsState()
    val playlists = remember(library) { library.filterIsInstance<PlaylistItem>() }
    // Pinned ones first (kept even if they've left the library), then the rest of the library.
    val pinnedIds = settings.homePlaylists.map { it.id }
    val options = settings.homePlaylists.map { it.id to it.title } + playlists.filter { it.id !in pinnedIds }.map { it.id to it.title }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Pinned playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "Shown as tiles after the shortcuts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            if (options.isEmpty()) item {
                Text(
                    "Your playlists show up here once your library has loaded", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(options, key = { it.first }) { (id, title) ->
                val on = id in pinnedIds
                SheetRow(title, checked = on) {
                    c.settings.setHomePlaylists(
                        if (on) settings.homePlaylists.filterNot { it.id == id }
                        else settings.homePlaylists + playlists.first { it.id == id }.let { PinnedPlaylist(it.id, it.title, it.thumbnail) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) = com.prism.music.ui.components.Eyebrow(text, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun SheetRow(title: String, checked: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (checked) {
            null -> Icon(Icons.Rounded.Add, "Add", tint = MaterialTheme.colorScheme.primary)
            true -> Icon(Icons.Rounded.Check, "Pinned", tint = MaterialTheme.colorScheme.primary)
            false -> {}
        }
    }
}
