package com.prism.music.download

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.toEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.prefs.AudioQuality
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val DOWNLOAD_CHANNEL = "downloads"

/** [lossless]: a lossless file on the phone that lossless sync matched to this song (see [LosslessSync]). */
data class DownloadInfo(val song: Song?, val state: Int, val percent: Float, val bytes: Long, val addedAt: Long = 0, val lossless: Boolean = false)

@OptIn(UnstableApi::class)
class DownloadRepository(private val context: Context, private val c: AppContainer, scope: CoroutineScope) {

    val manager: DownloadManager = DownloadManager(
        context,
        c.databaseProvider,
        c.downloadCache,
        c.downloadDataSourceFactory(),
        Executors.newFixedThreadPool(4),
    ).apply {
        maxParallelDownloads = 4
        requirements = Requirements(if (c.settings.current.downloadWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }

    /** Downloads Prism made itself, and lossless files on the phone ([LosslessSync]); both show as downloads. */
    private val _media = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val _lossless = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val _downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadInfo>> = _downloads
    private fun publish() { _downloads.value = _lossless.value + _media.value }

    private val lyricTries = context.getSharedPreferences("download_lyrics", Context.MODE_PRIVATE)
    private val lyricGate = kotlinx.coroutines.sync.Semaphore(2)
    private val _lyricsWorking = MutableStateFlow(0)
    /** How many downloaded songs are having their lyrics fetched right now. */
    val lyricsWorking: StateFlow<Int> = _lyricsWorking
    private val _lyricLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Saved lyrics per downloaded song, as [com.prism.music.data.lyrics.LyricsRepository.level]. */
    val lyricLevels: StateFlow<Map<String, Int>> = _lyricLevels
    init {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(DOWNLOAD_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.downloads)).build()
        )
        scope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, DownloadInfo>()
            manager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    map[d.request.id] = d.info()
                }
            }
            _media.value = map
            publish()
            // Songs downloaded before lyrics came with them (or while offline) get theirs now.
            backfillLyrics()
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                _media.update { it + (download.request.id to download.info()) }
                publish()
                if (download.state == Download.STATE_COMPLETED) download.info().song?.let { s ->
                    c.scope.launch(Dispatchers.IO) { ensureLyrics(s) }
                    // Its animated cover is kept with it.
                    if (c.settings.current.canvasEnabled && !c.canvas.saved.value.has(s.id, null)) c.canvasStore.saveWithDownload(s)
                }
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                _media.update { it - download.request.id }
                publish()
                c.canvasStore.forgetDownloads(listOf(download.request.id))
            }
        })
        scope.launch {
            c.losslessSync.songs.collect { synced ->
                val known = _lossless.value
                _lossless.value = synced.mapValues { (id, v) ->
                    DownloadInfo(v.first, Download.STATE_COMPLETED, 100f, v.second.size, known[id]?.addedAt ?: System.currentTimeMillis(), lossless = true)
                }
                publish()
                // Lyrics come with synced songs too, like with any download (same Wi-Fi-only rule).
                val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
                if (!metered || !c.settings.current.downloadWifiOnly) {
                    synced.keys.filter { it !in known }.forEach { id -> synced[id]?.first?.let { s -> c.scope.launch(Dispatchers.IO) { ensureLyrics(s) } } }
                }
            }
        }
        scope.launch {
            c.settings.flow.collect { s ->
                manager.requirements = Requirements(if (s.downloadWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
            }
        }
    }

    private fun Download.info() = DownloadInfo(
        song = runCatching { InnerTube.json.decodeFromString(Song.serializer(), String(request.data)) }.getOrNull(),
        state = state,
        percent = percentDownloaded,
        bytes = bytesDownloaded,
        addedAt = startTimeMs,
    )

    fun isDownloaded(id: String) = _downloads.value[id]?.state == Download.STATE_COMPLETED

    /** True when this song is only here as a lossless file on the phone (not downloaded by Prism). */
    fun isLosslessOnly(id: String) = id in _lossless.value && id !in _media.value

    fun download(song: Song) {
        // A lossless file that was taken out of Downloads just comes back.
        if (c.losslessSync.unhide(song.id)) return
        if (_downloads.value[song.id]?.state.let { it == Download.STATE_COMPLETED || it == Download.STATE_DOWNLOADING || it == Download.STATE_QUEUED }) return
        val request = DownloadRequest.Builder(song.id, android.net.Uri.parse("prism://audio/${song.id}"))
            .setCustomCacheKey(song.id)
            .setData(InnerTube.json.encodeToString(Song.serializer(), song).toByteArray())
            .build()
        c.scope.launch(Dispatchers.IO) {
            c.db.songs().upsert(song.toEntity(c.db.songs().get(song.id)))
            // Lyrics come down with the song (karaoke if any source has it), so they're there offline too.
            ensureLyrics(song, force = true)
            // ...and its loudness, so normalization works offline.
            if (!c.loudness.contains(song.id)) runCatching { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()).loudnessDb?.let { c.loudness.edit().putFloat(song.id, it.toFloat()).apply() } }
        }
        DownloadService.sendAddDownload(context, PrismDownloadService::class.java, request, false)
    }

    fun downloadAll(songs: List<Song>) = songs.forEach(::download)

    fun remove(id: String) {
        if (isLosslessOnly(id)) { c.losslessSync.hide(listOf(id)); return }
        DownloadService.sendRemoveDownload(context, PrismDownloadService::class.java, id, false)
        c.scope.launch(Dispatchers.IO) { c.db.songs().setSmart(id, false) }
    }

    /** Deletes many downloads at once, straight through the manager (no intent per song). */
    fun removeMany(ids: Collection<String>) {
        c.losslessSync.hide(ids.filter(::isLosslessOnly))
        ids.forEach { manager.removeDownload(it) }
        c.scope.launch(Dispatchers.IO) { ids.forEach { c.db.songs().setSmart(it, false) } }
    }

    fun removeAll() {
        val ids = _media.value.keys.toList()
        c.losslessSync.hide(_lossless.value.keys)
        manager.removeAllDownloads()
        c.scope.launch(Dispatchers.IO) { ids.forEach { c.db.songs().setSmart(it, false) } }
    }

    // ------------------------------------------------------------ Lyrics for downloads


    private fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Makes sure a downloaded song has the best lyrics going. Songs without karaoke lyrics are
     * looked at again every few days (sources keep adding them); [force] skips that wait.
     */
    private suspend fun ensureLyrics(song: Song, force: Boolean = false) {
        val have = runCatching { c.lyrics.savedLevel(song) }.getOrDefault(0)
        _lyricLevels.update { it + (song.id to have) }
        if (have >= 3 || !online()) return
        val last = lyricTries.getLong(song.id, 0L)
        if (!force && System.currentTimeMillis() - last < 3 * 86_400_000L) return
        lyricGate.acquire()
        _lyricsWorking.update { it + 1 }
        try {
            val got = runCatching { c.lyrics.download(song) }.getOrNull()
            lyricTries.edit().putLong(song.id, System.currentTimeMillis()).apply()
            _lyricLevels.update { it + (song.id to maxOf(have, c.lyrics.level(got))) }
        } finally {
            _lyricsWorking.update { it - 1 }
            lyricGate.release()
        }
    }

    /** Fills in lyrics for every downloaded song that's missing them (or only has them line by line). */
    fun backfillLyrics(force: Boolean = false) {
        c.scope.launch(Dispatchers.IO) {
            // Same rule as the downloads themselves: no mobile data when they're set to Wi-Fi only.
            val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (metered && c.settings.current.downloadWifiOnly && !force) {
                completedSongs().forEach { s -> _lyricLevels.update { it + (s.id to runCatching { c.lyrics.savedLevel(s) }.getOrDefault(0)) } }
                return@launch
            }
            for (song in completedSongs()) ensureLyrics(song, force)
        }
    }

    fun completedSongs(): List<Song> = _downloads.value.values
        .filter { it.state == Download.STATE_COMPLETED }.mapNotNull { it.song }

    val totalBytes: Long get() = _downloads.value.values.sumOf { it.bytes }
}

