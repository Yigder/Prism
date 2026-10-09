package com.prism.music.data.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.prefs.AudioQuality
import kotlinx.serialization.json.JsonObject
import com.prism.music.data.prefs.SettingsRepository
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import android.util.Log
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
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

/**
 * Resolves playable googlevideo URLs for a video id. NewPipeExtractor does this anonymously; songs
 * YouTube won't play anonymously (age-restricted, Music Premium-only) are asked for again as the
 * signed-in user ([InnerTube.signedInPlayer]), with the stream URLs deciphered by YouTube's player
 * JS ([PlayerJsSolver]).
 */
class StreamResolver(
    private val context: Context,
    http: OkHttpClient,
    private val settings: SettingsRepository,
    private val innerTube: InnerTube,
) {
    /** One stream format, from either source. [url] is deciphered only when it's picked. */
    private class Fmt(
        val itag: Int,
        val mime: String,
        val codec: String?,
        val kbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val contentLength: Long,
        val height: Int = 0,
        val fps: Int = 0,
        val original: Boolean = true,
        resolveUrl: () -> String,
    ) {
        val url: String by lazy(resolveUrl)
    }

    private class Formats(
        val audio: List<Fmt>,
        val videoOnly: List<Fmt>,
        val muxed: List<Fmt>,
        val durationSec: Long,
        val expiresAtMs: Long,
    )

    private val solver = PlayerJsSolver(context, http)
    private val cache = ConcurrentHashMap<String, Formats>()
    /** Resolutions under way, so the player and a prefetch never resolve the same song twice at once. */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Formats>>()
    /** Songs YouTube only plays signed in; they skip the anonymous attempt next time. */
    private val needsAccount = ConcurrentHashMap.newKeySet<String>()

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

    /** Resolves a song ahead of time (e.g. the next one in the queue) so it starts straight away. */
    fun prefetch(videoId: String) {
        runCatching { formats(videoId) }
    }

    private fun formats(videoId: String): Formats {
        cache[videoId]?.let { if (System.currentTimeMillis() < it.expiresAtMs) return it }
        val mine = CompletableFuture<Formats>()
        val running = inflight.putIfAbsent(videoId, mine)
        if (running != null) {
            return try {
                running.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? StreamException) ?: StreamException(e.cause?.message ?: "Couldn't load this track", e.cause)
            }
        }
        try {
            val f = load(videoId)
            cache[videoId] = f
            mine.complete(f)
            return f
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(videoId, mine)
        }
    }

    private fun load(videoId: String): Formats {
        val signedIn = settings.current.isLoggedIn
        if (signedIn && videoId in needsAccount) return signedInFormats(videoId)
        return try {
            anonymousFormats(videoId).also { if (it.audio.isEmpty()) throw StreamException("No playable audio stream was found") }
        } catch (e: Exception) {
            val restricted = e is ContentNotAvailableException
            when {
                // Anything YouTube won't play (or NewPipe can't get) anonymously is asked for as the user.
                signedIn -> try {
                    signedInFormats(videoId).also { if (restricted) needsAccount += videoId }
                } catch (signed: StreamException) {
                    throw if (restricted) signed else StreamException(e.message ?: "Couldn't load this track", e)
                }
                e is AgeRestrictedContentException -> throw StreamException("Sign in to play age-restricted songs", e)
                e is YoutubeMusicPremiumContentException -> throw StreamException("This song needs YouTube Music Premium: sign in with a Premium account to play it", e)
                else -> throw StreamException(e.message ?: "Couldn't load this track", e)
            }
        }
    }

    private fun anonymousFormats(videoId: String): Formats {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        fun clen(s: org.schabi.newpipe.extractor.stream.Stream) =
            s.itagItem?.contentLength?.takeIf { it > 0 } ?: Uri.parse(s.content).getQueryParameter("clen")?.toLongOrNull() ?: -1
        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { a ->
            Fmt(
                itag = a.itag, mime = a.format?.mimeType ?: "audio/*", codec = a.codec,
                kbps = a.averageBitrate.takeIf { it > 0 } ?: (a.bitrate / 1000),
                sampleRate = a.itagItem?.sampleRate ?: 0, channels = a.itagItem?.audioChannels ?: 2,
                contentLength = clen(a),
                original = a.audioTrackType == null || a.audioTrackType.toString() == "ORIGINAL",
            ) { a.content }
        }
        fun video(v: VideoStream) = Fmt(
            itag = v.itag, mime = v.format?.mimeType ?: "video/*", codec = v.codec, kbps = (v.itagItem?.bitrate ?: 0) / 1000,
            sampleRate = 0, channels = 0, contentLength = clen(v), height = v.height, fps = v.fps,
        ) { v.content }
        val progressive = { v: VideoStream -> v.isUrl && v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val expire = info.audioStreams.firstOrNull()?.content?.let { Uri.parse(it).getQueryParameter("expire")?.toLongOrNull() }
            ?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        return Formats(audio, info.videoOnlyStreams.filter(progressive).map(::video), info.videoStreams.filter(progressive).map(::video), info.duration, expire)
    }

    /** The player response as the signed-in user: the TV client first (no PO token needed), then the web app. */
    private fun signedInFormats(videoId: String): Formats {
        val player = try {
            solver.currentPlayer()
        } catch (e: Exception) {
            Log.w("StreamResolver", "Couldn't get YouTube's player JS: $e")
            null
        }
        var reason: String? = null
        for (client in InnerTube.PlayerClient.entries) {
            val res = try {
                innerTube.signedInPlayer(client, videoId, player?.second)
            } catch (e: Exception) {
                reason = reason ?: e.message
                continue
            }
            if (res.str("playabilityStatus", "status") != "OK") {
                reason = res.str("playabilityStatus", "reason") ?: reason
                continue
            }
            val f = parsePlayer(res, player?.first)
            if (f.audio.isNotEmpty()) return f
        }
        throw StreamException(reason ?: "Couldn't load this track")
    }

    /** [playerId] is the player JS whose signature timestamp the request sent; its ciphers are solved with it. */
    private fun parsePlayer(res: JsonObject, playerId: String?): Formats {
        val data = res.obj("streamingData")
        fun fmt(o: JsonObject): Fmt? {
            val mimeFull = o.str("mimeType") ?: return null
            val mime = mimeFull.substringBefore(";").trim()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.get(1)?.substringBefore(",")?.trim()
            val direct = o.str("url")
            val cipher = o.str("signatureCipher") ?: o.str("cipher")
            if (direct == null && cipher == null) return null
            return Fmt(
                itag = o.str("itag")?.toIntOrNull() ?: 0, mime = mime, codec = codec,
                kbps = ((o.str("averageBitrate") ?: o.str("bitrate"))?.toIntOrNull() ?: 0) / 1000,
                sampleRate = o.str("audioSampleRate")?.toIntOrNull() ?: 0,
                channels = o.str("audioChannels")?.toIntOrNull() ?: 2,
                contentLength = o.str("contentLength")?.toLongOrNull() ?: -1,
                height = o.str("height")?.toIntOrNull() ?: 0, fps = o.str("fps")?.toIntOrNull() ?: 0,
                original = o.obj("audioTrack")?.let { t -> t.str("audioIsDefault") != "false" } ?: true,
            ) { decipher(playerId, direct, cipher) }
        }
        val adaptive = data.arr("adaptiveFormats")?.mapNotNull { (it as? JsonObject)?.let(::fmt) }.orEmpty()
        val muxed = data.arr("formats")?.mapNotNull { (it as? JsonObject)?.let(::fmt) }.orEmpty()
        val expiresIn = data.str("expiresInSeconds")?.toLongOrNull() ?: 3600
        return Formats(
            audio = adaptive.filter { it.mime.startsWith("audio/") },
            videoOnly = adaptive.filter { it.mime.startsWith("video/") },
            muxed = muxed,
            durationSec = res.str("videoDetails", "lengthSeconds")?.toLongOrNull() ?: 0,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000 - 10 * 60_000,
        )
    }

    /**
     * A playable URL: the signature put back (ciphered formats) and the throttling parameter "n" solved,
     * both by [playerId]'s JS. An unsolved "n" still plays (slowly), so only the signature is required.
     */
    private fun decipher(playerId: String?, direct: String?, cipher: String?): String {
        var sig: String? = null
        var param = "signature"
        val base = direct ?: run {
            // A bare query string; android.net.Uri can't be trusted to parse it (a stray ':' makes it opaque).
            val q = cipher!!.split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            sig = q["s"]
            param = q["sp"] ?: param
            val url = q["url"]
            if (url == null || sig == null) {
                Log.w("StreamResolver", "signatureCipher has keys ${q.keys}")
                throw StreamException("Couldn't read this track's stream")
            }
            url
        }
        val url = base.toHttpUrlOrNull() ?: throw StreamException("Couldn't read this track's stream")
        val n = url.queryParameter("n")
        if (sig == null && n == null) return base
        val solved = try {
            solver.solve(playerId ?: throw IOException("No player JS to solve with"), sig, n)
        } catch (e: Exception) {
            Log.w("StreamResolver", "Couldn't solve the stream URL: $e")
            if (sig == null) return base
            throw e as? StreamException ?: StreamException("Couldn't unlock this track's stream", e)
        }
        return url.newBuilder().apply {
            if (sig != null) setQueryParameter(param, solved.first ?: throw StreamException("Couldn't unlock this track's stream"))
            if (n != null && solved.second != null) setQueryParameter("n", solved.second)
        }.build().toString()
    }

    data class AnalysisStream(val url: String, val contentLength: Long, val durationSec: Long)

    /** The smallest audio stream (AAC preferred, which decodes from a partial file), for analysis. */
    fun analysisAudio(videoId: String): AnalysisStream {
        val f = formats(videoId)
        val usable = f.audio.filter { it.original }.ifEmpty { f.audio }
        val pick = usable.filter { it.mime.contains("mp4") }.minByOrNull { it.kbps }
            ?: usable.minByOrNull { it.kbps } ?: throw StreamException("No audio stream")
        return AnalysisStream(pick.url, pick.contentLength, f.durationSec)
    }

    private fun pickAudio(streams: List<Fmt>, quality: AudioQuality): Fmt? {
        val usable = streams.filter { it.original }.ifEmpty { streams }
        if (usable.isEmpty()) return null
        val sorted = usable.sortedBy { it.kbps }
        return when (quality) {
            AudioQuality.HIGH -> sorted.last()
            AudioQuality.LOW -> sorted.first()
            AudioQuality.NORMAL -> sorted.minByOrNull { kotlin.math.abs((it.kbps.takeIf { b -> b > 0 } ?: 128) - 128) }
        }
    }

    private fun pickVideo(streams: List<Fmt>, maxHeight: Int): Fmt? {
        val usable = streams.filter { it.height in 1..maxHeight }
        val avc = usable.filter { it.codec?.startsWith("avc") == true || it.mime == "video/mp4" }
        return (avc.ifEmpty { usable }).maxWithOrNull(compareBy<Fmt> { it.height }.thenBy { it.fps })
    }

    /** Blocking; called from ExoPlayer loader threads. */
    fun resolve(videoId: String, withVideo: Boolean = false): ResolvedStream {
        val f = formats(videoId)
        val audio = pickAudio(f.audio, currentQuality())
            ?: throw StreamException("No playable audio stream was found")
        val video = if (withVideo) {
            pickVideo(f.videoOnly, settings.current.videoMaxHeight) ?: f.muxed.maxByOrNull { it.height }
        } else null
        val uri = Uri.parse(audio.url)
        return ResolvedStream(
            videoId = videoId,
            audioUrl = audio.url,
            itag = audio.itag,
            mimeType = audio.mime,
            codec = audio.codec,
            bitrate = audio.kbps,
            sampleRate = audio.sampleRate,
            channels = audio.channels,
            contentLength = audio.contentLength.takeIf { it > 0 } ?: uri.getQueryParameter("clen")?.toLongOrNull() ?: -1,
            client = uri.getQueryParameter("c"),
            expiresAtMs = f.expiresAtMs,
            videoUrl = video?.url,
            videoHeight = video?.height ?: 0,
            videoCodec = video?.codec,
            videoFps = video?.fps ?: 0,
        )
    }
}
