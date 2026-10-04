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
 * The lossless add-on: lossless files on the phone (FLAC, WAV, AIFF) that match
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
            else -> "Lossless"
        }
        val kbps: Int get() = if (durationMs > 0) (size * 8 / durationMs).toInt() else 0
    }

    private val mimes = listOf("audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/aiff", "audio/x-aiff")

    @Volatile private var byTitle: Map<String, List<Track>> = emptyMap()
    /** Songs Prism has matched to a file, by video id (for stats). */
    val matched = ConcurrentHashMap<String, Track>()
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
        byTitle = tracks.groupBy { norm(it.title) }
        matched.clear()
        found.value = tracks.size
    }

    /** The lossless file for a song, if there is one: same title, same artist, about the same length. */
    fun match(song: Song): Track? {
        if (byTitle.isEmpty() || song.isVideo) return null
        val candidates = byTitle[norm(song.title)] ?: return null
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
