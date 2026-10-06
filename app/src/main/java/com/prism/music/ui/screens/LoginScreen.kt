package com.prism.music.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.cos
import kotlin.math.sin

/**
 * Signs in through Google's own web sign-in page. Prism never sees your
 * password — it only keeps the resulting YouTube Music session cookies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    var finishing by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }

    var status by remember { mutableStateOf<String?>(null) }

    fun complete(cookie: String) {
        if (finishing) return
        finishing = true
        status = "Connecting your YouTube Music account…"
        scope.launch {
            CookieManager.getInstance().flush()
            val (visitor, dataSync) = c.innerTube.sessionInfo(cookie)
            c.settings.saveAccount(cookie, visitor.orEmpty(), dataSync.orEmpty())
            c.settings.setOnboarded(true)
            // Wait until the saved cookie is what requests will actually use,
            // otherwise the first account/library calls go out signed-out.
            c.settings.flow.first { it.cookie == cookie }
            val info = runCatching { c.ytm.accountInfo() }.getOrNull()
            if (info != null) c.settings.saveAccountInfo(info.name, info.email, info.avatar)
            c.library.syncInBackground()
            onDone()
        }
    }

    /** Signed in once the YouTube session cookies exist, whatever page the WebView is on. */
    fun checkCookies() {
        if (finishing) return
        val cm = CookieManager.getInstance()
        val cookie = cm.getCookie("https://music.youtube.com") ?: cm.getCookie("https://www.youtube.com") ?: return
        if (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")) complete(cookie)
    }

    LaunchedEffect(Unit) {
        while (isActive && !finishing) {
            checkCookies()
            delay(1200)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Sign in to YouTube Music") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { pasteDialog = true }) { Text("Use cookie") } },
        )
        if (finishing) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (progress < 1f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google blocks sign-in from user agents flagged as embedded web views.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            // Keep everything inside this WebView; app-link schemes
                            // (intent://, vnd.youtube://) would otherwise dead-end.
                            val scheme = request.url.scheme ?: return false
                            if (scheme != "http" && scheme != "https") {
                                view.loadUrl("https://music.youtube.com/")
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            checkCookies()
                            // Signed in on a Google/YouTube page that isn't Music:
                            // hop to Music so its session cookies get set.
                            if (!finishing && url.contains("youtube.com") && !url.contains("music.youtube.com")) {
                                val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com")
                                if (cookie?.contains("SID=") == true) view.loadUrl("https://music.youtube.com/")
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F&service=youtube&passive=true")
                }
            },
        )
    }

    if (pasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text("Sign in with a cookie") },
            text = {
                Column {
                    Text(
                        "If Google blocks the embedded sign-in, copy the Cookie header from a signed-in music.youtube.com browser session and paste it here. It's stored only on this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(140.dp), placeholder = { Text("SAPISID=…; __Secure-3PAPISID=…; …") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.contains("SAPISID"), onClick = { pasteDialog = false; complete(text.trim()) }) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun WelcomeScreen(onSignIn: () -> Unit, onSkip: () -> Unit) {
    val t = rememberInfiniteTransition(label = "welcome")
    val a by t.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "a")
    Box(Modifier.fillMaxSize().background(Color(0xFF07060C))) {
        Canvas(Modifier.fillMaxSize()) {
            val cols = listOf(Color(0xFF7C5CFF), Color(0xFFFF3B5C), Color(0xFF00B4D8), Color(0xFFFFB627))
            cols.forEachIndexed { i, col ->
                val ph = a + i * 1.6f
                val p = Offset(size.width * (0.5f + 0.35f * cos(ph)), size.height * (0.35f + 0.22f * sin(ph * 1.3f)))
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), p, size.width * 0.7f), size.width * 0.7f, p)
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF07060C)), startY = size.height * 0.35f))
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("Prism", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            Text("Your YouTube Music, refracted.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(28.dp))
            Feature(Icons.Rounded.SurroundSound, "Prism Spatial audio & a 10-band EQ")
            Feature(Icons.Rounded.Lyrics, "Live lyrics on the player from ten sources")
            Feature(Icons.Rounded.Category, "Sort any playlist by genre")
            Feature(Icons.Rounded.Download, "Smart downloads & offline liked songs")
            Feature(Icons.Rounded.Palette, "Make it yours: fonts, layout, player & more")
            Spacer(Modifier.height(32.dp))
            Button(onClick = onSignIn, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onSkip, Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Continue without an account", color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
