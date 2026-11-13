package com.prism.music.data.canvas

import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.8.6 Canvas, the second look at a loop on its real frames (plan-canvas 4.3): the loop ends on one of the video's own
 * cuts, a flashing loop is never shown, and the crop follows the faces. Frames here are made by hand (a grid of one
 * brightness, or two halves), so each rule is checked at a known frame.
 */
class ClipRefineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun flat(v: Int) = ByteArray(ClipRefine.GRID_W * ClipRefine.GRID_H) { v.toByte() }
    /** 30 frames a second from [fromMs]; [level] gives each frame's brightness by its number. */
    private fun frames(n: Int, fromMs: Long = 0, level: (Int) -> Int) = (0 until n).map { ClipRefine.Frame(fromMs + it * 1000L / 30, flat(level(it))) }

    // ------------------------------------------------------------------ Cuts and the end

    @Test fun aCutIsFoundAtItsFrame() {
        // Scene one (dark, drifting a little), then scene two from frame 100.
        val f = frames(200) { if (it < 100) 60 + it % 3 else 150 + it % 3 }
        assertEquals(listOf(100 * 1000L / 30), ClipRefine.cuts(f))
    }

    @Test fun movementAndADissolveAreNotCuts() {
        // Brightness climbing over a second (a dissolve), and a steady wobble.
        assertEquals(emptyList<Long>(), ClipRefine.cuts(frames(120) { if (it in 40..70) 60 + (it - 40) * 3 else if (it < 40) 60 else 150 }))
        assertEquals(emptyList<Long>(), ClipRefine.cuts(frames(120) { 100 + (it % 4) * 5 }))
    }

    @Test fun theLoopEndsOnTheLastCutBetweenFiveAndNineSeconds() {
        assertEquals(7_200L, ClipRefine.end(0, listOf(2_000, 5_500, 7_200, 9_400), 9_500))
        assertEquals("on the video's clock", 107_200L, ClipRefine.end(100_000, listOf(102_000, 107_200), 109_500))
    }

    @Test fun withNoCutInReachItRunsEightSecondsOrToTheLastFrame() {
        assertEquals(8_000L, ClipRefine.end(0, listOf(2_000, 4_900), 9_500))
        assertEquals(6_300L, ClipRefine.end(0, emptyList(), 6_300))
        assertEquals("a cut past the frames read doesn't count", 8_000L, ClipRefine.end(0, listOf(8_800), 8_500))
    }

    // ------------------------------------------------------------------ Flashing

    @Test fun aStrobeIsFlashing() {
        // Light and dark every third frame: five flashes a second.
        assertTrue(ClipRefine.flashing(frames(90) { if ((it / 3) % 2 == 0) 40 else 200 }))
    }

    @Test fun ordinaryEditingIsNot() {
        // A cut every second between a dark and a light scene; a slow pulse; a single flash of lightning.
        assertFalse(ClipRefine.flashing(frames(240) { if ((it / 30) % 2 == 0) 50 else 190 }))
        assertFalse(ClipRefine.flashing(frames(240) { 110 + (Math.sin(it / 15.0) * 60).toInt() }))
        assertFalse(ClipRefine.flashing(frames(240) { if (it == 100 || it == 101) 230 else 60 }))
    }

    @Test fun threeFlashesASecondPassAndMoreDoNot() {
        // Three flashes (six changes) in a second, then calm: allowed. Four: not.
        fun burst(flashes: Int) = frames(120) { i -> if (i < flashes * 8 && (i / 4) % 2 == 1) 200 else 50 }
        assertFalse(ClipRefine.flashing(burst(3)))
        assertTrue(ClipRefine.flashing(burst(4)))
    }

    @Test fun aSmallFlashingLightIsNotTheWholePicture() {
        // A tenth of the picture strobes (a lamp in the corner).
        val n = ClipRefine.GRID_W * ClipRefine.GRID_H
        val f = (0 until 90).map { i -> ClipRefine.Frame(i * 1000L / 30, ByteArray(n) { k -> (if (k < n / 10 && (i / 3) % 2 == 0) 220 else 60).toByte() }) }
        assertFalse(ClipRefine.flashing(f))
    }

    // ------------------------------------------------------------------ The crop

    private fun face(ms: Long, x: Float, size: Float = 0.1f, sure: Float = 0.6f) = ClipRefine.Face(ms, x, size, sure)

    @Test fun theCropPutsTheFaceInTheBand() {
        // A face a quarter of the way across: the band (30% wide) is centred on it.
        val x = ClipRefine.cropX(listOf(face(0, 0.25f), face(900, 0.26f), face(1800, 0.24f)), Bars.NONE)!!
        val bandLeft = x * (1f - ClipRefine.BAND)
        assertEquals(0.25f, bandLeft + ClipRefine.BAND / 2f, 0.02f)
    }

    @Test fun aFaceAtTheEdgeStopsAtTheEdge() {
        assertEquals(0f, ClipRefine.cropX(listOf(face(0, 0.05f), face(900, 0.06f), face(1800, 0.05f)), Bars.NONE)!!, 0.001f)
        assertEquals(1f, ClipRefine.cropX(listOf(face(0, 0.97f), face(900, 0.96f), face(1800, 0.97f)), Bars.NONE)!!, 0.001f)
    }

    @Test fun oneSightingIsNotTrusted() {
        assertNull(ClipRefine.cropX(listOf(face(0, 0.2f)), Bars.NONE))
        assertNull("two faces in one frame are still one sighting", ClipRefine.cropX(listOf(face(0, 0.2f), face(0, 0.8f)), Bars.NONE))
        assertNull(ClipRefine.cropX(listOf(face(0, 0.2f), face(900, 0.2f)), Bars.NONE))
        assertNull(ClipRefine.cropX(emptyList(), Bars.NONE))
    }

    @Test fun facesThatDisagreeLeaveTheCropWhereItWas() {
        // A loop of two scenes, the singer on the left in one and on the right in the other: no one place serves both.
        assertNull(ClipRefine.cropX(listOf(face(0, 0.2f), face(900, 0.22f), face(3_000, 0.8f), face(3_900, 0.78f)), Bars.NONE))
    }

    @Test fun theLargeSureFaceWinsOverAPasserBy() {
        val faces = listOf(face(0, 0.7f, 0.2f, 0.9f), face(900, 0.7f, 0.2f, 0.9f), face(1800, 0.7f, 0.2f, 0.9f), face(900, 0.1f, 0.04f, 0.45f))
        val x = ClipRefine.cropX(faces, Bars.NONE)!!
        assertEquals(0.7f, x * (1f - ClipRefine.BAND) + ClipRefine.BAND / 2f, 0.02f)
    }

    @Test fun barsAreLeftOutOfTheReckoning() {
        // A 4:3 picture in a wide frame: a face in the frame's middle is in the picture's middle.
        val x = ClipRefine.cropX(listOf(face(0, 0.5f), face(900, 0.5f), face(1800, 0.5f)), Bars(left = 0.125f, right = 0.125f))!!
        assertEquals(0.5f, x, 0.01f)
        // At the picture's own left edge (just inside the bar): the crop goes all the way left.
        assertEquals(0f, ClipRefine.cropX(listOf(face(0, 0.15f), face(900, 0.15f), face(1800, 0.15f)), Bars(left = 0.125f, right = 0.125f))!!, 0.001f)
    }

    @Test fun theWholeLook() {
        // Nine seconds from 2 s on the file's clock: a cut at 8 s (6 s in), faces on the right before it, one after it.
        val f = frames(300, fromMs = 1_000) { if (it < 210) 70 + it % 2 else 160 + it % 2 }
        val cut = 1_000 + 210 * 1000L / 30
        val r = ClipRefine.judge(f, listOf(face(2_500, 0.8f), face(3_400, 0.8f), face(4_300, 0.8f), face(cut + 300, 0.1f)), startMs = 2_000, bars = Bars.NONE)
        assertEquals(cut, r.endMs)
        assertFalse(r.flashing)
        assertEquals(0.8f, r.cropX!! * (1f - ClipRefine.BAND) + ClipRefine.BAND / 2f, 0.02f)
        // A strobe after the end doesn't condemn the part that shows; one inside it does.
        val after = f.map { if (it.timeMs > cut + 100) ClipRefine.Frame(it.timeMs, flat(if ((it.timeMs / 100) % 2 == 0L) 20 else 230)) else it }
        assertFalse(ClipRefine.judge(after, emptyList(), 2_000, Bars.NONE).flashing)
        val inside = f.map { if (it.timeMs in 4_000..5_500) ClipRefine.Frame(it.timeMs, flat(if ((it.timeMs / 100) % 2 == 0L) 20 else 230)) else it }
        assertTrue(ClipRefine.judge(inside, emptyList(), 2_000, Bars.NONE).flashing)
    }

    // ------------------------------------------------------------------ What the repository does with it

    private val song = Song("song1", "Dai Dai", listOf(ArtistRef("Shakira")), durationSec = 240)

    private class MemoryDao : CanvasClipDao {
        val rows = LinkedHashMap<String, CanvasClipEntity>()
        override suspend fun get(songId: String) = rows[songId]
        override suspend fun put(e: CanvasClipEntity) { rows[e.songId] = e }
        override suspend fun delete(songId: String) { rows.remove(songId) }
        override suspend fun clearGuesses() { rows.clear() }
        override suspend fun count() = rows.size
    }

    private object NoNet : ClipFinder.Net {
        override suspend fun findVideo(song: Song): String? = error("no network")
        override suspend fun videoType(videoId: String): String? = error("no network")
        override suspend fun player(videoId: String): PlayerInfo = error("no network")
        override suspend fun frames(board: Storyboard): ClipFinder.Frames = error("no network")
        override suspend fun heatmap(videoId: String): com.prism.music.data.Heatmap? = error("no network")
    }

    private val files by lazy { ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 }) }
    private fun keep(start: Long, end: Long) { val t = files.temp(false); t.writeBytes(ByteArray(64)); files.keep(t, "v1", 1080, 137, start, end, false, false) }
    private fun repo(dao: MemoryDao, refiner: ClipRefiner?) =
        ClipRepository(dao, ClipFinder(NoNet), ClipFetcher(OkHttpClient(), { error("no network in this test") }, files)).apply { this.refiner = refiner }
    private fun row(start: Long = 60_000, others: List<Long> = listOf(120_000)) =
        clipRow(song.id, VideoClip("v1", start, start + 8_000, 1080, cropX = 0.5f), null, null, now = 1, videoType = OFFICIAL, itag = 137, otherParts = others)

    @Test fun theLoopShowsAsJudgedAndIsJudgedOnce() = runBlocking {
        keep(60_000, 72_000)
        val dao = MemoryDao().apply { rows[song.id] = row() }
        var looks = 0
        val r = repo(dao) { _, loop -> looks++; ClipRefine.Refined(loop.startMs + 6_400, 0.8f, false, listOf(loop.startMs + 6_400), 4) }
        val art = r.artworkFor(song, 1080, saved = false, allowed = { false })
        assertNotNull(art)
        assertEquals(66_400L, art!!.clip!!.endMs); assertEquals(0.8f, art.clip!!.cropX, 0.001f); assertTrue(art.clip!!.refined)
        val kept = dao.rows.getValue(song.id)
        assertEquals(66_400L, kept.endMs); assertEquals(0.8f, kept.cropX, 0.001f); assertTrue(kept.refined)
        // The next play: the same loop, no second look.
        val again = r.artworkFor(song, 1080, saved = false, allowed = { false })
        assertEquals(art.clip, again!!.clip); assertEquals(1, looks)
    }

    @Test fun withNoFacesTheFindersCropStays() = runBlocking {
        keep(60_000, 72_000)
        val dao = MemoryDao().apply { rows[song.id] = row().copy(cropX = 0.3f) }
        val art = repo(dao) { _, loop -> ClipRefine.Refined(loop.startMs + 8_000, null, false, emptyList(), 0) }.artworkFor(song, 1080, false, { false })
        assertEquals(0.3f, art!!.clip!!.cropX, 0.001f); assertTrue(art.clip!!.refined)
    }

    @Test fun aFlashingLoopIsNeverShownAndAnotherPartIsTaken() = runBlocking {
        keep(60_000, 72_000); keep(120_000, 132_000)
        val dao = MemoryDao().apply { rows[song.id] = row() }
        val art = repo(dao) { _, loop -> ClipRefine.Refined(loop.startMs + 8_000, null, flashing = loop.startMs < 100_000, emptyList(), 0) }
            .artworkFor(song, 1080, false, { false })
        assertEquals("the other part", 120_000L, art!!.clip!!.startMs)
        assertEquals(emptyList<Long>(), dao.rows.getValue(song.id).otherParts())
    }

    @Test fun whenEveryPartFlashesTheCoverStays() = runBlocking {
        keep(60_000, 72_000); keep(120_000, 132_000)
        val dao = MemoryDao().apply { rows[song.id] = row() }
        val r = repo(dao) { _, loop -> ClipRefine.Refined(loop.startMs + 8_000, null, true, emptyList(), 0) }
        assertNull(r.artworkFor(song, 1080, false, { false }))
        val kept = dao.rows.getValue(song.id)
        assertNull(kept.videoId); assertEquals(NoClip.FLASHING.name, kept.why)
        // And with no other part to try.
        dao.rows[song.id] = row(others = emptyList())
        assertNull(r.artworkFor(song, 1080, false, { false }))
        assertEquals(NoClip.FLASHING.name, dao.rows.getValue(song.id).why)
    }

    @Test fun aPhoneThatCannotReadTheFramesShowsTheLoopAsGuessedAndDoesNotKeepTrying() = runBlocking {
        keep(60_000, 72_000)
        val dao = MemoryDao().apply { rows[song.id] = row() }
        var looks = 0
        val r = repo(dao) { _, _ -> looks++; null }
        val art = r.artworkFor(song, 1080, false, { false })
        assertNotNull(art); assertFalse(art!!.clip!!.refined)
        r.artworkFor(song, 1080, false, { false })
        assertEquals(1, looks)
        // A look that throws is the same as none.
        val thrown = repo(MemoryDao().apply { rows[song.id] = row() }) { _, _ -> error("decoder") }.artworkFor(song, 1080, false, { false })
        assertNotNull(thrown)
    }
}
