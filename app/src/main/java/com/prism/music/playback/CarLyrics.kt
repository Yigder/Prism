package com.prism.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.AppContainer
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.CarLyricsStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * What the media session (and so Android Auto) sees of the player: the real player, except that
 * while lyrics are showing, the current song's title and cover are swapped for the lyric.
 * The app itself talks to the real player and never sees the swap.
 */
@OptIn(UnstableApi::class)
class LyricsOverlayPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    class Overlay(val mediaId: String, val title: String, val artist: String, val artwork: ByteArray?)

    private var overlay: Overlay? = null
    private val window = Timeline.Window()

    fun show(o: Overlay?) {
        if (o == null && overlay == null) return
        overlay = o
        invalidateState()
    }

    override fun getState(): State {
        val s = super.getState()
        val o = overlay ?: return s
        val index = s.currentMediaItemIndex
        if (s.timeline.isEmpty || index !in 0 until s.timeline.windowCount) return s
        if (s.timeline.getWindow(index, window).mediaItem.mediaId != o.mediaId) return s
        val meta = s.currentMetadata.buildUpon()
            .setTitle(o.title)
            .setDisplayTitle(o.title)
            .setArtist(o.artist)
            .apply { if (o.artwork != null) { setArtworkData(o.artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER); setArtworkUri(null) } }
            .build()
        return s.buildUpon().setPlaylist(s.timeline, s.currentTracks, meta).build()
    }
}

/**
 * Lyrics on Android Auto's player. The car only shows a title, an artist and a cover, so the line
 * being sung becomes the title and the cover becomes a lyric card — in karaoke style, each word
 * lights up as it's sung (word-timed lyrics are looked for first).
 */
class CarLyrics(
    private val context: Context,
    private val c: AppContainer,
    private val player: Player,
    private val overlay: LyricsOverlayPlayer,
    private val scope: CoroutineScope,
) {
    /** Android Auto is connected to Prism right now. */
    var carConnected = false
        set(v) { if (field != v) { field = v; refresh() } }

    val active: Boolean get() = c.settings.current.let { it.carLyrics && (carConnected || it.carLyricsOnPhone) }

    private var songId: String? = null
    private var song: Song? = null
    private var lyrics: Lyrics? = null
    private var background: Bitmap? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var shown: Triple<Int, Int, CarLyricsStyle>? = null
    private var lastArtAt = 0L

    /** Call when the song, the settings, the car connection or play/pause changes. */
    fun refresh() {
        if (!active) {
            tickJob?.cancel(); tickJob = null
            overlay.show(null); shown = null
            return
        }
        val current = player.currentMediaItem?.toSong()
        if (current == null) { overlay.show(null); shown = null; return }
        if (current.id != songId) load(current)
        shown = null
        if (tickJob?.isActive != true) tickJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(if (player.isPlaying) 90 else 600)
            }
        }
    }

    private fun load(s: Song) {
        songId = s.id; song = s; lyrics = null; background = null; shown = null
        overlay.show(null)
        loadJob?.cancel()
        loadJob = scope.launch {
            val first = withContext(Dispatchers.IO) { runCatching { c.lyrics.auto(s) }.getOrNull() }
            if (songId != s.id) return@launch
            lyrics = first?.takeIf { it.synced }
            background = withContext(Dispatchers.IO) { CarLyricsCard.background(context, s.thumbnail) }
            shown = null
            // Karaoke first: if this song's lyrics are only line by line, look for word-timed ones elsewhere.
            if (first?.wordSynced != true && c.settings.current.carLyricsStyle == CarLyricsStyle.KARAOKE) {
                val better = withContext(Dispatchers.IO) { runCatching { c.lyrics.download(s) }.getOrNull() }
                if (songId == s.id && better != null && (better.wordSynced || lyrics == null) && better.synced) { lyrics = better; shown = null }
            }
        }
    }

    private suspend fun tick() {
        val s = song ?: return
        val l = lyrics
        val style = c.settings.current.carLyricsStyle
        if (l == null || player.currentMediaItem?.mediaId != s.id) { clear(); return }
        val pos = c.player.lyricPosition
        val lines = l.lines
        val idx = CarLyricsCard.lineAt(lines, pos)
        val line = lines.getOrNull(idx)
        if (line == null || line.isGap || line.text.isBlank()) { clear(); return }
        // Long pause after a line: back to the song's own title and cover until the next one.
        val next = lines.drop(idx + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }
        if (line.endMs > 0 && pos > line.endMs + 1_500 && (next == null || next.timeMs - pos > 2_500)) { clear(); return }
        val sung = if (style == CarLyricsStyle.KARAOKE && line.isWordSynced) line.words.count { it.startMs <= pos } else -1
        val key = Triple(idx, sung, style)
        if (key == shown) return
        val now = System.currentTimeMillis()
        val sameLine = shown?.first == idx && shown?.third == style
        // Word steps are spaced out a little: each one sends a new picture to the car.
        if (sameLine && now - lastArtAt < 280) return
        val art = if (style == CarLyricsStyle.TEXT) null else withContext(Dispatchers.Default) {
            CarLyricsCard.jpeg(CarLyricsCard.render(background, lines, idx, sung))
        }
        if (songId != s.id) return
        lastArtAt = now
        shown = key
        overlay.show(LyricsOverlayPlayer.Overlay(s.id, line.text, "${s.title} · ${s.artistText}", art))
    }

    private fun clear() {
        if (shown == null) return
        shown = null
        overlay.show(null)
    }
}

