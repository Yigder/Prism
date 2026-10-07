package com.prism.music.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.prism.music.BuildConfig
import com.prism.music.container
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A newer Prism on GitHub: its version and where to get it (the APK itself when the release has one). */
data class AppUpdate(val version: String, val url: String) {
    val isApk: Boolean get() = url.endsWith(".apk", ignoreCase = true)
}

/** Where updating Prism from inside the app has got to. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    /** [fraction] is null until the size is known. */
    data class Downloading(val fraction: Float?) : UpdateProgress
    /** Handed to Android's installer; waiting on the listener's confirmation or the install itself. */
    data object Installing : UpdateProgress
    /** Prism needs "Install unknown apps" switched on first. */
    data object NeedsPermission : UpdateProgress
    data class Failed(val message: String) : UpdateProgress
    /**
     * The new Prism is signed with a different key (the move off the debug key), so Android won't
     * install it over this one. The listener backs up, keeps [apk], uninstalls and installs it fresh.
     */
    data class NewSignature(val version: String, val apk: File) : UpdateProgress
}

private class SignatureChanged : Exception()

/**
 * Asks GitHub for Prism's latest release now and then (at most every six hours), and can update
 * Prism in place: the APK is downloaded inside the app and passed to Android's installer, which
 * asks the listener to confirm (Android never lets an app install silently the first time). Only
 * the public release info and file are fetched; nothing about the listener is sent. Pre-releases
 * are never offered, and Android itself refuses an APK that isn't signed like the installed Prism.
 */
class UpdateChecker(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _available = MutableStateFlow(saved())
    /** The update to offer, or null when Prism is current (or this version was dismissed). */
    val available: StateFlow<AppUpdate?> = _available
    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress
    @Volatile private var checking = false
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates")

    init {
        // A finished update leaves its APK behind; it's no use once that version is running.
        if (_available.value == null) scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

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
        _progress.value = UpdateProgress.Idle
    }

    /** Whether Android lets Prism hand APKs to its installer ("Install unknown apps" for Prism). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the listener allows Prism to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads [update] and starts installing it. Asks for the install permission first if it's
     * missing; [install] is simply called again once the listener comes back with it.
     */
    fun install(update: AppUpdate) {
        if (job?.isActive == true) return
        if (!canInstall()) { _progress.value = UpdateProgress.NeedsPermission; return }
        _progress.value = UpdateProgress.Downloading(null)
        job = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val apk = download(update)
                verify(apk)
                _progress.value = UpdateProgress.Installing
                startSession(apk)
            }
            result.exceptionOrNull()?.let { e ->
                if (!isActive) return@let
                _progress.value = if (e is SignatureChanged) UpdateProgress.NewSignature(update.version, File(dir, "Prism-${update.version}.apk"))
                else UpdateProgress.Failed(e.message ?: "The update didn't download")
            }
        }
    }

    private fun download(update: AppUpdate): File {
        dir.mkdirs()
        val file = File(dir, "Prism-${update.version}.apk")
        if (file.exists()) return file
        val part = File(dir, file.name + ".part")
        val req = Request.Builder().url(update.url).header("User-Agent", "Prism/${BuildConfig.VERSION_NAME}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub answered ${r.code}")
            val body = r.body
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: -1
                        if (pct != shown) { shown = pct; _progress.value = UpdateProgress.Downloading(total?.let { done.toFloat() / it }) }
                    }
                }
            }
            if (total != null && done != total) error("The download was cut short")
        }
        if (!part.renameTo(file)) error("Couldn't save the update")
        return file
    }

    /** Only Prism, and only something newer than what's running, is passed on. */
    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: error("The downloaded file isn't an app")
        if (info.packageName != context.packageName) error("The download isn't Prism")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) error("That's not newer than this Prism")
        val theirs = signers(context.packageManager.getPackageArchiveInfo(apk.path, signingFlag()))
        val ours = signers(context.packageManager.getPackageInfo(context.packageName, signingFlag()))
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) throw SignatureChanged()
    }

    @Suppress("DEPRECATION")
    private fun signingFlag() = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES

    /** SHA-256 of each certificate an app is signed with. */
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            else info.signatures
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Copies the downloaded new Prism to [uri] (somewhere that outlives uninstalling this one). */
    suspend fun saveApk(apk: File, uri: Uri) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open that file")
        out.use { o -> apk.inputStream().use { it.copyTo(o) } }
    }

    /** Android's "uninstall Prism?" prompt. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))

    private fun startSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once Prism installed itself, later updates can go through without the confirm screen (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("Prism.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallerResult(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // On success Android replaces Prism and closes it; there's nothing left to do here.
            PackageInstaller.STATUS_SUCCESS -> _progress.value = UpdateProgress.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _progress.value = UpdateProgress.Idle
            else -> _progress.value = UpdateProgress.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android wouldn't install it over this Prism (it's signed differently)"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install the update"
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install"
                }
            )
        }
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

/** Android's installer reports here: it asks for the listener's go-ahead, or says how the install went. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.container.updates.onInstallerResult(intent)
    }
}
