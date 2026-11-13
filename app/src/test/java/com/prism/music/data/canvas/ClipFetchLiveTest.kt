package com.prism.music.data.canvas

import com.prism.music.LiveNetwork
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.stream.YouTubeStreamInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Canvas's fetch (4.2) for real: 8 s of fcnDmrtj6Sk at 720p through the app's own stream client (the same
 * interceptor the player uses), then a box walk of the kept file. Prints the bytes and requests it took. Live.
 */
@Category(LiveNetwork::class)
class ClipFetchLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun eightSecondsAt720() = runBlocking {
        val videoRequests = AtomicInteger()
        val otherRequests = AtomicInteger()
        val videoBytes = AtomicLong()
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                if (chain.request().url.host.endsWith("googlevideo.com")) {
                    videoRequests.incrementAndGet()
                    resp.body.contentLength().takeIf { it > 0 }?.let(videoBytes::addAndGet)
                } else otherRequests.incrementAndGet()
                resp
            }.build()
        val streamHttp = http.newBuilder().addInterceptor(YouTubeStreamInterceptor(http)).build()
        var settings = AppSettings()
        val innerTube = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
        val files = ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 400L shl 20 })
        val fetcher = ClipFetcher(streamHttp, { id -> withContext(Dispatchers.IO) { innerTube.visionOsPlayer(id) } }, files)

        val got = fetcher.fetch("fcnDmrtj6Sk", 60_000, 68_000, 720, saved = false, allowed = { true })
        println("[clip-live] $got")
        assertTrue(got.toString(), got is ClipFetch.Got)
        got as ClipFetch.Got
        println("[clip-live] kept ${got.startMs}-${got.endMs} ms (${got.endMs - got.startMs} ms) at ${got.height}p itag ${got.itag}: " +
            "file ${got.bytes} bytes; googlevideo ${videoRequests.get()} requests, ${videoBytes.get()} bytes; other requests ${otherRequests.get()}; key frames ${got.keyFramesMs}")
        assertEquals(720, got.height)
        assertTrue(got.startMs <= 60_000 && got.endMs >= 68_000)
        val bytes = got.file.readBytes()
        val top = Fmp4.topLevel(bytes)
        assertEquals(listOf("ftyp", "moov", "sidx"), top.take(3))
        assertTrue(top.drop(3).isNotEmpty() && top.drop(3).chunked(2).all { it == listOf("moof", "mdat") })
        val sidx = Fmp4.parseSidx(bytes, Fmp4.boxes(bytes, 0, bytes.size).first { it.type == "sidx" }.start)
        assertEquals(bytes.size.toLong(), sidx.fragments.last().end)
        assertTrue("the file's own index runs as long as the kept seconds", kotlin.math.abs(got.endMs - got.startMs - sidx.endMs(sidx.fragments.last())) <= 2)

        // Again: from the phone, no request at all.
        val before = videoRequests.get() + otherRequests.get()
        val again = fetcher.fetch("fcnDmrtj6Sk", 60_000, 68_000, 720, saved = false, allowed = { false })
        assertTrue(again is ClipFetch.Got && !again.fromNetwork)
        assertEquals(before, videoRequests.get() + otherRequests.get())
    }
}
