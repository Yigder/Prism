package com.prism.music.data.canvas

import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Canvas's gate that need no network, on the by-eye round's 100 matches (.claude/canvas-study/eye/,
 * saved as canvas/eye-round.tsv: number, video id, YouTube Music's type, song title, video title).
 */
class ClipGateTest {
    private class Row(val n: Int, val videoId: String, val type: String, val songTitle: String, val videoTitle: String)

    private val rows: Map<Int, Row> = javaClass.getResourceAsStream("/canvas/eye-round.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
        .drop(1).filter { it.isNotBlank() }
        .map { it.split('\t') }.associate { f -> f[0].toInt() to Row(f[0].toInt(), f[1], f[2], f[3], f[4]) }

    /** The gate's order for what needs no network: the type first, then the title. */
    private fun gate(n: Int): NoClip? {
        val r = rows.getValue(n)
        return when {
            !ClipGate.official(r.type) -> NoClip.NOT_OFFICIAL
            ClipGate.titleSays(r.songTitle, r.videoTitle) -> NoClip.TITLE
            else -> null
        }
    }

    @Test fun theFixtureHoldsTheWholeRound() {
        assertEquals((1..100).toSet(), rows.keys)
    }

    @Test fun broadcasterStagesFanUploadsAndThirdPartiesAreNotOfficial() {
        for (n in listOf(1, 3, 5, 6, 7, 14, 30, 84, 96, 97)) assertEquals("#$n ${rows[n]?.videoTitle}", NoClip.NOT_OFFICIAL, gate(n))
    }

    @Test fun aPerformanceCutIsTurnedAwayByItsTitle() {
        assertEquals(NoClip.TITLE, gate(31))
    }

    @Test fun audioAndLyricUploadsAreTurnedAwayByTheirTitles() {
        for (n in listOf(2, 4, 15, 19, 21, 22, 23, 25, 28, 33, 34)) assertEquals("#$n ${rows[n]?.videoTitle}", NoClip.TITLE, gate(n))
    }

    @Test fun ownChannelFancamsRecordingsAndShowsAreTurnedAwayByTheirTitles() {
        // 推しカメラ (a member cam), "Solo Recording Video", "The Late Late Show Performance"; the BGM and jukebox uploads.
        for (n in listOf(9, 12, 88, 62, 75, 87, 99, 20)) assertEquals("#$n ${rows[n]?.videoTitle}", NoClip.TITLE, gate(n))
    }

    @Test fun officialVideosPass() {
        for (n in listOf(8, 35, 41, 51)) assertEquals("#$n ${rows[n]?.videoTitle}", null, gate(n))
    }

    /**
     * Every match judged right by eye passes, but for the losses the gate accepts knowingly: #16 and #65 (typed UGC: a
     * dance video, Joni Mitchell's own upload), #24 ("Full Song" without "video" in a label's title) and #48 (a label's
     * "Lyrical" video with film footage).
     */
    @Test fun theRightOnesPassButForTheKnownLosses() {
        val notRight = setOf(9, 12, 88, 1, 6, 7, 97, 31, 3, 5, 84, 90, 96, 14, 30, 2, 4, 15, 19, 21, 22, 23, 25, 28, 33, 34, 62, 75, 87, 99, 32, 20)
        val lost = (1..100).filter { it !in notRight && gate(it) != null }
        assertEquals(listOf(16, 24, 48, 65), lost)
    }

    /** #90 (a fan slideshow YouTube itself calls OMV, with an innocent title) passes: what "Not this video" is for. */
    @Test fun aKnownMissStillPasses() {
        assertEquals(null, gate(90))
    }

    @Test fun aWordInTheSongsOwnTitleDoesNotCount() {
        assertFalse(ClipGate.titleSays("Blue Amber (Live at Nissan Stadium)", "back number - Blue Amber (Live at Nissan Stadium)"))
        assertFalse(ClipGate.titleSays("Cover Me", "Cover Me (Official Video)"))
        assertTrue(ClipGate.titleSays("Blue Amber", "back number - Blue Amber (Live at Nissan Stadium)"))
    }

    @Test fun wordsOnlyCountWhereTheyStandAlone() {
        assertFalse(ClipGate.titleSays("Alive", "Artist - Alive (Official Video)"))
        assertFalse(ClipGate.titleSays("Backstage", "Artist - Backstage (Official Video)"))
        assertFalse(ClipGate.titleSays("Song", "Artist - Song (Official Visualizer)"))
        assertTrue(ClipGate.titleSays("Song", "Artist - Song (Official Audio)"))
        assertTrue(ClipGate.titleSays("Song", "Artist - Song [Official Lyric Video]"))
        assertTrue(ClipGate.titleSays("Canción", "Artista - Canción (En Vivo)"))
        assertTrue(ClipGate.titleSays("Canção", "Artista - Canção (Ao Vivo)"))
        assertTrue(ClipGate.titleSays("Canción", "Artista - Canción (Letra)"))
    }

    @Test fun fullSongCountsOnlyWithoutVideo() {
        assertTrue(ClipGate.titleSays("Darlingey", "Darlingey Full Song || Mirchi"))
        assertFalse(ClipGate.titleSays("Seeti Maar", "Seeti Maar Full Video Song | Allu Arjun"))
    }

    @Test fun wordsWithoutSpacesAreFoundInsideATitle() {
        assertTrue(ClipGate.titleSays("RUDE!", "Hearts2Hearts RUDE! 직캠"))
        assertTrue(ClipGate.titleSays("RUDE!", "Hearts2Hearts - RUDE! 무대"))
        assertFalse(ClipGate.titleSays("RUDE!", "Hearts2Hearts 'RUDE!' MV"))
    }

    @Test fun onlyAnOfficialMusicVideoIsOfficial() {
        assertTrue(ClipGate.official("MUSIC_VIDEO_TYPE_OMV"))
        assertTrue(ClipGate.official(OFFICIAL))
        assertFalse(ClipGate.official("MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC"))
        assertFalse(ClipGate.official("MUSIC_VIDEO_TYPE_UGC"))
        assertFalse(ClipGate.official("MUSIC_VIDEO_TYPE_ATV"))
        assertFalse(ClipGate.official(null))
    }

    @Test fun podcastsFilesUploadsAndStationsAreNotSongs() {
        assertTrue(ClipGate.isSong(Song("abc", "Song")))
        assertTrue(ClipGate.isSong(Song("abc", "Song", isVideo = true)))
        assertFalse(ClipGate.isSong(Song("${Song.RSS_PREFIX}x", "Episode", isEpisode = true)))
        assertFalse(ClipGate.isSong(Song("${Song.LOCAL_PREFIX}12", "File")))
        assertFalse(ClipGate.isSong(Song("abc", "Mine", isUpload = true)))
        assertFalse(ClipGate.isSong(Song("abc", "Episode", isEpisode = true)))
        assertFalse(ClipGate.isSong(Song("abc", "Station", isLive = true)))
    }
}
