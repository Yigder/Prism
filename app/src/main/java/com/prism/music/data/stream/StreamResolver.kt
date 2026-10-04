package com.prism.music.data.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.prefs.SettingsRepository
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** Bridges NewPipeExtractor's HTTP needs to OkHttp. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): NpResponse {
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", DESKTOP_UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}

/**
 * Mirrors NewPipe's YoutubeHttpDataSource on top of OkHttp: googlevideo
 * "videoplayback" requests are sent as POST with a range query parameter, a
 * request counter, and client-appropriate headers. Responses to a translated
 * range request are reported as 206 so ExoPlayer doesn't skip bytes.
 *
 * googlevideo throttles long open-ended reads to roughly playback speed, so
 * when [chunkClient] is given, any read longer than [chunkSize] is served as
 * one continuous response stitched from short range requests (the next one
 * prefetched while the current one is read), which arrive at full line speed.
 */
class YouTubeStreamInterceptor(
    private val chunkClient: OkHttpClient? = null,
    private val chunkSize: Long = 1L shl 20,
) : Interceptor {
    private val requestNumber = AtomicInteger(0)

    private fun isStream(url: HttpUrl) = url.host.endsWith("googlevideo.com") && url.encodedPath.startsWith("/videoplayback")

    /** The POST googlevideo expects, for bytes [from]..[to] (open-ended when [to] is null). */
    private fun streamRequest(base: okhttp3.Request, from: Long, to: Long?, ranged: Boolean): okhttp3.Request {
        val url = base.url
        val newUrl = url.newBuilder().setQueryParameter("rn", requestNumber.incrementAndGet().toString())
        if (ranged) newUrl.setQueryParameter("range", "$from-${to ?: ""}")
        val full = url.toString()
        val builder = base.newBuilder()
            .url(newUrl.build())
            .removeHeader("Range")
            .post(byteArrayOf(0x78, 0).toRequestBody(null))
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(full)) YoutubeParsingHelper.getVisionOsUserAgent(null)
                else DESKTOP_UA,
            )
        if (YoutubeParsingHelper.isWebStreamingUrl(full)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return builder.build()
    }

    /** googlevideo occasionally rejects a request transiently; one quick retry usually succeeds. */
    private inline fun withRetry(request: okhttp3.Request, send: (okhttp3.Request) -> Response): Response {
        val resp = send(request)
        if (resp.code != 403) return resp
        resp.close()
        Thread.sleep(350)
        return send(request.newBuilder().url(request.url.newBuilder().setQueryParameter("rn", requestNumber.incrementAndGet().toString()).build()).build())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (!isStream(url)) return chain.proceed(req)
        if (url.queryParameter("range") != null) return withRetry(streamRequest(req, 0, null, false), chain::proceed)

        val m = req.header("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLong() ?: 0L
        val clen = url.queryParameter("clen")?.toLongOrNull()
        val end = m?.groupValues?.get(2)?.toLongOrNull()?.let { e -> clen?.let { minOf(e, it - 1) } ?: e } ?: clen?.minus(1)

        if (chunkClient != null && clen != null && end != null && end - start + 1 > chunkSize) {
            val firstEnd = minOf(end, start + chunkSize - 1)
            val first = withRetry(streamRequest(req, start, firstEnd, true), chain::proceed)
            if (!first.isSuccessful) return first
            val len = end - start + 1
            val body = ChunkedBody(first, start, end, req)
            val rb = first.newBuilder().body(body).removeHeader("Content-Length").removeHeader("Content-Range")
                .header("Content-Length", len.toString())
            return if (m != null) rb.code(206).message("Partial Content").header("Content-Range", "bytes $start-$end/$clen").build()
            else rb.code(200).message("OK").build()
        }

        val translated = m != null && !(start == 0L && m.groupValues[2].isEmpty())
        val resp = withRetry(streamRequest(req, start, m?.groupValues?.get(2)?.toLongOrNull(), translated), chain::proceed)
        if (translated && resp.code == 200) {
            val len = resp.header("Content-Length")?.toLongOrNull()
            val rb = resp.newBuilder().code(206).message("Partial Content")
            if (len != null) rb.header("Content-Range", "bytes $start-${start + len - 1}/${clen ?: "*"}")
            return rb.build()
        }
        return resp
    }

    /** Streams bytes [start]..[end] by reading short ranges back to back. */
    private inner class ChunkedBody(first: Response, start: Long, private val end: Long, private val base: okhttp3.Request) : ResponseBody() {
        private val type = first.body.contentType()
        private var pos = start
        private var current: Response? = first
        private var currentEnd = minOf(end, start + chunkSize - 1)
        private var next: Pair<Long, Future<Response>>? = null

        private fun fetch(from: Long): Future<Response> {
            val to = minOf(end, from + chunkSize - 1)
            val f = CompletableFuture<Response>()
            val call = chunkClient!!.newCall(streamRequest(base, from, to, true))
            f.whenComplete { _, _ -> if (f.isCancelled) call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { f.completeExceptionally(e) }
                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 403) {
                        response.close()
                        runCatching { chunkClient.newCall(streamRequest(base, from, to, true)).execute() }
                            .onSuccess { if (!f.complete(it)) it.close() }.onFailure { f.completeExceptionally(it) }
                    } else if (!f.complete(response)) response.close()
                }
            })
            return f
        }

        private fun prefetch() {
            val from = currentEnd + 1
            if (from <= end && next == null) next = from to fetch(from)
        }

        private fun open() {
            current?.close()
            current = null
            val pending = next?.takeIf { it.first == pos }
            if (pending == null) next?.second?.cancel(true)
            next = null
            val resp = try {
                (pending?.second ?: fetch(pos)).get()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: InterruptedException) {
                throw java.io.InterruptedIOException()
            }
            if (!resp.isSuccessful) {
                resp.close()
                throw IOException("Stream chunk at $pos failed: HTTP ${resp.code}")
            }
            current = resp
            currentEnd = minOf(end, pos + chunkSize - 1)
        }

        private val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (pos > end) return -1
                while (true) {
                    val resp = current ?: run { open(); current!! }
                    if (next == null) prefetch()
                    val n = resp.body.source().read(sink, minOf(byteCount, end - pos + 1))
                    if (n > 0) {
                        pos += n
                        return n
                    }
                    if (pos > end) return -1
                    current?.close()
                    current = null
                }
            }

            override fun timeout() = Timeout.NONE
            override fun close() {
                current?.close()
                next?.second?.let { f -> f.cancel(true); if (f.isDone && !f.isCancelled) runCatching { f.get().close() } }
                next = null
            }
        }.buffer()

        override fun contentType() = type
        private val length = end - start + 1
        override fun contentLength() = length
        override fun source(): BufferedSource = source
    }
}

