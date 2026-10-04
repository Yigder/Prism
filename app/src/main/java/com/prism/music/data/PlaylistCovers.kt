package com.prism.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures the listener picked for their playlists (and for Liked songs and Downloads),
 * kept on this phone by playlist id: either a photo copied into the app or a song's cover.
 */
class PlaylistCovers(private val context: Context) {
    private val prefs = context.getSharedPreferences("playlist_covers", Context.MODE_PRIVATE)
    /** Compose state, so every cover on screen updates the moment one is changed. */
    private val covers = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) -> (v as? String)?.let { put(k, it) } }
    }
    val dir: File get() = File(context.filesDir, "covers")

    fun custom(id: String): String? = covers[id]

    /** The picture to show for playlist [id]: the listener's own if they picked one, else [fallback]. */
    fun art(id: String, fallback: String?): String? = covers[id] ?: fallback

    /** Any item's picture, with a playlist's custom cover in place of YouTube's. */
    fun art(item: BrowseItem): String? = if (item is PlaylistItem) art(item.id, item.thumbnail) else item.thumbnail

    private fun put(id: String, value: String) {
        val old = covers[id]
        covers[id] = value
        prefs.edit().putString(id, value).apply()
        old?.let(::deleteIfOurs)
    }

    /** One of the playlist's own songs' covers (a web address). */
    fun useImage(id: String, url: String) = put(id, url)

    /** Copies a picked photo into the app as a square, so it survives the original being moved or deleted. */
    suspend fun usePhoto(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bmp = runCatching { decode(uri) }.getOrNull() ?: return@withContext false
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = if (side > 1200) Bitmap.createScaledBitmap(square, 1200, 1200, true) else square
        dir.mkdirs()
        // A fresh name each time, so the new picture isn't served from the image cache.
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe-${System.currentTimeMillis()}.jpg")
        runCatching { file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrElse { return@withContext false }
        withContext(Dispatchers.Main) { put(id, Uri.fromFile(file).toString()) }
        true
    }

    fun reset(id: String) {
        val old = covers.remove(id) ?: return
        prefs.edit().remove(id).apply()
        deleteIfOurs(old)
    }

    private fun deleteIfOurs(value: String) {
        if (!value.startsWith("file:")) return
        val f = File(Uri.parse(value).path ?: return)
        if (f.canonicalPath.startsWith(dir.canonicalPath)) f.delete()
    }

    /** Decoded at a sensible size, the right way up (phone photos often carry a rotation). */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2400) d.setTargetSampleSize(longest / 2400 + 1)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
