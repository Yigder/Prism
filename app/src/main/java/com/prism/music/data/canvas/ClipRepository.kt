package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A song's Canvas loop from its music video, start to finish (1.8.6): the row that says which video and which seconds
 * ([ClipFinder], kept in `canvas_clips`), the file that holds them ([ClipFetcher]), and the loop inside that file that
 * starts on a key frame. What comes out is a [CanvasArtwork] the player shows like an animated cover.
 *
 * [allowed] is asked before anything touches the network (the caller folds Wi-Fi, the mobile-data chip and Data saver
 * into it): not allowed, a loop already on the phone still plays and every other song shows its cover.
 */
class ClipRepository(private val dao: CanvasClipDao, private val finder: ClipFinder, private val fetcher: ClipFetcher) {

    /** The listener's "Use:" for one song, if they chose. */
    suspend fun choice(songId: String): CanvasSource? = CanvasSource.of(dao.get(songId)?.prefer)

    /** The row as it stands (diagnostics, the song's menu). */
    suspend fun row(songId: String): CanvasClipEntity? = dao.get(songId)

    // ------------------------------------------------------------ The song's own choices (plan §3.8, the ⋯ menu)

    /** The kept loops on the phone, for "Not this video" to drop that video's files ([ClipFiles.delete]); set by the app. */
    var files: ClipFiles? = null

    private val changed = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Goes up each time the listener changes a song's row here, so the player looks at the song's canvas again. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = changed

    private fun bump() {
        while (true) { val v = changed.value; if (changed.compareAndSet(v, v + 1)) return }
    }

    /** The song's loop as "Pick another part" shows it: the part playing first, then the other good ones. */
    data class Parts(val videoId: String, val current: Long, val others: List<Long>) {
        val all: List<Long> get() = listOf(current) + others
    }

    /** The parts of the song's loop, or null when it has none. */
    suspend fun parts(songId: String): Parts? {
        val row = dao.get(songId) ?: return null
        val video = row.videoId ?: return null
        return Parts(video, row.startMs, row.otherParts().filter { it != row.startMs }.take(MAX_OTHER_PARTS))
    }

    /**
     * "Use:" for one song ([source] null: back to the setting's order). Works before the song was ever looked up: the
     * row then only holds the choice, and is looked up as if new.
     */
    suspend fun setChoice(songId: String, source: CanvasSource?) {
        val row = dao.get(songId)
        if (row == null && source == null) return
        dao.put((row ?: clipRow(songId, null, NoClip.NO_VIDEO, null, now = 0)).copy(prefer = source?.stored))
        bump()
    }

    /**
     * "Pick another part": the loop becomes [PICK_LENGTH_MS] from [startMs] of the same video, picked by the listener (a
     * new guess never replaces it) and cut again to the real frames when it is next fetched. The part it was takes the
     * picked one's place among the others. False when the song has no loop.
     */
    suspend fun pickPart(songId: String, startMs: Long): Boolean {
        val row = dao.get(songId) ?: return false
        if (row.videoId == null || startMs < 0) return false
        // The part already playing: kept as it is (already cut to its frames), only marked as the listener's.
        if (startMs == row.startMs) {
            if (!row.picked) { dao.put(row.copy(picked = true)); bump() }
            return true
        }
        val others = row.otherParts().let { o ->
            val at = o.indexOf(startMs)
            if (at >= 0) o.toMutableList().apply { set(at, row.startMs) } else listOf(row.startMs) + o
        }.filter { it != startMs }.distinct()
        dao.put(row.copy(startMs = startMs, endMs = startMs + PICK_LENGTH_MS, refined = false, picked = true, windows = others.joinToString(","), crops = ""))
        bump()
        return true
    }

