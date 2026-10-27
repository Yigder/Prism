package com.prism.music.ui.screens

import com.prism.music.ui.components.PrismSlider
import com.prism.music.ui.components.PrismSwitch
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.Backdrop
import com.prism.music.ui.theme.BackdropLayer
import com.prism.music.ui.theme.BackdropScreen
import com.prism.music.ui.theme.screensOnly
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Appearance → Backgrounds: a background for each main screen, ready-made or your own photo. [forPlayer]
 * (1.7.4, Settings → Player → Background → A background): the same choices for the player alone, drawn there dark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundsScreen(bottomPadding: Dp, forPlayer: Boolean = false) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // What the listener picked, also while Performance mode draws plain pages.
    val s by c.settings.stored.collectAsState()
    var screen by rememberSaveable { mutableStateOf(BackdropScreen.HOME) }
    var everywhere by rememberSaveable { mutableStateOf(!forPlayer && s.backdrops.screensOnly.values.toSet().size <= 1) }
    var dim by remember { mutableFloatStateOf(s.backdropDim) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val current = if (forPlayer) s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] ?: s.backdrops[BackdropScreen.HOME.name] else s.backdrops[screen.name]
    val (currentStyle, currentPhoto) = Backdrop.parse(current)

    fun apply(value: String?) {
        val map = s.backdrops.toMutableMap()
        if (forPlayer) {
            // "Ambient" for the player is kept as such (missing would mean "as Home").
            map[com.prism.music.ui.theme.PLAYER_BACKDROP] = value ?: Backdrop.DEFAULT.name
        } else {
            val targets = if (everywhere) BackdropScreen.entries else listOf(screen)
            targets.forEach { t -> if (value == null) map.remove(t.name) else map[t.name] = value }
        }
        c.settings.setBackdrops(map)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = if (forPlayer) "player" else if (everywhere) "all" else screen.name.lowercase()
            val file = withContext(Dispatchers.IO) { savePhoto(context, uri, name) }
            if (file != null) apply("PHOTO|${file.absolutePath}")
        }
    }

    val ui = com.prism.music.ui.theme.LocalUi.current
    com.prism.music.ui.components.SubPage(
        if (forPlayer) "Player background" else "Backgrounds", bottomPadding,
        subtitle = if (forPlayer) "Behind the player, darkened so its white text reads" else "Color from what's playing, ready-made designs or your own photo",
        horizontalPadding = 16.dp, spacing = 12.dp,
    ) {
            if (forPlayer) item {
                val own = s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP]
                Column(Modifier.pane(ui.card, MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable {
                        val map = s.backdrops.toMutableMap()
                        if (own == null) map[com.prism.music.ui.theme.PLAYER_BACKDROP] = s.backdrops[BackdropScreen.HOME.name] ?: Backdrop.DEFAULT.name
                        else map.remove(com.prism.music.ui.theme.PLAYER_BACKDROP)
                        c.settings.setBackdrops(map)
                    }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Same as Home", style = MaterialTheme.typography.bodyLarge)
                            Text("The player follows Home's background", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PrismSwitch(own == null, null)
                    }
                }
            }
            if (!forPlayer) item {
                Column(Modifier.pane(ui.card, MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { everywhere = !everywhere }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Same on every screen", style = MaterialTheme.typography.bodyLarge)
                            Text("Home, Search, Library, Replay and Settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PrismSwitch(everywhere, { everywhere = it })
                    }
                    if (!everywhere) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems(BackdropScreen.entries) { sc ->
                            val (st, _) = Backdrop.parse(s.backdrops[sc.name])
                            com.prism.music.ui.components.PrismChip(sc == screen, { screen = sc }, if (st == Backdrop.DEFAULT) sc.label else "${sc.label} · ${st.label}")
                        }
                    }
                }
            }
            item {
                com.prism.music.ui.components.Eyebrow(
                    if (forPlayer) "Background for the player" else if (everywhere) "Choose a background" else "Background for ${screen.label}",
                    Modifier.padding(start = 8.dp, top = 10.dp),
                )
            }
            com.prism.music.ui.theme.BackdropGroup.entries.forEach { group ->
            if (group != com.prism.music.ui.theme.BackdropGroup.YOURS) item(key = "g:${group.name}") {
                Column(Modifier.padding(start = 8.dp, top = 8.dp)) {
                    Text(group.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (group) {
                            com.prism.music.ui.theme.BackdropGroup.COLOR -> "Glowing in dark mode, soft pastels in light mode"
                            com.prism.music.ui.theme.BackdropGroup.DESIGNS -> "Detailed scenes that fade away behind the text"
                            else -> "Quiet patterns in your accent color"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Backdrop.entries.filter { it.group == group }.chunked(3).forEachIndexed { r, row -> item(key = "bg:${group.name}:$r") {
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              row.forEach { style ->
                val selected = style == currentStyle && (!forPlayer || s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] != null)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.62f).clip(ui.card)
                            // The picked one is ringed in split light.
                            .then(
                                if (selected) Modifier.border(2.5.dp, com.prism.music.ui.theme.PrismTokens.spectrum, ui.card)
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), ui.card)
                            )
                            .clickable {
                                when (style) {
                                    Backdrop.DEFAULT -> apply(null)
                                    Backdrop.PHOTO -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    else -> apply(style.name)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val photo = if (style == Backdrop.PHOTO) currentPhoto ?: s.backdrops.values.firstNotNullOfOrNull { Backdrop.parse(it).second } else null
                        BackdropLayer(style, photo, s.backdropDim, motion = false, modifier = Modifier.fillMaxSize())
                        // A hint of the screen on top, so the wash and contrast read true.
                        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth(0.6f).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)))
                            // Glass panels, as the screens draw them over any background.
                            repeat(3) { Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp)).background(com.prism.music.ui.components.frostFill(1.2f))) }
                        }
                        if (style == Backdrop.PHOTO && photo == null) Icon(PrismIcons.Photo, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurface)
                        if (selected) Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(com.prism.music.ui.theme.PrismTokens.spectrumRing()),
                            contentAlignment = Alignment.Center,
                        ) { Icon(PrismIcons.Check, null, Modifier.size(15.dp), tint = Color.White) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(style.label, style = MaterialTheme.typography.labelLarge)
                }
              }
              repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
              }
            } }
            }
            if (!forPlayer) item {
                Column(Modifier.padding(top = 8.dp).pane(ui.card, MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                    Text("Softness", style = MaterialTheme.typography.bodyLarge)
                    Text("How much the theme color washes over the background, for easier reading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrismSlider(dim, { dim = it }, valueRange = 0f..0.8f, onValueChangeFinished = { c.settings.setBackdropDim(dim) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Gentle motion", style = MaterialTheme.typography.bodyLarge)
                            Text("Color fields drift, waves roll and ripples spread, slowly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PrismSwitch(s.backdropMotion, { c.settings.setBackdropMotion(it) })
                    }
                    if (s.backdrops.isNotEmpty()) PrismTextButton(onClick = { c.settings.setBackdrops(emptyMap()) }, Modifier.align(Alignment.End)) { Text("Reset all to Ambient") }
                }
            }
    }
}

/** Copies a picked photo into the app (scaled to at most 1600 px), so it survives the original being moved. */
internal fun savePhoto(context: android.content.Context, uri: Uri, name: String): File? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val dir = File(context.filesDir, "backdrops").apply { mkdirs() }
    // A fresh name each time, so the new picture isn't served from the image cache.
    dir.listFiles { f -> f.name.startsWith("$name-") }?.forEach { it.delete() }
    File(dir, "$name-${System.currentTimeMillis()}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
}.getOrNull()
