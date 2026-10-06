package com.prism.music.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections
import com.prism.music.data.prefs.HomeShortcut
import com.prism.music.data.prefs.SectionStyle
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

private fun SectionStyle.icon(): ImageVector = when (this) {
    SectionStyle.CAROUSEL -> Icons.Rounded.ViewCarousel
    SectionStyle.GRID -> Icons.Rounded.GridView
    SectionStyle.LIST -> Icons.AutoMirrored.Rounded.ViewList
    SectionStyle.HERO -> Icons.Rounded.ViewDay
}

/**
 * Home "catalogue": every section Prism can show — built-in and every shelf
 * YouTube Music has offered — which you add, hide, restyle and reorder.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CatalogueScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val ytShelves by homeShelfCache.collectAsState()
    val layout = settings.homeLayout
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val builtIns = HomeSections.defaults
    val available = (builtIns + ytShelves.filterNot { it.title.equals("Quick picks", true) }.map { HomeSectionConfig(shelfKey(it.title), it.title) })
        .filter { cfg -> layout.none { it.key == cfg.key } }
        .distinctBy { it.key }

    fun save(list: List<HomeSectionConfig>) = c.settings.setHomeLayout(list)

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text("Customize Home") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { save(HomeSections.defaults) }) { Icon(Icons.Rounded.Restore, "Reset") } },
            scrollBehavior = scroll,
        )
        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-add new YouTube sections", style = MaterialTheme.typography.titleSmall)
                        Text("New shelves from your YouTube Music home appear at the bottom", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.autoAddSections, { c.settings.setAutoAddSections(it) })
                }
            }
            item { CatalogueHeader("Greeting & shortcuts", "The headline at the top of Home and the tiles under it") }
            item { GreetingEditor() }
            item { CatalogueHeader("On your Home", "${layout.count { it.visible }} sections · tap a style to change how it looks") }
            itemsIndexed(layout, key = { _, s -> s.key }) { i, cfg ->
                LayoutRow(
                    cfg = cfg,
                    canUp = i > 0,
                    canDown = i < layout.lastIndex,
                    onUp = { save(layout.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                    onDown = { save(layout.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                    onToggle = { v -> save(layout.map { if (it.key == cfg.key) it.copy(visible = v) else it }) },
                    onStyle = { st -> save(layout.map { if (it.key == cfg.key) it.copy(style = st) else it }) },
                    onRemove = { save(layout.filterNot { it.key == cfg.key }) },
                )
            }
            item { CatalogueHeader("Catalogue", if (ytShelves.isEmpty()) "Open Home once to load sections from YouTube Music" else "Tap + to add a section to your Home") }
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp).animateContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    available.forEach { cfg ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { save(layout + cfg.copy(visible = true)) }
                                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!cfg.key.startsWith(HomeSections.YT_PREFIX)) {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(cfg.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Add, "Add", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GreetingEditor() {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    var text by remember { mutableStateOf(settings.greetingText) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            text, { text = it; c.settings.setGreetingText(it) },
            Modifier.fillMaxWidth(),
            label = { Text("Greeting") },
            placeholder = { Text("Good morning / afternoon / evening") },
            supportingText = { Text("Leave empty for a greeting that follows the time of day") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show account name", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(settings.greetingShowName, { c.settings.setGreetingShowName(it) })
        }
        Text("Shortcuts · tap to add or remove, in the order you pick them", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeShortcut.entries.forEach { sc ->
                val on = sc in settings.homeShortcuts
                FilterChip(
                    on,
                    { c.settings.setHomeShortcuts(if (on) settings.homeShortcuts - sc else settings.homeShortcuts + sc) },
                    { Text(if (on) "${settings.homeShortcuts.indexOf(sc) + 1}. ${sc.label}" else sc.label) },
                )
            }
        }
        val library by c.library.playlists.collectAsState()
        val playlists = remember(library) { library.filterIsInstance<com.prism.music.data.model.PlaylistItem>() }
        Text("Playlists · tap to pin to Home", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
        if (playlists.isEmpty() && settings.homePlaylists.isEmpty()) Text(
            "Your playlists show up here once your library has loaded",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Pinned ones first (kept even if they've left the library), then the rest of the library.
            val pinnedIds = settings.homePlaylists.map { it.id }
            val options = settings.homePlaylists.map { it.id to it.title } +
                playlists.filter { it.id !in pinnedIds }.map { it.id to it.title }
            options.forEach { (id, title) ->
                val on = id in pinnedIds
                FilterChip(
                    on,
                    {
                        c.settings.setHomePlaylists(
                            if (on) settings.homePlaylists.filterNot { it.id == id }
                            else settings.homePlaylists + playlists.first { it.id == id }.let { com.prism.music.data.prefs.PinnedPlaylist(it.id, it.title, it.thumbnail) }
                        )
                    },
                    { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Text("Recently played tiles", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { n ->
                FilterChip(settings.homeRecentTiles == n, { c.settings.setHomeRecentTiles(n) }, { Text(if (n == 0) "None" else "$n") })
            }
        }
    }
}

@Composable
private fun CatalogueHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LayoutRow(
    cfg: HomeSectionConfig,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onStyle: (SectionStyle) -> Unit,
    onRemove: () -> Unit,
) {
    var styleMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(cfg.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (cfg.key.startsWith(HomeSections.YT_PREFIX)) "From YouTube Music" else "Prism",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { styleMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(cfg.style.icon(), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(4.dp))
                Text(cfg.style.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            DropdownMenu(styleMenu, { styleMenu = false }) {
                SectionStyle.entries.forEach { st ->
                    DropdownMenuItem({ Text(st.label) }, { onStyle(st); styleMenu = false }, leadingIcon = { Icon(st.icon(), null) })
                }
            }
        }
        Switch(cfg.visible, onToggle, Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(2.dp))
}
