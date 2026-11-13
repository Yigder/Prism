package com.prism.music.data.canvas

import com.prism.music.data.BestPart
import com.prism.music.data.Heatmap
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Canvas's clip finder (plan-canvas 4.1): for a song, whether it gets a loop from its music video and which ~8
 * seconds, without downloading or decoding any of the video. The gate is stricter than video mode's, because a silent
 * wrong picture is worse than none; each "no" says why ([NoClip]).
 *
 * Network failures are thrown, not turned into a "no": the caller keeps the old row and asks again later, rather than
 * storing a week-long "no loop" over a dropped connection. No database, settings or UI in here: the caller stores the row.
 */
class ClipFinder(private val net: Net) {
    /** What the finder asks the network for; [create] wires the real ones, tests pass fakes. */
    interface Net {
        /** The song's music video (YouTube's pairing, else a search), or null. */
        suspend fun findVideo(song: Song): String?
        /**
         * Other videos that may be the song's music video, best first, none of [not] (owner, 2026-11-13: YouTube pairs
         * "Pleaser" by Wallows with its lyric video, which is refused, while its music video is one search away).
         */
        suspend fun otherVideos(song: Song, not: Set<String>): List<String> = emptyList()
        /** Videos the listener may pick from for the song ("Pick another video"), YouTube's own pairing first. */
        suspend fun videoChoices(song: Song): List<VideoChoice> = emptyList()
        /** YouTube Music's own word for the video, as it says it ("MUSIC_VIDEO_TYPE_OMV"), or null. */
        suspend fun videoType(videoId: String): String?
        suspend fun player(videoId: String): PlayerInfo
        /** Every preview frame of [board]. */
        suspend fun frames(board: Storyboard): Frames
        suspend fun heatmap(videoId: String): Heatmap?
    }

    /** One video offered under "Pick another video": its id, its title and who posted it. */
    data class VideoChoice(val id: String, val title: String, val by: String)

    /** The videos offered for [song] under "Pick another video" (none the listener said "Not this video" to). */
    suspend fun choices(song: Song, old: CanvasClipEntity?): List<VideoChoice> =
        if (!ClipGate.isSong(song)) emptyList() else net.videoChoices(song).filter { it.id !in old?.rejected().orEmpty() }

    /**
     * The row for a video the listener picked themselves: its title and type are theirs to judge, so only what can't
     * make a loop refuses it (not playable, no stream, a still picture). Marked as picked: a later search keeps it.
     */
    suspend fun forVideo(song: Song, video: String, old: CanvasClipEntity?, height: Int, now: Long = System.currentTimeMillis()): CanvasClipEntity =
        tryVideo(song, video, old?.copy(picked = false), height, null, now, own = true).row.let { if (it.videoId != null) it.copy(picked = true) else it }

    /** A storyboard's frames, and how many bytes its sheets took. */
    class Frames(val grids: List<Grid>, val bytes: Long)

    /** A row, plus what it cost to find (diagnostics). */
    class Found(val row: CanvasClipEntity, val boardBytes: Long)

    /**
     * The row for [song]: a loop (unrefined, cut later from the stream [height] lines tall) or the reason there is none.
     * The listener's choices in [old] are kept: videos they said "Not this video" to are never offered again, and a part
     * they picked stays while its video still passes. [chorusMs]: the song's best part, in the video's time, if known.
     */
    suspend fun find(song: Song, old: CanvasClipEntity?, height: Int, chorusMs: Long? = null, now: Long = System.currentTimeMillis()): CanvasClipEntity =
        findWithCost(song, old, height, chorusMs, now).row

