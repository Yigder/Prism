package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import java.util.Locale

/**
 * The "Canvas" lines in Share diagnostics (1.8.6, plan-canvas 4.8), so a report like #446 can be read: the setting,
 * what shows for the song playing, and when it has no loop from its music video, why. English, like the rest of that
 * text. No titles: the video id is the most it names. Nothing here reaches the network.
 *
 * [stored] is the setting as the listener set it, [used] as Prism uses it ([AppSettings.effective]). [cover] is the
 * song's animated cover as far as it is known on the phone, [row] its `canvas_clips` row, [kept] the loop files kept for
 * the row's video.
 */
fun canvasDiagnostics(
    stored: AppSettings, used: AppSettings, metered: Boolean, videoMode: Boolean,
    song: Song?, hidden: Boolean, cover: CanvasArtwork?, row: CanvasClipEntity?, kept: List<ClipFiles.Kept>,
    now: Long = System.currentTimeMillis(),
): String = canvasSettingLine(stored) + "\n" + "Canvas now: " + canvasNow(stored, used, metered, videoMode, song, hidden, cover, row, kept, now)

/** "Canvas: full screen, music videos on (video first), videos on mobile data off, performance mode off". */
fun canvasSettingLine(stored: AppSettings): String {
    val p = stored.behavior.canvas
    val mode = when {
        !stored.canvasEnabled -> "off"
        stored.canvasFillsBackground -> "full screen"
        else -> "on the cover"
    }
    val extras = listOfNotNull("video first".takeIf { p.videoFirst }, "lighter, 480p".takeIf { p.lighter })
    return buildString {
        append("Canvas: ").append(mode)
        append(", music videos ").append(onOff(p.useVideos))
        if (p.useVideos && extras.isNotEmpty()) append(" (").append(extras.joinToString(", ")).append(')')
        append(", videos on mobile data ").append(onOff(p.videosOnCellular))
        append(", performance mode ").append(if (stored.performanceMode) "on (Canvas paused)" else "off")
    }
}

private fun canvasNow(
    stored: AppSettings, used: AppSettings, metered: Boolean, videoMode: Boolean,
    song: Song?, hidden: Boolean, cover: CanvasArtwork?, row: CanvasClipEntity?, kept: List<ClipFiles.Kept>, now: Long,
): String {
    if (song == null) return "nothing playing"
    if (!used.canvasEnabled) return "still cover (" + (if (stored.canvasEnabled) "Canvas paused by performance mode" else "Canvas off") + ")"
    if (videoMode) return "the music video plays (video mode), no canvas"
    val coverText = cover?.let { "animated cover from " + host(it.url) } ?: "still cover (no animated cover known)"
    val want = canvasWant(used, metered, hasCover = cover != null, songChoice = CanvasSource.of(row?.prefer), hidden = hidden, isSong = ClipGate.isSong(song))
    val choice = row?.prefer?.let { ", this song set to $it" }.orEmpty()
    if (!want.video) return "$coverText; no loop: not asked: ${want.notAsked?.text}$choice"
    if (row == null) return "$coverText; no loop: not looked up yet" + (if (want.network) "" else " and no Wi-Fi")
    val clip = row.clip()
        ?: return "$coverText; no loop: ${row.why.ifBlank { "unknown" }} (checked ${days(now - row.checkedAt)})$choice"
    val file = kept.filter { it.covers(clip.startMs, clip.endMs) }.maxByOrNull { it.height }
    if (file == null && !want.network) return "$coverText; no loop: ${clip.videoId} not on this phone and no Wi-Fi$choice"
    val height = file?.height ?: row.height.takeIf { it > 0 } ?: want.height
    return String.format(
        Locale.US, "video loop %s (%s, %dp, %.1f s, %s) %s%s",
        clip.videoId, row.videoType ?: "type unknown", height, clip.lengthMs / 1000.0,
        if (clip.refined) "refined" else "storyboard guess",
        file?.let { String.format(Locale.US, "from a kept file (%s, %.1f MB)", if (it.saved) "saved" else "recent", it.file.length() / 1_048_576.0) }
            ?: "from the network",
        choice + (if (row.picked) ", part picked by the listener" else ""),
    )
}

private fun onOff(b: Boolean) = if (b) "on" else "off"

private fun host(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull() ?: if (url.startsWith("file:")) "a file" else "?"

private fun days(ms: Long): String {
    val d = ms / 86_400_000L
    return when {
        ms < 0 -> "just now"
        d == 0L -> "today"
        d == 1L -> "1 day ago"
        else -> "$d days ago"
    }
}
