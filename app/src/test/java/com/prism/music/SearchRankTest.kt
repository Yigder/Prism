package com.prism.music

import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {
    private val ep = AlbumItem("MPREb_ep", "A Day", "EP • Polar Colors • 2021", null)
    private val adtr = ArtistItem("UCadtr", "A Day to Remember", "Artist • 2.1M monthly audience", null)
    private val song = SongItem(Song("vid1", "A Day", listOf(ArtistRef("Someone Else"))))
    private val results = listOf(
        SearchFilter.SONGS to listOf(song),
        SearchFilter.ALBUMS to listOf(ep),
        SearchFilter.ARTISTS to listOf(adtr),
    )

    private val fan = SearchRank.Taste(listOf(SearchRank.KnownArtist("UCadtr", "A Day to Remember", null, 0.6)), emptyMap())

    @Test fun exactMatchWinsWithoutTaste() {
        assertEquals("MPREb_ep", SearchRank.rank("A Day", results).top?.id)
    }

    @Test fun anArtistTheListenerPlaysComesFirst() {
        assertEquals("UCadtr", SearchRank.rank("A Day", results, fan).top?.id)
    }

    @Test fun aKnownArtistMissingFromResultsIsAdded() {
        val r = SearchRank.rank("a day", results.filter { it.first != SearchFilter.ARTISTS }, fan)
        assertEquals("UCadtr", r.top?.id)
    }

    @Test fun partialWordsDontBorrowTaste() {
        // "halo" is a prefix of Halocene, not a word of it: the exact song still wins.
        val halo = SongItem(Song("halo", "Halo", listOf(ArtistRef("Beyoncé"))))
        val halocene = ArtistItem("UChalocene", "Halocene", "Artist • 300K monthly audience", null)
        val taste = SearchRank.Taste(listOf(SearchRank.KnownArtist("UChalocene", "Halocene", null, 0.6)), emptyMap())
        val r = SearchRank.rank("halo", listOf(SearchFilter.SONGS to listOf(halo), SearchFilter.ARTISTS to listOf(halocene)), taste)
        assertEquals("halo", r.top?.id)
        assertTrue(SearchRank.knownArtists("halo", taste).isEmpty())
    }
}
