package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.ChoiceFlow
import com.prism.music.ui.components.Eyebrow
import com.prism.music.ui.components.IconBadge
import com.prism.music.ui.components.Segmented
import com.prism.music.ui.theme.LocalUi

/** A titled card of settings rows. */
@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    Column {
        if (title.isNotBlank()) Eyebrow(title, Modifier.padding(start = 8.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(ui.card).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
                .padding(vertical = ui.gap(6.dp)),
            content = content,
        )
    }
}

/** A small heading inside a group, above a choice. */
@Composable
fun Label(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
    )
}

/** Quiet explanatory text inside a group. */
@Composable
fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = ui.gap(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(uncheckedBorderColor = Color.Transparent, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

@Composable
fun Item(title: String, subtitle: String?, onClick: () -> Unit, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = ui.gap(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** A row that opens another page: coloured badge, title, summary, chevron. */
@Composable
fun NavRow(icon: ImageVector, color: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    val ui = LocalUi.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = ui.gap(11.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

/** One choice among a few: a segmented capsule for up to four short options, wrapping pills otherwise. */
@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    // Roughly what fits across a phone in one capsule without cutting words off.
    val segmented = options.size in 2..4 && options.all { label(it).length <= 12 } && options.sumOf { label(it).length } <= 30
    if (segmented) Segmented(options, selected, label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onSelect)
    else ChoiceFlow(options, { it == selected }, label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onSelect)
}

/** A slider with its title and current value; saves when you let go. */
@Composable
fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    steps: Int = 0,
    enabled: Boolean = true,
    onSave: (Float) -> Unit,
) {
    var v by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { v = value }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(valueText(v), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(v, { v = it }, enabled = enabled, valueRange = range, steps = steps, onValueChangeFinished = { onSave(v) })
    }
}
