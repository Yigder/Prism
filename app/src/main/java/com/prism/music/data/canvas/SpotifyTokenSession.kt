/*
 * Token harvesting and client-token setup are adapted from BitChord's Android
 * Spotify Canvas implementation: https://github.com/kushagrasinghx/BitChord
 * Licensed under GPL-3.0, compatible with Prism's GPL-3.0-or-later license.
 */
package com.prism.music.data.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume

private val SPOTIFY_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private const val SPOTIFY_DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L
private fun JsonObject.strValue(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/** Mints Spotify's logged-in web-player token from the locally saved sp_dc cookie. */
internal class SpotifyTokenSession(
    context: Context,
    private val preferences: SharedPreferences,
    private val http: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val tokenMutex = Mutex()
    private val clientTokenMutex = Mutex()

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var clientId: String? = null
    @Volatile private var clientToken: String? = null
    @Volatile private var clientTokenExpiresAt = 0L
    @Volatile private var sessionInfo: SessionInfo? = null

    private data class HarvestedToken(val token: String, val expiresAt: Long, val clientId: String?)
    private data class SessionInfo(val clientVersion: String, val deviceId: String)

    fun invalidate() {
        accessToken = null
        expiresAt = 0L
        clientId = null
        clientToken = null
        clientTokenExpiresAt = 0L
    }

    suspend fun accessToken(): String? {
        val cookie = preferences.getString("sp_dc", null)?.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < expiresAt - 30_000 }?.let { return it }
        return tokenMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            accessToken?.takeIf { lockedNow < expiresAt - 30_000 }?.let { return@withLock it }
            val harvested = withContext(Dispatchers.Main.immediate) { harvest(cookie) } ?: return@withLock null
            accessToken = harvested.token
            expiresAt = harvested.expiresAt
            harvested.clientId?.let { clientId = it }
            harvested.token
        }
    }

    suspend fun clientToken(): String? {
        val now = System.currentTimeMillis()
        clientToken?.takeIf { now < clientTokenExpiresAt - 30_000 }?.let { return it }
        return clientTokenMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            clientToken?.takeIf { lockedNow < clientTokenExpiresAt - 30_000 }?.let { return@withLock it }
            val id = clientId ?: return@withLock null
            val session = withContext(Dispatchers.IO) { sessionInfo() } ?: return@withLock null
            val payload = buildJsonObject {
                putJsonObject("client_data") {
                    put("client_version", session.clientVersion)
                    put("client_id", id)
                    putJsonObject("js_sdk_data") {
                        put("device_brand", "unknown")
                        put("device_model", "unknown")
                        put("os", "android")
                        put("os_version", android.os.Build.VERSION.RELEASE.orEmpty())
                        put("device_id", session.deviceId)
                        put("device_type", "smartphone")
                    }
                }
            }
            val request = Request.Builder().url(CLIENT_TOKEN_URL)
                .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("User-Agent", WEB_PLAYER_UA)
                .build()
            val json = withContext(Dispatchers.IO) {
                runCatching { http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) SPOTIFY_JSON.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject else null
                } }.getOrNull()
            } ?: return@withLock null
            if (json.strValue("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@withLock null
            val grant = json["granted_token"] as? JsonObject ?: return@withLock null
            val token = grant.strValue("token") ?: return@withLock null
            val lifetime = grant.strValue("expires_after_seconds")?.toLongOrNull() ?: 3600L
            clientToken = token
            clientTokenExpiresAt = System.currentTimeMillis() + lifetime * 1000
            token
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvest(cookie: String): HarvestedToken? {
        val result = CompletableDeferred<HarvestedToken?>()
        val cookies = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
        if (withTimeoutOrNull(3_000) { setCookie(cookies, "https://open.spotify.com/", cookie) } == null) return null
        if (withTimeoutOrNull(3_000) { setCookie(cookies, "https://accounts.spotify.com/", cookie) } == null) return null
        cookies.flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
        var view: WebView? = null
        return try {
            view = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = WEB_PLAYER_UA
                cookies.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(TokenBridge(result), BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(webView, url, favicon)
                        if (url?.startsWith("https://open.spotify.com") == true) webView.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                    override fun onPageFinished(webView: WebView, url: String?) {
                        super.onPageFinished(webView, url)
                        if (url?.startsWith("https://open.spotify.com") == true) webView.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }
                loadUrl("https://open.spotify.com/")
            }
            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { result.await() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                view?.removeJavascriptInterface(BRIDGE_NAME)
                view?.stopLoading()
                view?.destroy()
            }
        }
    }

    private suspend fun setCookie(manager: CookieManager, url: String, cookie: String) {
        suspendCancellableCoroutine { continuation ->
            manager.setCookie(url, "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure") {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private class TokenBridge(private val result: CompletableDeferred<HarvestedToken?>) {
        @JavascriptInterface
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || result.isCompleted) return
            runCatching {
                val json = SPOTIFY_JSON.parseToJsonElement(payload) as? JsonObject ?: return
                val token = json.strValue("accessToken")?.takeIf { it.isNotBlank() } ?: return
                if (json.strValue("isAnonymous") == "true") return
                val expiry = json.strValue("accessTokenExpirationTimestampMs")?.toLongOrNull()
                    ?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + SPOTIFY_DEFAULT_TOKEN_LIFETIME_MS
                result.complete(HarvestedToken(token, expiry, json.strValue("clientId")))
            }
        }
    }

    private fun sessionInfo(): SessionInfo? {
        sessionInfo?.let { return it }
        val request = Request.Builder().url("https://open.spotify.com/").header("User-Agent", WEB_PLAYER_UA).get().build()
        val response = runCatching { http.newCall(request).execute().use { it.body?.string().orEmpty() to it.headers.values("Set-Cookie") } }.getOrNull()
            ?: return null
        val config = APP_SERVER_CONFIG.find(response.first)?.groupValues?.get(1) ?: return null
        val version = runCatching {
            val decoded = String(Base64.getDecoder().decode(config), Charsets.UTF_8)
            (SPOTIFY_JSON.parseToJsonElement(decoded) as? JsonObject)?.strValue("clientVersion")
        }.getOrNull() ?: return null
        val deviceId = response.second.firstNotNullOfOrNull { SP_T_COOKIE.find(it)?.groupValues?.get(1) }
            ?: UUID.randomUUID().toString()
        return SessionInfo(version, deviceId).also { sessionInfo = it }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__prismSpotifyTokenHook) return;
          window.__prismSpotifyTokenHook = true;
          var report = function (body) { try { $BRIDGE_NAME.onTokenPayload(body); } catch (e) {} };
          var isToken = function (url) { try { return String(url).indexOf('/api/token') !== -1; } catch (e) { return false; } };
          var originalFetch = window.fetch;
          if (originalFetch) window.fetch = function (input) {
            var url = (input && input.url) ? input.url : input;
            var result = originalFetch.apply(this, arguments);
            if (isToken(url)) try { result.then(function (r) { r.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
            return result;
          };
          var originalOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) { this.__prismTokenUrl = url; return originalOpen.apply(this, arguments); };
          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try { xhr.addEventListener('load', function () { if (isToken(xhr.__prismTokenUrl)) try { report(xhr.responseText); } catch (e) {} }); } catch (e) {}
            return originalSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    companion object {
        private const val BRIDGE_NAME = "PrismSpotifyTokenBridge"
        private const val HARVEST_TIMEOUT_MS = 20_000L
        private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val WEB_PLAYER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val APP_SERVER_CONFIG = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
        private val SP_T_COOKIE = Regex("""sp_t=([^;]+)""")
    }
}
