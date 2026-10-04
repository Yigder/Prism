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


    companion object {
        const val BASE = "https://music.youtube.com/youtubei/v1/"
        const val ORIGIN = "https://music.youtube.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    @Volatile
    var clientVersion = "1.20260928.13.00"
    private var versionChecked = false

    private val locale: Locale get() = Locale.getDefault()

    private fun cookieMap(): Map<String, String> = current.cookie.split(";")
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authorization(): String? {
        val cookies = cookieMap()
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
        val ts = System.currentTimeMillis() / 1000
        // Same scheme as ytmusicapi's browser auth.
        return "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $ORIGIN")}"
    }

    private fun context(): JsonObject = buildJsonObject {
        val s = current
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", clientVersion)
            put("hl", locale.language.ifBlank { "en" })
            put("gl", locale.country.ifBlank { "US" })
            put("platform", "DESKTOP")
            put("userAgent", USER_AGENT)
            if (s.visitorData.isNotBlank()) put("visitorData", s.visitorData)
        }
        putJsonObject("user") {
            val parts = s.dataSyncId.split("||")
            if (s.isLoggedIn && parts.size > 1 && parts[1].isNotBlank()) put("onBehalfOfUser", parts[0])
        }
    }

    private fun Request.Builder.applyHeaders(): Request.Builder {
        val s = current
        header("User-Agent", USER_AGENT)
        header("Origin", ORIGIN)
        header("Referer", "$ORIGIN/")
        header("X-Origin", ORIGIN)
        header("X-YouTube-Client-Name", "67")
        header("X-YouTube-Client-Version", clientVersion)
        if (s.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", s.visitorData)
        if (s.isLoggedIn) {
            header("Cookie", s.cookie)
            authorization()?.let { header("Authorization", it) }
            header("X-Goog-AuthUser", "0")
        }
        return this
    }

    /** Reads the live web client version so requests keep looking like the current web app. */
    private suspend fun ensureClientVersion() {
        if (versionChecked) return
        versionChecked = true
        runCatching {
            val req = Request.Builder().url(ORIGIN).header("User-Agent", USER_AGENT).build()
            val html = http.newCall(req).execute().use { it.body.string() }
            Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
            if (current.visitorData.isBlank()) {
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { saveVisitorData(it) }
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
            val payload = buildJsonObject {
                put("context", context())
                body.forEach { (k, v) -> put(k, v) }
            }
            val req = Request.Builder()
                .url("$BASE$endpoint?prettyPrint=false$query")
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .applyHeaders()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(text).str("error", "message") }.getOrNull()
                    throw InnerTubeException(msg ?: "YouTube Music returned HTTP ${resp.code}", resp.code)
                }
                json.parseToJsonElement(text).jsonObject
            }
        }

    /** Fire-and-forget GET used for playback-history pings. */
    suspend fun ping(url: String) = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().applyHeaders().build()
            http.newCall(req).execute().close()
        }
    }
}
