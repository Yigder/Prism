package com.prism.music.data.canvas

import com.prism.music.data.Backup
import com.prism.music.data.BackupPart
import com.prism.music.data.Heatmap
import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.8.6 Canvas, the song's own choices from the player's ⋯ menu (plan-canvas §3.8, 4.6): another part of the loop,
 * "Use:" for one song, "Not this video" (sticks, never offered again), on an in-memory table and a fake network.
 */
class ClipRepositoryChoicesTest {
    private val song = Song("song1", "Dai Dai", listOf(ArtistRef("Shakira")), durationSec = 240)
    private val tmp = java.nio.file.Files.createTempDirectory("clipchoices").toFile()
    private val recent = File(tmp, "recent")
    private val saved = File(tmp, "saved")

    @After fun clean() { tmp.deleteRecursively() }

    /** The table, in memory: a "restart" is a new repository over the same one. */
    private class MemoryDao : CanvasClipDao {
        val rows = LinkedHashMap<String, CanvasClipEntity>()
        override suspend fun get(songId: String) = rows[songId]
        override suspend fun put(e: CanvasClipEntity) { rows[e.songId] = e }
        override suspend fun delete(songId: String) { rows.remove(songId) }
        override suspend fun clearGuesses() { rows.values.removeAll { !it.picked && it.prefer == null && it.rejectedVideos.isEmpty() } }
        override suspend fun count() = rows.values.count { it.videoId != null }
    }

    /** A network where the search always finds [found], an official video that moves; what was asked is kept. */
    private class Net(val found: String?) : ClipFinder.Net {
        val asked = mutableListOf<String>()
        var searches = 0
        override suspend fun findVideo(song: Song): String? { searches++; return found }
        override suspend fun videoType(videoId: String): String? { asked += "type:$videoId"; return "MUSIC_VIDEO_TYPE_OMV" }
        override suspend fun player(videoId: String): PlayerInfo {
            asked += "player:$videoId"
            return PlayerInfo("OK", "Shakira - Dai Dai (Official Video)", 240_000, storyboardSpec = null)
        }
        override suspend fun frames(board: Storyboard) = ClipFinder.Frames(emptyList(), 0)
        override suspend fun heatmap(videoId: String): Heatmap? = null
    }

    private fun files() = ClipFiles(recent, saved) { Long.MAX_VALUE }

    private fun repo(dao: MemoryDao, net: Net = Net(null), files: ClipFiles = files()) =
        ClipRepository(dao, ClipFinder(net), ClipFetcher(OkHttpClient(), { error("no network in this test") }, files)).apply { this.files = files }

    private fun loopRow(video: String = "v1", start: Long = 64_000, others: List<Long> = listOf(30_000, 90_000, 120_000)) =
        clipRow(song.id, VideoClip(video, start, start + 7_000, 1080, refined = true), null, null, now = 1, videoType = OFFICIAL, itag = 137, otherParts = others)

    // ------------------------------------------------------------------ Pick another part

