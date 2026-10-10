package com.prism.music.data.innertube

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import java.text.Normalizer
import kotlin.math.max

/** "Top results": the single best match, then each kind of result, best-matching kind first. */
data class TopSearch(val top: BrowseItem?, val sections: List<Pair<SearchFilter, List<BrowseItem>>>) {
    val isEmpty: Boolean get() = top == null && sections.isEmpty()
}

/**
 * Ranks results from YouTube Music's per-kind searches. Its own mixed search leans on
 * whatever is trending ("AM" turns up podcasts), so Prism asks for songs, albums, artists,
 * playlists and videos separately and judges how well each matches what was typed, and
 * how well it fits what the listener plays ([Taste]).
 */
object SearchRank {
    /** An artist the listener knows, with how much (0..1). */
    data class KnownArtist(val id: String?, val name: String, val thumbnail: String?, val familiarity: Double)

    /**
     * What the listener plays, for leaning results their way: "A Day" should find A Day to
     * Remember for someone who plays them, before an EP of that name. Built on the phone from
     * play history, likes and the library ([com.prism.music.data.TasteRepository.searchTaste]).
     */
    class Taste(artists: List<KnownArtist>, val songs: Map<String, Double>) {
        val artists: Map<String, KnownArtist> = artists.groupBy { norm(it.name) }.mapValues { (_, l) -> l.maxBy { it.familiarity } }
        fun artist(name: String): Double = artists[norm(name)]?.familiarity ?: 0.0
        companion object { val NONE = Taste(emptyList(), emptyMap()) }
    }

    /** How far a result the listener knows well can climb past a closer match they don't. */
    private const val TASTE_BOOST = 0.45

    /** 0..1: how much the listener plays [item] (or its artist). */
    fun familiarity(item: BrowseItem, taste: Taste): Double = when (item) {
        is ArtistItem -> taste.artist(item.title)
        is SongItem -> maxOf(taste.songs[item.id] ?: 0.0, 0.6 * (item.song.artists.maxOfOrNull { taste.artist(it.name) } ?: 0.0))
        // "Album • A Day to Remember • 2009"
        is AlbumItem -> 0.7 * (item.subtitle.split(" • ").maxOfOrNull { taste.artist(it) } ?: 0.0)
        else -> 0.0
    }

    /** Every typed word is a whole word of the title ("a day" in "A Day to Remember", not "halo" in "Halocene"). */
    private fun wholeWords(query: String, title: String): Boolean {
        val t = norm(title).split(" ").toSet()
        return norm(query).split(" ").all { it in t }
    }

