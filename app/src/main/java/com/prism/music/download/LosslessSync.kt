package com.prism.music.download

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.AppContainer
import com.prism.music.container
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.local.LocalLossless.Companion.norm
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Lossless sync: lossless files that land on the phone (FLAC, WAV, AIFF — from a PC, a synced
 * folder, anywhere) are looked up on YouTube Music and show up in Downloads as songs, playing
 * from the file. New files are picked up as soon as Android indexes them, and every few hours
 * by [LosslessSyncWorker]. Only runs while lossless playback and the lossless add-on are on.
 * Removing one from Downloads only hides it; the file itself is never touched.
 */
class LosslessSync(private val context: Context, private val c: AppContainer) {
    /** File uri -> the song it was matched to (JSON). */
    private val links = context.getSharedPreferences("lossless_links", Context.MODE_PRIVATE)
    /** File uri -> when a lookup last found nothing (tried again after a week). */
    private val misses = context.getSharedPreferences("lossless_misses", Context.MODE_PRIVATE)
    /** Song ids removed from Downloads by hand. */
    private val hidden = context.getSharedPreferences("lossless_hidden", Context.MODE_PRIVATE)

    private val _songs = MutableStateFlow<Map<String, Pair<Song, LocalLossless.Track>>>(emptyMap())
    /** Synced lossless songs, by video id, with the file each plays from. */
    val songs: StateFlow<Map<String, Pair<Song, LocalLossless.Track>>> = _songs
    private val _working = MutableStateFlow(false)
    /** True while files are being looked up. */
    val working: StateFlow<Boolean> = _working

    private val mutex = Mutex()
    private var pending: Job? = null

    private fun enabled(): Boolean {
        val s = c.settings.current
        return s.lossless && s.losslessLocal && c.localLossless.hasPermission()
    }

    /** Syncs now and whenever the setting changes or Android adds or removes audio files. */
    fun start() {
        c.scope.launch {
            c.settings.flow.map { it.lossless && it.losslessLocal }.distinctUntilChanged().collect { sync() }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (enabled()) syncSoon()
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
    }

    /** Copying an album fires a burst of changes: wait for it to settle, then sync once. */
    private fun syncSoon() {
        pending?.cancel()
        pending = c.scope.launch { delay(15_000); sync() }
    }

    /** Rescans the phone for lossless files and adds any new ones to Downloads. */
    suspend fun sync(lookUp: Boolean = true) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled()) {
                c.localLossless.linked = emptyMap()
                _songs.value = emptyMap()
                return@withLock
            }
            c.localLossless.scan()
            val tracks = c.localLossless.tracks
            val present = tracks.map { it.uri.toString() }.toSet()
            // Files that are gone take their song out of Downloads.
            links.all.keys.filter { it !in present }.let { gone ->
                if (gone.isNotEmpty()) links.edit().apply { gone.forEach { remove(it) } }.apply()
            }
            misses.all.keys.filter { it !in present }.let { gone ->
                if (gone.isNotEmpty()) misses.edit().apply { gone.forEach { remove(it) } }.apply()
            }
            publish(tracks)

            if (!lookUp || !online()) return@withLock
            val now = System.currentTimeMillis()
            val todo = tracks.filter { t ->
                val key = t.uri.toString()
                !links.contains(key) && now - misses.getLong(key, 0L) > 7 * 86_400_000L
            }.take(MAX_LOOKUPS)
            if (todo.isEmpty()) return@withLock
            _working.value = true
            var found = 0
            try {
                for (t in todo) {
                    val song = runCatching { lookUp(t) }.getOrElse { e ->
                        Log.w("LosslessSync", "lookup failed for ${t.title}", e)
                        null
                    }
                    val key = t.uri.toString()
                    if (song != null) {
                        links.edit().putString(key, InnerTube.json.encodeToString(Song.serializer(), song)).apply()
                        found++
                        // Shows up in Downloads as it's found, not all at the end.
                        if (found % 5 == 0) publish(tracks)
                    } else {
                        misses.edit().putLong(key, now).apply()
                    }
                    delay(400) // gentle on YouTube Music
                }
            } finally {
                _working.value = false
                publish(tracks)
            }
            Log.i("LosslessSync", "${tracks.size} lossless files; looked up ${todo.size}, matched $found")
            Unit
        }
    }

    private fun publish(tracks: List<LocalLossless.Track>) {
        val byUri = tracks.associateBy { it.uri.toString() }
        val out = LinkedHashMap<String, Pair<Song, LocalLossless.Track>>()
        for ((key, json) in links.all) {
            val track = byUri[key] ?: continue
            val song = runCatching { InnerTube.json.decodeFromString(Song.serializer(), json as String) }.getOrNull() ?: continue
            if (!out.containsKey(song.id)) out[song.id] = song to track
        }
        c.localLossless.linked = out.mapValues { it.value.second.uri }
        _songs.value = out.filterKeys { !hidden.contains(it) }
    }

    /** The YouTube Music song a file is: same title, same artist, about the same length. */
    private suspend fun lookUp(t: LocalLossless.Track): Song? {
        val title = norm(t.title).ifBlank { return null }
        val artist = norm(t.artist.substringBefore(";").substringBefore(","))
        val results = c.ytm.search("${t.artist} ${t.title}".trim(), SearchFilter.SONGS).items
            .mapNotNull { (it as? SongItem)?.song }.filter { !it.isVideo }
        fun lengthOk(s: Song, slack: Int) = s.durationSec <= 0 || t.durationMs <= 0 || kotlin.math.abs(t.durationMs / 1000 - s.durationSec) <= slack
        fun titleOk(s: Song) = norm(s.title).let { it == title || it.contains(title) || title.contains(it) }
        fun artistOk(s: Song) = artist.isBlank() || s.artists.any { a -> norm(a.name).let { it.isNotBlank() && (it.contains(artist) || artist.contains(it)) } }
        return results.firstOrNull { titleOk(it) && artistOk(it) && lengthOk(it, 4) }
            ?: results.firstOrNull { titleOk(it) && lengthOk(it, 2) && t.durationMs > 0 && it.durationSec > 0 }
    }

    fun isSynced(id: String) = _songs.value.containsKey(id)

    /** Takes a song out of Downloads; its file stays where it is. */
    fun hide(ids: Collection<String>) {
        val local = ids.filter { _songs.value.containsKey(it) }
        if (local.isEmpty()) return
        hidden.edit().apply { local.forEach { putBoolean(it, true) } }.apply()
        _songs.value = _songs.value - local.toSet()
    }

    /** Puts a hidden song back in Downloads. True if it has a lossless file to play from. */
    fun unhide(id: String): Boolean {
        if (!hidden.contains(id) || !c.localLossless.linked.containsKey(id)) return false
        hidden.edit().remove(id).apply()
        c.scope.launch { sync(lookUp = false) }
        return true
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        /** Lookups per sync; a big library fills in over a few runs. */
        private const val MAX_LOOKUPS = 300
    }
}

/** Looks for new lossless files every few hours, even when Prism hasn't been opened. */
class LosslessSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.losslessSync.sync()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<LosslessSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("lossless_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
