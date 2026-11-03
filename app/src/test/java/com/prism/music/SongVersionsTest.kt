package com.prism.music

import com.prism.music.data.SongVersions
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** "Play a different version" (Reddit 1.7.9): which recordings are offered, and that a pick is kept and taken back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SongVersionsTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false, album: String? = null) =
        Song(id, title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) }, 200, isVideo = video)

    @Test
    fun otherRecordingsOfTheSameTitleAreOffered() {
        val mine = song("a", "Night Drive", "The Band", album = "Roads")
        val results = listOf(
            mine,
            song("b", "Night Drive (Acoustic Guitar Cover)", "Some Guy"),
            song("c", "Night Drive - Remastered 2011", "The Band", album = "Roads (Deluxe)"),
            song("d", "Night Drive (Official Video)", "The Band", video = true),
            song("e", "Night Drives Home", "The Band"),
            song("c", "Night Drive - Remastered 2011", "The Band", album = "Roads (Deluxe)"),
        )
        // Not the song itself, nor another title; one of each; its own artist first, songs before videos.
        assertEquals(listOf("c", "d", "b"), SongVersions.candidates(mine, results).map { it.id })
    }

    @Test
    fun editsComeLastAndReuploadsAreNamed() {
        val mine = song("a", "Night Drive", "The Band", album = "Roads")
        val results = listOf(
            song("s", "Night Drive (Slowed + Reverb)", "The Band", video = true),
            song("n", "night drive - sped up//nightcore", "Someone", video = true),
            song("x", "Night Drive", "Another Band"),
            song("r", "Night Drive", "The Band", album = "Roads"),
        )
        assertEquals(listOf("r", "x", "s", "n"), SongVersions.candidates(mine, results).map { it.id })
        assertTrue(SongVersions.sameRecording(mine, results[3]))
        assertFalse(SongVersions.sameRecording(mine, results[2]))
    }

    @Test
    fun aPickIsKeptAndCanBeTakenBack() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("song_versions_test", 0)
        prefs.edit().clear().commit()
        val v = SongVersions(prefs)
        v.set("a", SongVersions.Pick("c", "The Band · Roads (Deluxe) · 3:20"))
        assertEquals("c", SongVersions(prefs).of("a"))
        assertEquals("The Band · Roads (Deluxe) · 3:20", SongVersions(prefs).picks.value["a"]?.label)
        v.set("a", null)
        assertNull(SongVersions(prefs).of("a"))
        // Picking the song itself is no pick.
        v.set("a", SongVersions.Pick("a", ""))
        assertNull(v.of("a"))
    }
}