    @Test fun pickingAnotherPartMakesItTheLoopAndKeepsTheOldOneAmongTheOthers() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow() }
        val r = repo(dao)
        assertEquals(listOf(64_000L, 30_000L, 90_000L, 120_000L), r.parts(song.id)!!.all)
        val before = r.changes.value
        assertTrue(r.pickPart(song.id, 90_000))
        val row = dao.rows.getValue(song.id)
        assertEquals(90_000L, row.startMs)
        assertEquals(90_000L + ClipRepository.PICK_LENGTH_MS, row.endMs)
        assertTrue(row.picked)
        assertFalse("cut again to the real frames when fetched", row.refined)
        assertEquals("v1", row.videoId)
        assertEquals(1080, row.height)
        assertEquals("the old part takes the picked one's place", listOf(30_000L, 64_000L, 120_000L), row.otherParts())
        assertEquals(before + 1, r.changes.value)
        val parts = r.parts(song.id)!!
        assertEquals(90_000L, parts.current)
        assertEquals(listOf(90_000L, 30_000L, 64_000L, 120_000L), parts.all)
    }

    @Test fun tappingThePartPlayingKeepsItAsItIs() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow() }
        assertTrue(repo(dao).pickPart(song.id, 64_000))
        val row = dao.rows.getValue(song.id)
        assertEquals(64_000L, row.startMs)
        assertEquals(71_000L, row.endMs)
        assertTrue(row.refined)
        assertTrue(row.picked)
    }

    @Test fun aSongWithoutALoopHasNoPartsToPick() = runBlocking {
        val dao = MemoryDao()
        val r = repo(dao)
        assertNull(r.parts(song.id))
        assertFalse(r.pickPart(song.id, 10_000))
        assertTrue("no row made", dao.rows.isEmpty())
        dao.rows[song.id] = clipRow(song.id, null, NoClip.STILL, null, 1)
        assertFalse(r.pickPart(song.id, 10_000))
        assertEquals(0, r.changes.value)
    }

    @Test fun aPickedPartSurvivesTheNextFind() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow() }
        repo(dao).pickPart(song.id, 90_000)
        val again = ClipFinder(Net("v2")).find(song, dao.rows[song.id], 1080, now = 9)
        assertEquals("v1", again.videoId)
        assertEquals(90_000L, again.startMs)
        assertTrue(again.picked)
    }

    // ------------------------------------------------------------------ Not this video

    @Test fun notThisVideoDropsTheLoopAndItsFilesAndSticksAfterARestart() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow().copy(prefer = "video", picked = true) }
        recent.mkdirs(); saved.mkdirs()
        val kept = File(recent, ClipFiles.name("v1", 1080, 137, 60_000, 76_000, false)).apply { writeText("x") }
        val keptSaved = File(saved, ClipFiles.name("v1", 720, 136, 60_000, 76_000, false)).apply { writeText("x") }
        val other = File(recent, ClipFiles.name("v9", 1080, 137, 0, 10_000, false)).apply { writeText("x") }
        val r = repo(dao)
        assertTrue(r.rejectVideo(song.id, now = 100))
        val row = dao.rows.getValue(song.id)
        assertNull(row.videoId)
        assertEquals(NoClip.REJECTED.name, row.why)
        assertEquals(setOf("v1"), row.rejected())
        assertFalse(row.picked)
        assertEquals("the song's Use: stays", "video", row.prefer)
        assertTrue(row.otherParts().isEmpty())
        assertEquals(100L, row.checkedAt)
        assertFalse(kept.exists() || keptSaved.exists())
        assertTrue("another video's loop stays", other.exists())
        assertEquals(1, r.changes.value)
        assertNull(r.parts(song.id))
        assertFalse("nothing left to reject", r.rejectVideo(song.id))

        // A restart: a new repository over the same table. Not looked up again within the week, and when it is, the
        // search finds the same video and it is still turned away without asking anything about it.
        val net = Net("v1")
        val after = repo(dao, net)
        val allowed = { true }
        assertNull(after.artworkFor(song, 1080, saved = false, allowed = allowed, now = 200))
        assertEquals(0, net.searches)
        assertNull(after.artworkFor(song, 1080, saved = false, allowed = allowed, now = 100 + CLIP_RECHECK_MS + 1))
        assertEquals(1, net.searches)
        assertTrue(net.asked.toString(), net.asked.isEmpty())
        val again = dao.rows.getValue(song.id)
        assertEquals(NoClip.REJECTED.name, again.why)
        assertEquals(setOf("v1"), again.rejected())
        // The guesses' clean-up keeps it too.
        dao.clearGuesses()
        assertEquals(setOf("v1"), dao.rows.getValue(song.id).rejected())
    }

    @Test fun aSecondRejectedVideoJoinsTheFirst() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow(video = "v2").copy(rejectedVideos = "v1") }
        repo(dao).rejectVideo(song.id, now = 5)
        assertEquals(setOf("v1", "v2"), dao.rows.getValue(song.id).rejected())
        // A later search that finds a third video may use it.
        val row = ClipFinder(Net("v3")).find(song, dao.rows[song.id], 1080, now = 9)
        assertEquals("v3", row.videoId)
        assertEquals(setOf("v1", "v2"), row.rejected())
    }

    // ------------------------------------------------------------------ Use: for one song

    @Test fun useIsStoredForTheSongAndCanBeClearedAgain() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow() }
        val r = repo(dao)
        assertNull(r.choice(song.id))
        r.setChoice(song.id, CanvasSource.VIDEO)
        assertEquals(CanvasSource.VIDEO, r.choice(song.id))
        assertEquals("the loop is untouched", loopRow().copy(prefer = "video"), dao.rows.getValue(song.id))
        // Picking the one already in effect simply stores it.
        r.setChoice(song.id, CanvasSource.VIDEO)
        assertEquals(CanvasSource.VIDEO, r.choice(song.id))
        r.setChoice(song.id, CanvasSource.COVER)
        assertEquals(CanvasSource.COVER, r.choice(song.id))
        r.setChoice(song.id, null)
        assertNull(r.choice(song.id))
        assertEquals(4, r.changes.value)
    }

    @Test fun useBeforeTheSongWasLookedUpKeepsTheChoiceAndStillLooksItUp() = runBlocking {
        val dao = MemoryDao()
        val r = repo(dao)
        r.setChoice(song.id, null)
        assertTrue("nothing to clear, nothing made", dao.rows.isEmpty())
        r.setChoice(song.id, CanvasSource.COVER)
        val row = dao.rows.getValue(song.id)
        assertEquals("cover", row.prefer)
        assertTrue(ClipRepository.needsFinding(row, System.currentTimeMillis()))
        // The finder keeps the choice when it fills the row.
        assertEquals("cover", ClipFinder(Net("v1")).find(song, row, 1080, now = 9).prefer)
    }

    /** What the player shows for the song once its "Use:" is stored: the stored choice feeds [pickSource]. */
    @Test fun theStoredChoiceDecidesWhatShows() = runBlocking {
        val dao = MemoryDao().apply { rows[song.id] = loopRow() }
        val r = repo(dao)
        for (videoFirst in listOf(false, true)) {
            r.setChoice(song.id, CanvasSource.VIDEO)
            assertEquals(CanvasSource.VIDEO, pickSource(true, true, true, videoFirst, r.choice(song.id)))
            r.setChoice(song.id, CanvasSource.COVER)
            assertEquals(CanvasSource.COVER, pickSource(true, true, true, videoFirst, r.choice(song.id)))
            r.setChoice(song.id, null)
            assertEquals(if (videoFirst) CanvasSource.VIDEO else CanvasSource.COVER, pickSource(true, true, true, videoFirst, r.choice(song.id)))
        }
    }

    // ------------------------------------------------------------------ Stills and backups

    @Test fun aStillIsTheFrameNearestThePartsStart() {
        // 160x90 frames, 5 x 5 a sheet, one every 2 s, 120 of them.
        val board = Storyboard.parse("https://i.ytimg.com/sb/x/storyboard3_L\$L/\$N.jpg?sqp=x|48#27#100#10#10#0#default#rs\$a|160#90#120#5#5#2000#M\$M#rs\$b")!!
        assertEquals(Storyboard.Spot(0, 0, 0), board.frameAt(0))
        assertEquals(Storyboard.Spot(0, 160, 0), board.frameAt(2_900))
        assertEquals(Storyboard.Spot(0, 320, 0), board.frameAt(3_000))
        // 64 s: frame 32, sheet 1, its 8th (row 1, column 2).
        assertEquals(Storyboard.Spot(1, 320, 90), board.frameAt(64_000))
        // Past the end: the last frame (119: sheet 4, its 20th, row 3, column 4).
        assertEquals(Storyboard.Spot(4, 640, 270), board.frameAt(999_000))
        assertEquals(Storyboard.Spot(0, 0, 0), board.frameAt(-5))
    }

    /**
     * The song's choices travel in backups without code of their own: the table is in Prism's database (prism.db, the
     * "History" part), and the per-song Canvas switch is a saved preference that counts as "Settings".
     */
    @Test fun theSongsChoicesTravelInBackups() {
        assertEquals(BackupPart.SETTINGS, Backup.prefsPart("canvas_hidden"))
    }
}
