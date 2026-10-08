package com.prism.music

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.prism.music.data.LibraryRepository
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.meta.MetaRepository
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.ResolvedStream
import com.prism.music.data.stream.StreamResolver
import com.prism.music.data.stream.YouTubeStreamInterceptor
import com.prism.music.download.DownloadRepository
import com.prism.music.playback.NerdStats
import com.prism.music.playback.PlayerConnection
import com.prism.music.playback.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PrismApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // A restore from a backup goes in before anything opens the files it replaces.
        com.prism.music.data.Backup.applyPending(this)
        container = AppContainer(this)
        runCatching { container.backup.resumeDownloads() }
    }
}

val Context.container: AppContainer get() = (applicationContext as PrismApp).container

/** Hand-rolled dependency graph; everything lives for the app process. */
@OptIn(UnstableApi::class)
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app, scope)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamHttp: OkHttpClient = http.newBuilder()
        .addInterceptor(YouTubeStreamInterceptor(http))
        .build()

    val innerTube = InnerTube(http, { settings.current }, settings::setVisitorData)
    val ytm = YouTubeMusic(innerTube)
    val db = PrismDatabase.build(app)
    val streams by lazy { StreamResolver(app, http, settings) }
    val lyrics = LyricsRepository(http, db.lyrics(), ytm) { settings.current }.apply {
        choices = app.getSharedPreferences("lyrics_choice", android.content.Context.MODE_PRIVATE)
    }
    val meta = MetaRepository(http, db.meta())
    val canvas = com.prism.music.data.canvas.CanvasRepository(http).apply {
        hiddenPrefs = app.getSharedPreferences("canvas_hidden", android.content.Context.MODE_PRIVATE)
        savedPrefs = app.getSharedPreferences("canvas_saved", android.content.Context.MODE_PRIVATE)
        useIndex(File(app.filesDir, "canvas_index.json"))
    }
    val videoSync by lazy { com.prism.music.playback.VideoSync(app, streamHttp, streams, scope) }
    /** Per-song lyric timing nudges, in ms (positive = lyrics earlier). Keyed by song id, plus ":video" in video mode. */
    /** YouTube's loudness figure per song (dB from its reference level), kept for offline normalization. */
    val loudness: android.content.SharedPreferences = app.getSharedPreferences("loudness", android.content.Context.MODE_PRIVATE)
    val lyricOffsets: android.content.SharedPreferences = app.getSharedPreferences("lyric_offsets", android.content.Context.MODE_PRIVATE)

    init {
        // Misses are cheap to re-check and may have been wrong (e.g. a provider was down).
        scope.launch(Dispatchers.IO) { runCatching { db.lyrics().clearMisses() } }
    }
    val queue = QueueState()
    val nerdStats = MutableStateFlow(NerdStats())
    /** video id -> (source label, resolved stream) for the stats overlay */
    val streamDetails = ConcurrentHashMap<String, Pair<String, ResolvedStream?>>()
    @Volatile var onStreamResolved: ((String) -> Unit)? = null

    val databaseProvider by lazy { StandaloneDatabaseProvider(app) }
    val playerCache: SimpleCache by lazy {
        val mb = settings.current.cacheSizeMb
        val evictor = if (mb <= 0) NoOpCacheEvictor() else LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L)
        SimpleCache(File(app.cacheDir, "song_cache"), evictor, databaseProvider)
    }
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    val library = LibraryRepository(this)
    val localLossless = com.prism.music.data.local.LocalLossless(app)
    val losslessSync by lazy { com.prism.music.download.LosslessSync(app, this) }
    // Started only after localLossless exists: a launch from an earlier init block can run on a
    // background thread before this property is assigned.
    init { losslessSync.start() }
    val taste = com.prism.music.data.TasteRepository(this)

    /** One-off upkeep that runs once per install, in the background, after Prism has settled. */
    private val migrations: android.content.SharedPreferences = app.getSharedPreferences("migrations", android.content.Context.MODE_PRIVATE)
    init {
        // Genre detection picked the wrong artist when several share a name (and now finds second
        // genres), so saved genres are looked up again once. Retried on a later launch if offline.
        if (!migrations.getBoolean("genres_refreshed_1", false)) scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(15_000)
            val songs = runCatching {
                (db.songs().liked() + db.songs().recentFlow(300).first()).map { it.toSong() }.distinctBy { it.id }
            }.getOrNull() ?: return@launch
            if (meta.refreshAll(songs)) migrations.edit().putBoolean("genres_refreshed_1", true).apply()
        }
    }
    val downloads by lazy { DownloadRepository(app, this, scope) }
    val covers by lazy { com.prism.music.data.PlaylistCovers(app) }
    val updates by lazy { com.prism.music.data.UpdateChecker(app, http, scope) }
    val backup by lazy { com.prism.music.data.Backup(this) }
    val player = PlayerConnection(app, this)

    private fun Cache.fullyCached(key: String, position: Long, length: Long): Boolean {
        val total = ContentMetadata.getContentLength(getContentMetadata(key))
        if (total <= 0) return false
        val len = if (length == C.LENGTH_UNSET.toLong()) total - position else length
        return len <= 0 || isCached(key, position, len)
    }

    private fun resolveAudio(spec: DataSpec, useCaches: Boolean): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "prism") return spec
        val id = uri.lastPathSegment ?: return spec
        if (useCaches) {
            if (downloadCache.fullyCached(id, spec.position, spec.length)) {
                streamDetails[id] = "Downloaded" to streamDetails[id]?.second
                onStreamResolved?.invoke(id)
                return spec.buildUpon().setKey(id).build()
            }
            if (playerCache.fullyCached(id, spec.position, spec.length)) {
                streamDetails[id] = "Cached" to streamDetails[id]?.second
                onStreamResolved?.invoke(id)
                return spec.buildUpon().setKey(id).build()
            }
        }
        val s = try {
            streams.resolve(id)
        } catch (e: Exception) {
            throw IOException(e.message ?: "Couldn't resolve stream", e)
        }
        streamDetails[id] = (if (useCaches) "Streaming" else "Downloading") to s
        if (useCaches) onStreamResolved?.invoke(id)
        return spec.buildUpon().setUri(s.audioUrl).setKey(id).build()
    }

    /** Download cache → song cache → network, with prism:// URIs resolved lazily. */
    fun playbackDataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(streamHttp)
        val songCache = CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloadsFirst = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(songCache)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(downloadsFirst) { spec -> resolveAudio(spec, true) }
    }

    fun videoDataSourceFactory(): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(streamHttp)) { spec ->
            val id = spec.uri.lastPathSegment
            if (spec.uri.scheme != "prism" || id == null) spec else {
                val s = try { streams.resolve(id, withVideo = true) } catch (e: Exception) { throw IOException(e.message, e) }
                spec.buildUpon().setUri(s.videoUrl ?: throw IOException("No video stream available")).build()
            }
        }

    fun downloadDataSourceFactory(): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(streamHttp)) { spec -> resolveAudio(spec, false) }

    /** Animated-cover clips on the phone: recently played, and saved (from the player, by album, or with downloads). */
    val canvasStore by lazy { com.prism.music.data.canvas.CanvasStore(app, http, canvas, ytm, databaseProvider, scope) }
    fun songCacheBytes(): Long = runCatching { playerCache.cacheSpace }.getOrDefault(0)

    fun clearSongCache() {
        runCatching { playerCache.keys.toList().forEach { playerCache.removeResource(it) } }
    }
}