/** Draws the lyric card the car shows in place of the cover. */
object CarLyricsCard {
    const val SIZE = 720

    fun lineAt(lines: List<LyricLine>, ms: Long): Int {
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs in 0..ms) idx = i else if (lines[i].timeMs > ms) break
        return idx
    }

    /** The song's cover, blurred and darkened, for the card to sit on. Null falls back to a gradient. */
    suspend fun background(context: Context, thumbnail: String?): Bitmap? {
        val url = hiRes(thumbnail, 226) ?: return null
        val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return null
        // A cheap, smooth blur: down to a few pixels, then back up with filtering.
        val tiny = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        val mid = Bitmap.createScaledBitmap(tiny, 90, 90, true)
        return Bitmap.createScaledBitmap(mid, SIZE, SIZE, true)
    }

    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.04f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /**
     * The card: the previous line faint above, the current line large (sung words bright, the rest dim
     * when [sung] ≥ 0), and the next line below.
     */
    fun render(background: Bitmap?, lines: List<LyricLine>, index: Int, sung: Int): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (background != null) canvas.drawBitmap(background, 0f, 0f, null)
        else canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), 0xFF2A1B5C.toInt(), 0xFF0E0E14.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawColor(Color.argb(120, 0, 0, 0))
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP) })

        val margin = 56
        val width = SIZE - margin * 2
        val line = lines[index]
        val prev = lines.take(index).lastOrNull { !it.isGap && it.text.isNotBlank() }?.text
        val next = lines.drop(index + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }?.text

        // Current line, as large as fits in four lines.
        val text: CharSequence = if (sung >= 0 && line.words.isNotEmpty()) {
            val full = line.words.joinToString("") { it.text }.trimEnd()
            val sungLen = line.words.take(sung).joinToString("") { it.text }.length.coerceAtMost(full.length)
            SpannableString(full).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, sungLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.argb(105, 255, 255, 255)), sungLen, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else line.text
        val main = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; color = Color.WHITE }
        var size = 74f
        var current: StaticLayout
        while (true) {
            main.textSize = size
            current = layout(text, main, width, 4)
            if ((current.lineCount <= 3 && current.height <= SIZE * 0.5f) || size <= 40f) break
            size -= 4f
        }

        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 34f }
        val prevLayout = prev?.let { layout(it, small.apply { color = Color.argb(95, 255, 255, 255) }, width, 2) }
        val nextPaint = TextPaint(small).apply { color = Color.argb(150, 255, 255, 255); textSize = 38f; typeface = bold }
        val nextLayout = next?.let { layout(it, nextPaint, width, 2) }

        val gap = 30
        val total = (prevLayout?.height?.plus(gap) ?: 0) + current.height + (nextLayout?.height?.plus(gap) ?: 0)
        var y = ((SIZE - total) / 2f).coerceAtLeast(40f)
        fun draw(l: StaticLayout) {
            canvas.save(); canvas.translate(margin.toFloat(), y); l.draw(canvas); canvas.restore()
            y += l.height + gap
        }
        prevLayout?.let(::draw)
        draw(current)
        nextLayout?.let(::draw)
        return out
    }

    fun jpeg(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
        out.toByteArray()
    }
}
