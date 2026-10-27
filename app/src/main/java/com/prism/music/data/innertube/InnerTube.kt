package com.prism.music.data.innertube

import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

class InnerTubeException(message: String, val code: Int = 0) : IOException(message)

/**
 * Minimal client for YouTube Music's private "InnerTube" API (the same API the
 * music.youtube.com web app uses). Authenticated requests use the signed-in
 * browser cookies plus a SAPISIDHASH authorization header.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val settingsProvider: () -> AppSettings,
    private val saveVisitorData: (String) -> Unit = {},
) {
    private val current: AppSettings get() = settingsProvider()

    /** Requests go out as the signed-in account. */
    val signedIn: Boolean get() = current.isLoggedIn


    companion object {
        const val BASE = "https://music.youtube.com/youtubei/v1/"
        const val ORIGIN = "https://music.youtube.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        /** yt-dlp's tv_samsung client version (PR #17723); bump it if TV playback starts being refused. */
        const val TV_CLIENT_VERSION = "5.20260707"
        /** The YouTube Music Android app version [androidMusicPost] asks as (for synced lyrics). */
        const val ANDROID_MUSIC_VERSION = "7.27.52"
        /** youtube.com's web client, for [webPost] (live stream search). */
        const val WEB_CLIENT_VERSION = "2.20250925.01.00"
        /**
         * The visionOS YouTube app's version [visionOsPlayer] asks as (NewPipe 0.26.5's). If YouTube stops answering it,
         * newer NewPipe releases' ClientsConstants have the current one.
         */
        const val VISIONOS_VERSION = "1.02"
    }

    @Volatile
    var clientVersion = "1.20260928.13.00"
    @Volatile
    private var versionChecked = false
    /** The visitor id read at start-up, used until the saved one comes through the settings. */
    @Volatile
    private var fetchedVisitor = ""
    private val visitorData: String get() = current.visitorData.ifBlank { fetchedVisitor }

    private val locale: Locale get() = Locale.getDefault()

    /**
     * Set when YouTube refused a request as "Request contains an invalid argument" and the plain version of it went
     * through: from then on requests leave out the parts that can trip it (the brand-account delegation, a stale
     * visitor id, an unusual region), for this session.
     */
    @Volatile
    private var plainContext = false

    /** The phone's language as YouTube takes it. */
    private val hl: String get() = if (plainContext) "en" else locale.language.lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"

    /**
     * The country picked in Settings (for places YouTube Music isn't offered), else the phone's. Regions that aren't
     * countries ("419" in es-419, Latin America) make YouTube refuse requests.
     */
    private val gl: String get() = if (plainContext) "US" else
        current.contentCountry.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: locale.country.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"

    /** A brand account's id when the signed-in session is one (DATASYNC_ID "brand||user"), else null. */
    private fun onBehalfOfUser(): String? {
        if (plainContext) return null
        val parts = current.dataSyncId.split("||")
        return parts[0].takeIf { parts.size > 1 && parts[1].isNotBlank() && it.isNotBlank() }
    }

    private fun cookieMap(): Map<String, String> = current.cookie.split(";")
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authorization(origin: String = ORIGIN): String? {
        val cookies = cookieMap()
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
        val ts = System.currentTimeMillis() / 1000
        // Same scheme as ytmusicapi's browser auth.
        return "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $origin")}"
    }

    private fun context(anonymous: Boolean = false): JsonObject = buildJsonObject {
        val s = current
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", clientVersion)
            put("hl", hl)
            put("gl", gl)
            put("platform", "DESKTOP")
            put("userAgent", USER_AGENT)
            if (visitorData.isNotBlank() && !plainContext) put("visitorData", visitorData)
        }
        putJsonObject("user") {
            if (s.isLoggedIn && !anonymous) onBehalfOfUser()?.let { put("onBehalfOfUser", it) }
        }
    }

    private fun Request.Builder.applyHeaders(anonymous: Boolean = false): Request.Builder {
        val s = current
        header("User-Agent", USER_AGENT)
        header("Origin", ORIGIN)
        header("Referer", "$ORIGIN/")
        header("X-Origin", ORIGIN)
        header("X-YouTube-Client-Name", "67")
        header("X-YouTube-Client-Version", clientVersion)
        if (visitorData.isNotBlank() && !plainContext) header("X-Goog-Visitor-Id", visitorData)
        if (s.isLoggedIn && !anonymous) {
            header("Cookie", s.cookie)
            authorization()?.let { header("Authorization", it) }
            header("X-Goog-AuthUser", s.authUser.toString())
        }
        return this
    }

    private val versionLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Reads the live web client version (and a visitor id) so requests keep looking like the current web app.
     * Requests that arrive together (Top results asks five searches at once) wait for it: without a visitor id
     * YouTube answers differently, and Top results lost "After Hours" by The Weeknd from its albums that way.
     */
    private suspend fun ensureClientVersion() {
        if (versionChecked) return
        versionLock.lock()
        try {
            if (!versionChecked) fetchClientVersion()
        } finally {
            versionChecked = true
            versionLock.unlock()
        }
    }

    private fun fetchClientVersion() {
        runCatching {
            val req = Request.Builder().url(ORIGIN).header("User-Agent", USER_AGENT).build()
            val html = http.newCall(req).execute().use { it.body.string() }
            Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
            if (current.visitorData.isBlank()) {
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { fetchedVisitor = it; saveVisitorData(it) }
            }
        }
    }

    /** Reads VISITOR_DATA and DATASYNC_ID for a freshly signed-in cookie. */
    suspend fun sessionInfo(cookie: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(ORIGIN).header("User-Agent", USER_AGENT).header("Cookie", cookie).build()
            val html = http.newCall(req).execute().use { it.body.string() }
            val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val dataSync = Regex("\"DATASYNC_ID\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            visitor to dataSync
        }.getOrDefault(null to null)
    }

    suspend fun post(endpoint: String, body: JsonObject = JsonObject(emptyMap()), query: String = ""): JsonObject =
        withContext(Dispatchers.IO) {
            ensureClientVersion()
            try {
                postOnce(endpoint, body, query)
            } catch (e: InnerTubeException) {
                // "Request contains an invalid argument": seen right after signing in (a brand-account delegation or
                // visitor id YouTube won't take, or an odd region). The plain request usually goes through; keep to it.
                if (e.code != 400 || plainContext) throw e
                plainContext = true
                android.util.Log.w("InnerTube", "$endpoint refused (${e.message}); retrying with a plain context")
                try {
                    postOnce(endpoint, body, query)
                } catch (again: InnerTubeException) {
                    plainContext = false
                    throw e
                }
            }
        }

    /**
     * The same request without the account (no cookies, no brand-account delegation; the visitor id stays). For
     * searches, which YouTube otherwise saves to the account's search history, every keystroke's and every
     * "title artist" lookup's included (#27), and for public pages YouTube refuses an account.
     */
    suspend fun postAnonymous(endpoint: String, body: JsonObject = JsonObject(emptyMap()), query: String = ""): JsonObject =
        withContext(Dispatchers.IO) {
            ensureClientVersion()
            postOnce(endpoint, body, query, anonymous = true)
        }

    /**
     * The channels of the Google account at [authUser] (0 = the first signed in on [cookie]'s session), as YouTube's
     * account switcher lists them: the account's own channel and its brand accounts. No channel delegation, so every
     * one of them is listed whichever is in use. Null when there's no Google account at that index.
     */
    suspend fun accountsList(authUser: Int, cookie: String = current.cookie): JsonObject? = withContext(Dispatchers.IO) {
        ensureClientVersion()
        val payload = buildJsonObject { put("context", context(anonymous = true)) }
        val sapisid = cookie.split(";").map { it.trim() }.firstNotNullOfOrNull { part ->
            part.takeIf { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }?.substringAfter('=')
        } ?: return@withContext null
        val ts = System.currentTimeMillis() / 1000
        val req = Request.Builder()
            .url("${BASE}account/accounts_list?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .applyHeaders(anonymous = true)
            .header("Cookie", cookie)
            .header("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $ORIGIN")}")
            .header("X-Goog-AuthUser", authUser.toString())
            .build()
        runCatching {
            http.newCall(req).execute().use { resp -> if (resp.isSuccessful) json.parseToJsonElement(resp.body.string()).jsonObject else null }
        }.getOrNull()
    }

    private fun postOnce(endpoint: String, body: JsonObject, query: String, anonymous: Boolean = false): JsonObject {
        val payload = buildJsonObject {
            put("context", context(anonymous))
            body.forEach { (k, v) -> put(k, v) }
        }
        val req = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false$query")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .applyHeaders(anonymous)
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).str("error", "message") }.getOrNull()
                throw InnerTubeException(msg ?: "YouTube Music returned HTTP ${resp.code}", resp.code)
            }
            json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * Asks youtube.com itself (the WEB client, anonymously) rather than YouTube Music. Used for what
     * YouTube Music has no filter for, like live streams.
     */
    suspend fun webPost(endpoint: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_CLIENT_VERSION)
                    put("hl", hl)
                    put("gl", gl)
                    put("platform", "DESKTOP")
                    put("userAgent", USER_AGENT)
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw InnerTubeException("YouTube returned HTTP ${resp.code}", resp.code)
            json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * Asks YouTube Music as its Android app (anonymously). Its lyrics page carries time-synced lyrics (LyricFind's,
     * line by line) that the web client's has only as plain text.
     */
    suspend fun androidMusicPost(endpoint: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", ANDROID_MUSIC_VERSION)
                    put("androidSdkVersion", 30)
                    put("osName", "Android")
                    put("osVersion", "11")
                    put("hl", hl)
                    put("gl", gl)
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", "com.google.android.apps.youtube.music/$ANDROID_MUSIC_VERSION (Linux; U; Android 11) gzip")
            .header("X-YouTube-Client-Name", "21")
            .header("X-YouTube-Client-Version", ANDROID_MUSIC_VERSION)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw InnerTubeException("YouTube returned HTTP ${resp.code}", resp.code)
            json.parseToJsonElement(text).jsonObject
        }
    }

    /**
     * A client the player endpoint can be asked as (signed in), for streams NewPipe's anonymous playback can't get.
     * Tried and dropped as anonymous fallbacks (2026-10): ANDROID_VR answers, but googlevideo refuses its streams past
     * the first megabyte; TV without cookies answers "The page needs to be reloaded".
     */
    enum class PlayerClient(
        val clientName: String, val nameId: Int, val userAgent: String, val origin: String,
        val deviceMake: String? = null, val deviceModel: String? = null,
        val osName: String? = null, val osVersion: String? = null,
    ) {
        /**
         * YouTube on TVs, as yt-dlp's tv_samsung client (PR #17723): takes the signed-in cookies, plays
         * age-restricted and Premium-only songs, needs no PO token, and has to identify as a Samsung/Tizen TV
         * or YouTube answers "The page needs to be reloaded".
         */
        TV(
            "TVHTML5", 7,
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
            "https://www.youtube.com",
            deviceMake = "Samsung", deviceModel = "UKS9800", osName = "Tizen", osVersion = "2.4.0",
        ),
        /** The YouTube Music web app itself. */
        WEB_REMIX("WEB_REMIX", 67, InnerTube.USER_AGENT, InnerTube.ORIGIN),
    }

    /**
     * The player response for [videoId] as the signed-in user, from [client]. Blocking: called from
     * ExoPlayer's loader threads. [signatureTimestamp] is the current player JS's, so its ciphered
     * stream URLs can be deciphered.
     */
    fun signedInPlayer(client: PlayerClient, videoId: String, signatureTimestamp: Int?): JsonObject {
        val s = current
        val version = if (client == PlayerClient.TV) TV_CLIENT_VERSION else clientVersion
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", version)
                    put("hl", hl)
                    put("gl", gl)
                    put("userAgent", client.userAgent)
                    client.deviceMake?.let { put("deviceMake", it) }
                    client.deviceModel?.let { put("deviceModel", it) }
                    client.osName?.let { put("osName", it) }
                    client.osVersion?.let { put("osVersion", it) }
                    if (visitorData.isNotBlank()) put("visitorData", visitorData)
                }
                putJsonObject("user") {
                    onBehalfOfUser()?.let { put("onBehalfOfUser", it) }
                }
            }
            put("videoId", videoId)
            // Skips the "this may be inappropriate" interstitial on age-restricted videos.
            put("racyCheckOk", true)
            put("contentCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    if (signatureTimestamp != null) put("signatureTimestamp", signatureTimestamp)
                }
            }
        }
        val req = Request.Builder()
            .url("${client.origin}/youtubei/v1/player?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", client.userAgent)
            .header("Origin", client.origin)
            .header("Referer", "${client.origin}/")
            .header("X-Origin", client.origin)
            .header("X-YouTube-Client-Name", client.nameId.toString())
            .header("X-YouTube-Client-Version", version)
            .apply {
                if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData)
                header("Cookie", s.cookie)
                authorization(client.origin)?.let { header("Authorization", it) }
                header("X-Goog-AuthUser", s.authUser.toString())
            }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).str("error", "message") }.getOrNull()
                throw InnerTubeException(msg ?: "YouTube returned HTTP ${resp.code}", resp.code)
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** The visitor id the visionOS player requests go out with (asked for once, again after a bot check). */
    @Volatile
    private var visionVisitor: String? = null

    private fun visionOsUserAgent() =
        "com.google.visionos.youtube/$VISIONOS_VERSION(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; $gl)"

    private fun visionOsPost(endpoint: String, body: JsonObject, visitor: String?): JsonObject {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "VISIONOS")
                    put("clientVersion", VISIONOS_VERSION)
                    put("clientScreen", "WATCH")
                    put("platform", "MOBILE")
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice14,1")
                    put("osName", "visionOS")
                    put("osVersion", "25.6.0.23O471")
                    put("hl", hl)
                    put("gl", gl)
                    put("utcOffsetMinutes", 0)
                    if (visitor != null) put("visitorData", visitor)
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", visionOsUserAgent())
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-YouTube-Client-Name", "101")
            .header("X-YouTube-Client-Version", VISIONOS_VERSION)
            .apply { if (visitor != null) header("X-Goog-Visitor-Id", visitor) }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw InnerTubeException("YouTube returned HTTP ${resp.code}", resp.code)
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun visionVisitor(): String =
        visionVisitor ?: (visionOsPost("visitor_id", JsonObject(emptyMap()), null).str("responseContext", "visitorData")
            ?: throw InnerTubeException("No visitor id")).also { visionVisitor = it }

    /**
     * A song's player response as YouTube's visionOS app, anonymously: the client NewPipe (0.26.5) gets its streams
     * from, asked directly in one request (~0.2 s) instead of NewPipe's three or four (visitor id, player, the web
     * page's details), ~2 s. Its stream URLs need no signature or "n" solving, and googlevideo serves them whole
     * (ANDROID_VR's stopped after the first megabyte). Without a visitor id YouTube answers with a bot check, so one is
     * asked for once and kept; a bot check later gets one fresh id and one more try. Blocking.
     */
    fun visionOsPlayer(videoId: String): JsonObject {
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val res = visionOsPost("player", body, visionVisitor())
        val botCheck = res.str("playabilityStatus", "status") == "LOGIN_REQUIRED" &&
            res.str("playabilityStatus", "reason")?.contains("bot", ignoreCase = true) == true
        if (!botCheck) return res
        visionVisitor = null
        return visionOsPost("player", body, visionVisitor())
    }

    /** Fire-and-forget GET used for playback-history pings. */
    suspend fun ping(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().applyHeaders().build()
            http.newCall(req).execute().use { it.code }
        }.getOrNull()
    }
}
