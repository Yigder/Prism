package com.prism.music.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.LyricsAhead
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.data.prefs.LyricsBackground
import com.prism.music.data.prefs.LyricsColor
import com.prism.music.data.prefs.LyricsExtraPlace
import com.prism.music.data.prefs.LyricsPosition
import com.prism.music.data.prefs.LyricsStyle
import com.prism.music.data.prefs.LyricsWeight
import com.prism.music.ui.player.LyricsLook
import com.prism.music.ui.player.RestingLine
import com.prism.music.ui.player.SungLine
import com.prism.music.ui.player.cardColor
import com.prism.music.ui.player.extraStyle
import com.prism.music.ui.player.lyricsFill
import com.prism.music.ui.player.rememberLyricsLook
import com.prism.music.ui.player.solid
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

/*
 * Settings → Lyrics, the parts that came with 1.7.5 ("lyrics, your way"): the style the sung line is drawn in, its look,
 * colors, where it sits, translations, and what's behind the lyrics. Every choice shows what it does as a small example,
 * drawn by the same code as the lyrics themselves (always dark, white type), and the preview at the top of the page shows
 * the lyrics as they'll be.
 */

private val SampleDark = Color(0xFF15141B)

/** Prism's own words for the examples, timed word by word: a line sung over three seconds. */
private val SAMPLE_WORDS = listOf("Every ", "color ", "finds ", "its ", "way")
private val SAMPLE_LINE = LyricLine(
    0, SAMPLE_WORDS.joinToString(""), 3000,
    SAMPLE_WORDS.mapIndexed { i, w -> LyricWord(i * 560L, i * 560L + 520L, w) },
)
private val SAMPLE_BEFORE = LyricLine(-1, "Somewhere a light is turning on")
private val SAMPLE_AFTER = LyricLine(-1, "We follow the line of the sound")

/** The examples' clock: round and round the sample line, or held part-way through with Reduce motion. */
@Composable
private fun rememberSampleClock(): State<Long> {
    val still = LocalAppSettings.current.reduceMotion
    if (still) return remember { mutableLongStateOf(1500L) }
    val t by rememberInfiniteTransition(label = "lyricSample").animateFloat(
        -400f, 3600f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "lyricClock",
    )
    return remember { derivedStateOf { t.toLong() } }
}

/** The lyrics' typeface on its weight axis (as the lyrics page uses it), for Prism's sweep in the examples. */
@Composable
private fun sampleAxis(): androidx.compose.ui.text.font.FontFamily? {
    val s = LocalAppSettings.current
    val choice = if (s.lyricsFont.isEmpty()) s.fontChoice else com.prism.music.data.prefs.FontChoice.entries.firstOrNull { it.name == s.lyricsFont }
    return remember(choice) { choice?.let(com.prism.music.ui.theme.AppFonts::axis) }
}

/** The look as the settings have it now, for the song playing (its cover's colors). */
@Composable
private fun currentLook(): LyricsLook {
    val s = LocalAppSettings.current
    return rememberLyricsLook(s, LocalContainer.current.player.currentSong.value?.thumbnail)
}

/**
 * Three lines as the lyrics page draws them in [look]: the one before, the sung one going through its words, the next.
 * [ground] stands in for the lyrics' background ([look]'s card color when it has one).
 */
@Composable
fun LyricsSample(look: LyricsLook, modifier: Modifier = Modifier, ground: Brush? = null, size: Float = 1f, lines: Int = 3, extra: String? = null) {
    val clock = rememberSampleClock()
    val axis = sampleAxis()
    val s = LocalAppSettings.current
    val centred = s.lyricsAlign == LyricsAlign.CENTER
    val font = com.prism.music.ui.theme.LocalLyricsFont.current ?: MaterialTheme.typography.bodyLarge.fontFamily
    val style = TextStyle(
        fontSize = (17 * size * look.sizeScale).sp, lineHeight = (21 * size * look.sizeScale).sp, fontFamily = font,
        fontWeight = FontWeight(look.weight), textAlign = if (centred) TextAlign.Center else TextAlign.Start,
    )
    val bg = ground ?: look.card?.let { SolidColor(it) } ?: Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark))
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((5 * look.spacing).dp)) {
            if (lines >= 3) RestingLine(look.shown(SAMPLE_BEFORE), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
            Column(Modifier.fillMaxWidth()) {
                if (extra != null && look.extraAbove) SampleExtra(extra, style, look)
                SungLine(look.shown(SAMPLE_LINE), clock, style, look, axis, Modifier.fillMaxWidth(), maxLines = 1, onCard = look.card != null)
                if (extra != null && !look.extraAbove) SampleExtra(extra, style, look)
            }
            if (lines >= 2 && look.ahead > 0) RestingLine(look.shown(SAMPLE_AFTER), style, look, 0.8f * look.othersAlpha, Modifier.fillMaxWidth(), maxLines = 1)
        }
    }
}

