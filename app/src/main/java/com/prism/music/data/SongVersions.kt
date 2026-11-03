package com.prism.music.data

import android.content.SharedPreferences
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The version of a song the listener picked to hear in its place (1.7.9, Reddit: an album's song played as someone's
 * guitar cover, and nothing in Prism could change it). Song id → the picked recording's id, kept on the phone.
 * Everything that fetches the song's sound (playing, caching, downloading, scans) asks for the pick instead
 * ([com.prism.music.data.stream.StreamResolver.versionOf]); the song keeps its own id, title, lyrics, likes and history.
 */
class SongVersions(private val prefs: SharedPreferences) {
    /** A picked version: its id and what the menu shows for it ("Artist · Album · 3:41"). */
    data class Pick(val id: String, val label: String)

    private val _picks = MutableStateFlow(read())
    val picks: StateFlow<Map<String, Pick>> = _picks

    /** Reads the picks again (after "Bring your Prism" merged a phone's in, 1.8.0). */
    fun reload() { _picks.value = read() }

    /** The recording that plays for [songId], or null for its own. */
    fun of(songId: String): String? = _picks.value[songId]?.id

    /** Plays [pick] for [songId] from now on; null goes back to the song's own. */
    fun set(songId: String, pick: Pick?) {
        val ed = prefs.edit()
        if (pick == null || pick.id == songId) ed.remove(songId) else ed.putString(songId, "${pick.id}\t${pick.label}")
        ed.apply()
        _picks.value = read(if (pick == null || pick.id == songId) _picks.value - songId else _picks.value + (songId to pick))
    }

    private fun read(now: Map<String, Pick>? = null): Map<String, Pick> = now ?: prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split('\t', limit = 2) ?: return@mapNotNull null
        parts[0].takeIf { it.isNotBlank() }?.let { k to Pick(it, parts.getOrElse(1) { "" }) }
    }.toMap()

    companion object {
        private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
        private val DASH = Regex("\\s+[-–—]\\s+.*$")

        /** A title without its brackets and " - Remastered"-style tails, letters and digits only. */
        fun bare(title: String): String =
            title.replace(BRACKETS, " ").replace(DASH, "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

        /**
         * The other recordings of [song] among search [results]: the same title (brackets and tails aside), not the song
         * itself, one of each; the song's own artist first, slowed / sped-up / karaoke edits last, songs before videos.
         * At most [max].
         */
        fun candidates(song: Song, results: List<Song>, max: Int = 12): List<Song> {
            val want = bare(song.title)
            if (want.isEmpty()) return emptyList()
            val mine = song.artists.map { it.name.lowercase() }.filter { it.isNotBlank() }.toSet()
            fun byArtist(s: Song) = s.artists.any { a -> a.name.lowercase().let { n -> mine.any { it in n || n in it } } }
            val asked = com.prism.music.data.innertube.SearchRank.norm(song.title)
            // "sped up//nightcore": slashes would join the words, so they part them first.
            fun knockoff(s: Song) = com.prism.music.data.innertube.SearchRank.knockoffMarks(s.title.replace(Regex("[/|]+"), " "), asked).isNotEmpty()
            return results.asSequence()
                .filter { it.id != song.id && !it.isLive && !it.isEpisode && bare(it.title) == want }
                .distinctBy { it.id }
                .sortedWith(compareBy({ knockoff(it) }, { !byArtist(it) }, { it.isVideo }))
                .take(max)
                .toList()
        }

        /** [v] is [song]'s own recording put up again (same album and length): "Another upload" in the list. */
        fun sameRecording(song: Song, v: Song): Boolean =
            !v.isVideo && song.album?.title != null && v.album?.title.equals(song.album?.title, ignoreCase = true) &&
                (song.durationSec <= 0 || v.durationSec <= 0 || kotlin.math.abs(song.durationSec - v.durationSec) <= 2)
    }
}
