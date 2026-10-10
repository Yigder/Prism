package com.prism.music.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.AppContainer
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class QueueEntry(val index: Int, val song: Song, val uid: String)

sealed interface VideoSyncState {
    data object Off : VideoSyncState
    data object Measuring : VideoSyncState
    /** Video time minus song time. */
    data class Synced(val lagMs: Long) : VideoSyncState
    data object Unmatched : VideoSyncState
}

/**
 * UI-facing handle on playback. The service runs in-process, so once bound we
 * drive its ExoPlayer directly and mirror its state into flows for Compose.
 */
class PlayerConnection(private val context: Context, private val c: AppContainer) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: PlaybackService? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(ExoPlayer) -> Unit>()

    val player: ExoPlayer? get() = service?.player

    private val _current = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _current
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue
    private val _index = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _index
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    /** Queue indices in the order they'll actually play after the current one (follows shuffle). */
    private val _upNext = MutableStateFlow<List<Int>>(emptyList())
    val upNext: StateFlow<List<Int>> = _upNext
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeat
    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode
    private val _videoAvailable = MutableStateFlow(false)
    val videoAvailable: StateFlow<Boolean> = _videoAvailable
    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext
    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt
    private val _output = MutableStateFlow<AudioDeviceInfo?>(null)
    /** The output picked in Prism's device sheet; null follows the system (e.g. newly connected headphones). */
    val preferredOutput: StateFlow<AudioDeviceInfo?> = _output

    init {
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            private var primed = false
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                // The callback reports existing devices once on registration; after that, a new
                // device (headphones plugged in, earbuds connected) takes over like it would elsewhere.
                if (primed) setOutput(null)
                primed = true
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id == _output.value?.id }) setOutput(null)
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    // ------------------------------------------------------------ Lyric timing

    private val _videoSync = MutableStateFlow<VideoSyncState>(VideoSyncState.Off)
    /** How the lyrics are lined up with the current music video. */
    val videoSync: StateFlow<VideoSyncState> = _videoSync
    private val _lyricOffset = MutableStateFlow(0L)
    /** The listener's own nudge for this song (and mode), in ms; positive shows lyrics earlier. */
    val lyricOffset: StateFlow<Long> = _lyricOffset
    private val _lyricShift = MutableStateFlow(0L)
    /** Added to the playhead to get the lyrics' time (the offset, minus any video lag). */
    val lyricShift: StateFlow<Long> = _lyricShift
    private var syncKey: String? = null
    private var syncJob: kotlinx.coroutines.Job? = null

    /** The playhead in the song's (lyrics') timeline. */
    val lyricPosition: Long get() = position + _lyricShift.value

    fun seekToLyric(ms: Long) = seekTo((ms - _lyricShift.value).coerceAtLeast(0))

    private fun offsetKey(): String? {
        val song = _current.value ?: return null
        return song.id + if (player?.currentMediaItem?.videoModeId != null) ":video" else ""
    }

    fun setLyricOffset(ms: Long) {
        val key = offsetKey() ?: return
        c.lyricOffsets.edit().apply { if (ms == 0L) remove(key) else putLong(key, ms) }.apply()
        _lyricOffset.value = ms
        updateShift()
    }

    private fun updateLyricTiming() {
        val song = _current.value
        val vid = player?.currentMediaItem?.videoModeId
        _lyricOffset.value = offsetKey()?.let { c.lyricOffsets.getLong(it, 0L) } ?: 0L
        val key = if (song != null && vid != null && vid != song.id) "${song.id}|$vid" else null
        if (key != syncKey) {
            syncKey = key
            syncJob?.cancel()
            if (key == null || song == null || vid == null) _videoSync.value = VideoSyncState.Off
            else {
                val known = c.videoSync.cached(song.id, vid)
                if (known != null) _videoSync.value = VideoSyncState.Synced(known)
                else {
                    _videoSync.value = VideoSyncState.Measuring
                    syncJob = scope.launch {
                        val lag = c.videoSync.lagMs(song.id, vid)
                        if (syncKey == key) {
                            _videoSync.value = lag?.let { VideoSyncState.Synced(it) } ?: VideoSyncState.Unmatched
                            updateShift()
                        }
                    }
                }
            }
        }
        updateShift()
    }

    /** Measures the current music video against the song again. */
    fun resyncVideo() {
        val song = _current.value ?: return
        val vid = player?.currentMediaItem?.videoModeId ?: return
        c.videoSync.forget(song.id, vid)
        syncKey = null
        updateLyricTiming()
    }

    private fun updateShift() {
        val lag = (_videoSync.value as? VideoSyncState.Synced)?.lagMs ?: 0L
        _lyricShift.value = _lyricOffset.value - lag
    }

    fun setOutput(device: AudioDeviceInfo?) {
        _output.value = device
        player?.setPreferredAudioDevice(device)
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    internal fun attach(s: PlaybackService) {
        service = s
        s.player.addListener(this)
        refresh()
        pending.toList().forEach { it(s.player) }
        pending.clear()
    }

    internal fun detach() {
        service?.player?.removeListener(this)
        service = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    /**
     * Starts the playback service (not just binds to it) so it keeps running as
     * a media service — with its notification and system media controls — after
     * the app leaves the screen.
     */
    private fun ensureStarted() {
        runCatching { context.startService(android.content.Intent(context, PlaybackService::class.java)) }
    }

    private fun withPlayer(block: (ExoPlayer) -> Unit) {
        val p = player
        if (p != null) block(p) else {
            pending += block
            connect()
        }
    }

    fun reportError(msg: String) {
        _errors.tryEmit(msg)
    }

    // ------------------------------------------------------------ Commands

    private val _showPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Asks the UI to bring the full player up. */
    val showPlayer = _showPlayer.asSharedFlow()

    /** Songs found for music videos / live performances this session (video id -> song). */
    private val videoSongs = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private var playToken = 0

    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: QueueSource, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val token = ++playToken
        val tapped = songs[startIndex.coerceIn(0, songs.lastIndex)]
        if (shuffle || !tapped.isVideo) { startQueue(songs, startIndex, source, shuffle, null); return }
        // A music video or live performance opens as the song it's of, already showing the video
        // (the Song button then plays the track itself). With no song to match, the video plays as it is.
        _showPlayer.tryEmit(Unit)
        scope.launch {
            val song = videoSongs[tapped.id] ?: kotlinx.coroutines.withTimeoutOrNull(4_000) {
                runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { c.ytm.songForVideo(tapped) } }.getOrNull()
            }?.also { videoSongs[tapped.id] = it }
            if (token != playToken) return@launch
            val list = if (song == null) songs else songs.toMutableList().also { it[startIndex.coerceIn(0, songs.lastIndex)] = song }
            startQueue(list, startIndex, source, false, tapped.id)
        }
    }

    private fun startQueue(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean, startVideo: String?) {
        c.queue.source.value = source
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        ensureStarted()
        withPlayer { p ->
            p.shuffleModeEnabled = shuffle
            val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
            p.setMediaItems(songs.mapIndexed { i, s -> s.toMediaItem(if (i == start) startVideo else null) }, start, 0)
            if (shuffle) reshuffle(p)
            p.prepare()
            p.play()
            service?.maybeAutoplay()
        }
    }

    /** Plays a lone song; autoplay then continues with similar music. */
    fun playSingle(song: Song) = playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "Autoplay", seedId = song.id))

    fun playRadio(song: Song) = playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "${song.title} Radio", seedId = song.id))

    fun playNext(song: Song) = withPlayer { p ->
        if (p.mediaItemCount == 0) playSingle(song)
        else p.addMediaItem(p.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) = withPlayer { p ->
        if (p.mediaItemCount == 0) playSingle(song) else p.addMediaItem(song.toMediaItem())
    }

    /** A whole album or playlist straight after the current song (or played, with nothing on). */
    fun playNext(songs: List<Song>, source: QueueSource) {
        if (songs.isEmpty()) return
        withPlayer { p ->
            if (p.mediaItemCount == 0) playQueue(songs, 0, source)
            else p.addMediaItems(p.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        }
    }

    /** A whole album or playlist at the end of the queue (or played, with nothing on). */
    fun addToQueue(songs: List<Song>, source: QueueSource) {
        if (songs.isEmpty()) return
        withPlayer { p ->
            if (p.mediaItemCount == 0) playQueue(songs, 0, source) else p.addMediaItems(songs.map { it.toMediaItem() })
        }
    }

    /** Listen Together: plays the host's queue ([songs], the current one first) from [positionMs], as songs, in order. */
    fun follow(songs: List<Song>, positionMs: Long, title: String, playing: Boolean) {
        if (songs.isEmpty()) return
        ++playToken
        c.queue.source.value = QueueSource(QueueKind.OTHER, title)
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        ensureStarted()
        withPlayer { p ->
            p.shuffleModeEnabled = false
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.setMediaItems(songs.map { it.toMediaItem() }, 0, positionMs.coerceAtLeast(0))
            p.prepare()
            p.playWhenReady = playing
        }
    }

    /** Listen Together: makes what plays after the current song match the host's. */
    fun replaceUpcoming(songs: List<Song>) = withPlayer { p ->
        val idx = p.currentMediaItemIndex
        val have = (idx + 1 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        if (have == songs.map { it.id }) return@withPlayer
        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
        p.addMediaItems(songs.map { it.toMediaItem() })
    }

    /** The listener wants it playing (it may still be buffering). */
    val playWhenReady: Boolean get() = player?.playWhenReady ?: false

    fun setPlaying(playing: Boolean) = withPlayer { p ->
        if (playing) {
            ensureStarted()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        } else p.pause()
    }

    /** Takes everything after the current song out of the queue. */
    fun clearUpcoming() = withPlayer { p ->
        val i = p.currentMediaItemIndex
        if (i + 1 < p.mediaItemCount) p.removeMediaItems(i + 1, p.mediaItemCount)
        c.queue.autoplayStart.value = -1
    }

    fun togglePlay() = withPlayer { p ->
        if (!p.isPlaying) ensureStarted()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() = withPlayer { it.seekToNext() }
    fun previous() = withPlayer { it.seekToPrevious() }
    fun seekTo(ms: Long) = withPlayer { it.seekTo(ms) }
    fun skipTo(index: Int) = withPlayer { p ->
        p.seekToDefaultPosition(index)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }
    fun removeAt(index: Int) = withPlayer { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withPlayer { it.moveMediaItem(from, to) }
    fun toggleShuffle() = withPlayer {
        if (!it.shuffleModeEnabled) reshuffle(it)
        it.shuffleModeEnabled = !it.shuffleModeEnabled
    }

    /**
     * A fresh random order every time shuffle is pressed (the player otherwise keeps reusing its old one),
     * with the current song first so everything else is still ahead of it.
     */
    private fun reshuffle(p: ExoPlayer) {
        val n = p.mediaItemCount
        if (n < 2) return
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        val order = intArrayOf(cur) + (0 until n).filter { it != cur }.shuffled().toIntArray()
        p.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }
    fun cycleRepeat() = withPlayer {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private val _videoLoading = MutableStateFlow(false)
    /** True from tapping the video button until its first frame shows. */
    val videoLoading: StateFlow<Boolean> = _videoLoading

    fun setVideoMode(enabled: Boolean) {
        val s = service ?: return
        _videoLoading.value = enabled
        scope.launch {
            s.setVideoMode(enabled)?.let { _errors.tryEmit(it); _videoLoading.value = false }
            if (!enabled) _videoLoading.value = false
            refresh()
        }
    }

    override fun onRenderedFirstFrame() {
        _videoLoading.value = false
    }

    suspend fun videoIdFor(song: Song): String? = service?.videoIdFor(song)?.id ?: if (song.isVideo) song.id else null

    private val _sleepEndOfSong = MutableStateFlow(false)
    /** The sleep timer is set to pause when the current song ends. */
    val sleepEndOfSong: StateFlow<Boolean> = _sleepEndOfSong

    fun setSleepEndOfSong(on: Boolean) {
        _sleepEndOfSong.value = on
        if (on) _sleepAt.value = 0
        withPlayer { it.pauseAtEndOfMediaItems = on }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Paused at the end of the song for the sleep timer: done; playing on from here goes on as usual.
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _sleepEndOfSong.value) setSleepEndOfSong(false)
    }

    fun setSleepTimer(minutes: Int) {
        if (_sleepEndOfSong.value) setSleepEndOfSong(false)
        _sleepAt.value = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
        if (minutes > 0) scope.launch {
            val target = _sleepAt.value
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (_sleepAt.value == target) {
                player?.pause()
                _sleepAt.value = 0
            }
        }
    }

    val position: Long get() = player?.currentPosition ?: 0
    val duration: Long get() = player?.duration?.takeIf { it > 0 } ?: ((_current.value?.durationSec ?: 0) * 1000L)
    val bufferedPosition: Long get() = player?.bufferedPosition ?: 0

    // ------------------------------------------------------------ State mirroring

    private fun refresh() {
        val p = player ?: return
        val item = p.currentMediaItem
        val song = item?.toSong()
        val changed = song?.id != _current.value?.id
        _current.value = song
        _isPlaying.value = p.isPlaying
        _buffering.value = p.playbackState == Player.STATE_BUFFERING
        _index.value = p.currentMediaItemIndex
        _shuffle.value = p.shuffleModeEnabled
        _repeat.value = p.repeatMode
        _videoMode.value = item?.videoModeId != null
        _hasNext.value = p.hasNextMediaItem()
        _hasPrevious.value = p.hasPreviousMediaItem()
        // Keys stay stable when items move (id + occurrence), so a dragged row keeps its gesture.
        val seen = HashMap<String, Int>()
        _queue.value = (0 until p.mediaItemCount).mapNotNull { i ->
            val mi: MediaItem = p.getMediaItemAt(i)
            val n = seen.merge(mi.mediaId, 1, Int::plus) ?: 1
            mi.toSong()?.let { QueueEntry(i, it, "${mi.mediaId}#$n") }
        }
        _upNext.value = buildList {
            val tl = p.currentTimeline
            if (tl.isEmpty || p.currentMediaItemIndex !in 0 until tl.windowCount) return@buildList
            var i = tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && size < tl.windowCount) { add(i); i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) }
        }
        if (changed && song != null) checkVideo(song)
        updateLyricTiming()
    }

    /** Called when a music video failed to load; the button stays usable so it can be tried again. */
    internal fun onVideoFailed(songId: String) {
        _videoLoading.value = false
    }

    /**
     * Finds the song's music video as it starts (and warms it up), so the
     * button is ready. Greyed out only when there definitely isn't a video;
     * if YouTube couldn't be reached, it stays tappable to try again.
     */
    private fun checkVideo(song: Song) {
        _videoLoading.value = false
        if (song.id in c.queue.noVideo) { _videoAvailable.value = false; return }
        val known = if (song.isVideo) song.id else c.queue.counterparts[song.id]
        _videoAvailable.value = known != null
        val s = service ?: return
        if (known != null) { s.prefetchVideo(song, known); return }
        scope.launch {
            val found = s.videoIdFor(song)
            if (_current.value?.id != song.id) return@launch
            _videoAvailable.value = found.id != null || found.lookupFailed
            found.id?.let { s.prefetchVideo(song, it) }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        refresh()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refresh()
    }
}