@Composable
private fun SampleExtra(text: String, style: TextStyle, look: LyricsLook) {
    Text(
        text, style = look.extraStyle(style, 0.6f, 0.66f), color = (if (look.card != null) Color.Black else look.extraColor).copy(alpha = 0.78f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------- Style

@Composable
fun LyricsStyleGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val look = currentLook()
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val palette = com.prism.music.ui.components.rememberArtPalette(cover)
    val cardGround = cardColor(palette?.vibrant ?: palette?.dominant ?: Color(0xFF6B3FA0))
    val styleInfo = when (s.lyricsStyle) {
        LyricsStyle.CARD -> "Bold card: the lyrics sit on a color from the cover, changing with each song."
        LyricsStyle.TYPEWRITER, LyricsStyle.KARAOKE -> "${s.lyricsStyle.label}: lyrics timed by the line, not the word, are filled in at a steady pace through each line."
        else -> null
    }
    Group("Style", info = styleInfo) {
        ChoiceCards(
            LyricsStyle.entries, s.lyricsStyle, title = { it.label }, detail = { it.note },
            onSelect = { prefs.setLyricsStyle(it) },
        ) { style ->
            // Each style as it would draw with the rest of your settings.
            val sample = look.copy(
                style = style,
                card = if (style == LyricsStyle.CARD) cardGround else null,
                others = if (style == LyricsStyle.CARD) Color.Black else look.others,
                othersAlpha = if (style == LyricsStyle.CARD) 0.8f else look.othersAlpha,
                weight = if (style == LyricsStyle.CARD) maxOf(look.weight, 700) else if (look.style == LyricsStyle.CARD) s.lyricsWeight.weight else look.weight,
                sung = look.sung ?: if (style == LyricsStyle.KARAOKE) com.prism.music.ui.theme.PrismTokens.spectrum(true) else null,
            ).let { it.copy(sungWeight = (it.weight + 200).coerceAtMost(900), sungSolid = it.sung.solid()) }
            LyricsSample(sample, Modifier.fillMaxWidth(), size = 0.7f, lines = 2)
        }
        // Why Bold card shows no color with this background: a state, so it stays in view.
        if (s.lyricsStyle == LyricsStyle.CARD && s.lyricsBackground != LyricsBackground.PLAYER && s.lyricsBackground != LyricsBackground.COVER_COLOR)
            Note("Bold card's color shows with the background As now or The cover's color (Background, below); here it keeps its bold type.")
    }
}

// ---------------------------------------------------------------------------- Look

/** The lyrics' look: a live example, then alignment, size, weight, spacing, the other lines, blur and the extras. */
@Composable
fun LyricsLookDetails() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    Label("Alignment")
    Chips(LyricsAlign.entries, s.lyricsAlign, { it.label }) { prefs.setLyricsAlign(it) }
    SliderSetting("Text size", s.lyricsScale, 0.75f..1.4f, { "${(it * 100).roundToInt()}%" }) { prefs.setLyricsScale(it) }
    Label("Weight")
    Chips(LyricsWeight.entries, s.lyricsWeight, { it.label }) { prefs.setLyricsWeight(it) }
    SliderSetting("Line spacing", s.lyricsSpacing, 0.6f..1.8f, { if (it < 0.85f) "Tight" else if (it <= 1.15f) "As usual" else "Airy" }) { prefs.setLyricsSpacing(it) }
    SliderSetting("Other lines", s.lyricsOthersAlpha, 0.2f..1f, { if (it < 0.35f) "Faint" else if (it < 0.75f) "Dimmed" else "Bright" }) { prefs.setLyricsOthersAlpha(it) }
    SliderSetting("Focus blur", s.lyricsBlurAmount, 0f..2f, { if (it < 0.05f) "Off" else "${(it * 100).roundToInt()}%" }) { prefs.setLyricsBlurAmount(if (it < 0.05f) 0f else it) }
    if (android.os.Build.VERSION.SDK_INT < 31 && s.lyricsBlurAmount > 0f) Note("Focus blur needs Android 12 or later; on this phone the other lines are only dimmed.")
    SliderSetting("Background vocals", s.lyricsBackingSize, 0.4f..0.9f, { "${(it * 100).roundToInt()}% of the line" }) { prefs.setLyricsBackingSize(it) }
    Toggle("Progress line", "Split light under the line being sung", s.lyricsProgressLine) { prefs.setLyricsProgressLine(it) }
    Toggle("Shadow", "Helps the words read over photos and moving covers", s.lyricsShadow) { prefs.setLyricsShadow(it) }
    Toggle("Capitals", "EVERY LINE IN CAPITALS", s.lyricsUppercase) { prefs.setLyricsUppercase(it) }
}

// ---------------------------------------------------------------------------- Colors

@Composable
fun LyricsColorsGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    var picking by remember { mutableStateOf<String?>(null) }
    Group("Colors") {
        LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), lines = 2)
        Label("The line being sung")
        LyricsColorSwatches(s.lyricsSungColor, s.lyricsSungCustom, { prefs.setLyricsSungColor(it) }) { picking = "sung" }
        Label("The other lines", info = if (s.lyricsStyle == LyricsStyle.CARD) "On Bold card's color, the lines still to come are dark." else null)
        LyricsColorSwatches(s.lyricsOtherColor, s.lyricsOtherCustom, { prefs.setLyricsOtherColor(it) }, spectrum = false) { picking = "other" }
    }
    picking?.let { which ->
        val sung = which == "sung"
        com.prism.music.ui.components.ColorPickerSheet(
            if (sung) "The line being sung" else "The other lines",
            if (sung) s.lyricsSungCustom else s.lyricsOtherCustom,
            onDismiss = { picking = null },
            preview = { col ->
                val look = currentLook()
                LyricsSample(
                    if (sung) look.copy(sung = SolidColor(com.prism.music.ui.player.readableOnDark(Color(col))), sungSolid = com.prism.music.ui.player.readableOnDark(Color(col)))
                    else look.copy(others = com.prism.music.ui.player.readableOnDark(Color(col))),
                    Modifier.fillMaxWidth(), lines = 2,
                )
            },
        ) { col -> if (sung) prefs.setLyricsSungCustom(col) else prefs.setLyricsOtherCustom(col) }
    }
}

