package com.prism.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.lyrics.LyricsRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibMatchTest {
    private val http = OkHttpClient()
    private var settings = AppSettings()
    private val ytm = YouTubeMusic(InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) }))
    private val repo = LyricsRepository(http, object : LyricsDao {
        override suspend fun get(songId: String, source: String): LyricsEntity? = null
        override suspend fun put(e: LyricsEntity) = Unit
        override suspend fun clearMisses() = Unit
        override suspend fun allFor(songId: String): List<LyricsEntity> = emptyList()
    }, ytm) { settings }

    private fun hit(track: String, artist: String, duration: Double) = buildJsonObject {
        put("trackName", track)
        put("artistName", artist)
        put("duration", duration)
        put("syncedLyrics", "[00:01.00]lyrics")
    }

    @Test
    fun sameTitleByAnotherArtistIsRejected() {
        val song = Song("id", "São Paulo", listOf(ArtistRef("The Weeknd")), durationSec = 239)
        val wrong = hit("São Paulo", "Some Other Artist", 239.0)
        val right = hit("São Paulo", "The Weeknd", 239.0)

        assertNull(repo.pickLrcLib(song, wrong, null))
        assertEquals(right, repo.pickLrcLib(song, null, buildJsonArray { add(wrong); add(right) }))
    }

    @Test
    fun wrongArtistAndEditionAreRejected() {
        val song = Song("id", "This Is What Winter Feels Like", listOf(ArtistRef("Requested Artist")), durationSec = 180)
        val wrongArtist = hit("This Is What Winter Feels Like", "Another Artist", 180.0)
        val wrongEdit = hit("This Is What Winter Feels Like", "Requested Artist", 240.0)

        assertNull(repo.pickLrcLib(song, wrongArtist, null))
        assertNull(repo.pickLrcLib(song, null, buildJsonArray { add(wrongArtist); add(wrongEdit) }))
    }
}
