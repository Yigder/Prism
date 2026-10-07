package com.prism.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What's written first in every backup. */
@Serializable
private data class BackupManifest(
    val format: Int = 1,
    val app: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val createdAt: Long = 0,
    val withDownloads: Boolean = false,
    /** Downloaded songs, so they can be fetched again when the audio itself isn't in the backup. */
    val downloads: List<Song> = emptyList(),
)

/**
 * Backs Prism up to one file the listener keeps (a zip: the library and Replay database, settings
 * and sign-in, saved preferences, custom covers and backgrounds; optionally the downloaded audio),
 * and restores one. A restore is staged and put in place on the next launch, before anything has
 * opened those files, then Prism restarts. Nothing leaves the phone except to where the listener saves it.
 */
class Backup(private val c: AppContainer) {
    private val ctx: Context get() = c.app
    private val data: File get() = ctx.filesDir.parentFile!!

    /** Writes a backup to [uri]. */
    suspend fun export(uri: Uri, withDownloads: Boolean) = withContext(Dispatchers.IO) {
        // Fold Room's write-ahead log into the main file so the copy is complete.
        runCatching { c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close() }
        val manifest = BackupManifest(
            app = ctx.packageName, versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(), withDownloads = withDownloads,
            downloads = c.downloads.completedSongs(),
        )
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open that file")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(InnerTube.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            filesToBackUp(withDownloads).forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(data).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** About how big a backup would be, in bytes. */
    suspend fun estimate(withDownloads: Boolean): Long = withContext(Dispatchers.IO) { filesToBackUp(withDownloads).sumOf { it.length() } }

    private fun filesToBackUp(withDownloads: Boolean): List<File> {
        val keep = mutableListOf<File>()
        File(data, "databases").listFiles()?.forEach { f ->
            if (f.name.startsWith(DB) || (withDownloads && f.name.startsWith(EXO_DB))) keep += f
        }
        File(data, "shared_prefs").listFiles()?.forEach { f ->
            // Saved animated covers live with the downloads; without them, the list of them would point at nothing.
            if (withDownloads || f.name != "canvas_saved.xml") keep += f
        }
        File(data, "datastore").walkTopDown().filter { it.isFile }.forEach { keep += it }
        ctx.filesDir.walkTopDown()
            .onEnter { dir -> dir == ctx.filesDir || dir.relativeTo(ctx.filesDir).invariantSeparatorsPath.substringBefore('/').let { top -> top != STAGING && (withDownloads || top !in MEDIA) } }
            .filter { it.isFile }
            .forEach { keep += it }
        return keep
    }

    /**
     * Checks the backup at [uri] and unpacks it to be put in place at the next launch, then
     * restarts Prism. Throws with a readable message if it isn't a Prism backup.
     */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val staging = File(ctx.filesDir, STAGING)
        staging.deleteRecursively()
        staging.mkdirs()
        var manifest: BackupManifest? = null
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("Couldn't open that file")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == MANIFEST) {
                        manifest = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), zip.readBytes().decodeToString()) }.getOrNull()
                        continue
                    }
                    // Only Prism's own folders, and nothing that climbs out of them.
                    if (name.contains("..") || name.startsWith("/") || ALLOWED.none { name.startsWith(it) }) continue
                    val target = File(staging, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            val m = manifest ?: error("That file isn't a Prism backup")
            if (m.app != ctx.packageName) error("That backup is from a different app")
            if (m.format > FORMAT) error("That backup is from a newer Prism; update first")
            File(staging, MANIFEST).writeText(InnerTube.json.encodeToString(BackupManifest.serializer(), m))
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        restart()
    }

    private fun restart() {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    /** After a restore: fetches the downloads again when their audio wasn't in the backup. */
    fun resumeDownloads() {
        val f = File(ctx.filesDir, REDOWNLOAD)
        if (!f.exists()) return
        val songs = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), f.readText()).downloads }.getOrDefault(emptyList())
        f.delete()
        c.downloads.downloadAll(songs)
    }

    companion object {
        private const val FORMAT = 1
        private const val MANIFEST = "prism-backup.json"
        private const val STAGING = "restore_pending"
        private const val REDOWNLOAD = "restore_downloads.json"
        private const val DB = "prism.db"
        private const val EXO_DB = "exoplayer_internal.db"
        /** Folders under files/ that hold downloaded audio and the animated covers saved with it. */
        private val MEDIA = setOf("downloads", "canvas_saved")
        private val ALLOWED = listOf("databases/", "shared_prefs/", "datastore/", "files/")

        /**
         * Puts a staged restore in place. Runs first thing at launch, before Room, DataStore or any
         * preferences are opened, so nothing holds the old files.
         */
        fun applyPending(context: Context) {
            val files = context.filesDir
            val staging = File(files, STAGING)
            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.exists()) { staging.deleteRecursively(); return }
            runCatching {
                val m = InnerTube.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
                val data = files.parentFile!!
                // Out with what the backup replaces. Downloads stay unless the backup brings its own.
                File(data, "databases").listFiles()?.forEach { if (it.name.startsWith(DB) || (m.withDownloads && it.name.startsWith(EXO_DB))) it.delete() }
                File(data, "shared_prefs").listFiles()?.forEach { it.delete() }
                File(data, "datastore").deleteRecursively()
                files.listFiles()?.forEach { if (it.name != STAGING && (m.withDownloads || it.name !in MEDIA)) it.deleteRecursively() }
                // In with the backup.
                staging.walkTopDown().filter { it.isFile && it != manifestFile }.forEach { f ->
                    val target = File(data, f.relativeTo(staging).path)
                    target.parentFile?.mkdirs()
                    if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
                }
                if (!m.withDownloads && m.downloads.isNotEmpty()) manifestFile.copyTo(File(files, REDOWNLOAD), overwrite = true)
            }
            staging.deleteRecursively()
        }
    }
}
