package com.prism.music.data.canvas

import java.io.File

/**
 * Where loops cut from music videos are kept on the phone: "recent" (in the cache folder, least recently used go
 * first once they pass the caller's byte budget) and "saved" (in the files folder, for downloaded songs, kept until
 * removed). Plain files, one per loop, each a whole MP4 that plays by itself; everything about a loop is in its name,
 * so there is no index to keep in step:
 *
 *     <videoId>.<height>.<itag>.<startMs>-<endMs>[.c].mp4
 *
 * with the times in the video's own time (the file's own time starts at 0 = startMs) and ".c" when the fetch was
 * capped short of what was asked (so asking again for the same seconds doesn't fetch the same fragments again).
 */
class ClipFiles(
    private val recentDir: File,
    private val savedDir: File,
    /** The recent folder's budget in bytes, read at each prune (the caller's setting). */
    private val recentBudget: () -> Long,
) {
    data class Kept(
        val file: File,
        val videoId: String,
        val height: Int,
        val itag: Int,
        /** The kept seconds, in the video's time; the file's 0 is [startMs]. */
        val startMs: Long,
        val endMs: Long,
        /** Fetched short of the asked end by the size cap. */
        val capped: Boolean,
        val saved: Boolean,
    ) {
        /** Whether this file answers a request for [from]..[to] of the video. */
        fun covers(from: Long, to: Long): Boolean = startMs <= from && from < endMs && (endMs >= to || capped)
    }

    private val lock = Any()

    /**
     * The best kept file for [videoId]'s [startMs]..[endMs], at [minHeight] or more: the highest, saved before recent
     * at the same height. A recent one counts as used (it moves to the back of the pruning line).
     */
    fun find(videoId: String, startMs: Long, endMs: Long, minHeight: Int = 0): Kept? = synchronized(lock) {
        val best = (list(savedDir, true) + list(recentDir, false))
            .filter { it.videoId == safeId(videoId) && it.height >= minHeight && it.covers(startMs, endMs) }
            .sortedWith(compareByDescending<Kept> { it.height }.thenByDescending { it.saved })
            .firstOrNull() ?: return null
        if (!best.saved) touch(best.file)
        best
    }

    /** Every kept file of [videoId], both folders. */
    fun of(videoId: String): List<Kept> = synchronized(lock) {
        (list(savedDir, true) + list(recentDir, false)).filter { it.videoId == safeId(videoId) }
    }

    /** A temporary file to write a new loop into, in the folder it will end up in (so keeping it is a rename). */
    fun temp(saved: Boolean): File {
        val dir = if (saved) savedDir else recentDir
        dir.mkdirs()
        return File.createTempFile("clip", ".part", dir)
    }

    /** Keeps a finished [temp] file under the loop's name; prunes the recent folder after. */
    fun keep(temp: File, videoId: String, height: Int, itag: Int, startMs: Long, endMs: Long, capped: Boolean, saved: Boolean): Kept {
        val kept = synchronized(lock) {
            val dir = if (saved) savedDir else recentDir
            val target = File(dir, name(videoId, height, itag, startMs, endMs, capped))
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw java.io.IOException("Could not keep ${target.name}")
            }
            touch(target)
            Kept(target, safeId(videoId), height, itag, startMs, endMs, capped, saved)
        }
        if (!saved) prune(except = kept.file)
        return kept
    }

    /** Moves a recent loop to saved (a downloaded song's), or back; the same file under the other folder. */
    fun save(kept: Kept, saved: Boolean = true): Kept {
        val moved = synchronized(lock) {
            if (kept.saved == saved || !kept.file.exists()) return kept
            val dir = if (saved) savedDir else recentDir
            dir.mkdirs()
            val target = File(dir, kept.file.name)
            if (target.exists()) target.delete()
            if (!kept.file.renameTo(target)) {
                // Another volume (shouldn't be: both are the app's own storage): copy, then drop the old one.
                kept.file.copyTo(target, overwrite = true)
                kept.file.delete()
            }
            touch(target)
            kept.copy(file = target, saved = saved)
        }
        if (!saved) prune()
        return moved
    }

    /** Moves every saved loop of [videoId] back to recent (its song's download was removed): pruned like any other. */
    fun unsave(videoId: String) {
        of(videoId).filter { it.saved }.forEach { save(it, saved = false) }
    }

    fun delete(videoId: String) = synchronized(lock) {
        of(videoId).forEach { it.file.delete() }
    }

    val recentBytes: Long get() = synchronized(lock) { files(recentDir).sumOf { it.length() } }
    val savedBytes: Long get() = synchronized(lock) { files(savedDir).sumOf { it.length() } }

    fun clearRecent() = synchronized(lock) {
        recentDir.listFiles()?.forEach { it.delete() }
    }

    /** Drops the least recently used recent loops until the folder fits its budget; saved ones are never touched. */
    fun prune(except: File? = null) = synchronized(lock) {
        val budget = recentBudget()
        // The loop just kept is never the one dropped, even over a budget smaller than it: the caller is about to play it.
        val all = files(recentDir).filter { it != except }.sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() } + (except?.takeIf { it.exists() }?.length() ?: 0L)
        for (f in all) {
            if (total <= budget) break
            total -= f.length()
            f.delete()
        }
        // Parts left by a fetch that died half way (the app killed mid-write).
        recentDir.listFiles { f -> f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000 }?.forEach { it.delete() }
    }

    private fun touch(f: File) { f.setLastModified(System.currentTimeMillis()) }

    private fun files(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.toList().orEmpty()

    private fun list(dir: File, saved: Boolean): List<Kept> = files(dir).mapNotNull { parse(it, saved) }

    companion object {
        private val NAME = Regex("""([A-Za-z0-9_-]+)\.(\d+)\.(\d+)\.(\d+)-(\d+)(\.c)?\.mp4""")

        /** Video ids are [A-Za-z0-9_-]; anything else is dropped so a name can never leave its folder. */
        fun safeId(videoId: String): String = videoId.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }

        fun name(videoId: String, height: Int, itag: Int, startMs: Long, endMs: Long, capped: Boolean): String =
            "${safeId(videoId)}.$height.$itag.$startMs-$endMs${if (capped) ".c" else ""}.mp4"

        fun parse(f: File, saved: Boolean): Kept? {
            val m = NAME.matchEntire(f.name) ?: return null
            val (id, h, itag, s, e) = m.destructured
            return Kept(f, id, h.toInt(), itag.toInt(), s.toLong(), e.toLong(), m.groupValues[6].isNotEmpty(), saved)
        }
    }
}
