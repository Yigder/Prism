package com.prism.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.prism.music.data.ModelStore
import com.prism.music.data.lyrics.RubySpan
import com.prism.music.data.lyrics.hasKanji
import com.prism.music.data.lyrics.roman.Script
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

/** How big a reading is against the line it sits over, and how tall its row is. */
private const val RUBY_SCALE = 0.45f
private const val RUBY_LINE = 0.85f
/** How much extra room a furigana line needs between its rows. */
private const val RUBY_ROOM = 0.7f

/**
 * Each line's kanji and its hiragana reading, line for line, when furigana is on and this is a Japanese song with the
 * Japanese reading data on the phone; null otherwise.
 */
@Composable
fun rememberFurigana(state: LyricsState): List<List<RubySpan>>? {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val lines = state.lines
    if (!settings.lyricsFurigana || lines.isEmpty()) return null
    // A song is Japanese as the romanizer decides it: any kana in its title or words (a line of only kanji alone looks
    // Chinese).
    val japanese = remember(state.song.id, state.song.title, lines) {
        Script.KANA in Script.foreignIn(state.song.title) || lines.any { Script.KANA in Script.foreignIn(it.text) }
    }
    if (!japanese || !lines.any { hasKanji(it.text) }) return null
    val models by c.models.states.collectAsState()
    val ready = models[ModelStore.Model.JAPANESE] == ModelStore.State.Ready
    val ruby by produceState<List<List<RubySpan>>?>(null, state.song.id, lines, ready) {
        value = if (!ready) null else runCatching { c.romanizer.furigana(lines) }
            .onFailure { android.util.Log.w("PrismFurigana", "Couldn't read the kanji: $it") }
            .getOrNull()
    }
    return ruby
}

/** [style] with room above its rows for furigana (wrapped lines need it too). */
internal fun TextStyle.withRubyRoom(): TextStyle {
    if (!fontSize.isSp || !lineHeight.isSp) return this
    val extra = fontSize.value * RUBY_SCALE * RUBY_ROOM
    return copy(
        lineHeight = (lineHeight.value + extra).sp,
        // The extra room goes between rows; the last row doesn't need it below it.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.LastLineBottom),
    )
}

/**
 * Draws [ruby] above the text [content] draws, using [layout] (that text's own layout) to line each reading up over its
 * kanji. The room above comes from a matching top padding, so nothing else moves.
 */
@Composable
internal fun RubyOverlay(
    ruby: List<RubySpan>?,
    style: TextStyle,
    layout: TextLayoutResult?,
    modifier: Modifier,
    color: Color,
    content: @Composable (Modifier) -> Unit,
) {
    if (ruby.isNullOrEmpty()) { content(modifier); return }
    val density = LocalDensity.current
    val rubySize = style.fontSize * RUBY_SCALE
    val pad = with(density) { (rubySize * RUBY_LINE).toDp() }
    val measurer = rememberTextMeasurer()
    val rubyStyle = remember(style, rubySize) {
        style.copy(
            fontSize = rubySize, lineHeight = rubySize * RUBY_LINE, fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp, textAlign = TextAlign.Start, textDirection = TextDirection.Ltr,
        )
    }
    // Each reading, split where its kanji wrap onto another row, measured once.
    val pieces = remember(layout, ruby) { layout?.let { rubyPieces(it, ruby) }.orEmpty() }
    val measured = remember(pieces, rubyStyle) { pieces.map { it to measurer.measure(AnnotatedString(it.reading), rubyStyle, maxLines = 1) } }
    Box(modifier, propagateMinConstraints = true) {
        content(modifier.padding(top = pad))
        val l = layout ?: return@Box
        Canvas(Modifier.matchParentSize()) {
            val room = pad.toPx()
            for ((piece, reading) in measured) drawRuby(l, piece, reading, room, color)
        }
    }
}

/** A plain line of text with [ruby] above it (for lines drawn without [SungLine]'s animations). */
@Composable
internal fun TextWithFurigana(
    text: String,
    ruby: List<RubySpan>?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val st = if (!ruby.isNullOrEmpty()) style.withRubyRoom() else style
    RubyOverlay(ruby, st, layout, modifier, color) { m ->
        Text(text, m, style = st, color = color, maxLines = maxLines, overflow = overflow, onTextLayout = { layout = it })
    }
}

/** A translation of one [RubySpan] for one row of a wrapped line. */
private data class RubyPiece(val reading: String, val start: Int, val end: Int)

private fun rubyPieces(layout: TextLayoutResult, ruby: List<RubySpan>): List<RubyPiece> {
    val textLength = layout.layoutInput.text.length
    if (textLength <= 0) return emptyList()
    val out = ArrayList<RubyPiece>(ruby.size)
    for (span in ruby) {
        if (span.reading.isEmpty()) continue
        val start = span.start.coerceIn(0, textLength - 1)
        val end = (span.end - 1).coerceIn(start, textLength - 1)
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(end)
        if (first == last) {
            out += RubyPiece(span.reading, start, end)
            continue
        }
        // A long run wrapped over more than one row: its reading is shared out by the kanji on each row.
        val total = end - start + 1
        for (row in first..last) {
            val from = maxOf(start, layout.getLineStart(row))
            val to = minOf(end, layout.getLineEnd(row, visibleEnd = true) - 1)
            if (from > to) continue
            val a = span.reading.length * (from - start) / total
            val b = span.reading.length * (to - start + 1) / total
            if (b <= a) continue
            out += RubyPiece(span.reading.substring(a, b), from, to)
        }
    }
    return out
}

private fun DrawScope.drawRuby(layout: TextLayoutResult, piece: RubyPiece, reading: TextLayoutResult, room: Float, color: Color) {
    val first = layout.getBoundingBox(piece.start)
    val last = layout.getBoundingBox(piece.end)
    val center = (first.left + last.right) / 2f
    val w = reading.size.width
    val x = (center - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    // Above the row's letters; [room] is the padding kept above the text for it.
    val y = (room + first.top - reading.size.height).coerceAtLeast(0f)
    drawText(reading, color = color, topLeft = Offset(x, y))
}
