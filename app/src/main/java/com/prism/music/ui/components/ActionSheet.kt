package com.prism.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalIsDark
import com.prism.music.ui.theme.LocalUi

/**
 * One choice in an [ActionSheet], as a tile ([QuickActions]) or a row ([ActionGroup]).
 * [value] is shown on the right of a row (a timer, a percentage); [active] lights it up (a mode
 * that's on); [keepOpen] runs it without closing the sheet; [danger] is for deleting things.
 */
@Immutable
class ActionItem(
    val icon: ImageVector,
    val label: String,
    val value: String? = null,
    val active: Boolean = false,
    val keepOpen: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

fun SheetItem.toAction() = ActionItem(icon, label, danger = danger, onClick = onClick)

/**
 * The menus behind ⋯ and a long press, in the app's frosted look: the sheet wears the
 * artwork ([art]) as frosted glass, as the artist and album pages do, with its accent taken from
 * the picture. [content] gets a `close { … }` that slides the sheet away and then runs the action.
 */
@Composable
fun ActionSheet(
    art: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val settings = LocalAppSettings.current
    val dark = LocalIsDark.current
    val frosted = settings.frostedPages
    val palette = rememberArtPalette(art)
    val frost = rememberFrost(art)
    val scheme = MaterialTheme.colorScheme
    ArtworkAccent(palette) {
        PrismSheet(
            onDismiss,
            containerColor = if (frosted) palette?.settle(dark) ?: scheme.surfaceContainerLow else scheme.surfaceContainerLow,
            contentColor = scheme.onSurface,
            dragHandle = null,
        ) { close ->
            Box {
                if (frosted) FrostedBackdrop(frost, palette, 200.dp, { 0f }, Modifier.matchParentSize(), parallax = 0f)
                CompositionLocalProvider(LocalFrosted provides frosted) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                        }
                        content(close)
                        Spacer(Modifier.height(22.dp))
                    }
                }
            }
        }
    }
}

/** Runs [a]: straight away when it keeps the sheet open, else once the sheet has slid away. */
fun runAction(a: ActionItem, close: (() -> Unit) -> Unit) = if (a.keepOpen) a.onClick() else close(a.onClick)

/** What the sheet is about: its artwork, title and a quiet line, with round [actions] (like, share) on the right. */
@Composable
fun ActionHeader(
    art: String?,
    title: String,
    subtitle: String?,
    shape: Shape = LocalUi.current.art,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(art, Modifier.size(64.dp), shape, size = 544, placeholderIcon = placeholderIcon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round frosted button for the header; [active] fills it with the accent (a liked heart). */
@Composable
fun SheetIconButton(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val edge = if (LocalFrosted.current) Modifier.border(0.7.dp, Color.White.copy(alpha = if (LocalIsDark.current) 0.14f else 0.6f), CircleShape) else Modifier
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (active) scheme.primary else quietFill()).then(edge)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(21.dp), tint = if (active) scheme.onPrimary else scheme.onSurface) }
}

/** The sheet's main actions as a row of frosted tiles, icon over label. */
@Composable
fun QuickActions(items: List<ActionItem>, onPick: (ActionItem) -> Unit) {
    if (items.isEmpty()) return
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { a ->
            val tint = if (a.active) scheme.primary else scheme.onSurface
            Column(
                Modifier.weight(1f).height(74.dp).pane(ui.shape(16.dp)).clickable(onClickLabel = a.label) { onPick(a) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(a.icon, null, Modifier.size(23.dp), tint = tint)
                Spacer(Modifier.height(6.dp))
                Text(
                    a.value ?: a.label, style = MaterialTheme.typography.labelMedium, color = tint,
                    fontWeight = if (a.active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A titled pane of rows, like a settings group. */
@Composable
fun ActionGroup(title: String?, items: List<ActionItem>, onPick: (ActionItem) -> Unit) {
    if (items.isEmpty()) return
    val ui = LocalUi.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        if (title != null) Eyebrow(title, Modifier.padding(start = 8.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().pane(ui.card, MaterialTheme.colorScheme.surfaceContainerHigh)) {
            items.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ActionRow(a) { onPick(a) }
            }
        }
    }
}

@Composable
private fun ActionRow(a: ActionItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = when {
        a.danger -> scheme.error
        else -> scheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            a.icon, null, Modifier.size(22.dp),
            tint = when { a.danger -> scheme.error; a.active -> scheme.primary; else -> scheme.onSurfaceVariant },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (a.value != null) Text(
            a.value, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            color = if (a.active) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
        ) else if (a.active) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = scheme.primary)
    }
}