@OptIn(UnstableApi::class)
class PrismDownloadService : DownloadService(
    1001, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, DOWNLOAD_CHANNEL, R.string.downloads, 0,
) {
    override fun getDownloadManager(): DownloadManager = container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, DOWNLOAD_CHANNEL)
            .buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)
}

/** Sizes offered for smart downloads, in GB. */
val SMART_DOWNLOAD_SIZES = listOf(0.5f, 1f, 2f, 3f, 5f, 7.5f, 10f, 15f, 20f, 25f)

fun formatGb(gb: Float): String = when {
    gb < 1f -> "${(gb * 1024).roundToInt()} MB"
    gb % 1f == 0f -> "${gb.toInt()} GB"
    else -> "$gb GB"
}

private fun kbps(q: AudioQuality) = when (q) { AudioQuality.HIGH -> 150; AudioQuality.NORMAL -> 128; AudioQuality.LOW -> 55 }

/** Rough size of a download at a quality; long mixes and missing durations count as a typical song. */
fun estimatedSongBytes(durationSec: Int, q: AudioQuality): Long = (durationSec.takeIf { it in 30..1200 } ?: 210) * kbps(q) * 125L

fun smartSongEstimate(gb: Float, q: AudioQuality): String =
    "%,d".format((gb * 1_073_741_824.0 / estimatedSongBytes(210, q)).toLong())