data class ResolvedStream(
    val videoId: String,
    val audioUrl: String,
    val itag: Int,
    val mimeType: String,
    val codec: String?,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val contentLength: Long,
    val client: String?,
    val expiresAtMs: Long,
    val videoUrl: String? = null,
    val videoHeight: Int = 0,
    val videoCodec: String? = null,
    val videoFps: Int = 0,
)

class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Resolves playable googlevideo URLs for a video id with NewPipeExtractor. */
class StreamResolver(
    private val context: Context,
    http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val cache = ConcurrentHashMap<String, Pair<StreamInfo, Long>>()

    init {
        NewPipe.init(NewPipeDownloader(http), Localization(Locale.getDefault().language.ifBlank { "en" }, Locale.getDefault().country.ifBlank { "US" }))
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    fun currentQuality(): AudioQuality = when {
        settings.current.lossless -> AudioQuality.HIGH // lossless mode always takes the best stream there is
        isMetered() -> settings.current.cellularQuality
        else -> settings.current.audioQuality
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun info(videoId: String): StreamInfo {
        cache[videoId]?.let { (info, exp) -> if (System.currentTimeMillis() < exp) return info }
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) {
            throw StreamException(e.message ?: "Couldn't load this track", e)
        }
        val firstUrl = info.audioStreams.firstOrNull()?.content
        val expire = firstUrl?.let { Uri.parse(it).getQueryParameter("expire")?.toLongOrNull() }
            ?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        cache[videoId] = info to expire
        return info
    }

    data class AnalysisStream(val url: String, val contentLength: Long, val durationSec: Long)

    /** The smallest audio stream (AAC preferred, which decodes from a partial file), for analysis. */
    fun analysisAudio(videoId: String): AnalysisStream {
        val info = info(videoId)
        val usable = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .let { list -> list.filter { it.audioTrackType == null || it.audioTrackType.toString() == "ORIGINAL" }.ifEmpty { list } }
        val rate = { s: AudioStream -> s.averageBitrate.takeIf { b -> b > 0 } ?: (s.bitrate / 1000) }
        val pick = usable.filter { it.format?.mimeType?.contains("mp4") == true }.minByOrNull(rate)
            ?: usable.minByOrNull(rate) ?: throw StreamException("No audio stream")
        val clen = pick.itagItem?.contentLength?.takeIf { it > 0 } ?: Uri.parse(pick.content).getQueryParameter("clen")?.toLongOrNull() ?: -1
        return AnalysisStream(pick.content, clen, info.duration)
    }

    private fun pickAudio(streams: List<AudioStream>, quality: AudioQuality): AudioStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .let { list ->
                val original = list.filter { it.audioTrackType == null || it.audioTrackType.toString() == "ORIGINAL" }
                original.ifEmpty { list }
            }
        if (usable.isEmpty()) return null
        val sorted = usable.sortedBy { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate / 1000 }
        return when (quality) {
            AudioQuality.HIGH -> sorted.last()
            AudioQuality.LOW -> sorted.first()
            AudioQuality.NORMAL -> sorted.minByOrNull { kotlin.math.abs((it.averageBitrate.takeIf { b -> b > 0 } ?: 128) - 128) }
        }
    }

    private fun pickVideo(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..maxHeight }
        val avc = usable.filter { it.codec?.startsWith("avc") == true || it.format?.mimeType == "video/mp4" }
        return (avc.ifEmpty { usable }).maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps })
    }

    /** Blocking; called from ExoPlayer loader threads. */
    fun resolve(videoId: String, withVideo: Boolean = false): ResolvedStream {
        val info = info(videoId)
        val audio = pickAudio(info.audioStreams, currentQuality())
            ?: throw StreamException("No playable audio stream was found")
        val video = if (withVideo) {
            pickVideo(info.videoOnlyStreams, settings.current.videoMaxHeight)
                ?: info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.height }
        } else null
        val itagItem = audio.itagItem
        val uri = Uri.parse(audio.content)
        return ResolvedStream(
            videoId = videoId,
            audioUrl = audio.content,
            itag = audio.itag,
            mimeType = audio.format?.mimeType ?: "audio/*",
            codec = audio.codec,
            bitrate = audio.averageBitrate.takeIf { it > 0 } ?: (audio.bitrate / 1000),
            sampleRate = itagItem?.sampleRate ?: 0,
            channels = itagItem?.audioChannels ?: 2,
            contentLength = itagItem?.contentLength ?: uri.getQueryParameter("clen")?.toLongOrNull() ?: -1,
            client = uri.getQueryParameter("c"),
            expiresAtMs = cache[videoId]?.second ?: 0,
            videoUrl = video?.content,
            videoHeight = video?.height ?: 0,
            videoCodec = video?.codec,
            videoFps = video?.fps ?: 0,
        )
    }
}