    suspend fun findWithCost(song: Song, old: CanvasClipEntity?, height: Int, chorusMs: Long?, now: Long): Found {
        if (!ClipGate.isSong(song)) return Found(clipRow(song.id, null, NoClip.NOT_A_SONG, old, now), 0)
        val rejected = old?.rejected().orEmpty()
        // A part the listener picked: its video is asked first, so a new search can't take the part away.
        val picked = old?.takeIf { it.picked }?.videoId?.takeIf { it !in rejected && !song.isVideo }
        val first = picked?.let { tryVideo(song, it, old, height, chorusMs, now, own = true) }
        if (first != null && first.row.videoId != null) return first
        val bytes = first?.boardBytes ?: 0L
        val video = if (song.isVideo) song.id else net.findVideo(song)
        if (video == null) return Found(clipRow(song.id, null, NoClip.NO_VIDEO, old, now), bytes)
        if (video in rejected) return Found(clipRow(song.id, null, NoClip.REJECTED, old, now), bytes)
        // The search found the picked part's video again: it was just judged.
        if (first != null && video == picked) return first
        val tried = tryVideo(song, video, old, height, chorusMs, now)
        var spent = bytes + tried.boardBytes
        // The video YouTube gave is a lyric video, an audio upload or not official: the real one may be a search away.
        if (tried.row.videoId == null && tried.row.why in SEARCH_AGAIN && !song.isVideo) {
            val others = try { net.otherVideos(song, rejected + video) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            for (other in others.take(OTHER_VIDEOS)) {
                val next = tryVideo(song, other, old, height, chorusMs, now)
                spent += next.boardBytes
                if (next.row.videoId != null) return Found(next.row, spent)
            }
        }
        // No better video: the song's own, whatever it is called (a lyric video, a live take, not marked official).
        if (tried.row.videoId == null && tried.row.why in SEARCH_AGAIN) {
            val asItIs = tryVideo(song, video, old, height, chorusMs, now, own = true)
            spent += asItIs.boardBytes
            // Its own reason, when it has one (a still picture, not playable), says more than "its title".
            return Found(asItIs.row, spent)
        }
        return Found(tried.row, spent)
    }

    /** One video through the gate (official, title, playable, not a still) and, if it passes, its window. */
    private suspend fun tryVideo(song: Song, video: String, old: CanvasClipEntity?, height: Int, chorusMs: Long?, now: Long, own: Boolean = false): Found {
        fun no(why: NoClip, type: String?, bytes: Long = 0) = Found(clipRow(song.id, null, why, old, now, videoType = type), bytes)
        val type = net.videoType(video)?.removePrefix(TYPE_PREFIX)
        // [own]: the song's own video, with no better one found: its type and title are not held against it (owner,
        // 2026-11-13: "if a song has a video, Canvas should be allowed. This should be a universal rule"). What is left
        // is what can't make a loop at all: not playable, no stream, a still picture; and later, on its frames, flashing.
        if (!own && type != OFFICIAL) return no(NoClip.NOT_OFFICIAL, type)
        val info = net.player(video)
        if (!own && ClipGate.titleSays(song.title, info.title)) return no(NoClip.TITLE, type)
        if (!info.playable) return no(NoClip.RESTRICTED, type)
        val board = Storyboard.parse(info.storyboardSpec, info.lengthMs)
        val frames = board?.let { net.frames(it) }
        val bytes = frames?.bytes ?: 0
        val length = maxOf(info.lengthMs, if (board != null && frames != null) frames.grids.size * board.intervalMs else 0)
        // A video with no length and no frames: nothing to cut a loop from.
        if (length < FrameJudge.WINDOW_MS) return no(NoClip.NO_STREAM, type, bytes)
        val verdict = if (board == null || frames == null || frames.grids.size < MIN_FRAMES) {
            // No preview frames: nothing to judge by, so the middle of the video (plan §3.4).
            FrameJudge.noFrames(length)
        } else {
            val bars = FrameJudge.bars(frames.grids)
            // The still check first: a still upload needs no "most replayed" curve.
            if (FrameJudge.isStill(frames.grids, bars)) return no(NoClip.STILL, type, bytes)
            // Without the curve the chorus or the middle decides: not worth failing the whole find over.
            val heat = try { net.heatmap(video) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val peak = heat?.let { BestPart.peakMs(it, length) }
            FrameJudge.judge(frames.grids, board.intervalMs, length, peak, chorusMs)
        }
        if (verdict !is FrameJudge.Verdict.Loop) return no(NoClip.STILL, type, bytes)
        val keep = old?.takeIf { it.picked && it.videoId == video && it.endMs > it.startMs }
        val clip = when {
            // The same part, already cut to its real frames at this height: nothing to redo.
            keep != null && keep.refined && keep.height == height -> keep.clip()!!
            keep != null -> VideoClip(video, keep.startMs, keep.endMs, height, verdict.cropX, verdict.bars)
            else -> VideoClip(video, verdict.startMs, verdict.endMs, height, verdict.cropX, verdict.bars)
        }
        val others = verdict.otherParts.filter { kotlin.math.abs(it - clip.startMs) >= FrameJudge.APART_MS }
        return Found(clipRow(song.id, clip, null, old, now, videoType = type, otherParts = others, picked = keep != null), bytes)
    }

    companion object {
        private const val TYPE_PREFIX = "MUSIC_VIDEO_TYPE_"
        /** A first video refused for these is not the last word: another is searched for ([Net.otherVideos]). */
        private val SEARCH_AGAIN = setOf(NoClip.TITLE.name, NoClip.NOT_OFFICIAL.name)
        private const val OTHER_VIDEOS = 2
        /** Fewer preview frames than this can't tell a still from a moving video. */
        private const val MIN_FRAMES = 8
        /** Storyboards are 0.21 MB at the median and 0.47 MB at most (100 sampled videos): more is not a storyboard. */
        private const val MAX_BOARD_BYTES = 2L * 1024 * 1024

        /** The finder on the real network: YouTube Music for the video, its type and curve, the visionOS player for the rest. */
        fun create(ytm: YouTubeMusic, api: InnerTube, http: OkHttpClient, decoder: SheetDecoder = BitmapSheetDecoder): ClipFinder =
            ClipFinder(object : Net {
                override suspend fun findVideo(song: Song) = ytm.findMusicVideo(song)
                override suspend fun otherVideos(song: Song, not: Set<String>) = ytm.otherMusicVideos(song, not) { ClipGate.titleSays(song.title, it) }
                override suspend fun videoChoices(song: Song) = ytm.musicVideoChoices(song).map { VideoChoice(it.id, it.title, it.artistText) }

                // The WEB_REMIX player answer carries the type even where that client can't play the video.
                override suspend fun videoType(videoId: String) =
                    api.post("player", buildJsonObject { put("videoId", videoId) }).str("videoDetails", "musicVideoType")

                override suspend fun player(videoId: String) = withContext(Dispatchers.IO) { PlayerInfo.parse(api.visionOsPlayer(videoId)) }

                override suspend fun frames(board: Storyboard) = fetchFrames(http, board, decoder)

                override suspend fun heatmap(videoId: String) = ytm.heatmap(videoId)
            })

        /**
         * Downloads [board]'s sheets and cuts them into grids. A sheet that fails ends it: the frames so far are what
         * there is (the still check needs only a few). Only network errors are thrown.
         */
        suspend fun fetchFrames(http: OkHttpClient, board: Storyboard, decoder: SheetDecoder): Frames = withContext(Dispatchers.IO) {
            val grids = ArrayList<Grid>(board.count)
            var bytes = 0L
            for (n in 0 until board.sheets) {
                val body = http.newCall(Request.Builder().url(board.sheetUrl(n)).build()).execute().use { r ->
                    if (r.isSuccessful) r.body.bytes() else null
                } ?: break
                bytes += body.size
                val sheet = decoder.decode(body) ?: break
                board.cut(sheet, n, grids)
                if (bytes > MAX_BOARD_BYTES) break
            }
            Frames(grids, bytes)
        }
    }
}

/** What the visionOS player answer says about a video, for the gate and the storyboard. */
data class PlayerInfo(
    /** playabilityStatus.status: "OK", "LOGIN_REQUIRED" (age or sign-in), "UNPLAYABLE", "ERROR"… */
    val status: String,
    val title: String,
    val lengthMs: Long,
    val isPrivate: Boolean = false,
    /** A live stream (on now, or one with no length yet). A finished stream with a length is a video like any other. */
    val isLive: Boolean = false,
    val storyboardSpec: String? = null,
) {
    val playable: Boolean get() = status == "OK" && !isPrivate && !isLive

    companion object {
        fun parse(res: JsonObject): PlayerInfo {
            val d = res.obj("videoDetails")
            val length = d.str("lengthSeconds")?.toLongOrNull() ?: 0L
            val live = d.str("isLive") == "true" || d.str("isLiveContent") == "true" && length <= 0
            val spec = (res.obj("storyboards")?.values?.firstOrNull() as? JsonObject).str("spec")
            return PlayerInfo(
                status = res.str("playabilityStatus", "status") ?: "ERROR",
                title = d.str("title").orEmpty(),
                lengthMs = length * 1000,
                isPrivate = d.str("isPrivate") == "true",
                isLive = live,
                storyboardSpec = spec,
            )
        }
    }
}

/** The parts of the gate that need no network: is it a song at all, is the video's type official, what its title says. */
object ClipGate {
    /** Podcasts, feed episodes, files on the phone, the listener's uploads and live stations are never looked up. */
    fun isSong(song: Song): Boolean = !song.isRss && !song.isLocal && !song.isUpload && !song.isEpisode && !song.isLive

    fun official(type: String?): Boolean = type?.removePrefix("MUSIC_VIDEO_TYPE_") == OFFICIAL

    /**
     * Whether a video's title says it is not the music video itself: the audio, a lyric video, a live take, a stage, a
     * cover, a fancam, a recording session, a jukebox, a film's background score… A word that is in the song's own
     * title doesn't count (a live album's song may use its live video). "Visualizer" is allowed: the still check
     * decides. "Full song" only counts when the title doesn't also say video ("Full Video Song" is the video).
     */
    fun titleSays(songTitle: String, videoTitle: String, lyricsOk: Boolean = false): Boolean {
        val song = songTitle.lowercase()
        val video = videoTitle.lowercase()
        fun counts(r: Regex) = r.containsMatchIn(video) && !r.containsMatchIn(song)
        if (WORDS.any(::counts)) return true
        if (!lyricsOk && LYRIC_WORDS.any(::counts)) return true
        if (FULL_SONG.containsMatchIn(video) && !VIDEO.containsMatchIn(video) && !FULL_SONG.containsMatchIn(song)) return true
        return (if (lyricsOk) SCRIPT - LYRIC_SCRIPT else SCRIPT).any { it in video && it !in song }
    }

    /** As in MusicVideoMatch: a word only where it stands alone ("live" is not in "alive", "stage" not in "backstage"). */
    private fun words(vararg w: String) = w.map { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])") }

    private val WORDS = words(
        "audio", "karaoke", "chords", "performance", "live", "stage", "cover", "fancam", "fan cam",
        "recording", "jukebox", "bgm",
        // Spanish, Portuguese.
        "en vivo", "ao vivo", "en directo",
    )
    /**
     * "Lyric video" and its like. An official one is let through when the song has no other video ([lyricsOk]; owner,
     * 2026-11-13: "if the song has a video it should have a canvas"): the still check and the second look still apply.
     */
    private val LYRIC_WORDS = words("lyric", "lyrics", "lyrical", "letra", "letras", "paroles", "testo")
    private val LYRIC_SCRIPT = setOf("가사", "歌詞", "歌词")
    private val FULL_SONG = words("full song").single()
    private val VIDEO = words("video").single()

    /** Words in scripts without spaces between words: matched anywhere in the title. */
    private val SCRIPT = listOf(
        "직캠", "무대", "가사", "라이브", // Korean: fancam, stage, lyrics, live
        "推しカメラ", "歌詞", "ライブ", "歌ってみた", // Japanese: member cam, lyrics, live, "I sang it" covers
        "歌词", "现场", "翻唱", // Chinese: lyrics, live, cover
    )
}
