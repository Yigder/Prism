package com.prism.music.data

import android.content.Context
import com.prism.music.data.innertube.InnerTube
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** An artist the listener starred. */
@Serializable
data class FavoriteArtist(val id: String, val name: String, val thumbnail: String? = null, val at: Long = 0)

/**
 * What the listener chose about artists, kept on this phone: the ones they starred, and the
 * signature style picked for an artist's name (instead of the one Prism would choose).
 */
class ArtistPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("artist_prefs", Context.MODE_PRIVATE)
    private val listSerializer = ListSerializer(FavoriteArtist.serializer())

    val favorites = MutableStateFlow(
        prefs.getString("favorites", null)?.let { runCatching { InnerTube.json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
    )

    /** Artist id -> signature style key. */
    val signatures = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(SIG) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(SIG) to it } }.toMap()
    )

    fun isFavorite(id: String) = favorites.value.any { it.id == id }

    fun setFavorite(artist: FavoriteArtist, on: Boolean) {
        val rest = favorites.value.filterNot { it.id == artist.id }
        val next = if (on) listOf(artist.copy(at = System.currentTimeMillis())) + rest else rest
        favorites.value = next
        prefs.edit().putString("favorites", InnerTube.json.encodeToString(listSerializer, next)).apply()
    }

    /** Null means Prism picks (by genre and audience). */
    fun signature(id: String): String? = signatures.value[id]

    fun setSignature(id: String, key: String?) {
        signatures.value = if (key == null) signatures.value - id else signatures.value + (id to key)
        prefs.edit().apply { if (key == null) remove(SIG + id) else putString(SIG + id, key) }.apply()
    }

    private companion object {
        const val SIG = "sig:"
    }
}
