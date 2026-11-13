package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
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

    /**
     * The loop for [song] cut at up to [height] lines, or null: no video that passes, nothing on the phone and no
     * network allowed, or the fetch failed (the cover shows; it is asked again next time).
     */
    suspend fun artworkFor(
        song: Song, height: Int, saved: Boolean, allowed: () -> Boolean, chorusMs: Long? = null, now: Long = System.currentTimeMillis(),
    ): CanvasArtwork? {
        var row = dao.get(song.id)
        // A "no loop" is asked again after a week (a video may have been posted); a loop that was found stays.
        if (needsFinding(row, now) && allowed()) row = find(song, row, height, chorusMs, now) ?: row
        var clip = row?.clip() ?: return null
        var got = fetcher.fetch(clip.videoId, clip.startMs, clip.endMs, height, saved, allowed)
        if (got is ClipFetch.None && got.why in GONE && allowed() && row?.picked != true) {
            // The video went away or changed under the row: look once more, and take what that finds.
            row = find(song, row, height, chorusMs, now) ?: return null
            clip = row.clip() ?: return null
            got = fetcher.fetch(clip.videoId, clip.startMs, clip.endMs, height, saved, allowed)
        }
        if (got !is ClipFetch.Got) return null
        val loop = loopIn(clip, got.startMs, got.endMs, got.keyFramesMs).copy(height = got.height)
        val current = row ?: return null
        if (loop.startMs != current.startMs || loop.endMs != current.endMs || got.height != current.height || got.itag != current.itag) {
            dao.put(current.copy(startMs = loop.startMs, endMs = loop.endMs, height = got.height, itag = got.itag))
        }
        return CanvasArtwork(url = fileUrl(got.file, loop), title = song.title, artist = song.artistText, album = song.album?.title, clip = loop)
    }

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
            return want.copy(startMs = start, endMs = end, fileStartMs = fileStartMs, refined = want.refined && start == want.startMs)
        }

        /** The file's address for the player; the loop's seconds ride along so another part of the same file is another clip. */
        fun fileUrl(file: File, loop: VideoClip): String = "file://" + file.absolutePath + "#" + loop.startMs + "-" + loop.endMs
    }
}
