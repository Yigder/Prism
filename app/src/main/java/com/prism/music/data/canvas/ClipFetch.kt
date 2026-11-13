package com.prism.music.data.canvas

import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

/** What [ClipFetcher.fetch] gives back. */
sealed interface ClipFetch {
    /**
     * A loop on the phone. Times are in the video's own time, so they can be stored; the file's own time starts at 0,
     * which is [startMs] (a key frame: the first kept fragment's start).
     */
    data class Got(
        val file: File,
        val height: Int,
        val itag: Int,
        val startMs: Long,
        val endMs: Long,
        /** Every key frame in the kept seconds (fragment starts among them), in the video's time: where a loop can start. */
        val keyFramesMs: List<Long>,
        val bytes: Long,
        val fromNetwork: Boolean,
        val saved: Boolean,
    ) : ClipFetch

    data class None(val why: Why, val detail: String = "") : ClipFetch

    enum class Why {
        /** Nothing kept, and the caller's rules (Wi-Fi only, data saver…) said no network. */
        NOT_ALLOWED,
        /** The video's player answer could not be had, or says it can't be played (age, region, private…). */
        UNPLAYABLE,
        /** No video-only H.264 stream with an index (see [ClipFetcher.chooseStream]). */
        NO_STREAM,
        /** The asked seconds lie outside the video. */
        OUT_OF_RANGE,
        /** The fetch failed (refused twice, cut short, malformed). */
        NETWORK,
    }
}

/**
 * Fetches a few seconds of a music video for Canvas (plan §3.4, row 4.2): only the fragments that hold them, of the
 * video-only H.264 stream, and keeps them as a small MP4 that plays by itself offline ([ClipFiles]).
 *
 * Two requests a loop: the stream's first couple of KB (its init segment and index), then the wanted fragments as one
 * byte range. The link comes from a fresh player answer every time (links expire in hours) and is never kept; a link
 * turned away (403) gets one fresh answer and one more try.
 *
 * [http] is the app's stream client, whose interceptor turns a Range header into the ranged POST googlevideo wants
 * (and stitches long reads from short ones). [player] is the visionOS player answer for a video id.
 */
