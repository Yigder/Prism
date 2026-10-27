package com.prism.music.data.local

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library → Local music: every music file on the phone (MP3, M4A, FLAC, OGG, WAV…), as Prism songs that play from
 * the file. What a song is comes from the file's own tags (title, artist, album, track number) and its album's
 * artwork; nothing is looked up online for it, and Prism never moves or deletes the files. Local songs' ids are
 * `local:<MediaStore id>` ([Song.isLocal]).
 */
class LocalMusic(private val context: Context, private val scope: CoroutineScope) {
    data class Track(
        val song: Song,
        val uri: Uri,
        val albumId: Long,
        val trackNumber: Int,
        val year: Int,
        val addedAt: Long,
        val mime: String,
        val size: Long,
    )

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    /** Every music file found in the last scan, newest first. */
    val tracks: StateFlow<List<Track>> = _tracks
    private val _scanned = MutableStateFlow(false)
    /** True once a scan has run with the permission (so an empty list means there's no music, not no access). */
    val scanned: StateFlow<Boolean> = _scanned
    @Volatile private var byId: Map<String, Track> = emptyMap()
    private var pending: Job? = null
    private var watching = false

    fun hasPermission() = context.checkSelfPermission(LocalLossless.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The file a local song plays from. */
    fun uriFor(id: String): Uri? = byId[id]?.uri
        ?: id.removePrefix(Song.LOCAL_PREFIX).toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

    fun track(id: String): Track? = byId[id]

    /** Scans now, then again whenever Android's music files change. */
    fun start() {
        scope.launch { scan() }
        if (watching) return
        watching = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Copying an album fires a burst of changes: wait for it to settle, then scan once.
                pending?.cancel()
                pending = scope.launch { delay(5_000); scan() }
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        if (!hasPermission()) { _scanned.value = false; return@withContext }
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
        )
        // Music only (not ringtones, recordings or podcasts' downloads), and long enough to be a song.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        // Whether each album has artwork, looked at once per album (asking for art that isn't there fails every time the
        // notification or a list asks, so songs without any get none).
        val art = HashMap<Long, String?>()
        fun artFor(albumId: Long): String? = if (albumId <= 0) null else art.getOrPut(albumId) {
            val uri = "content://media/external/audio/albumart/$albumId"
            uri.takeIf { runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close() != null }.getOrDefault(false) }
        }
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(MIN_DURATION_MS.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val albumId = c.getLong(4)
                    val song = Song(
                        id = Song.LOCAL_PREFIX + id,
                        title = title,
                        artists = artistsOf(c.getString(2)),
                        album = c.getString(3)?.takeIf { it.isNotBlank() && it != UNKNOWN }?.let { AlbumRef(it) },
                        durationSec = (c.getLong(5) / 1000).toInt(),
                    )
                    out += Track(
                        song, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), albumId,
                        // MediaStore's TRACK is disc × 1000 + track.
                        c.getInt(6) % 1000, c.getInt(7), c.getLong(8) * 1000, c.getString(9) ?: "", c.getLong(10),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "scan failed", it) }
        // The list shows as soon as the files are known; covers follow (opening every album's artwork took long
        // enough on a big library that the page sat on its spinner).
        publish(out)
        _scanned.value = true
        Log.i(TAG, "${out.size} music files")
        // The album's artwork as Android serves it (from the files' embedded covers): it opens as an image, so Coil,
        // the media notification and the car can all show it. Android files untagged songs from one folder (and other
        // mixes) under one album, whose artwork is then one file's cover for all of them (#129): those songs read their
        // own covers instead.
        val mixed = mixedAlbums(out)
        val withArt = out.map { t ->
            if (t.albumId in mixed) t else artFor(t.albumId)?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t
        }
        if (art.values.any { it != null }) publish(withArt)
        val filled = fillEmbeddedArt(withArt, mixed)
        // A song in a real album (a compilation) with no cover of its own still shows the album's.
        val rest = filled.map { t ->
            if (t.song.thumbnail != null || t.albumId !in mixed || t.song.album == null) t
            else artFor(t.albumId)?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t
        }
        if (rest != filled) publish(rest)
    }

    private fun publish(tracks: List<Track>) {
        byId = tracks.associateBy { it.song.id }
        _tracks.value = tracks
    }

    private val artDir by lazy { java.io.File(context.filesDir, "artwork/local").apply { mkdirs() } }
    private val noArt by lazy { context.getSharedPreferences("local_no_art", Context.MODE_PRIVATE) }

    /**
     * Songs whose album Android has no artwork for: the cover embedded in the file itself, read once and kept (#54), so
     * they show their own cover (offline, of course) instead of none. Files with no cover in them are remembered.
     */
    private fun fillEmbeddedArt(tracks: List<Track>, mixed: Set<Long>): List<Track> {
        val missing = tracks.filter { it.song.thumbnail == null }
        if (missing.isEmpty()) return tracks
        val found = HashMap<String, String>()
        val none = ArrayList<String>()
        var reads = 0
        for (t in missing) {
            // One cover per album, except in mixed albums (see [mixedAlbums]), where each song keeps its own.
            val key = if (t.albumId > 0 && t.albumId !in mixed) "a${t.albumId}" else "s${t.song.id.removePrefix(Song.LOCAL_PREFIX)}"
            val file = java.io.File(artDir, "$key.jpg")
            if (!file.exists()) {
                // Files are opened a few hundred a scan at most; the rest follow on the next scans.
                if (key in none || noArt.contains(key) || reads >= MAX_COVER_READS) continue
                reads++
                val bytes = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(context, t.uri); r.embeddedPicture } finally { r.release() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) { none += key; continue }
                runCatching { file.writeBytes(bytes) }.onFailure { continue }
            }
            found[t.song.id] = Uri.fromFile(file).toString()
        }
        if (none.isNotEmpty()) noArt.edit().apply { none.forEach { putBoolean(it, true) } }.apply()
        if (found.isEmpty()) return tracks
        return tracks.map { t -> found[t.song.id]?.let { t.copy(song = t.song.copy(thumbnail = it)) } ?: t }.also(::publish)
    }

    companion object {
        private const val TAG = "PrismLocal"
        private const val MIN_DURATION_MS = 20_000L
        private const val MAX_COVER_READS = 400
        private const val UNKNOWN = "<unknown>"

        /**
         * Album ids whose one artwork may be wrong for some of its songs (#129): a song with no album tag (Android groups
         * those by folder), songs naming different albums, or different artists under one album name (a downloader's
         * catch-all "album"). Their songs read their own embedded covers; a real compilation's files all carry its cover,
         * so it looks the same, read once per song instead of once per album.
         */
        internal fun mixedAlbums(tracks: List<Track>): Set<Long> =
            tracks.filter { it.albumId > 0 }.groupBy { it.albumId }.filterValues { songs ->
                songs.size > 1 && (songs.any { it.song.album == null } ||
                    songs.mapTo(HashSet()) { it.song.album?.title?.trim()?.lowercase() }.size > 1 ||
                    songs.mapTo(HashSet()) { it.song.artists.firstOrNull()?.name?.trim()?.lowercase() }.size > 1)
            }.keys

        /** "A; B" or "A / B" in a tag are several artists; "<unknown>" is none. */
        fun artistsOf(tag: String?): List<ArtistRef> {
            if (tag.isNullOrBlank() || tag == UNKNOWN) return emptyList()
            return tag.split(";", " / ", "\u0000").map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { ArtistRef(it) }
        }
    }
}
