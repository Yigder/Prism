package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.meta.Genres
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Shelf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** Moods & genres tiles ordered by how well they fit what you listen to. */
data class RankedCategories(
    /** The best fits across every section, mixing moods and genres. */
    val picks: List<MoodItem>,
    /** YouTube's own sections (For you, Moods & moments, Genres…), each re-ordered best first. */
    val sections: List<Pair<String, List<MoodItem>>>,
) {
    companion object { val EMPTY = RankedCategories(emptyList(), emptyList()) }
}

/**
 * Builds a listening profile (genre weights from what you've played recently,
 * plus liked songs) and scores YouTube Music's mood and genre tiles against it.
 */
class TasteRepository(private val c: AppContainer) {
    @Volatile private var cached: Pair<Long, Map<String, Double>>? = null

    /** Genre bucket (see [com.prism.music.data.meta.Genres]) -> share of listening, summing to 1. */
    suspend fun profile(): Map<String, Double> {
        cached?.let { (at, p) -> if (System.currentTimeMillis() - at < 10 * 60_000) return p }
        val now = System.currentTimeMillis()
        val weights = LinkedHashMap<String, Double>()
        c.db.plays().topSongs(now - 90L * 86_400_000, now, 120).forEach { weights.merge(it.songId, it.plays.toDouble(), Double::plus) }
        c.db.songs().liked().take(200).forEach { weights.merge(it.id, 0.6, Double::plus) }
        c.library.recent.value.take(30).forEach { weights.merge(it.id, 0.8, Double::plus) }
        if (weights.isEmpty()) return emptyMap()

        val songs = weights.keys.toList().chunked(500).flatMap { c.db.songs().getAll(it) }.map { it.toSong() }
        val genres: MutableMap<String, String?> = c.meta.cachedGenres(songs).toMutableMap()
        // Look up genres for the heaviest songs we haven't classified yet (cached after the first time).
        val byId = songs.associateBy { it.id }
        val missing = weights.entries.sortedByDescending { it.value }.map { it.key }.filter { it !in genres }.take(30).mapNotNull { byId[it] }
        if (missing.isNotEmpty()) withTimeoutOrNull(8_000) {
            coroutineScope { missing.map { s -> async { s.id to runCatching { c.meta.genreFor(s) }.getOrNull() } }.awaitAll() }
                .forEach { (id, g) -> genres[id] = g }
        }
        val byGenre = HashMap<String, Double>()
        weights.forEach { (id, w) -> genres[id]?.let { g -> byGenre.merge(g, w, Double::plus) } }
        val total = byGenre.values.sum().takeIf { it > 0 } ?: return emptyMap()
        return byGenre.mapValues { it.value / total }.also { cached = System.currentTimeMillis() to it }
    }

    suspend fun rank(shelves: List<Shelf>): RankedCategories {
        val profile = runCatching { profile() }.getOrDefault(emptyMap())
        return CategoryRanker.rank(shelves, profile, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }
}

object CategoryRanker {
    /** How naturally each genre fits each mood tile. */
    private val moodAffinity: Map<String, Map<String, Double>> = mapOf(
        "Hip-hop" to mapOf("workout" to 1.0, "party" to 0.9, "energ" to 0.8, "gaming" to 0.6, "commute" to 0.5),
        "R&B & soul" to mapOf("romance" to 1.0, "chill" to 0.8, "feel good" to 0.5, "sleep" to 0.3, "sad" to 0.3),
        "Dance & electronic" to mapOf("party" to 1.0, "workout" to 0.8, "energ" to 0.8, "gaming" to 0.7, "focus" to 0.5),
        "Pop" to mapOf("feel good" to 1.0, "party" to 0.7, "commute" to 0.7, "romance" to 0.5, "sad" to 0.4),
        "Rock" to mapOf("energ" to 0.8, "workout" to 0.7, "commute" to 0.6, "gaming" to 0.5),
        "Metal" to mapOf("workout" to 1.0, "gaming" to 0.8, "energ" to 0.8),
        "Indie & alternative" to mapOf("sad" to 0.8, "chill" to 0.7, "commute" to 0.6, "focus" to 0.5),
        "Country & Americana" to mapOf("feel good" to 0.8, "commute" to 0.7, "romance" to 0.5, "sad" to 0.4),
        "Jazz" to mapOf("focus" to 0.8, "chill" to 0.8, "romance" to 0.5, "sleep" to 0.5),
        "Classical" to mapOf("focus" to 1.0, "sleep" to 0.8, "chill" to 0.5),
        "Folk & acoustic" to mapOf("chill" to 0.8, "sad" to 0.6, "focus" to 0.4, "sleep" to 0.3),
        "Soundtracks & musicals" to mapOf("focus" to 0.8, "gaming" to 0.8, "sleep" to 0.3),
        "Reggae & caribbean" to mapOf("chill" to 0.8, "feel good" to 0.8, "party" to 0.5),
        "Christian & gospel" to mapOf("feel good" to 0.6, "chill" to 0.5),
        "Blues" to mapOf("chill" to 0.7, "sad" to 0.6, "romance" to 0.4),
    )


