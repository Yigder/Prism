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
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
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
            if (!first.isSuccessful) {
                runCatching {
                    com.prism.music.playback.AudioDiagnostics.log(
                        "stream", "refused: HTTP ${first.code} at byte $start of ${url.queryParameter("id") ?: "?"} (itag ${url.queryParameter("itag")}, client ${url.queryParameter("c")})",
                    )
                }
                return first
            }
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
                // Which of YouTube's clients the link came from, and how far in (#114/#100: the first chunk played, then
                // 403 at 1 MB for one listener on every song): the diagnostics say so, so the next log can tell why.
                val u = base.url
                val what = "HTTP ${resp.code} at byte $pos of ${u.queryParameter("id") ?: "?"} (itag ${u.queryParameter("itag")}, client ${u.queryParameter("c")}, " +
                    "${if (u.queryParameter("pot") != null) "with" else "no"} PO token, chunk ${chunkSize / 1024} KB)"
                runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "refused: $what") }
                throw IOException("Stream chunk at $pos failed: $what")
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

/** [videoId] turned out to be live: it plays from its HLS manifest (`prism://live/…`), not as a file. */
class LiveStreamException(val videoId: String) : IOException("$videoId is a live stream")

/**
 * Whether a player answer is a stream on air now. A past broadcast (many podcasts' episodes, #128) still says
 * `isLiveContent` and still carries an HLS manifest, but it has a length and no `isLive`: it plays as a normal video.
 */
internal fun isOnAir(isLive: Boolean, liveContent: Boolean, hasHls: Boolean, lengthSec: Long): Boolean =
    isLive || liveContent && hasHls && lengthSec <= 0