    /**
     * "Not this video": the song's video is never offered for it again (a later search skips it), its loop goes, and so
     * do the files kept of it; the song shows its animated cover if it has one, else its cover. False when it has no loop.
     */
    suspend fun rejectVideo(songId: String, now: Long = System.currentTimeMillis()): Boolean {
        val row = dao.get(songId) ?: return false
        val video = row.videoId ?: return false
        val rejected = (row.rejected() + video).joinToString(",")
        dao.put(clipRow(songId, null, NoClip.REJECTED, row.copy(rejectedVideos = rejected), now, videoType = row.videoType))
        files?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.delete(video) } }
        bump()
        return true
    }

    /**
     * The loop for [song] cut at up to [height] lines, or null: no video that passes, nothing on the phone and no
     * network allowed, or the fetch failed (the cover shows; it is asked again next time).
     */
    suspend fun artworkFor(
        song: Song, height: Int, saved: Boolean, allowed: () -> Boolean, chorusMs: Long? = null, now: Long = System.currentTimeMillis(),
    ): CanvasArtwork? = lock(song.id).withLock { loopFor(song, height, saved, allowed, chorusMs, now, retried = false) }

    /** One song at a time is worked on once: the player, the look ahead and a download may all ask for the same one. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun lock(songId: String) = locks.getOrPut(songId) { kotlinx.coroutines.sync.Mutex() }

    /**
     * Looks the song's video up (which video, which seconds: no video is fetched) if that hasn't been done, so the player
     * can do it while it still asks whether the song has an animated cover. Whoever asks for the loop next finds it done.
     */
    suspend fun findAhead(song: Song, height: Int, allowed: () -> Boolean, now: Long = System.currentTimeMillis()) {
        lock(song.id).withLock { if (needsFinding(dao.get(song.id), now) && allowed()) find(song, dao.get(song.id), height, null, now) }
    }

    private suspend fun loopFor(song: Song, height: Int, saved: Boolean, allowed: () -> Boolean, chorusMs: Long?, now: Long, retried: Boolean): CanvasArtwork? {
        var row = dao.get(song.id)
        val t0 = System.nanoTime()
        // A "no loop" is asked again after a week (a video may have been posted); a loop that was found stays.
        if (needsFinding(row, now) && allowed()) row = find(song, row, height, chorusMs, now) ?: row
        val t1 = System.nanoTime()
        var clip = row?.clip() ?: return null
        var got = fetcher.fetch(clip.videoId, clip.startMs, clip.endMs, height, saved, allowed)
        val t2 = System.nanoTime()
        lastTimes = "find ${(t1 - t0) / 1_000_000} ms, fetch ${(t2 - t1) / 1_000_000} ms" + if ((got as? ClipFetch.Got)?.fromNetwork == true) " (network)" else " (kept)"
        if (got is ClipFetch.None && got.why in GONE && allowed() && row?.picked != true) {
            // The video went away or changed under the row: look once more, and take what that finds.
            row = find(song, row, height, chorusMs, now) ?: return null
            clip = row.clip() ?: return null
            got = fetcher.fetch(clip.videoId, clip.startMs, clip.endMs, height, saved, allowed)
        }
        if (got !is ClipFetch.Got) return null
        var loop = loopIn(clip, got.startMs, got.endMs, got.keyFramesMs).copy(height = got.height)
        val current = row ?: return null
        // The second look, on the real frames, once per loop and before it is ever shown (a flashing loop never is).
        if (!loop.refined && got.file.path !in unreadable) {
            val judged = try { refiner?.refine(got.file, loop) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            when {
                judged == null -> if (refiner != null) unreadable += got.file.path
                judged.flashing -> {
                    // Another part of the same video, once; if that flashes too (or there is none), the cover.
                    val next = current.otherParts().firstOrNull { it != current.startMs }
                    if (next == null || retried) {
                        dao.put(clipRow(song.id, null, NoClip.FLASHING, current, now, videoType = current.videoType))
                        return null
                    }
                    dao.put(current.copy(
                        startMs = next, endMs = next + PICK_LENGTH_MS, refined = false, picked = false, crops = "",
                        windows = current.otherParts().filter { it != next && it != current.startMs }.joinToString(","),
                    ))
                    return loopFor(song, height, saved, allowed, chorusMs, now, retried = true)
                }
                // Mostly a face filling the frame (half a face, upright): the next part is tried once, unless the listener
                // picked this one. The part stays among the others, last, so "Pick another part" can still have it.
                judged.closeUp && !retried && !current.picked && allowed() && current.otherParts().any { it != current.startMs } -> {
                    val next = current.otherParts().first { it != current.startMs }
                    dao.put(current.copy(
                        startMs = next, endMs = next + PICK_LENGTH_MS, refined = false, crops = "",
                        windows = (current.otherParts().filter { it != next && it != current.startMs } + current.startMs).joinToString(","),
                    ))
                    return loopFor(song, height, saved, allowed, chorusMs, now, retried = true)
                }
                else -> loop = loop.copy(endMs = judged.endMs.coerceIn(loop.startMs + 1, got.endMs.coerceAtLeast(loop.startMs + 1)), cropX = judged.cropX ?: loop.cropX, refined = true, crops = judged.crops)
            }
        }
        if (loop.startMs != current.startMs || loop.endMs != current.endMs || got.height != current.height || got.itag != current.itag ||
            loop.cropX != current.cropX || loop.refined != current.refined || SceneCrop.encode(loop.crops) != current.crops
        ) {
            dao.put(current.copy(startMs = loop.startMs, endMs = loop.endMs, height = got.height, itag = got.itag, cropX = loop.cropX, refined = loop.refined, crops = SceneCrop.encode(loop.crops)))
        }
        return CanvasArtwork(url = fileUrl(got.file, loop), title = song.title, artist = song.artistText, album = song.album?.title, clip = loop)
    }

    /** How long the last loop took to find and fetch (Share diagnostics, the live test). */
    @Volatile var lastTimes: String? = null; private set

    /** Reads a kept loop's real frames ([ClipDecode] on a phone); set by the app, absent in most tests. */
    var refiner: ClipRefiner? = null
    /** Files this phone couldn't read frames from: not tried again until Prism restarts. */
    private val unreadable = java.util.Collections.synchronizedSet(HashSet<String>())

    private suspend fun find(song: Song, old: CanvasClipEntity?, height: Int, chorusMs: Long?, now: Long): CanvasClipEntity? = try {
        finder.find(song, old, height, chorusMs, now).also { dao.put(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Offline or a hiccup: what was known stays, and it is asked again next time.
        null
    }

    companion object {
        private val GONE = setOf(ClipFetch.Why.UNPLAYABLE, ClipFetch.Why.OUT_OF_RANGE, ClipFetch.Why.NO_STREAM)
        const val MIN_LOOP_MS = 5_000L
        const val MAX_LOOP_MS = 9_000L
        /** A part the listener picks runs this long from where they tapped (cut to the real frames when fetched). */
        const val PICK_LENGTH_MS = 8_000L
        /** "Pick another part" shows the part playing and up to this many others. */
        const val MAX_OTHER_PARTS = 4

        fun needsFinding(row: CanvasClipEntity?, now: Long): Boolean =
            row == null || (row.videoId == null && now - row.checkedAt > CLIP_RECHECK_MS)

        /**
         * The loop inside a kept file: it must start on a key frame (the phone decodes everything since the last one
         * before the first frame shows, up to 1.4 s at 1080p on the A16), so the wanted start moves to the nearest key
         * frame that leaves at least [MIN_LOOP_MS] before the file ends, and the length stays within 5 to 9 s. A loop
         * already cut to the real frames keeps its end.
         */
        fun loopIn(want: VideoClip, fileStartMs: Long, fileEndMs: Long, keyFramesMs: List<Long>): VideoClip {
            val keys = (keyFramesMs + fileStartMs).filter { it >= fileStartMs && it + MIN_LOOP_MS <= fileEndMs }.distinct().sorted()
            val start = keys.minWithOrNull(compareBy<Long>({ kotlin.math.abs(it - want.startMs) }, { it })) ?: fileStartMs
            val end = if (want.refined && start == want.startMs) want.endMs.coerceIn(start + 1, fileEndMs.coerceAtLeast(start + 1))
            else (start + want.lengthMs.coerceIn(MIN_LOOP_MS, MAX_LOOP_MS)).coerceAtMost(fileEndMs.coerceAtLeast(start + 1))
            val kept = want.refined && start == want.startMs
            return want.copy(startMs = start, endMs = end, fileStartMs = fileStartMs, refined = kept, crops = if (kept) want.crops else emptyList())
        }

        /** The file's address for the player; the loop's seconds ride along so another part of the same file is another clip. */
        fun fileUrl(file: File, loop: VideoClip): String = "file://" + file.absolutePath + "#" + loop.startMs + "-" + loop.endMs
    }

    /**
     * Gets [song]'s loop onto the phone before it shows (the next songs, a download: [CanvasAhead]); what would show is
     * thrown away. A loop already kept costs no network. True when a loop is on the phone.
     */
    suspend fun prepare(song: Song, height: Int, saved: Boolean, allowed: () -> Boolean): Boolean =
        artworkFor(song, height, saved, allowed) != null
}