    private val moodWords =listOf("chill", "commute", "energ", "feel good", "focus", "gaming", "party", "romance", "sad", "sleep", "workout")

    private fun timeOfDay(hour: Int): Map<String, Double> = when (hour) {
        in 5..9 -> mapOf("commute" to 1.0, "energ" to 0.8, "feel good" to 0.6, "workout" to 0.5)
        in 10..15 -> mapOf("focus" to 1.0, "feel good" to 0.6, "workout" to 0.5, "energ" to 0.4)
        in 16..18 -> mapOf("commute" to 0.9, "workout" to 0.7, "feel good" to 0.6, "chill" to 0.5)
        in 19..21 -> mapOf("chill" to 0.9, "party" to 0.7, "gaming" to 0.7, "romance" to 0.6)
        else -> mapOf("sleep" to 1.0, "chill" to 0.8, "romance" to 0.5, "sad" to 0.4)
    }

    private fun isMood(title: String) = moodWords.any { title.lowercase().contains(it) }

    fun score(tile: MoodItem, profile: Map<String, Double>, hour: Int): Double {
        val t = tile.title.lowercase()
        var s = 0.0
        if (isMood(t)) {
            profile.forEach { (g, w) -> moodAffinity[g]?.forEach { (k, a) -> if (t.contains(k)) s += w * a } }
            s += 0.35 * (timeOfDay(hour).entries.firstOrNull { t.contains(it.key) }?.value ?: 0.0)
        } else {
            // Our categories are YouTube Music's own genre names, so a tile matches by name
            // (and its regional spins, e.g. "Hip-hop en Español", start with it).
            profile.forEach { (g, w) ->
                val k = g.lowercase()
                if (t == k || t.startsWith("$k ")) s += w * 1.6
            }

        }
        return s
    }

    fun rank(shelves: List<Shelf>, profile: Map<String, Double>, hour: Int): RankedCategories {
        // Prism sticks to mostly English-language music: regional tiles (K-Pop, Bollywood, "… en Español") are dropped.
        val sections = shelves.mapNotNull { sh ->
            val tiles = sh.items.filterIsInstance<MoodItem>().filter { !Genres.isRegional(it.title) }
            if (tiles.isEmpty()) null else sh.title to tiles
        }
        val personal = sections.firstOrNull { it.first.contains("for you", true) }?.second?.map { it.id + it.params }?.toSet() ?: emptySet()
        // YouTube's own "For you" picks get a small nudge; original order breaks ties.
        fun scored(list: List<MoodItem>) = list.mapIndexed { i, m ->
            m to score(m, profile, hour) + (if (m.id + m.params in personal) 0.06 else 0.0) - i * 0.0005
        }
        val ranked = sections.map { (title, list) -> title to scored(list).sortedByDescending { it.second }.map { it.first } }
        val all = scored(sections.flatMap { it.second }).distinctBy { it.first.title.lowercase() }.sortedByDescending { it.second }
        val moods = all.filter { isMood(it.first.title) }
        val genres = all.filter { !isMood(it.first.title) }
        // Alternate the best moods and genres so both kinds show up on Home.
        val picks = buildList {
            val n = maxOf(moods.size, genres.size)
            for (i in 0 until n) {
                genres.getOrNull(i)?.let { add(it) }
                moods.getOrNull(i)?.let { add(it) }
            }
        }.sortedByDescending { it.second }.let { list ->
            // Keep strong matches first, but make sure the first dozen isn't all one kind.
            val top = list.take(12).toMutableList()
            if (top.none { isMood(it.first.title) }) moods.firstOrNull()?.let { top[top.lastIndex] = it }
            if (top.none { !isMood(it.first.title) }) genres.firstOrNull()?.let { top[top.lastIndex] = it }
            top + list.drop(12).filter { it !in top }
        }.map { it.first }
        return RankedCategories(picks, ranked)
    }
}