class ClipFetcher(
    private val http: OkHttpClient,
    private val player: suspend (videoId: String) -> JsonObject,
    private val files: ClipFiles,
    /** The most media one fetch takes, whichever of the two comes first (fragments are dropped from the end). */
    private val capMs: Long = 20_000,
    private val capBytes: Long = 12L shl 20,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Fetches under way, so the player and the next-song preparation asking for the same loop share one. */
    private val inFlight = HashMap<String, Deferred<ClipFetch>>()

    /**
     * The loop for [videoId]'s [startMs]..[endMs]: a kept file when there is one (no network at all), else fetched at
     * the best height up to [maxHeight] if [allowed] says the network may be used now (the caller folds Wi-Fi, the
     * mobile-data chip and Data saver into it). [saved] keeps it with the song's download. A kept file below
     * [minHeight] doesn't count (to fetch a sharper one); offline callers leave it at 0.
     */
    suspend fun fetch(
        videoId: String, startMs: Long, endMs: Long, maxHeight: Int, saved: Boolean,
        allowed: () -> Boolean, minHeight: Int = 0,
    ): ClipFetch {
        require(endMs > startMs) { "endMs must be after startMs" }
        val kept = withContext(Dispatchers.IO) { files.find(videoId, startMs, endMs, minHeight) }
        if (kept != null) return withContext(Dispatchers.IO) { gotFromFile(if (saved) files.save(kept) else kept) }
        if (!allowed()) return ClipFetch.None(ClipFetch.Why.NOT_ALLOWED)
        val key = "$videoId/$startMs-$endMs/$maxHeight/$minHeight"
        val job = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try { network(videoId, startMs, endMs, maxHeight, saved) } finally { synchronized(inFlight) { inFlight.remove(key) } }
            }.also { inFlight[key] = it }
        }
        val got = job.await()
        // A second asker wanted it saved, the first didn't: same file, moved.
        if (saved && got is ClipFetch.Got && !got.saved) {
            val moved = withContext(Dispatchers.IO) {
                ClipFiles.parse(got.file, false)?.let { files.save(it) }
            }
            if (moved != null) return got.copy(file = moved.file, saved = true)
        }
        return got
    }

    /** One stream, as the player answer lists it. */
    data class Stream(
        val itag: Int, val url: String, val height: Int, val fps: Int, val contentLength: Long,
        val initEnd: Long, val indexStart: Long, val indexEnd: Long,
    )

    private suspend fun network(videoId: String, startMs: Long, endMs: Long, maxHeight: Int, saved: Boolean): ClipFetch {
        var lastError = ""
        for (attempt in 0..1) {
            val answer = try { player(videoId) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return ClipFetch.None(ClipFetch.Why.UNPLAYABLE, e.message.orEmpty())
            }
            val status = answer.str("playabilityStatus", "status")
            if (status != "OK") return ClipFetch.None(ClipFetch.Why.UNPLAYABLE, status ?: "no status")
            val stream = chooseStream(answer, maxHeight) ?: return ClipFetch.None(ClipFetch.Why.NO_STREAM)
            try {
                return cut(videoId, stream, startMs, endMs, saved)
            } catch (e: Refused) {
                // An expired or turned-away link: once more with a fresh one.
                lastError = e.message.orEmpty()
            } catch (e: OutOfRange) {
                return ClipFetch.None(ClipFetch.Why.OUT_OF_RANGE, e.message.orEmpty())
            } catch (e: IOException) {
                return ClipFetch.None(ClipFetch.Why.NETWORK, e.message.orEmpty())
            }
        }
        return ClipFetch.None(ClipFetch.Why.NETWORK, lastError)
    }

    private class Refused(message: String) : IOException(message)
    private class OutOfRange(message: String) : IOException(message)

    private fun cut(videoId: String, stream: Stream, startMs: Long, endMs: Long, saved: Boolean): ClipFetch.Got {
        // The init segment (ftyp + moov) and the index (sidx) sit together at the head: one small request.
        val head = get(stream.url, 0, stream.indexEnd) { it.readByteArray() }
        if (head.size.toLong() != stream.indexEnd + 1) throw IOException("Head: ${head.size} bytes, wanted ${stream.indexEnd + 1}")
        val sidx = Fmp4.parseSidx(head, stream.indexStart.toInt())
        val initBytes = head.copyOfRange(0, stream.indexStart.toInt())
        val init = Fmp4.parseInit(initBytes)
        val pick = pickFragments(sidx, startMs, endMs, capMs, capBytes)
            ?: throw OutOfRange("$startMs-$endMs ms is outside the video's ${sidx.fragments.lastOrNull()?.let(sidx::endMs) ?: 0} ms")
        val frags = pick.fragments
        val from = frags.first().offset
        val to = frags.last().end - 1
        val tmp = files.temp(saved)
        try {
            val keys = ArrayList<Long>()
            tmp.sink().buffer().use { out ->
                out.write(initBytes)
                val sidxStart = initBytes.size.toLong()
                val sidxSize = Fmp4.sidxSize(frags.size)
                // The first moof's own decode time is the shift; it is read from the moof, not assumed from the index.
                get(stream.url, from, to) { src ->
                    val firstHeader = src.peek().readByteArray(8)
                    val firstSize = Fmp4.u32(firstHeader, 0)
                    if (String(firstHeader, 4, 4, Charsets.ISO_8859_1) != "moof" || firstSize > 4L shl 20) throw IOException("Range doesn't start on a moof")
                    val shift = Fmp4.decodeTime(src.peek().readByteArray(firstSize))
                    // The index keeps the first fragment's presentation offset from its decode time (the B-frame delay).
                    val shiftInSidx = shift * sidx.timescale / init.mediaTimescale
                    Fmp4.writeSidx(out, sidx.referenceId, sidx.timescale, (frags.first().startTicks - shiftInSidx).coerceAtLeast(0), frags)
                    val moved = sidxStart + sidxSize - from
                    keys += Fmp4.copyFragments(src, out, to - from + 1, init, shift, moved)
                }
            }
            val startOut = sidx.startMs(frags.first())
            val endOut = sidx.endMs(frags.last())
            val kept = files.keep(tmp, videoId, stream.height, stream.itag, startOut, endOut, pick.capped, saved)
            val keyMs = mergeKeys(keys.map { it * 1000 / init.mediaTimescale }, frags.map(sidx::startMs))
            return ClipFetch.Got(kept.file, stream.height, stream.itag, startOut, endOut, keyMs, kept.file.length(), fromNetwork = true, saved = saved)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Asks for bytes [from]..[to] of [url]; a refusal is [Refused], so the caller can ask for a fresh link. */
    private fun <T> get(url: String, from: Long, to: Long, read: (okio.BufferedSource) -> T): T {
        val req = Request.Builder().url(url).header("Range", "bytes=$from-$to").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 403 || resp.code == 410 || resp.code == 401) throw Refused("HTTP ${resp.code} for bytes $from-$to")
            if (resp.code != 206 && !(resp.code == 200 && from == 0L && resp.body.contentLength() == to + 1)) {
                throw IOException("HTTP ${resp.code} for bytes $from-$to")
            }
            val len = resp.body.contentLength()
            if (len >= 0 && len != to - from + 1) throw IOException("Got $len bytes for $from-$to")
            return try { read(resp.body.source()) } catch (e: IOException) {
                // A later chunk of a stitched read turned away: as the first one would be.
                if (e.message?.contains("403") == true) throw Refused(e.message.orEmpty()) else throw e
            }
        }
    }

    /** A kept file as a result: its key frames are read back from its own moofs (no network, no side files). */
    private fun gotFromFile(k: ClipFiles.Kept): ClipFetch.Got {
        val keys = runCatching { keyFramesOf(k.file) }.getOrDefault(emptyList()).map { k.startMs + it }
        return ClipFetch.Got(k.file, k.height, k.itag, k.startMs, k.endMs, keys.ifEmpty { listOf(k.startMs) }, k.file.length(), fromNetwork = false, saved = k.saved)
    }

    companion object {
        /** The heights a loop is cut at, best first. */
        val LADDER = listOf(1080, 720, 480, 360)

        /**
         * The stream to cut from: video-only H.264 with a direct link and an index, the best height on [LADDER] not
         * above [maxHeight]. At a height the lowest frame rate; a height with only 50/60 frames a second gives way to
         * the next one down that has 30 or fewer (a loop doesn't need the frames, and they cost decoding), unless no
         * height has, when the lowest frame rate at the best height is taken.
         */
        fun chooseStream(answer: JsonObject, maxHeight: Int): Stream? {
            val all = answer.arr("streamingData", "adaptiveFormats").orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val mime = f.str("mimeType") ?: return@mapNotNull null
                if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) return@mapNotNull null
                val url = f.str("url") ?: return@mapNotNull null
                val init = f.obj("initRange") ?: return@mapNotNull null
                val index = f.obj("indexRange") ?: return@mapNotNull null
                val w = f.str("width")?.toIntOrNull() ?: 0
                val h = f.str("height")?.toIntOrNull() ?: 0
                // "1080p" is the shorter side's lines: a 21:9 film's is 1920×800, an upright video's 1080×1920.
                val lines = f.str("qualityLabel")?.takeWhile { it.isDigit() }?.toIntOrNull()
                    ?: if (w > 0 && h > 0) minOf(w, h) else h
                Stream(
                    itag = f.str("itag")?.toIntOrNull() ?: return@mapNotNull null,
                    url = url,
                    height = lines,
                    fps = f.str("fps")?.toIntOrNull() ?: 30,
                    contentLength = f.str("contentLength")?.toLongOrNull() ?: 0,
                    initEnd = init.str("end")?.toLongOrNull() ?: return@mapNotNull null,
                    indexStart = index.str("start")?.toLongOrNull() ?: return@mapNotNull null,
                    indexEnd = index.str("end")?.toLongOrNull() ?: return@mapNotNull null,
                ).takeIf { it.indexStart > it.initEnd && it.indexEnd > it.indexStart && it.indexEnd < 1L shl 20 }
            }
            val ladder = LADDER.filter { it <= maxHeight }
            for (h in ladder) {
                all.filter { it.height == h && it.fps <= 30 }.sortedWith(compareBy({ it.fps }, { it.itag })).firstOrNull()?.let { return it }
            }
            for (h in ladder) {
                all.filter { it.height == h }.sortedWith(compareBy({ it.fps }, { it.itag })).firstOrNull()?.let { return it }
            }
            return null
        }

        data class Pick(val fragments: List<Fmp4.Fragment>, val capped: Boolean)

        /**
         * The fragments that hold [startMs]..[endMs]: from the one [startMs] falls in (so the loop starts on its key
         * frame) through the one [endMs] falls in. Dropped from the end past [capMs] of media or [capBytes], but the
         * first is always kept. Null when the start is past the video's end.
         */
        fun pickFragments(sidx: Fmp4.Sidx, startMs: Long, endMs: Long, capMs: Long, capBytes: Long): Pick? {
            val frags = sidx.fragments
            if (frags.isEmpty()) return null
            // Compared in the index's own ticks: a fragment ending at 3586.9 ms holds 3586 ms.
            val ts = sidx.timescale
            val first = frags.indexOfFirst { it.endTicks * 1000 > startMs * ts }.takeIf { it >= 0 } ?: return null
            var last = frags.indexOfFirst { it.endTicks * 1000 >= endMs * ts }.let { if (it < 0) frags.lastIndex else it }
            if (last < first) last = first
            val out = ArrayList<Fmp4.Fragment>()
            var bytes = 0L
            for (i in first..last) {
                val f = frags[i]
                val span = sidx.endMs(f) - sidx.startMs(frags[first])
                if (out.isNotEmpty() && (span > capMs || bytes + f.size > capBytes)) return Pick(out, capped = true)
                out += f
                bytes += f.size
            }
            return Pick(out, capped = false)
        }

        /**
         * The samples marked as key frames, plus the fragment starts the index promises are key frames (a fragment's
         * first sample is marked too, so they mostly agree; one counted from the index and one from the samples can
         * differ by a rounding millisecond, kept once).
         */
        fun mergeKeys(samples: List<Long>, fragmentStarts: List<Long>): List<Long> {
            val out = samples.distinct().sorted().toMutableList()
            for (s in fragmentStarts) if (out.none { kotlin.math.abs(it - s) <= 2 }) out += s
            return out.sorted()
        }

        /** The key frames of a kept file, in its own time (0 = its first frame), from its moofs. */
        fun keyFramesOf(file: File): List<Long> {
            val raf = java.io.RandomAccessFile(file, "r")
            raf.use {
                val head = ByteArray(minOf(raf.length(), 64L * 1024).toInt())
                raf.readFully(head)
                val boxes = Fmp4.boxes(head, 0, head.size)
                val moov = boxes.firstOrNull { it.type == "moov" } ?: return emptyList()
                val init = Fmp4.parseInit(head.copyOfRange(0, moov.end))
                val out = ArrayList<Long>()
                var p = 0L
                val hdr = ByteArray(16)
                while (p + 8 <= raf.length()) {
                    raf.seek(p)
                    raf.readFully(hdr, 0, 8)
                    var size = Fmp4.u32(hdr, 0)
                    if (size == 1L) { raf.readFully(hdr, 8, 8); size = Fmp4.u64(hdr, 8) }
                    if (size < 8 || p + size > raf.length()) break
                    if (String(hdr, 4, 4, Charsets.ISO_8859_1) == "moof" && size < 4L shl 20) {
                        val moof = ByteArray(size.toInt())
                        raf.seek(p)
                        raf.readFully(moof)
                        out += Fmp4.keyFrameTicks(moof, init).map { it * 1000 / init.mediaTimescale }
                    }
                    p += size
                }
                return out.distinct().sorted()
            }
        }
    }
}