/** Round swatches for a lyrics color, each showing what it is; Your color opens the picker ([onCustom]). */
@Composable
private fun LyricsColorSwatches(selected: LyricsColor, custom: Int, onSelect: (LyricsColor) -> Unit, spectrum: Boolean = true, onCustom: () -> Unit) {
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val options = LyricsColor.entries.filter { spectrum || it != LyricsColor.SPECTRUM }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        options.forEach { o ->
            val fill = lyricsFill(o, custom, cover) ?: SolidColor(Color.White)
            val on = o == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { if (o == LyricsColor.CUSTOM) onCustom() else onSelect(o) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(SampleDark).background(fill, CircleShape)
                        .then(
                            if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    o.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- Placement

@Composable
fun LyricsPlacementGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Where the lines go") {
        ChoiceCards(
            LyricsPosition.entries, s.lyricsPosition, title = { it.label }, detail = { it.note }, columns = 3,
            onSelect = { prefs.setLyricsPosition(it) },
        ) { PositionSample(it.fraction, s.lyricsAhead.lines) }
        Label("Lines to come", info = "How many of the next lines show; the rest appear as the song gets to them.")
        Chips(LyricsAhead.entries, s.lyricsAhead, { it.label }) { prefs.setLyricsAhead(it) }
        Label("The line on the player", info = "The lyric over the progress bar, on the player itself.")
        ChoiceCards(
            listOf(LyricsAlign.START, LyricsAlign.CENTER), s.lyricsStripAlign, title = { if (it == LyricsAlign.START) "Left" else "Centered" },
            detail = { null }, onSelect = { prefs.setLyricsStripAlign(it) },
        ) { StripSample(it == LyricsAlign.CENTER, if (s.lyricsStripTwoLines) 2 else 1) }
        Toggle(
            "Up to two lines", "A long line wraps instead of being cut off", s.lyricsStripTwoLines,
            info = "The line keeps room for two lines all the time, so the player doesn't move as the lines change.",
        ) { prefs.setLyricsStripTwoLines(it) }
    }
}

/** The player's foot as bars: the lyric line ([rows] lines, left or [centered]) over the progress bar. */
@Composable
private fun StripSample(centered: Boolean, rows: Int) {
    val light = Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
            val h = 4.dp.toPx()
            val gap = 5.dp.toPx()
            val bar = size.height - 2.dp.toPx()
            // Progress bar: a third played.
            drawRoundRect(Color.White.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, bar), androidx.compose.ui.geometry.Size(size.width * 0.34f, 2.dp.toPx()))
            // The line, bottom up; a two-line one wraps its end onto the second.
            val widths = if (rows > 1) listOf(0.86f, 0.46f) else listOf(0.58f)
            widths.forEachIndexed { i, f ->
                val w = size.width * f
                val y = bar - 8.dp.toPx() - (widths.size - i) * (h + gap)
                val x = if (centered) (size.width - w) / 2 else 0f
                drawRoundRect(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}

/** A tall dark screen of lyrics as bars: the sung one bright at [fraction] of the way down, then [ahead] lines to come. */
@Composable
private fun PositionSample(fraction: Float, ahead: Int) {
    val light = Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A2140), SampleDark)))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val row = 9.dp.toPx()
            val h = 3.5.dp.toPx()
            val at = 4.dp.toPx() + (size.height - 8.dp.toPx()) * fraction * 1.4f
            val before = (at / row).toInt()
            for (k in -before..10) {
                val y = at + k * row
                if (y < 0 || y > size.height - h) continue
                if (k > 0 && k > ahead) continue
                val w = size.width * (if (k % 2 == 0) 0.82f else 0.64f)
                if (k == 0) drawRoundRect(light, androidx.compose.ui.geometry.Offset(0f, y - 1.dp.toPx()), androidx.compose.ui.geometry.Size(w, h + 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(h))
                else drawRoundRect(Color.White.copy(alpha = if (k < 0) 0.2f else 0.32f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h))
            }
        }
    }
}

