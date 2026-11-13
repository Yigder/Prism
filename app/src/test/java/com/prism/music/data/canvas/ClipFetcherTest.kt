package com.prism.music.data.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canvas's fetch (4.2) against a fake googlevideo: only the wanted byte ranges are asked, the kept file is a whole MP4
 * (ftyp, moov, its own sidx, moof/mdat pairs, time from 0), asking again needs no network, nothing is asked when the
 * rules say no, an expired link is asked for once more, and a 50/60-frame-only height steps down.
 */
class ClipFetcherTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Answers Range requests from [content] (by link), turning away links [refuse] names with a 403. */
    class FakeGooglevideo(val content: (HttpUrl) -> (Long, Long) -> ByteArray, val delayMs: Long = 0) : Interceptor {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var refuse: (HttpUrl) -> Boolean = { false }

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val m = Regex("bytes=(\\d+)-(\\d+)").find(req.header("Range") ?: error("no Range header"))!!
            val from = m.groupValues[1].toLong()
            val to = m.groupValues[2].toLong()
            asked += "$from-$to"
            if (delayMs > 0) Thread.sleep(delayMs)
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            if (refuse(req.url)) return b.code(403).message("Forbidden").body(ByteArray(0).toResponseBody(null)).build()
            val body = content(req.url)(from, to)
            return b.code(206).message("Partial Content").header("Content-Range", "bytes $from-$to/*")
                .body(body.toResponseBody(null)).build()
        }
    }

    private fun format(itag: Int, height: Int, fps: Int, url: String, indexStart: Long, indexEnd: Long, mime: String = "video/mp4; codecs=\"avc1.4d401f\""): JsonObject =
        buildJsonObject {
            put("itag", itag); put("url", url); put("mimeType", mime)
            put("width", height * 16 / 9); put("height", height); put("fps", fps)
            put("qualityLabel", "${height}p${if (fps > 30) fps.toString() else ""}")
            put("contentLength", "1000")
            putJsonObject("initRange") { put("start", "0"); put("end", (indexStart - 1).toString()) }
            putJsonObject("indexRange") { put("start", indexStart.toString()); put("end", indexEnd.toString()) }
        }

    private fun answer(vararg formats: JsonObject, status: String = "OK") = buildJsonObject {
        putJsonObject("playabilityStatus") { put("status", status) }
        putJsonObject("streamingData") { putJsonArray("adaptiveFormats") { formats.forEach { add(it) } } }
    }

    private val stream = SyntheticStream()
    private fun serveSynthetic(delayMs: Long = 0) = FakeGooglevideo({ { from, to -> stream.bytes.copyOfRange(from.toInt(), to.toInt() + 1) } }, delayMs)
    private val files by lazy { ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 }) }
    private val playerCalls = AtomicInteger()
    private var link = "https://rr1---sn-test.googlevideo.com/videoplayback?id=x&itag=136&sig=new"

    private fun fetcher(fake: FakeGooglevideo, links: List<String>? = null) = ClipFetcher(
        OkHttpClient.Builder().addInterceptor(fake).build(),
        { id ->
            val n = playerCalls.getAndIncrement()
            val url = links?.getOrNull(n) ?: link
            answer(format(136, 720, 24, url, stream.indexStart, stream.indexEnd))
        },
        files,
    )

    private fun tfdts(file: File): List<Long> {
        val b = file.readBytes()
        return Fmp4.boxes(b, 0, b.size).filter { it.type == "moof" }.map { Fmp4.decodeTime(b.copyOfRange(it.start, it.end)) }
    }

    @Test fun onlyTheWantedRangesAndAWholeFile() = runBlocking {
        val fake = serveSynthetic()
        val got = fetcher(fake).fetch("vidvidvid01", 5_000, 9_000, 1080, saved = false, allowed = { true }) as ClipFetch.Got
        // The head (init + index), then fragments 2..4 (4 s to 10 s) as one range.
        assertEquals(listOf("0-${stream.indexEnd}", "${stream.offsets[2]}-${stream.offsets[5] - 1}"), fake.asked)
        assertEquals(4_000L, got.startMs)
        assertEquals(10_000L, got.endMs)
        assertEquals(listOf(4_000L, 6_000L, 8_000L), got.keyFramesMs)
        assertEquals(720, got.height)
        assertEquals(136, got.itag)
        assertTrue(got.fromNetwork)
        val bytes = got.file.readBytes()
        assertEquals(got.bytes, bytes.size.toLong())
        assertEquals(listOf("ftyp", "moov", "sidx", "moof", "mdat", "moof", "mdat", "moof", "mdat"), Fmp4.topLevel(bytes))
        assertEquals(stream.init.size + Fmp4.sidxSize(3) + stream.fragments.subList(2, 5).sumOf { it.size }, bytes.size.toLong())
        assertEquals("time from 0, distances kept", listOf(0L, 48_000L, 96_000L), tfdts(got.file))
        // Its own index: 3 fragments right after it, from 0.
        val sidxBox = Fmp4.boxes(bytes, 0, bytes.size).first { it.type == "sidx" }
        val sidx = Fmp4.parseSidx(bytes, sidxBox.start)
        assertEquals(3, sidx.fragments.size)
        assertEquals(0L, sidx.fragments.first().startTicks)
        assertEquals(sidxBox.end.toLong(), sidx.fragments.first().offset)
        assertEquals(bytes.size.toLong(), sidx.fragments.last().end)
        // The mdat bytes arrive untouched.
        val firstMdat = Fmp4.boxes(bytes, 0, bytes.size).first { it.type == "mdat" }
        val srcMdat = Fmp4.boxes(stream.fragments[2], 0, stream.fragments[2].size).first { it.type == "mdat" }
        assertTrue(bytes.copyOfRange(firstMdat.start, firstMdat.end).contentEquals(stream.fragments[2].copyOfRange(srcMdat.start, srcMdat.end)))
        assertTrue("no parts left behind", File(tmp.root, "recent").listFiles()!!.all { it.name.endsWith(".mp4") })
    }

    @Test fun askingAgainNeedsNoNetwork() = runBlocking {
        val fake = serveSynthetic()
        val f = fetcher(fake)
        val first = f.fetch("vidvidvid01", 5_000, 9_000, 1080, saved = false, allowed = { true }) as ClipFetch.Got
        val n = fake.asked.size
        val calls = playerCalls.get()
        // Offline now: the rules say no, and the network would fail.
        fake.refuse = { true }
        val again = f.fetch("vidvidvid01", 6_000, 9_000, 1080, saved = false, allowed = { false }) as ClipFetch.Got
        assertEquals(n, fake.asked.size)
        assertEquals(calls, playerCalls.get())
        assertFalse(again.fromNetwork)
        assertEquals(first.file, again.file)
        assertEquals(first.startMs, again.startMs)
        assertEquals(first.endMs, again.endMs)
        assertEquals(first.keyFramesMs, again.keyFramesMs)
        // Asked again for a download: the same file moves to saved, still without the network.
        val saved = f.fetch("vidvidvid01", 5_000, 9_000, 1080, saved = true, allowed = { false }) as ClipFetch.Got
        assertTrue(saved.saved)
        assertTrue(saved.file.path.contains("saved"))
        assertEquals(n, fake.asked.size)
    }

    @Test fun nothingAskedWhenNotAllowed() = runBlocking {
        val fake = serveSynthetic()
        val r = fetcher(fake).fetch("vidvidvid01", 5_000, 9_000, 1080, saved = false, allowed = { false })
        assertEquals(ClipFetch.None(ClipFetch.Why.NOT_ALLOWED), r)
        assertTrue(fake.asked.isEmpty())
        assertEquals(0, playerCalls.get())
    }

    @Test fun expiredLinkIsAskedForOnceMore() = runBlocking {
        val fake = serveSynthetic()
        fake.refuse = { it.queryParameter("sig") == "old" }
        val got = fetcher(fake, links = listOf("https://rr1---sn-test.googlevideo.com/videoplayback?id=x&sig=old", link))
            .fetch("vidvidvid01", 0, 3_000, 720, saved = false, allowed = { true })
        assertTrue(got.toString(), got is ClipFetch.Got)
        assertEquals(2, playerCalls.get())
        assertEquals(listOf("0-${stream.indexEnd}", "0-${stream.indexEnd}", "${stream.offsets[0]}-${stream.offsets[2] - 1}"), fake.asked)
    }

    @Test fun refusedTwiceGivesUp() = runBlocking {
        val fake = serveSynthetic()
        fake.refuse = { true }
        val r = fetcher(fake).fetch("vidvidvid01", 0, 3_000, 720, saved = false, allowed = { true })
        assertEquals(ClipFetch.Why.NETWORK, (r as ClipFetch.None).why)
        assertEquals(2, playerCalls.get())
        assertTrue(File(tmp.root, "recent").listFiles().orEmpty().isEmpty())
    }

    @Test fun pastTheEndAndUnplayable() = runBlocking {
        val fake = serveSynthetic()
        val r = fetcher(fake).fetch("vidvidvid01", 25_000, 30_000, 720, saved = false, allowed = { true })
        assertEquals(ClipFetch.Why.OUT_OF_RANGE, (r as ClipFetch.None).why)
        val blocked = ClipFetcher(OkHttpClient.Builder().addInterceptor(fake).build(), { answer(status = "LOGIN_REQUIRED") }, files)
            .fetch("vidvidvid02", 0, 3_000, 720, saved = false, allowed = { true })
        assertEquals(ClipFetch.Why.UNPLAYABLE, (blocked as ClipFetch.None).why)
        val noStream = ClipFetcher(OkHttpClient.Builder().addInterceptor(fake).build(), {
            answer(format(248, 1080, 24, link, 100, 200, mime = "video/webm; codecs=\"vp9\""))
        }, files).fetch("vidvidvid03", 0, 3_000, 1080, saved = false, allowed = { true })
        assertEquals(ClipFetch.Why.NO_STREAM, (noStream as ClipFetch.None).why)
    }

    @Test fun sameClipAtOnceSharesOneFetch() = runBlocking {
        val fake = serveSynthetic(delayMs = 150)
        val f = fetcher(fake)
        val results = (1..3).map { async(Dispatchers.IO) { f.fetch("vidvidvid01", 5_000, 9_000, 1080, saved = false, allowed = { true }) } }.awaitAll()
        assertTrue(results.all { it is ClipFetch.Got })
        assertEquals(1, playerCalls.get())
        assertEquals(2, fake.asked.size)
        assertEquals(1, results.map { (it as ClipFetch.Got).file }.toSet().size)
    }

    @Test fun streamChoice() {
        fun pick(max: Int, vararg f: JsonObject) = ClipFetcher.chooseStream(answer(*f), max)?.let { "${it.itag}:${it.height}@${it.fps}" }
        val u = link
        val f1080x60 = format(299, 1080, 60, u, 740, 1500)
        val f1080x30 = format(137, 1080, 30, u, 740, 1500)
        val f720x60 = format(298, 720, 60, u, 740, 1500)
        val f720x30 = format(136, 720, 30, u, 740, 1500)
        val f480 = format(135, 480, 30, u, 740, 1500)
        assertEquals("137:1080@30", pick(1080, f1080x60, f1080x30, f720x30))
        assertEquals("at a height the lowest frame rate", "137:1080@30", pick(1080, f1080x30, f1080x60))
        assertEquals("1080 only at 60: the next height at 30", "136:720@30", pick(1080, f1080x60, f720x60, f720x30, f480))
        assertEquals("only 60 anywhere near: down to 480 at 30", "135:480@30", pick(1080, f1080x60, f720x60, f480))
        assertEquals("nothing at 30: the best height's lowest", "299:1080@60", pick(1080, f1080x60, f720x60))
        assertEquals("never above the max", "136:720@30", pick(720, f1080x30, f720x30))
        assertEquals(null, pick(1080, format(248, 1080, 30, u, 740, 1500, mime = "video/webm; codecs=\"vp9\"")))
        // A link without an index can't be cut; one that needs a signature has no url.
        val noIndex = buildJsonObject { f1080x30.forEach { (k, v) -> if (k != "indexRange") put(k, v) } }
        val ciphered = buildJsonObject { f720x30.forEach { (k, v) -> if (k != "url") put(k, v) } }
        assertEquals("135:480@30", pick(1080, noIndex, ciphered, f480))
        // A wide film's "1080p" is 1920×800: the label counts, not the pixel height.
        val film = buildJsonObject { f1080x30.forEach { (k, v) -> if (k != "height") put(k, v) }; put("height", 800) }
        assertEquals("137:1080@30", pick(1080, film))
    }

    @Test fun fragmentChoiceOnARealIndex() {
        val head = javaClass.classLoader!!.getResourceAsStream("canvas/fcnDmrtj6Sk-720.head.bin")!!.readBytes()
        val sidx = Fmp4.parseSidx(head, 739)
        // Fragments: 0 at 0, 1 at 3.587 s, 2 at 6.965 s, 3 at 13.138 s, 4 at 20.145 s.
        fun pick(s: Long, e: Long, capMs: Long = 20_000, capBytes: Long = 12L shl 20) =
            ClipFetcher.pickFragments(sidx, s, e, capMs, capBytes)!!.let { p -> p.fragments.map { it.index } to p.capped }
        assertEquals("start mid-fragment snaps back, end mid-fragment runs through", listOf(1, 2, 3) to false, pick(5_000, 15_000))
        assertEquals(3_586L, sidx.startMs(sidx.fragments[1]))
        assertEquals("a start at a fragment's start is that fragment", listOf(1) to false, pick(3_587, 6_000))
        assertEquals("3586 ms is still fragment 0 (it ends at 3586.9)", listOf(0, 1) to false, pick(3_586, 6_000))
        assertEquals("an end on a boundary stops there", listOf(1) to false, pick(4_000, 6_965))
        assertEquals("the time cap drops from the end", listOf(1, 2) to true, pick(5_000, 15_000, capMs = 10_000))
        val f1 = sidx.fragments[1].size
        val f2 = sidx.fragments[2].size
        assertEquals("the size cap too", listOf(1) to true, pick(5_000, 15_000, capBytes = f1 + f2 - 1))
        assertEquals("the first is always kept", listOf(1) to false, pick(5_000, 6_000, capBytes = 1))
        assertEquals(null, ClipFetcher.pickFragments(sidx, 300_000, 310_000, 20_000, 12L shl 20))
        assertEquals("an end past the video runs to its last fragment", 48, pick(238_000, 250_000).first.last())
    }

    /** The same fetch over a real 720p stream on disk (skipped where it isn't), checked by ffprobe when it's there. */
    @Test fun realStreamFromDisk() = runBlocking {
        val src = File("C:/Dev/Prism/.claude/canvas-study/spike/fcnDmrtj6Sk-720.mp4")
        assumeTrue("the spike's stream is on this PC", src.exists())
        val fake = FakeGooglevideo({ { from, to ->
            RandomAccessFile(src, "r").use { r -> ByteArray((to - from + 1).toInt()).also { r.seek(from); r.readFully(it) } }
        } })
        val f = ClipFetcher(OkHttpClient.Builder().addInterceptor(fake).build(), { answer(format(136, 720, 24, link, 739, 1358)) }, files)
        val got = f.fetch("fcnDmrtj6Sk", 30_000, 38_000, 720, saved = false, allowed = { true }) as ClipFetch.Got
        val sidx = Fmp4.parseSidx(javaClass.classLoader!!.getResourceAsStream("canvas/fcnDmrtj6Sk-720.head.bin")!!.readBytes(), 739)
        val frags = sidx.fragments.filter { it.endTicks * 1000 > 30_000L * sidx.timescale && it.startTicks * 1000 < 38_000L * sidx.timescale }
        assertEquals(listOf("0-1358", "${frags.first().offset}-${frags.last().end - 1}"), fake.asked)
        println("[clip] real 720p 30-38 s: kept ${got.startMs}-${got.endMs} ms, ${got.bytes} bytes, key frames ${got.keyFramesMs}")
        val bytes = got.file.readBytes()
        val top = Fmp4.topLevel(bytes)
        assertEquals(listOf("ftyp", "moov", "sidx"), top.take(3))
        assertEquals(List(frags.size) { listOf("moof", "mdat") }.flatten(), top.drop(3))
        assertEquals(0L, tfdts(got.file).first())
        val probe = runCatching {
            ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
                "-show_entries", "stream=nb_read_frames:format=duration", "-of", "default=nw=1", got.file.path)
                .redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        }.getOrNull()
        assumeTrue("ffprobe on this PC", probe != null)
        println("[clip] ffprobe: ${probe!!.trim().replace('\n', ' ')}")
        val frames = Regex("nb_read_frames=(\\d+)").find(probe)!!.groupValues[1].toInt()
        val duration = Regex("duration=([\\d.]+)").find(probe)!!.groupValues[1].toDouble()
        val wanted = (got.endMs - got.startMs) / 1000.0
        assertTrue("$frames frames for $wanted s at 23.976", kotlin.math.abs(frames - wanted * 24_000 / 1001) < 3)
        assertTrue("duration $duration for $wanted", kotlin.math.abs(duration - wanted) < 0.2)
        assertFalse(probe.contains("error", ignoreCase = true))
        val first = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
            "-show_entries", "frame=pts_time,key_frame", "-of", "csv=p=0", got.file.path)
            .start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }.trim()
        println("[clip] first frame: $first")
        assertTrue("the first frame is a key frame at 0: $first", first.startsWith("1,0.000000") || first.startsWith("0.000000,1"))
    }
}
