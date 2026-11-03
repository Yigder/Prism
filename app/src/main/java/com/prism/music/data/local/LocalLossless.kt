package com.prism.music.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The lossless add-on: lossless files on the phone (FLAC, WAV, AIFF, ALAC) that match
 * songs in Prism are played instead of YouTube's compressed stream.
 * YouTube Music itself has no lossless audio, so this is where real lossless comes from.
 */
class LocalLossless(private val context: Context) {
    data class Track(
        val uri: Uri,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val mime: String,
        val size: Long,
    ) {
        val format: String get() = when {
            mime.contains("flac") -> "FLAC"
            mime.contains("wav") -> "WAV"
            mime.contains("aif") -> "AIFF"
            mime.contains("alac") -> "ALAC"
            else -> "Lossless"
        }
        val kbps: Int get() = if (durationMs > 0) (size * 8 / durationMs).toInt() else 0
    }

    private val mimes = listOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/aiff", "audio/x-aiff")
    /** MP4 audio: ALAC or AAC inside, which only the file itself says ([isAlac]). */
    private val mp4Mimes = listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm")
    private val alacChecks by lazy { context.getSharedPreferences("lossless_alac", Context.MODE_PRIVATE) }

    /**
     * Whether an .m4a holds ALAC (Apple Lossless, #135) rather than AAC: read once per file (by id and size) with
     * Android's own MediaExtractor, and remembered. Unreadable counts as no.
     */
    private fun isAlac(uri: Uri, key: String): Boolean {
        if (alacChecks.contains(key)) return alacChecks.getBoolean(key, false)
        val alac = runCatching {
            val x = android.media.MediaExtractor()
            try {
                x.setDataSource(context, uri, null)
                (0 until x.trackCount).any { i -> x.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.contains("alac", true) == true }
            } finally { x.release() }
        }.getOrDefault(false)
        alacChecks.edit().putBoolean(key, alac).apply()
        return alac
    }

    @Volatile private var byTitle: Map<String, List<Track>> = emptyMap()
    /** Every lossless file found in the last scan. */
    @Volatile var tracks: List<Track> = emptyList()
        private set
    /** Files lossless sync has tied to a song, by video id: these always match, whatever their tags say. */
    @Volatile var linked: Map<String, Uri> = emptyMap()
    /** Songs Prism has matched to a file, by video id (for stats). */
    val matched = ConcurrentHashMap<String, Track>()
    /** Files this phone couldn't decode (ALAC without a decoder, #135): the song's stream plays instead, this run. */
    val unplayable: MutableSet<Uri> = ConcurrentHashMap.newKeySet()
    /** How many lossless files were found in the last scan (-1 = not scanned). */
    val found = MutableStateFlow(-1)

    fun hasPermission(): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    suspend fun scan() = withContext(Dispatchers.IO) {
        if (!hasPermission()) { found.value = -1; return@withContext }
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
        )
        val selection = "${MediaStore.Audio.Media.MIME_TYPE} IN (${mimes.joinToString { "?" }})"
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, mimes.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    tracks += Track(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: continue, c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "", c.getLong(5),
                    )
                }
            }
        }
        // ALAC (#135): .m4a files whose track is Apple Lossless, looked at once each (a few hundred a scan at most; the
        // rest on the next scans).
        runCatching {
            var reads = 0
            val mp4 = "${MediaStore.Audio.Media.MIME_TYPE} IN (${mp4Mimes.joinToString { "?" }})"
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, mp4, mp4Mimes.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val key = "$id:${c.getLong(5)}"
                    if (!alacChecks.contains(key) && reads++ >= MAX_ALAC_READS) continue
                    if (!isAlac(uri, key)) continue
                    tracks += Track(uri, c.getString(1) ?: continue, c.getString(2) ?: "", c.getLong(3), "audio/alac", c.getLong(5))
                }
            }
        }
        byTitle = tracks.groupBy { norm(it.title) }
        this@LocalLossless.tracks = tracks
        matched.clear()
        found.value = tracks.size
    }

    /** The lossless file for a song, if there is one: same title, same artist, about the same length. */
    fun match(song: Song): Track? {
        if (byTitle.isEmpty() || song.isVideo) return null
        linked[song.id]?.let { uri -> tracks.firstOrNull { it.uri == uri && it.uri !in unplayable } }?.let { matched[song.id] = it; return it }
        val candidates = byTitle[norm(song.title)]?.filter { it.uri !in unplayable } ?: return null
        val artist = norm(song.primaryArtist)
        val hit = candidates.firstOrNull { t ->
            val a = norm(t.artist)
            val artistOk = artist.isBlank() || a.isBlank() || a.contains(artist) || artist.contains(a)
            val lengthOk = song.durationSec <= 0 || t.durationMs <= 0 || kotlin.math.abs(t.durationMs / 1000 - song.durationSec) <= 4
            artistOk && lengthOk
        } ?: return null
        matched[song.id] = hit
        return hit
    }

    companion object {
        /** .m4a files opened per scan to see whether they're ALAC. */
        private const val MAX_ALAC_READS = 400
        val permission: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        /** "Mr. Brightside (Remastered 2020) [feat. X]" -> "mr brightside" */
        fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("\\s-\\s.*(remaster|version|edit|mix|live).*"), "")
            .replace(Regex("\\b(feat|ft|featuring)\\b.*"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