// ---------------------------------------------------------------------------- Translation & romanization

@Composable
fun LyricsExtrasGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    Group("Translations & romanized lines", info = "How a translation, or a line spelled out in Latin letters, sits with each line.") {
        LyricsSample(currentLook(), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), lines = 1, extra = "Cada color encuentra su camino")
        Chips(LyricsExtraPlace.entries, s.lyricsExtraPlace, { it.label }) { prefs.setLyricsExtraPlace(it) }
        SliderSetting("Size", s.lyricsExtraSize, 0.7f..1.4f, { "${(it * 100).roundToInt()}%" }) { prefs.setLyricsExtraSize(it) }
        Label("Color", info = if (s.lyricsExtraColor == LyricsColor.CUSTOM) "Your color here is the one picked for the other lines (Colors, above)." else null)
        LyricsColorSwatches(s.lyricsExtraColor, s.lyricsOtherCustom, { prefs.setLyricsExtraColor(it) }) { prefs.setLyricsExtraColor(LyricsColor.CUSTOM) }
        Toggle("Italics", "Sets them apart from the lyrics", s.lyricsExtraItalic) { prefs.setLyricsExtraItalic(it) }
    }
}

// ---------------------------------------------------------------------------- Background

@Composable
fun LyricsBackgroundGroup() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    var picking by remember { mutableStateOf<Int?>(null) }
    Group("Background") {
        Label("Behind the lyrics", info = if (s.lyricsBackground == LyricsBackground.CANVAS) "Animated cover: songs without a moving cover show the cover blurred." else null)
        ChoiceCards(
            LyricsBackground.entries, s.lyricsBackground, title = { it.label }, detail = { if (it == LyricsBackground.PLAYER) "The player's own background" else it.note },
            onSelect = { prefs.setLyricsBackground(it) },
        ) { LyricsSample(currentLook(), Modifier.fillMaxWidth(), ground = backgroundSample(it), size = 0.7f, lines = 2) }
        if (s.lyricsBackground == LyricsBackground.COLOR || s.immersiveBackground == LyricsBackground.COLOR) {
            Label("Your colors", info = "Kept dark enough for white words.")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot("Top", Color(s.lyricsBgColor)) { picking = 1 }
                ColorDot("Bottom", if (s.lyricsBgColor2 == 0) null else Color(s.lyricsBgColor2)) { picking = 2 }
                if (s.lyricsBgColor2 != 0) Text(
                    "One color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { prefs.setLyricsBgColors(s.lyricsBgColor, 0) }.padding(8.dp),
                )
            }
        }
        Label("Immersive mode")
        ChoiceCards(
            LyricsBackground.entries, s.immersiveBackground, title = { if (it == LyricsBackground.PLAYER) "The cover" else it.label },
            detail = { if (it == LyricsBackground.PLAYER) "Filling the screen, as now" else it.note },
            onSelect = { prefs.setImmersiveBackground(it) },
        ) { LyricsSample(currentLook().withoutCard(s.lyricsOthersAlpha), Modifier.fillMaxWidth(), ground = backgroundSample(it, immersive = true), size = 0.7f, lines = 2) }
        Toggle(
            "Same look everywhere", "The current-lyric line and immersive mode follow these settings", s.lyricsEverywhere,
            info = "On, the line above the progress bar and immersive mode draw the lyrics in the style, colors, weight and capitals picked here. Off, they keep Prism's own look.",
        ) { prefs.setLyricsEverywhere(it) }
    }
    picking?.let { which ->
        val prefsNow = LocalAppSettings.current
        com.prism.music.ui.components.ColorPickerSheet(
            if (which == 1) "Top color" else "Bottom color",
            if (which == 1) prefsNow.lyricsBgColor else prefsNow.lyricsBgColor2.takeIf { it != 0 } ?: prefsNow.lyricsBgColor,
            onDismiss = { picking = null },
            reset = if (which == 2 && prefsNow.lyricsBgColor2 != 0) "One color" to { prefs.setLyricsBgColors(prefsNow.lyricsBgColor, 0) } else null,
            preview = { col ->
                val top = cardColor(Color(if (which == 1) col else prefsNow.lyricsBgColor))
                val bottom = if (which == 2) cardColor(Color(col)) else if (prefsNow.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(prefsNow.lyricsBgColor2))
                LyricsSample(currentLook(), Modifier.fillMaxWidth(), ground = Brush.verticalGradient(listOf(top, bottom)), lines = 2)
            },
        ) { col -> if (which == 1) prefs.setLyricsBgColors(col, prefsNow.lyricsBgColor2) else prefs.setLyricsBgColors(prefsNow.lyricsBgColor, col) }
    }
}

