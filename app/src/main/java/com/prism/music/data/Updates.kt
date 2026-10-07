package com.prism.music.data

import android.content.Context
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer Prism on GitHub: its version and where to get it (the APK itself when the release has one). */
data class AppUpdate(val version: String, val url: String)

/**
 * Asks GitHub for Prism's latest release now and then (at most every six hours). Only the public
 * release info is fetched; nothing about the listener is sent. Pre-releases are never offered.
 */
class UpdateChecker(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow(saved())
    /** The update to offer, or null when Prism is current (or this version was dismissed). */
    val available: StateFlow<AppUpdate?> = _available
    @Volatile private var checking = false

    private fun saved(): AppUpdate? {
        val v = prefs.getString("latest_version", null) ?: return null
        val url = prefs.getString("latest_url", null) ?: return null
        return AppUpdate(v, url).takeIf { isNewer(v, BuildConfig.VERSION_NAME) && v != prefs.getString("dismissed", null) }
    }

    fun check(force: Boolean = false) {
        if (checking || (!force && System.currentTimeMillis() - prefs.getLong("checked_at", 0) < 6 * 3_600_000L)) return
        checking = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val json = InnerTube.json.parseToJsonElement(r.body.string())
                    val version = json.str("tag_name")?.removePrefix("v")?.trim() ?: return@use
                    val apk = json.arr("assets")?.firstOrNull { it.str("name")?.endsWith(".apk", true) == true }?.str("browser_download_url")
                    val url = apk ?: json.str("html_url") ?: "https://github.com/$REPO/releases/latest"
                    prefs.edit().putString("latest_version", version).putString("latest_url", url).putLong("checked_at", System.currentTimeMillis()).apply()
                    _available.value = saved()
                }
            }
            checking = false
        }
    }

    /** Hides the banner until a version newer than this one comes out. */
    fun dismiss() {
        _available.value?.let { prefs.edit().putString("dismissed", it.version).apply() }
        _available.value = null
    }

    companion object {
        const val REPO = "Yigder/Prism"

        /** "1.10.0" is newer than "1.9.2"; anything after a dash ("-beta") is ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
