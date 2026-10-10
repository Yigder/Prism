package com.prism.music.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.guava.future
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.MainActivity
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.roundToInt

data class VideoLookup(val id: String?, val lookupFailed: Boolean = false)

@Serializable
private data class SavedQueue(val songs: List<Song>, val index: Int, val positionMs: Long, val title: String)

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), Player.Listener {
    private val c by lazy { container }
    lateinit var player: ExoPlayer
        private set
    private lateinit var session: MediaLibrarySession
    private val tree by lazy { MediaTree(c) }
    lateinit var effects: AudioEffects
        private set
    private val spatial = SpatialAudioProcessor()
    private val normalizer = NormalizerProcessor()
    private var songLoudness: Float? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val likeCommand = SessionCommand("prism.LIKE", Bundle.EMPTY)
    private val shuffleCommand = SessionCommand("prism.SHUFFLE", Bundle.EMPTY)
    private val repeatCommand = SessionCommand("prism.REPEAT", Bundle.EMPTY)
    private val lyricsCommand = SessionCommand("prism.CAR_LYRICS", Bundle.EMPTY)
    private val radioCommand = SessionCommand("prism.RADIO", Bundle.EMPTY)
    private val dislikeCommand = SessionCommand("prism.DISLIKE", Bundle.EMPTY)
    private val customCommands get() = listOf(likeCommand, shuffleCommand, repeatCommand, lyricsCommand, radioCommand, dislikeCommand)
    /** What the media session (Android Auto, the notification) sees: the player, with lyrics laid over the title and cover. */
    private lateinit var sessionPlayer: LyricsOverlayPlayer
    private lateinit var carLyrics: CarLyrics
    /** Android Auto's connections to Prism. */
    private val carControllers = HashSet<MediaSession.ControllerInfo>()
    private var playStartedAt = 0L
    private var accumulatedMs = 0L
    private var trackedSong: Song? = null
    private val retries = HashMap<String, Int>()
    private var saveJob: Job? = null
    private var extrasJob: Job? = null


    override fun onCreate() {
        super.onCreate()
        effects = AudioEffects(this)
        // Lossless mode keeps hi-res files (24-bit FLAC) at full resolution instead of rounding to 16-bit.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput || c.settings.current.lossless)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(spatial, normalizer))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            // Start (and resume after a switch to video) once 1 s is buffered rather than 2.5 s.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 1_000, 2_000)
                    .build()
            )
            .setMediaSourceFactory(
                PrismMediaSourceFactory(
                    c.playbackDataSourceFactory(), c.videoDataSourceFactory(),
                    androidx.media3.datasource.DefaultDataSource.Factory(this),
                    localAudio = { item -> losslessFileFor(item) },
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Matching the display to the video's frame rate also throttled the whole
        // app (lyrics included) to 60 Hz; the UI should stay at the panel's full rate.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        player.addListener(this)
        player.addAnalyticsListener(StatsListener())
        player.skipSilenceEnabled = c.settings.current.skipSilence

        sessionPlayer = LyricsOverlayPlayer(player)
        carLyrics = CarLyrics(this, c, player, sessionPlayer, scope)
        carLyrics.start()
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
        // Keep the media notification (and the system media controls) up for a
        // paused or restored queue, not only while audio is actively playing.
        setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        effects.attach(player.audioSessionId)
        c.onStreamResolved = { id -> scope.launch { applyStreamStats(id) } }
        scope.launch { c.settings.flow.map { it.eq }.distinctUntilChanged().collect { effects.apply(it) } }
        scope.launch { c.settings.flow.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it } }
        scope.launch { c.settings.flow.map { it.autoplay }.distinctUntilChanged().drop(1).collect { onAutoplayChanged(it) } }
        scope.launch { c.settings.flow.map { it.spatial to it.spatialAmount }.distinctUntilChanged().collect { (on, amount) -> spatial.configure(on, amount) } }
        scope.launch { c.settings.flow.map { it.normalize }.distinctUntilChanged().collect { applyNormalization() } }
        scope.launch { c.library.likedIds.collect { updateButtons() } }
        scope.launch {
            c.settings.flow.map { Triple(it.carLyrics, it.carLyricsStyle, it.carLyricsOnPhone) }.distinctUntilChanged().collect {
                carLyrics.refresh(); updateButtons()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (player.mediaItemCount > 0) c.nerdStats.value = c.nerdStats.value.copy(
                    bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
                    effects = effects.activeSummary,
                    audioSessionId = player.audioSessionId,
                )
            }
        }
        restoreQueue()
        c.player.attach(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        flushPlayTime()
        saveQueue()
        c.player.detach()
        carLyrics.stop()
        effects.release()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Player events

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        flushPlayTime()
        val song = mediaItem?.toSong() ?: return
        // Disliked songs are passed over when the queue reaches them on its own (tapping one still plays it).
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && c.library.isDisliked(song.id) && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        trackedSong = song
        retries.remove(song.id)
        if (player.isPlaying) playStartedAt = SystemClock.elapsedRealtime()
        c.nerdStats.value = NerdStats(videoId = song.id, outputDevice = AudioEffects.outputDevice(this))
        c.streamDetails[song.id]?.let { applyStreamStats(song.id) }
        scope.launch(Dispatchers.IO) { c.library.recordPlayed(song) }
        loadExtras(song)
        maybeAutoplay()
        prefetchNext()
        updateButtons()
        carLyrics.refresh()
        scheduleSave()
        // Every new song starts as the song; the track that just finished goes back to audio too,
        // so returning to it doesn't land in its video.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            for (i in 0 until player.mediaItemCount) {
                if (i == player.currentMediaItemIndex) continue
                val other = player.getMediaItemAt(i)
                if (other.videoModeId != null) other.toSong()?.let { player.replaceMediaItem(i, it.toMediaItem(null)) }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) playStartedAt = now
        else if (playStartedAt > 0) {
            accumulatedMs += now - playStartedAt
            playStartedAt = 0
            scheduleSave()
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateButtons()

    override fun onRepeatModeChanged(repeatMode: Int) = updateButtons()

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        effects.attach(audioSessionId)
    }

    override fun onPlayerError(error: PlaybackException) {
        val id = player.currentMediaItem?.mediaId ?: return
        // A music video that won't load falls back to the song itself — never skip ahead.
        if (player.currentMediaItem?.videoModeId != null) {
            scope.launch { revertVideo(id) }
            return
        }
        val httpCode = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val attempts = retries[id] ?: 0
        if (attempts < 2 && (httpCode == 403 || httpCode == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        ) {
            retries[id] = attempts + 1
            c.streams.invalidate(id)
            val pos = player.currentPosition
            player.prepare()
            player.seekTo(pos)
            player.play()
            return
        }
        c.player.reportError(error.cause?.message ?: error.message ?: "Playback failed")
        if (player.hasNextMediaItem()) {
            scope.launch {
                delay(1500)
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    // ------------------------------------------------------------ Features

    /**
     * Normalization gain for the current song: YouTube's loudness figure says how far the track
     * sits from its reference level, so loud songs come down and quiet ones go up (by up to 6 dB;
     * the limiter in [NormalizerProcessor] keeps those from clipping).
     */
    private fun applyNormalization() {
        val loud = songLoudness
        val gain = if (c.settings.current.normalize && loud != null) (-loud).coerceIn(-12f, 6f) else 0f
        normalizer.setGainDb(gain)
        c.nerdStats.value = c.nerdStats.value.copy(loudnessDb = loud?.toDouble(), normalizationGainDb = gain)
        android.util.Log.i("PrismNormalize", "loudness ${loud ?: "unknown"} dB -> gain %.1f dB (normalize ${c.settings.current.normalize})".format(gain))
    }

    private fun loadExtras(song: Song) {
        extrasJob?.cancel()
        // Remembered loudness applies straight away (and offline); the network refreshes it.
        songLoudness = c.loudness.getFloat(song.id, Float.NaN).takeIf { !it.isNaN() }
        applyNormalization()
        extrasJob = scope.launch {
            val ex = runCatching { withContext(Dispatchers.IO) { c.ytm.playerExtras(song.id, c.streams.signatureTimestamp()) } }.getOrNull()
            ex?.loudnessDb?.toFloat()?.let { loud ->
                c.loudness.edit().putFloat(song.id, loud).apply()
                if (trackedSong?.id == song.id) { songLoudness = loud; applyNormalization() }
            }
            if (ex != null && c.settings.current.isLoggedIn) {
                withContext(Dispatchers.IO) { runCatching { c.ytm.registerPlayback(ex) } }
            }
        }
    }

    /**
     * With Autoplay on, keeps the queue topped up with YouTube Music radio
     * suggestions as it nears its end: seeded by the song for a single-song
     * play, or by the last track of an album / playlist.
     */
    fun maybeAutoplay() {
        val src = c.queue.source.value
        if (!c.settings.current.autoplay || player.mediaItemCount == 0) return
        if (src.kind != QueueKind.SINGLE && player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || c.queue.autoplayLoading) return
        val seed = src.seedId ?: player.getMediaItemAt(player.mediaItemCount - 1).mediaId.takeIf { it.isNotBlank() } ?: return
        c.queue.autoplayLoading = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) { c.ytm.next(seed, "RDAMVM$seed", continuation = c.queue.autoplayContinuation) }
            }.getOrNull()
            c.queue.autoplayLoading = false
            // Dropped if the queue was replaced, or Autoplay was switched off, while loading.
            if (r == null || c.queue.source.value !== src || !c.settings.current.autoplay) return@launch
            c.queue.counterparts.putAll(r.counterparts)
            c.queue.autoplayContinuation = r.continuation
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val add = r.songs.filter { it.id !in existing && !c.library.isDisliked(it.id) }
            if (add.isNotEmpty()) {
                if (c.queue.autoplayStart.value < 0) c.queue.autoplayStart.value = player.mediaItemCount
                player.addMediaItems(add.map { it.toMediaItem() })
            }
        }
    }

    /** Switching Autoplay off takes its suggestions back out of the queue; on, fetches them. */
    private fun onAutoplayChanged(enabled: Boolean) {
        if (enabled) { maybeAutoplay(); return }
        val start = c.queue.autoplayStart.value
        if (start >= 0 && start < player.mediaItemCount) {
            val from = maxOf(start, player.currentMediaItemIndex + 1)
            if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        }
        c.queue.autoplayStart.value = -1
        c.queue.autoplayContinuation = null
    }

    /**
     * The song's music video id. Null with [lookupFailed] = true means YouTube
     * couldn't be reached (worth trying again); otherwise there simply isn't one.
     */
    suspend fun videoIdFor(song: Song): VideoLookup {
        if (song.isVideo) return VideoLookup(song.id)
        c.queue.counterparts[song.id]?.let { return VideoLookup(it) }
        if (song.id in c.queue.noVideo) return VideoLookup(null)
        val found = runCatching { withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }
        found.getOrNull()?.let { c.queue.counterparts[song.id] = it; return VideoLookup(it) }
        if (found.isSuccess) c.queue.noVideo.add(song.id)
        return VideoLookup(null, lookupFailed = found.isFailure)
    }

    /**
     * Gets a song's video ready before it's asked for: the video's streams are
     * resolved (the slow part of switching), and on Wi-Fi the lyric/position
     * offset is measured, so the video button switches straight away.
     */
    fun prefetchVideo(song: Song, videoId: String) {
        if (videoId in prefetched) return
        prefetched += videoId
        scope.launch(Dispatchers.IO) {
            runCatching { c.streams.resolve(videoId, withVideo = true) }.onFailure { prefetched -= videoId }
            val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (!metered && videoId != song.id) runCatching { c.videoSync.lagMs(song.id, videoId) }
        }
    }

    /**
     * Resolves the next song's stream while this one plays (the slow part of starting a song), so
     * skipping ahead or moving on starts straight away. Downloaded songs don't need it.
     */
    private fun prefetchNext() {
        val i = player.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val next = player.getMediaItemAt(i).mediaId.takeIf { it.isNotBlank() } ?: return
        if (c.downloads.isDownloaded(next)) return
        scope.launch(Dispatchers.IO) { c.streams.prefetch(next) }
    }

    private val prefetched = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Videos that failed to play; their streams are re-resolved on the next try. */
    private val failedVideos = HashSet<String>()

    /** Swaps the current item between its audio track and its music video. Returns an error message, if any. */
    suspend fun setVideoMode(enabled: Boolean): String? {
        val idx = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return null
        val song = item.toSong() ?: return null
        if (enabled == (item.videoModeId != null)) return null
        val vid = if (enabled) {
            val found = videoIdFor(song)
            found.id ?: return if (found.lookupFailed) "Couldn't reach YouTube for the music video. Tap to try again." else "No music video for this song"
        } else null
        if (player.currentMediaItemIndex != idx) return null
        if (vid != null && failedVideos.remove(vid)) c.streams.invalidate(vid)
        // Videos often have an intro, so the same moment falls at a different time: carry the
        // listener to the matching spot. Uses the measured offset if it's ready (it's prefetched);
        // otherwise switch now and correct the position as soon as it's known.
        val known = when {
            vid != null -> c.videoSync.cached(song.id, vid)
            else -> item.videoModeId?.let { c.videoSync.cached(song.id, it) }?.let { -it }
        }
        val now = player.currentPosition
        val pos = (now + (known ?: 0L)).coerceAtLeast(0)
        val playWhenReady = player.playWhenReady
        player.replaceMediaItem(idx, song.toMediaItem(vid))
        player.seekTo(idx, pos)
        player.playWhenReady = playWhenReady
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        videoWatchdog?.cancel()
        if (vid != null) {
            if (known == null && vid != song.id && now > 1_500) scope.launch {
                val lag = c.videoSync.lagMs(song.id, vid) ?: return@launch
                val current = player.currentMediaItem
                // Only if the listener is still on this video and hasn't moved far since switching.
                if (kotlin.math.abs(lag) >= 1_000 && current?.mediaId == song.id && current.videoModeId == vid &&
                    kotlin.math.abs(player.currentPosition - pos) < 20_000
                ) player.seekTo(player.currentPosition + lag)
            }
            videoWatchdog = scope.launch {
                // No picture after a fair wait: fall back to the song (the button stays usable).
                delay(20_000)
                val cur = player.currentMediaItem
                if (cur?.mediaId == song.id && cur.videoModeId != null && player.videoSize.width <= 0) revertVideo(song.id)
            }
        }
        return null
    }

    private var videoWatchdog: Job? = null

    /** Switches back to the audio version after a video failure; the video can be tried again. */
    private suspend fun revertVideo(songId: String) {
        videoWatchdog?.cancel()
        val idx = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        val song = current.toSong() ?: return
        current.videoModeId?.let { failedVideos += it; prefetched -= it }
        val lag = current.videoModeId?.takeIf { it != song.id }?.let { c.videoSync.cached(song.id, it) } ?: 0L
        val pos = (player.currentPosition - lag).coerceAtLeast(0)
        player.replaceMediaItem(idx, song.toMediaItem(null))
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
        c.player.onVideoFailed(songId)
        c.player.reportError("The music video didn't load, so the song is playing. Tap the video button to try again.")
    }

    private fun flushPlayTime() {
        val now = SystemClock.elapsedRealtime()
        if (playStartedAt > 0) accumulatedMs += now - playStartedAt
        playStartedAt = if (::player.isInitialized && player.isPlaying) now else 0
        val song = trackedSong
        val ms = accumulatedMs
        accumulatedMs = 0
        if (song != null && ms >= 10_000) {
            scope.launch(Dispatchers.IO) {
                c.db.plays().insert(PlayEventEntity(songId = song.id, timestamp = System.currentTimeMillis(), playedMs = ms))
            }
        }
    }

    /**
     * Buttons beside play/pause/skip. The phone's media controls show the first two (like, shuffle);
     * Android Auto shows them all: like, shuffle, repeat, lyrics, start radio and "not for me".
     */
    private fun updateButtons() {
        if (!::session.isInitialized) return
        val id = player.currentMediaItem?.mediaId
        val liked = id != null && id in c.library.likedIds.value
        fun button(icon: Int, name: String, command: SessionCommand) =
            CommandButton.Builder(icon).setDisplayName(name).setSessionCommand(command).build()
        val buttons = listOf(
            button(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, if (liked) "Remove from liked" else "Like", likeCommand),
            button(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF, if (player.shuffleModeEnabled) "Shuffle off" else "Shuffle", shuffleCommand),
            when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> button(CommandButton.ICON_REPEAT_ALL, "Repeat one", repeatCommand)
                Player.REPEAT_MODE_ONE -> button(CommandButton.ICON_REPEAT_ONE, "Repeat off", repeatCommand)
                else -> button(CommandButton.ICON_REPEAT_OFF, "Repeat", repeatCommand)
            },
            if (c.settings.current.carLyrics) button(CommandButton.ICON_SUBTITLES, "Hide lyrics", lyricsCommand)
            else button(CommandButton.ICON_SUBTITLES_OFF, "Show lyrics", lyricsCommand),
            button(CommandButton.ICON_RADIO, "Start radio", radioCommand),
            button(CommandButton.ICON_THUMB_DOWN_UNFILLED, "Not for me", dislikeCommand),
        )
        session.setMediaButtonPreferences(buttons)
    }

    /** A fresh shuffle order each time shuffle goes on, current song first. */
    private fun reshuffle() {
        val n = player.mediaItemCount
        if (n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val order = intArrayOf(cur) + (0 until n).filter { it != cur }.shuffled().toIntArray()
        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    /** Swaps everything after the current song for a radio station seeded by it. */
    private fun startRadio() {
        val song = player.currentMediaItem?.toSong() ?: return
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { c.ytm.next(song.id, "RDAMVM${song.id}") } }.getOrNull() ?: return@launch
            if (player.currentMediaItem?.mediaId != song.id) return@launch
            c.queue.counterparts.putAll(r.counterparts)
            val add = r.songs.filter { it.id != song.id && !c.library.isDisliked(it.id) }
            if (add.isEmpty()) return@launch
            val idx = player.currentMediaItemIndex
            if (idx + 1 < player.mediaItemCount) player.removeMediaItems(idx + 1, player.mediaItemCount)
            if (idx > 0) player.removeMediaItems(0, idx)
            player.shuffleModeEnabled = false
            player.addMediaItems(add.map { it.toMediaItem() })
            c.queue.source.value = QueueSource(QueueKind.RADIO, "${song.title} radio", seedId = song.id)
            c.queue.autoplayStart.value = -1
            c.queue.autoplayContinuation = r.continuation
        }
    }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        controller.packageName == "com.google.android.projection.gearhead" ||
            runCatching { session.isAutoCompanionController(controller) || session.isAutomotiveController(controller) }.getOrDefault(false)

    fun applyStreamStats(id: String) {
        val (source, s) = c.streamDetails[id] ?: return
        if (player.currentMediaItem?.mediaId != id && player.currentMediaItem?.videoModeId != id) return
        // Downloaded and cached songs have no stream info this session: work the bitrate out
        // from the saved file's size over the song's length.
        val savedBytes = if (s == null) savedLength(id) else 0L
        val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: player.currentMediaItem?.toSong()?.durationSec?.takeIf { it > 0 }?.times(1000L) ?: 0L
        val savedKbps = if (savedBytes > 0 && durationMs > 0) (savedBytes * 8 / durationMs).toInt() else 0
        c.nerdStats.value = c.nerdStats.value.copy(
            source = source,
            itag = s?.itag ?: 0,
            container = s?.mimeType ?: c.nerdStats.value.container,
            codec = s?.codec ?: c.nerdStats.value.codec,
            streamBitrateKbps = s?.bitrate ?: savedKbps.takeIf { it > 0 } ?: c.nerdStats.value.streamBitrateKbps,
            contentLength = s?.contentLength ?: savedBytes,
            client = s?.client ?: "",
        )
    }

    /** The lossless add-on: a matching FLAC / WAV / AIFF on the phone, when lossless mode and the add-on are on. */
    private fun losslessFileFor(item: MediaItem): android.net.Uri? {
        val s = c.settings.current
        if (!s.lossless || !s.losslessLocal) return null
        val song = item.toSong() ?: return null
        val track = c.localLossless.match(song) ?: return null
        c.streamDetails[song.id] = "Lossless · ${track.format} on this phone" to null
        scope.launch { applyStreamStats(song.id) }
        return track.uri
    }

    private fun savedLength(id: String): Long = c.localLossless.matched[id]?.size ?: runCatching {
        listOf(c.downloadCache, c.playerCache).firstNotNullOfOrNull { cache ->
            androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(id)).takeIf { it > 0 }
        } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------ Queue persistence

    private val queueFile get() = File(filesDir, "queue.json")

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(2000); saveQueue() }
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong() }
        val q = SavedQueue(songs, player.currentMediaItemIndex, player.currentPosition, c.queue.source.value.title)
        val text = InnerTube.json.encodeToString(SavedQueue.serializer(), q)
        scope.launch(Dispatchers.IO) { runCatching { queueFile.writeText(text) } }
    }

    private fun restoreQueue() {
        val q = runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull() ?: return
        if (q.songs.isEmpty()) return
        c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
        player.setMediaItems(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
    }

    // ------------------------------------------------------------ Session

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (isCar(session, controller)) {
                carControllers += controller
                carLyrics.carConnected = true
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply { customCommands.forEach { add(it) } }.build()
                )
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) carLyrics.carConnected = carControllers.isNotEmpty()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                likeCommand.customAction -> player.currentMediaItem?.toSong()?.let { c.library.toggleLike(it) }
                shuffleCommand.customAction -> {
                    if (!player.shuffleModeEnabled) reshuffle()
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                repeatCommand.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                lyricsCommand.customAction -> c.settings.setCarLyrics(!c.settings.current.carLyrics)
                radioCommand.customAction -> startRadio()
                dislikeCommand.customAction -> player.currentMediaItem?.toSong()?.let { song ->
                    c.library.setDisliked(song, true)
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ---- Browsing (Android Auto, Assistant, other media browsers)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Android Auto / the system ask for a "recent" root to offer a resume card.
            Futures.immediateFuture(LibraryResult.ofItem(if (params?.isRecent == true) tree.recentRoot() else tree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = tree.search(query)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = tree.searchResults()
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        // ---- Playing what a controller picked

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.mediaId.startsWith(MediaTree.SHUFFLE)) tree.shuffleSongs(item.mediaId)?.second.orEmpty().shuffled().map { it.toMediaItem() }
                else listOfNotNull((item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem())
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            // "Hey Google, play … on Prism": a search query instead of an id.
            val query = single?.requestMetadata?.searchQuery
            if (single != null && single.mediaId.isBlank() && !query.isNullOrBlank()) {
                val top = tree.searchTop(query)
                if (top != null) {
                    c.queue.source.value = QueueSource(QueueKind.SINGLE, "Autoplay", seedId = top.id)
                    c.queue.autoplayStart.value = -1
                    c.queue.autoplayContinuation = null
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(top.toMediaItem()), 0, C.TIME_UNSET)
                }
            }
            // "Shuffle play" at the top of a list.
            if (single != null && single.mediaId.startsWith(MediaTree.SHUFFLE)) {
                tree.shuffleSongs(single.mediaId)?.takeIf { it.second.isNotEmpty() }?.let { (title, list) ->
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    player.shuffleModeEnabled = true
                    // Shuffled here too, so each "Shuffle play" is a new order rather than the player's last one.
                    return@future MediaSession.MediaItemsWithStartPosition(list.shuffled().map { it.toMediaItem() }, 0, C.TIME_UNSET)
                }
            }
            // One song tapped in a list: play the list from that song.
            if (single != null && single.toSong() == null) {
                tree.folderOf(single.mediaId)?.let { (title, list) ->
                    val index = list.indexOfFirst { it.id == single.mediaId }.coerceAtLeast(0)
                    c.queue.source.value = QueueSource(QueueKind.PLAYLIST, title)
                    c.queue.autoplayStart.value = -1
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, startPositionMs)
                }
                tree.song(single.mediaId, single)?.let { song ->
                    c.queue.source.value = QueueSource(QueueKind.SINGLE, "Autoplay", seedId = song.id)
                    c.queue.autoplayStart.value = -1
                    c.queue.autoplayContinuation = null
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(song.toMediaItem()), 0, startPositionMs)
                }
            }
            val resolved = mediaItems.mapNotNull { item -> (item.toSong() ?: tree.song(item.mediaId, item))?.toMediaItem() }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }

        /** Lets the system media controls (and the car) resume the last queue after Prism was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val q = withContext(Dispatchers.IO) {
                runCatching { InnerTube.json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull()
            }
            if (q == null || q.songs.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
            } else {
                c.queue.source.value = QueueSource(QueueKind.OTHER, q.title)
                MediaSession.MediaItemsWithStartPosition(q.songs.map { it.toMediaItem() }, q.index.coerceIn(0, q.songs.lastIndex), q.positionMs)
            }
        }
    }
    // ------------------------------------------------------------ Stats for nerds

    private inner class StatsListener : AnalyticsListener {
        private fun update(block: NerdStats.() -> NerdStats) {
            c.nerdStats.value = c.nerdStats.value.block()
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                codec = format.codecs ?: format.sampleMimeType ?: codec,
                sampleRate = format.sampleRate.takeIf { it > 0 } ?: sampleRate,
                channels = format.channelCount.takeIf { it > 0 } ?: channels,
                container = format.containerMimeType ?: container,
                streamBitrateKbps = streamBitrateKbps.takeIf { it > 0 }
                    ?: (format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 })?.let { it / 1000 } ?: 0,
            )
        }

        // The length isn't always known when a downloaded song starts; fill its bitrate in once it is.
        override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (c.nerdStats.value.streamBitrateKbps <= 0) applyStreamStats(id)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = update { copy(decoder = decoderName) }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            update {
                copy(
                    outputEncoding = AudioEffects.encodingName(audioTrackConfig.encoding),
                    outputSampleRate = audioTrackConfig.sampleRate,
                    offload = audioTrackConfig.offload,
                    spatializer = c.settings.current.let { s ->
                        if (s.spatial) "Prism Spatial · ${(s.spatialAmount * 100).roundToInt()}%" else "Off"
                    },
                )
            }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) = update { copy(bandwidthKbps = bitrateEstimate / 1000) }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = update {
            copy(
                videoResolution = "${format.width}×${format.height}" + if (format.frameRate > 0) " @ ${format.frameRate.toInt()}fps" else "",
                videoCodec = format.codecs ?: format.sampleMimeType ?: "",
            )
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
            update { copy(droppedFrames = this.droppedFrames + droppedFrames) }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) = update { copy(underruns = underruns + 1) }
    }
}
