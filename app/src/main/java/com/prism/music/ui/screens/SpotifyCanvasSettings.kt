package com.prism.music.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalContainer

/** Spotify login stays in its own WebView; Prism stores only its session cookies locally. */
@Composable
fun SpotifyCanvasSettings() {
    val c = LocalContainer.current
    val prefs = remember { c.app.getSharedPreferences("spotify_canvas", android.content.Context.MODE_PRIVATE) }
    var connected by remember { mutableStateOf(prefs.getString("sp_dc", null).orEmpty().isNotBlank()) }
    var showLogin by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (connected) {
            Text("Spotify connected", style = MaterialTheme.typography.bodyMedium)
            PrismTextButton(onClick = { c.canvas.spotifyCanvas?.disconnect(); connected = false }) { Text("Disconnect Spotify") }
        } else {
            Text("Sign in to look up Spotify Canvas videos for the song playing.", style = MaterialTheme.typography.bodySmall)
            PrismButton(onClick = { showLogin = true }, Modifier.fillMaxWidth()) { Text("Connect Spotify") }
        }
    }
    if (showLogin) SpotifyCanvasLoginDialog(
        onConnected = { connected = true; showLogin = false },
        onDismiss = { showLogin = false },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyCanvasLoginDialog(onConnected: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val prefs = remember { c.app.getSharedPreferences("spotify_canvas", android.content.Context.MODE_PRIVATE) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    fun capture() {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie("https://open.spotify.com") ?: cm.getCookie("https://spotify.com")
        if (raw == null) { message = "Finish signing in, then tap Check sign-in."; return }
        val cookies = raw.split(';').mapNotNull { cookie ->
            cookie.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val dc = cookies["sp_dc"]?.takeIf { it.isNotBlank() }
        if (dc == null) { message = "Spotify has not finished signing in yet."; return }
        prefs.edit().putString("sp_dc", dc).putString("sp_key", cookies["sp_key"].orEmpty()).apply()
        cm.flush()
        onConnected()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Spotify") },
        text = {
            Column {
                Text("Sign in on Spotify. Prism uses this session only to find Canvas videos. Prism backups include this sign-in, so keep backup files private.", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                pageError?.let { Text("Spotify sign-in page couldn't load: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(480.dp).padding(top = 10.dp),
                    onRelease = { it.stopLoading(); it.destroy() },
                    factory = { context ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(context).apply {
                            // Match the measured Compose viewport; WRAP_CONTENT can give Spotify's responsive page a zero-sized viewport.
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString
                                .replace(Regex(";\\s*wv\\)"), ")")
                                .replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    pageError = null
                                    capture()
                                }

                                override fun onPageFinished(view: WebView, url: String) = capture()

                                override fun onReceivedError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                    error: android.webkit.WebResourceError,
                                ) {
                                    if (request.isForMainFrame) pageError = error.description?.toString() ?: "Network error"
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                    response: android.webkit.WebResourceResponse,
                                ) {
                                    if (request.isForMainFrame && response.statusCode >= 400) {
                                        pageError = "Spotify returned HTTP ${response.statusCode}"
                                    }
                                }
                            }
                            loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                        }
                    },
                )
            }
        },
        confirmButton = { PrismTextButton(onClick = { capture() }) { Text("Check sign-in") } },
        dismissButton = { PrismTextButton(onClick = { webView?.stopLoading(); onDismiss() }) { Text("Cancel") } },
    )
}