/**
 * Resolves playable googlevideo URLs for a video id. One direct request as YouTube's visionOS app comes first
 * ([InnerTube.visionOsPlayer], ~0.2 s); NewPipeExtractor does it anonymously when that has no plain answer; songs
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
        /** The real average bitrate (size over length), shown to the listener; [kbps] is YouTube's nominal one, for picking. */
        val actualKbps: Int = kbps,
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
        /** A live stream (a YouTube Music "Listen together" station, a 24/7 radio): only playable from [hlsUrl]. */
        val live: Boolean = false,
        val hlsUrl: String? = null,
        /** Which way it was asked for (NewPipe, visionOS, or the account's client), for the diagnostics. */
        val via: String = "",
    )

    private val solver = PlayerJsSolver(context, http)

    /** The current player JS's signature timestamp, or null if it couldn't be fetched. Blocking. */
    fun signatureTimestamp(): Int? = runCatching { solver.currentPlayer().second }
        .onFailure { Log.w("StreamResolver", "Couldn't get YouTube's player JS: $it") }.getOrNull()
    private val cache = ConcurrentHashMap<String, Formats>()
    /** Resolutions under way, so the player and a prefetch never resolve the same song twice at once. */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Formats>>()
    /** Songs YouTube only plays signed in; they skip the anonymous attempt next time. */
    private val needsAccount = ConcurrentHashMap.newKeySet<String>()

    init {
        // The content country picked in Settings (where YouTube Music isn't offered) goes to NewPipe too.
        val country = settings.current.contentCountry.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: Locale.getDefault().country.ifBlank { "US" }
        NewPipe.init(
            NewPipeDownloader(http),
            Localization(Locale.getDefault().language.ifBlank { "en" }, Locale.getDefault().country.ifBlank { "US" }),
            org.schabi.newpipe.extractor.localization.ContentCountry(country),
        )
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    fun currentQuality(): AudioQuality = when {
        // Data saver on mobile data (#40) wins over lossless: the mobile data quality, at most Normal.
        settings.current.dataSaver && isMetered() -> settings.current.cellularQuality.takeIf { it != AudioQuality.HIGH } ?: AudioQuality.NORMAL
        settings.current.lossless -> AudioQuality.HIGH // lossless mode always takes the best stream there is
        isMetered() -> settings.current.cellularQuality
        else -> settings.current.audioQuality
    }

    /**
     * Forgets [videoId]'s stream, to be looked up again. [refused]: its server turned the stream away (403), so the next
     * lookup doesn't take the direct answer again (on the phone, "Beat It" `kOn-HdEg6AQ` signed in got 403s on every
     * direct link, so it failed twice and was skipped); it goes through NewPipe, as before the direct lookup.
     */
    fun invalidate(videoId: String, refused: Boolean = false) {
        cache.remove(videoId)
        // Turned away a second time (the direct link and then NewPipe's): a song only the account may play, such as
        // music the listener uploaded themselves (GitHub #101, "response code: 403"). Asked for as the account from then on.
        if (refused && !directRefused.add(videoId) && settings.current.isLoggedIn) {
            needsAccount += videoId
            Log.i("PrismStream", "$videoId refused twice: asking as the signed-in account from now on")
        }
    }

    /** [videoId] is the listener's own upload (#101): asked for as the signed-in account from the start. */
    fun forAccount(videoId: String) {
        if (settings.current.isLoggedIn && needsAccount.add(videoId)) Log.i("PrismStream", "$videoId is an upload: asking as the account")
    }

    /** Songs whose direct ([directFormats]) links were turned away: looked up through NewPipe from then on. */
    private val directRefused = ConcurrentHashMap.newKeySet<String>()

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
            val started = System.nanoTime()
            val f = load(videoId)
            val took = (System.nanoTime() - started) / 1_000_000
            Log.i("PrismStream", "$videoId resolved via ${f.via} in $took ms")
            runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId resolved via ${f.via} in $took ms") }
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

    /** Until when NewPipe is skipped: YouTube was turning its anonymous web clients away from this IP ("not a bot"). */
    @Volatile
    private var newPipeBlockedUntil = 0L

    /** Until when [premiumFormats] isn't tried: the account's answers had no Premium formats (not a Premium account). */
    @Volatile
    private var premiumMissingUntil = 0L

    /**
     * Settings → Playback & sound → "Premium quality" (Reddit 1.7): asked as the signed-in account, which YouTube answers
     * with its 256 kbps formats (AAC itag 141, Opus 774) for Music Premium. Kept only when it has them; otherwise the
     * usual quick path plays and, for an hour, the account isn't asked first again (so songs don't start slower for
     * nothing). Not on metered networks with Data saver on.
     */
    private fun premiumFormats(videoId: String): Formats? {
        val s = settings.current
        if (!s.premiumStream || !s.isLoggedIn || System.currentTimeMillis() < premiumMissingUntil) return null
        if (s.dataSaver && isMetered()) return null
        val f = runCatching { signedInFormats(videoId) }.getOrNull() ?: return null
        if (f.live) return f
        val premium = f.audio.filter { it.itag in PREMIUM_ITAGS }
        if (premium.isEmpty()) {
            if (f.audio.isNotEmpty()) premiumMissingUntil = System.currentTimeMillis() + 60 * 60_000L
            Log.i("PrismStream", "$videoId: no Premium formats for this account (${f.audio.map { it.itag }})")
            return null
        }
        Log.i("PrismStream", "$videoId resolved as the account (Premium formats ${premium.map { it.itag }})")
        return f
    }

    private fun load(videoId: String): Formats {
        val signedIn = settings.current.isLoggedIn
        if (signedIn && videoId in needsAccount) return signedInFormats(videoId)
        premiumFormats(videoId)?.let { return it }
        // While YouTube is blocking NewPipe here, signed-in playback goes straight to the account's own clients
        // (each song would otherwise wait for NewPipe to be refused first).
        if (signedIn && System.currentTimeMillis() < newPipeBlockedUntil) {
            runCatching { signedInFormats(videoId) }.getOrNull()?.let { return it }
        }
        if (videoId !in directRefused) directFormats(videoId, signedIn)?.let { return it }
        return try {
            anonymousFormats(videoId).also { if (it.audio.isEmpty() && it.hlsUrl == null) throw StreamException("No playable audio stream was found") }
        } catch (e: Exception) {
            val restricted = e is ContentNotAvailableException
            val blocked = e is SignInConfirmNotBotException || e is ReCaptchaException
            if (blocked) {
                newPipeBlockedUntil = System.currentTimeMillis() + BLOCK_BACKOFF_MS
                Log.w("StreamResolver", "YouTube is blocking anonymous web playback here; using other clients for a while: $e")
            }
            val why = if (blocked) BLOCKED else e.message ?: "Couldn't load this track"
            when {
                // Anything YouTube won't play (or NewPipe can't get) anonymously is asked for as the user.
                signedIn -> try {
                    signedInFormats(videoId).also { if (restricted) needsAccount += videoId }
                } catch (signed: StreamException) {
                    Log.w("StreamResolver", "Signed-in playback of $videoId failed too: ${signed.message}")
                    throw if (restricted) signed else StreamException(why, e)
                }
                e is AgeRestrictedContentException -> throw StreamException("Sign in to play age-restricted songs", e)
                e is YoutubeMusicPremiumContentException -> throw StreamException("This song needs YouTube Music Premium: sign in with a Premium account to play it", e)
                else -> throw StreamException(why, e)
            }
        }
    }

    /** Until when [directFormats] is skipped: YouTube was answering it with bot checks from this network. */
    @Volatile
    private var directOffUntil = 0L

    /**
     * The quick way: one player request as YouTube's visionOS app ([InnerTube.visionOsPlayer]), the client NewPipe
     * takes its streams from, without NewPipe's other requests. On the phone a song not resolved ahead started ~2 s
     * later through NewPipe. Null whenever its answer isn't one to play as it is (refused, ciphered, an "n" to solve,
     * a live stream without its HLS manifest): NewPipe then resolves it as before. Songs that need an account
     * (age-restricted, Premium-only) go straight to the signed-in clients when signed in.
     */
    private fun directFormats(videoId: String, signedIn: Boolean): Formats? {
        if (System.currentTimeMillis() < directOffUntil) return null
        val res = try {
            innerTube.visionOsPlayer(videoId)
        } catch (e: Exception) {
            Log.w("StreamResolver", "Direct lookup of $videoId failed: $e")
            return null
        }
        val status = res.str("playabilityStatus", "status")
        if (status != "OK") {
            val reason = res.str("playabilityStatus", "reason")
            Log.i("StreamResolver", "Direct lookup of $videoId: $status ($reason)")
            if (reason?.contains("bot", ignoreCase = true) == true) {
                directOffUntil = System.currentTimeMillis() + BLOCK_BACKOFF_MS
            } else if (signedIn && status == "LOGIN_REQUIRED") {
                runCatching { signedInFormats(videoId) }.getOrNull()?.let { needsAccount += videoId; return it }
            }
            return null
        }
        // Every format plain (music videos' pictures come from the same answer).
        val adaptive = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        if (adaptive.any { o -> o.str("url").let { it == null || Regex("[?&]n=").containsMatchIn(it) } }) return null
        val f = parsePlayer(res, null)
        if (f.live) return f.takeIf { it.hlsUrl != null }
        return f.takeIf { it.audio.isNotEmpty() }?.also { Log.i("PrismStream", "$videoId resolved directly (visionOS)") }
    }

    private fun anonymousFormats(videoId: String): Formats {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        fun clen(s: org.schabi.newpipe.extractor.stream.Stream) =
            s.itagItem?.contentLength?.takeIf { it > 0 } ?: Uri.parse(s.content).getQueryParameter("clen")?.toLongOrNull() ?: -1
        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { a ->
            val len = clen(a)
            val ms = a.itagItem?.approxDurationMs?.takeIf { it > 0 } ?: (info.duration * 1000)
            val nominal = a.averageBitrate.takeIf { it > 0 } ?: (a.bitrate / 1000)
            Fmt(
                itag = a.itag, mime = a.format?.mimeType ?: "audio/*", codec = a.codec,
                kbps = nominal,
                sampleRate = a.itagItem?.sampleRate ?: 0, channels = a.itagItem?.audioChannels ?: 2,
                contentLength = len,
                original = a.audioTrackType == null || a.audioTrackType.toString() == "ORIGINAL",
                actualKbps = realKbps(len, ms, nominal),
            ) { a.content }
        }
        fun video(v: VideoStream) = Fmt(
            itag = v.itag, mime = v.format?.mimeType ?: "video/*", codec = v.codec, kbps = (v.itagItem?.bitrate ?: 0) / 1000,
            sampleRate = 0, channels = 0, contentLength = clen(v), height = v.height, fps = v.fps,
        ) { v.content }
        val progressive = { v: VideoStream -> v.isUrl && v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val expire = info.audioStreams.firstOrNull()?.content?.let { Uri.parse(it).getQueryParameter("expire")?.toLongOrNull() }
            ?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        return Formats(
            audio, info.videoOnlyStreams.filter(progressive).map(::video), info.videoStreams.filter(progressive).map(::video), info.duration, expire,
            live = live, hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() }, via = "NewPipe",
        )
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
        // A live answer without an HLS manifest is kept only until a client gives one ([liveManifest] fills it in).
        var liveWithoutHls: Formats? = null
        for (client in InnerTube.PlayerClient.entries) {
            val res = try {
                innerTube.signedInPlayer(client, videoId, player?.second)
            } catch (e: Exception) {
                reason = reason ?: e.message
                runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId client ${client.name} failed: $e") }
                continue
            }
            if (res.str("playabilityStatus", "status") != "OK") {
                reason = res.str("playabilityStatus", "reason") ?: reason
                runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId client ${client.name} not playable: ${res.str("playabilityStatus", "status")} / ${res.str("playabilityStatus", "reason")}") }
                continue
            }
            val f = parsePlayer(res, player?.first, via = "account (${client.name})")
            if (f.live && f.hlsUrl == null) { liveWithoutHls = liveWithoutHls ?: f; continue }
            if (f.audio.isNotEmpty() || (f.live && f.hlsUrl != null)) return f
        }
        liveWithoutHls?.let { return it }
        throw StreamException(reason ?: "Couldn't load this track")
    }

    private companion object {
        /** YouTube Music Premium's 256 kbps audio: AAC (141) and Opus (774). */
        val PREMIUM_ITAGS = setOf(141, 774)
        const val BLOCK_BACKOFF_MS = 30 * 60_000L
        const val BLOCKED = "YouTube is limiting playback on this network right now. Try again in a while, switch networks " +
            "(or turn off a VPN), or sign in."

        /**
         * A stream's real average bitrate, from its size and length. YouTube's own figure is the format's nominal one
         * (Opus always says 160 kbps), which is why every song showed the same number.
         */
        fun realKbps(bytes: Long, durationMs: Long, nominal: Int): Int =
            if (bytes > 0 && durationMs > 1000) (bytes * 8 / durationMs).toInt().takeIf { it in 8..5000 } ?: nominal else nominal
    }

    /** [playerId] is the player JS whose signature timestamp the request sent; its ciphers are solved with it. */
    private fun parsePlayer(res: JsonObject, playerId: String?, via: String = "visionOS"): Formats {
        val data = res.obj("streamingData")
        fun fmt(o: JsonObject): Fmt? {
            val mimeFull = o.str("mimeType") ?: return null
            val mime = mimeFull.substringBefore(";").trim()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.get(1)?.substringBefore(",")?.trim()
            val direct = o.str("url")
            val cipher = o.str("signatureCipher") ?: o.str("cipher")
            if (direct == null && cipher == null) return null
            val len = o.str("contentLength")?.toLongOrNull() ?: -1
            val nominal = ((o.str("averageBitrate") ?: o.str("bitrate"))?.toIntOrNull() ?: 0) / 1000
            return Fmt(
                itag = o.str("itag")?.toIntOrNull() ?: 0, mime = mime, codec = codec,
                kbps = nominal,
                actualKbps = if (mime.startsWith("audio/")) realKbps(len, o.str("approxDurationMs")?.toLongOrNull() ?: 0, nominal) else nominal,
                sampleRate = o.str("audioSampleRate")?.toIntOrNull() ?: 0,
                channels = o.str("audioChannels")?.toIntOrNull() ?: 2,
                contentLength = len,
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
            live = isOnAir(
                isLive = res.str("videoDetails", "isLive") == "true",
                liveContent = res.str("videoDetails", "isLiveContent") == "true",
                hasHls = data.str("hlsManifestUrl") != null,
                lengthSec = res.str("videoDetails", "lengthSeconds")?.toLongOrNull() ?: 0,
            ),
            hlsUrl = data.str("hlsManifestUrl"),
            via = via,
        )
    }

    /** Whether [videoId] is a live stream (resolving it if it hasn't been). Blocking. */
    fun isLive(videoId: String): Boolean = formats(videoId).live

    /** A live stream's HLS manifest, fresh enough to play. Blocking; called from ExoPlayer loader threads. */
    fun liveManifest(videoId: String): String {
        val f = formats(videoId)
        f.hlsUrl?.let { return it }
        // The account's clients (signed in, while NewPipe is backed off) can answer a live stream with its DASH
        // formats and no HLS manifest, which left live streams unplayable in song and video mode alike. The
        // anonymous web player always has the manifest.
        val anon = try {
            anonymousFormats(videoId)
        } catch (e: Exception) {
            throw StreamException("This live stream can't be played", e)
        }
        val url = anon.hlsUrl ?: throw StreamException("This live stream can't be played")
        cache[videoId] = Formats(f.audio, f.videoOnly, f.muxed, f.durationSec, minOf(f.expiresAtMs, anon.expiresAtMs), live = true, hlsUrl = url)
        return url
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

    private fun pickAudio(streams: List<Fmt>, quality: AudioQuality): Fmt? =
        AudioPick.pick(streams.map { AudioPick.Choice(it.mime, it.codec, it.kbps, it.original) }, quality)?.let { streams[it] }

    private fun pickVideo(streams: List<Fmt>, maxHeight: Int): Fmt? {
        val usable = streams.filter { it.height in 1..maxHeight }
        val avc = usable.filter { it.codec?.startsWith("avc") == true || it.mime == "video/mp4" }
        return (avc.ifEmpty { usable }).maxWithOrNull(compareBy<Fmt> { it.height }.thenBy { it.fps })
    }

    /** Blocking; called from ExoPlayer loader threads. */
    fun resolve(videoId: String, withVideo: Boolean = false): ResolvedStream {
        val f = formats(videoId)
        // A live stream's "file" is only its newest few seconds: the player has to take it as HLS instead.
        if (f.live) throw LiveStreamException(videoId)
        val audio = pickAudio(f.audio, currentQuality())
            ?: throw StreamException("No playable audio stream was found")
        val video = if (withVideo) {
            // Data saver on mobile data (#40): music videos at 480p at most.
            val maxHeight = if (settings.current.dataSaver && isMetered()) minOf(480, settings.current.videoMaxHeight) else settings.current.videoMaxHeight
            pickVideo(f.videoOnly, maxHeight) ?: f.muxed.minByOrNull { if (it.height <= maxHeight) maxHeight - it.height else it.height }
        } else null
        val uri = Uri.parse(audio.url)
        return ResolvedStream(
            videoId = videoId,
            audioUrl = audio.url,
            itag = audio.itag,
            mimeType = audio.mime,
            codec = audio.codec,
            bitrate = audio.actualKbps,
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