/** A round swatch with its name; empty ([color] null) for a color not picked yet. */
@Composable
private fun ColorDot(label: String, color: Color?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(color ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (color == null) Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What each background looks like, drawn small (the cover's colors from the song playing, or Prism's own). */
@Composable
private fun backgroundSample(kind: LyricsBackground, immersive: Boolean = false): Brush {
    val s = LocalAppSettings.current
    val cover = LocalContainer.current.player.currentSong.value?.thumbnail
    val palette = com.prism.music.ui.components.rememberArtPalette(cover)
    val a = palette?.vibrant ?: Color(0xFF8A6BFF)
    val b = palette?.dominant ?: Color(0xFFFF7A59)
    val dim = { c: Color -> androidx.compose.ui.graphics.lerp(c, Color.Black, 0.55f) }
    return when (kind) {
        LyricsBackground.PLAYER -> if (immersive) Brush.linearGradient(listOf(a, b)) else Brush.verticalGradient(listOf(dim(a), Color(0xFF15141B)))
        LyricsBackground.DARK -> SolidColor(Color(0xFF0C0C10))
        LyricsBackground.BLUR -> Brush.radialGradient(listOf(dim(a), dim(b), Color(0xFF101014)))
        LyricsBackground.CANVAS -> Brush.linearGradient(listOf(dim(b), dim(a), dim(b)))
        LyricsBackground.COVER_COLOR -> cardColor(a).let { Brush.verticalGradient(listOf(it, androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f))) }
        LyricsBackground.COLOR -> cardColor(Color(s.lyricsBgColor)).let { top ->
            Brush.verticalGradient(listOf(top, if (s.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(s.lyricsBgColor2))))
        }
    }
}