    private val limits = mapOf(
        SearchFilter.SONGS to 6, SearchFilter.ALBUMS to 8, SearchFilter.ARTISTS to 6,
        SearchFilter.PLAYLISTS to 8, SearchFilter.VIDEOS to 6,
    )

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** "1989 (Taylor's Version)" and "Song - Remastered 2011" match "1989" and "Song". */
    private fun core(title: String) = norm(title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), ""))

    /** 0..1: how well [title] (with [extra], e.g. the artist) matches the query. */
    fun match(query: String, title: String, extra: String = ""): Double {
        val q = norm(query)
        if (q.isEmpty()) return 0.0
        val t = norm(title)
        if (t == q) return 1.0
        if (core(title) == q) return 0.97
        val qWords = q.split(" ")
        val all = norm("$title $extra").split(" ").toSet()
        // Title and artist typed together ("do i wanna know arctic monkeys").
        if (qWords.all { it in all } && core(title).split(" ").all { it in qWords }) return 0.95
        if (t.startsWith(q)) return 0.75
        if (qWords.all { w -> all.any { it.startsWith(w) } }) return 0.6
        if (t.contains(q)) return 0.45
        return 0.2
    }

    /** "444M monthly audience", "3.55K subscribers" -> a number. */
    private fun audience(subtitle: String): Long {
        val m = Regex("([\\d.,]+)\\s*([KMB])?\\s*(monthly|subscriber)", RegexOption.IGNORE_CASE).find(subtitle) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (n * when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
    }

    /** How strongly each kind of result answers an exact match. */
    private fun weight(item: BrowseItem): Double = when (item) {
        is ArtistItem -> if (audience(item.subtitle) >= 1_000_000) 1.0 else 0.8
        is AlbumItem -> if (item.subtitle.startsWith("Single")) 0.8 else 0.93
        is SongItem -> if (item.song.isVideo) 0.72 else 0.86
        is PlaylistItem -> 0.62
        else -> 0.5
    }

    fun score(query: String, item: BrowseItem, position: Int, taste: Taste = Taste.NONE): Double {
        val extra = when (item) {
            is SongItem -> item.song.artistText
            else -> item.subtitle
        }
        val m = match(query, item.title, extra)
        val fam = if (m >= 0.6) familiarity(item, taste) else 0.0
        // An artist the listener plays counts as a big one, whatever YouTube says of their audience.
        val w = if (item is ArtistItem && fam > 0.0) 1.0 else weight(item)
        // YouTube's own order within each kind stands in for popularity (a cover album ranks below the original).
        val base = m * w - position * 0.08
        // What the listener plays climbs, but only when it really answers what was typed;
        // half a word ("halo" of "Halocene") only nudges it.
        if (fam <= 0.0) return base
        val fit = if (m >= 0.95 || wholeWords(query, item.title)) 1.0 else 0.2
        return base + TASTE_BOOST * fam * fit
    }

    /**
     * Artists the listener plays whose name starts with every word typed ("a day" → A Day to
     * Remember), for when YouTube's artist search leaves them out.
     */
    fun knownArtists(query: String, taste: Taste): List<ArtistItem> {
        val q = norm(query)
        // Too short to mean one artist ("the" starts half the library).
        if (q.length < 4) return emptyList()
        return taste.artists.values
            .filter { a -> a.id != null && a.familiarity >= 0.5 && norm(a.name).let { n -> n == q || n.startsWith("$q ") } }
            .sortedByDescending { it.familiarity }
            .take(2)
            .map { ArtistItem(it.id!!, it.name, "Artist", it.thumbnail) }
    }

    fun rank(query: String, results: List<Pair<SearchFilter, List<BrowseItem>>>, taste: Taste = Taste.NONE): TopSearch {
        // The listener's own artists lead YouTube's artist results when they fit and are missing from them.
        val known = knownArtists(query, taste)
        val withKnown = results.map { (f, items) ->
            if (f != SearchFilter.ARTISTS) f to items
            else f to (known.filter { k -> items.none { it.id == k.id || norm(it.title) == norm(k.title) } } + items)
        }.let { list ->
            if (list.any { it.first == SearchFilter.ARTISTS } || known.isEmpty()) list
            else list + (SearchFilter.ARTISTS to known)
        }
        val trimmed = withKnown.mapNotNull { (f, items) ->
            items.distinctBy { it.id }.take(limits[f] ?: 6).takeIf { it.isNotEmpty() }?.let { f to it }
        }
        val scored = trimmed.map { (f, items) -> Triple(f, items, items.mapIndexed { i, it -> score(query, it, i, taste) }) }
        var top = scored.flatMap { (_, items, scores) -> items.zip(scores).take(3) }.maxByOrNull { it.second }?.first
        // A title track ("After Hours" by The Weeknd) gives way to the album it names.
        (top as? SongItem)?.song?.let { song ->
            val albums = trimmed.firstOrNull { it.first == SearchFilter.ALBUMS }?.second.orEmpty()
            albums.filterIsInstance<AlbumItem>().firstOrNull { a ->
                !a.subtitle.startsWith("Single") && core(a.title) == core(song.title) && core(a.title) == norm(query) &&
                    song.primaryArtist.isNotBlank() && a.subtitle.contains(song.primaryArtist, ignoreCase = true)
            }?.let { top = it }
        }
        val sections = scored
            .sortedByDescending { (f, _, scores) ->
                // Songs stay near the top: they're what most searches are for.
                max(scores.maxOrNull() ?: 0.0, if (f == SearchFilter.SONGS) 0.5 else 0.0)
            }
            .map { (f, items, _) -> f to items.filter { it != top } }
            .filter { it.second.isNotEmpty() }
        return TopSearch(top, sections)
    }
}
