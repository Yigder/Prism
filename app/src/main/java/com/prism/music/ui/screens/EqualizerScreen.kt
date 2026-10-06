package com.prism.music.ui.screens

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.EqSettings
import com.prism.music.playback.AudioEffects
import com.prism.music.playback.EqPresets
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

private const val MAX_DB = 12f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val saved = LocalAppSettings.current.eq
    var eq by remember { mutableStateOf(saved) }
    fun update(new: EqSettings) { eq = new; c.settings.setEq(new) }

    com.prism.music.ui.components.SubPage(
        "Equalizer", bottomPadding,
        subtitle = if (eq.enabled) "On · ${eq.preset}" else "Off",
        horizontalPadding = 16.dp, spacing = 22.dp,
        actions = { Switch(eq.enabled, { update(eq.copy(enabled = it)) }) },
    ) {
            item {
                Group("10-band equalizer · ${eq.preset}") {
                    EqGraph(eq, Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 8.dp)) { band, db ->
                        update(eq.copy(enabled = true, preset = "Custom", bands = eq.bands.toMutableList().also { it[band] = db }))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        EqPresets.labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    }
                    com.prism.music.ui.components.ChoiceFlow(
                        EqPresets.presets.toList(), { it.first == eq.preset }, { it.first }, Modifier.padding(12.dp),
                    ) { (name, bands) -> update(eq.copy(enabled = true, preset = name, bands = bands)) }
                }
            }
            item {
                Group("Tone") {
                    SliderRow("Preamp", "%+.1f dB".format(eq.preampDb), eq.preampDb, -12f..6f) { update(eq.copy(enabled = true, preampDb = it)) }
                    SliderRow("Bass boost", "${eq.bassBoost / 10}%", eq.bassBoost.toFloat(), 0f..1000f) { update(eq.copy(enabled = true, bassBoost = it.toInt())) }
                    SliderRow("Loudness", "+%.1f dB".format(eq.loudnessGainMb / 100f), eq.loudnessGainMb.toFloat(), 0f..1500f) { update(eq.copy(enabled = true, loudnessGainMb = it.toInt())) }
                }
            }
            item {
                val s = LocalAppSettings.current
                Group("Prism Spatial") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Software spatial audio", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(s.spatial, { c.settings.setSpatial(it) })
                    }
                    if (s.spatial) SliderRow("Intensity", "${(s.spatialAmount * 100).toInt()}%", s.spatialAmount, 0.1f..1f) { c.settings.setSpatialAmount(it) }
                }
            }
    }
}

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(v, onChange, valueRange = range)
    }
}

/** Draggable 10-band curve. */
@Composable
private fun EqGraph(eq: EqSettings, modifier: Modifier, onBand: (Int, Float) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val alpha = if (eq.enabled) 1f else 0.4f
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width / 10
                fun y(db: Float) = size.height / 2 - (db / MAX_DB) * (size.height / 2 - 16f)
                listOf(-MAX_DB, -6f, 0f, 6f, MAX_DB).forEach { d -> drawLine(grid.copy(alpha = if (d == 0f) 0.9f else 0.35f), Offset(0f, y(d)), Offset(size.width, y(d)), 2f) }
                val pts = eq.bands.mapIndexed { i, db -> Offset(w * i + w / 2, y(db)) }
                val path = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) {
                        val p0 = pts[i - 1]; val p1 = pts[i]
                        val mx = (p0.x + p1.x) / 2
                        cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                    }
                }
                val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f * alpha), tertiary.copy(alpha = 0.02f))))
                drawPath(path, Brush.horizontalGradient(listOf(primary.copy(alpha = alpha), tertiary.copy(alpha = alpha))), style = Stroke(6f, cap = StrokeCap.Round))
                pts.forEach { drawCircle(primary.copy(alpha = alpha), 11f, it); drawCircle(androidx.compose.ui.graphics.Color.White, 5f, it) }
            }
            Row(Modifier.fillMaxSize()) {
                (0 until 10).forEach { band ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pointerInput(band) {
                            detectVerticalDragGestures { change, _ ->
                                val h = size.height.toFloat()
                                val db = ((h / 2 - change.position.y) / (h / 2 - 16f) * MAX_DB).coerceIn(-MAX_DB, MAX_DB)
                                onBand(band, (db * 2).toInt() / 2f)
                            }
                        }
                    )
                }
            }
        }
    }
}
