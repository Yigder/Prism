package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipEntity
import kotlinx.serialization.Serializable

/**
 * Canvas from a song's own music video (1.8.6): a few silent seconds of it, looping, where the song has no animated
 * cover (or the listener would rather have the video). The shapes every part of it shares; the finder, the fetch, the
 * refine pass and the player each have their own file.
 *
 * Rules that came out of the preparation (.claude/release-check/1.8.6/prep.md):
 * - the video must be what YouTube Music itself calls an official music video ([OFFICIAL]); its own song-to-video
 *   pairing is not enough (TV-stage uploads come back paired);
 * - a loop starts on a key frame (else the phone decodes up to 1.4 s before the first frame shows);
 * - the still check looks at the middle of the frame (labels post a still cover inside a turning ornament).
 */

/** The loop itself: which seconds of which video, and how it sits in an upright frame. */
@Serializable
data class VideoClip(
    val videoId: String,
    /** The loop in the video's time; [startMs] is on a key frame once [refined]. */
    val startMs: Long,
    val endMs: Long,
    /** The height of the stream it is cut from (1080, 720, 480…). */
    val height: Int,
    /** Where an upright crop sits across the picture inside the bars: 0 left edge, 0.5 centre, 1 right edge. */
    val cropX: Float = 0.5f,
    val bars: Bars = Bars.NONE,
    /** False while this is the storyboard's guess (restart by dissolve); true once cut to the real frames. */
    val refined: Boolean = false,
) {
    val lengthMs: Long get() = endMs - startMs

    /** What the clip is kept under on the phone: it outlives YouTube's links, which expire in hours. */
    val key: String get() = "prism-clip://$videoId/$height/$startMs-$endMs"
}

/** Black bars baked into a video, as shares of its width and height (0.125 top and bottom = a 21:9 film in 16:9). */
@Serializable
data class Bars(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val any: Boolean get() = left > 0f || top > 0f || right > 0f || bottom > 0f

    companion object { val NONE = Bars() }
}

/** Why a song has no loop from its video (kept with the song, shown in diagnostics; names are stored: don't rename). */
enum class NoClip {
    /** No video found for the song. */
    NO_VIDEO,
    /** Found, but not an official music video by YouTube Music's own word. */
    NOT_OFFICIAL,
    /** Its title says audio, lyrics, a live take, a cover… */
    TITLE,
    /** A still picture with the song under it. */
    STILL,
    /** Age-restricted, private, blocked here, a live stream. */
    RESTRICTED,
    /** No H.264 stream of 480 lines or more that can be fetched in parts. */
    NO_STREAM,
    /** Every good part flashes too hard. */
    FLASHING,
    /** A podcast, a local file, an upload, a feed's episode: not looked up at all. */
    NOT_A_SONG,
    /** The listener said "Not this video", and there is no other. */
    REJECTED,
}

/** The listener's "Use:" for one song when it has both an animated cover and a video loop. */
enum class CanvasSource(val stored: String) {
    COVER("cover"), VIDEO("video");

    companion object { fun of(stored: String?): CanvasSource? = entries.firstOrNull { it.stored == stored } }
}

/** YouTube Music's `musicVideoType` (without its MUSIC_VIDEO_TYPE_ prefix) for an official music video. */
const val OFFICIAL = "OMV"

/** A guess is asked again after this long; "no loop" too (a video may have been posted since). */
const val CLIP_RECHECK_MS = 7L * 86_400_000L

fun CanvasClipEntity.clip(): VideoClip? = videoId?.let {
    VideoClip(it, startMs, endMs, height, cropX, Bars(barLeft, barTop, barRight, barBottom), refined)
}

fun CanvasClipEntity.otherParts(): List<Long> = windows.split(',').mapNotNull { it.trim().toLongOrNull() }

fun CanvasClipEntity.rejected(): Set<String> = rejectedVideos.split(',').filter { it.isNotBlank() }.toSet()

/** A row for [songId] with [clip] (or none, and [why]), keeping what the listener chose in [old]. */
fun clipRow(
    songId: String, clip: VideoClip?, why: NoClip?, old: CanvasClipEntity?, now: Long,
    videoType: String? = null, itag: Int = 0, otherParts: List<Long> = emptyList(), picked: Boolean = old?.picked ?: false,
): CanvasClipEntity = CanvasClipEntity(
    songId = songId, videoId = clip?.videoId, why = if (clip == null) (why ?: NoClip.NO_VIDEO).name else "", videoType = videoType,
    height = clip?.height ?: 0, itag = itag, startMs = clip?.startMs ?: 0, endMs = clip?.endMs ?: 0, cropX = clip?.cropX ?: 0.5f,
    barLeft = clip?.bars?.left ?: 0f, barTop = clip?.bars?.top ?: 0f, barRight = clip?.bars?.right ?: 0f, barBottom = clip?.bars?.bottom ?: 0f,
    windows = otherParts.joinToString(","), refined = clip?.refined ?: false, picked = picked && clip != null,
    rejectedVideos = old?.rejectedVideos.orEmpty(), prefer = old?.prefer, checkedAt = now,
)

/**
 * Which picture a song shows, best first, from what it has and what was chosen (plan §3.1, §3.8):
 * the listener's choice for the song wins, then the setting "When a song has both", and whatever is missing falls
 * through to the other. Null: the still cover.
 */
fun pickSource(hasCover: Boolean, hasClip: Boolean, useVideos: Boolean, videoFirst: Boolean, songChoice: CanvasSource?): CanvasSource? {
    val clip = hasClip && useVideos
    val first = songChoice ?: if (videoFirst) CanvasSource.VIDEO else CanvasSource.COVER
    return when {
        first == CanvasSource.VIDEO && clip -> CanvasSource.VIDEO
        first == CanvasSource.COVER && hasCover -> CanvasSource.COVER
        hasCover -> CanvasSource.COVER
        clip -> CanvasSource.VIDEO
        else -> null
    }
}
