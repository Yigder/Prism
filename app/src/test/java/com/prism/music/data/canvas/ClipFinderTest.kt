package com.prism.music.data.canvas

import com.prism.music.data.Heatmap
import com.prism.music.data.db.CanvasClipEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The finder's order of questions and the rows it gives, on a fake network (plan-canvas 4.1). */
class ClipFinderTest {
    private val song = Song("song1", "Dai Dai", listOf(ArtistRef("Shakira")), durationSec = 240)

    /** A video that moves everywhere: every frame a different picture, mid-grey. */
    private fun moving(n: Int) = List(n) { i -> Grid(IntArray(Grid.W * Grid.H) { c -> 60 + ((c * 7 + i * 37) % 120) }) }

    /** A still picture. */
    private fun still(n: Int) = List(n) { Grid(IntArray(Grid.W * Grid.H) { c -> 40 + c % 90 }) }

    private class Video(val type: String?, val info: PlayerInfo, val grids: List<Grid>?, val heat: Heatmap?)

    private class FakeNet(val found: String?, val videos: Map<String, Video>, val others: List<String> = emptyList()) : ClipFinder.Net {
        val asked = mutableListOf<String>()
        var searches = 0
        override suspend fun findVideo(song: Song): String? { searches++; return found }
        var otherSearches = 0
        override suspend fun otherVideos(song: Song, not: Set<String>): List<String> { otherSearches++; return others.filter { it !in not } }
        override suspend fun videoType(videoId: String): String? { asked += "type:$videoId"; return videos.getValue(videoId).type }
        override suspend fun player(videoId: String): PlayerInfo { asked += "player:$videoId"; return videos.getValue(videoId).info }
        override suspend fun frames(board: Storyboard): ClipFinder.Frames {
            val id = board.sheetUrl(0).substringAfter("/sb/").substringBefore('/')
            asked += "frames:$id"
            return ClipFinder.Frames(videos.getValue(id).grids.orEmpty(), 1234)
        }
        override suspend fun heatmap(videoId: String): Heatmap? = videos.getValue(videoId).heat
    }

    private fun spec(id: String) = "https://i.ytimg.com/sb/$id/storyboard3_L\$L/\$N.jpg?sqp=x|48#27#100#10#10#0#default#rs\$a|160#90#120#5#5#2000#M\$M#rs\$b"

    private fun finder(net: FakeNet) = ClipFinder(net)

    private fun video(id: String, grids: List<Grid>? = moving(120), type: String? = "MUSIC_VIDEO_TYPE_OMV", title: String = "Shakira - Dai Dai (Official Video)", status: String = "OK", heat: Heatmap? = null, spec: String? = spec(id), lengthMs: Long = 240_000) =
        id to Video(type, PlayerInfo(status, title, lengthMs, storyboardSpec = spec), grids, heat)

    @Test fun aVideoWithNoLengthAndNoFramesHasNothingToCut() = runBlocking {
        val row = finder(FakeNet("v1", mapOf(video("v1", spec = null, lengthMs = 0)))).find(song, null, 1080, now = 5)
        assertEquals(NoClip.NO_STREAM.name, row.why)
    }

    @Test fun notASongIsNeverLookedUp() = runBlocking {
        val net = FakeNet("v1", mapOf(video("v1")))
        for (s in listOf(Song("${Song.LOCAL_PREFIX}1", "File"), Song("${Song.RSS_PREFIX}1", "Ep", isEpisode = true), Song("u", "Mine", isUpload = true))) {
            val row = finder(net).find(s, null, 1080, now = 5)
            assertEquals(NoClip.NOT_A_SONG.name, row.why)
            assertNull(row.videoId)
        }
        assertEquals(0, net.searches)
        assertTrue(net.asked.isEmpty())
    }

    /** Owner, 2026-11-13: "Pleaser" by Wallows is paired with its lyric video; its music video is one search away. */
    @Test fun aLyricVideoGivenFirstIsNotTheLastWord() = runBlocking {
        val net = FakeNet("lyric", mapOf(video("lyric", title = "Wallows - Pleaser (Lyric Video)"), video("real", title = "Wallows - Pleaser (Official Video)")), others = listOf("real"))
        val row = finder(net).find(song, null, 1080, now = 5)
        assertEquals("real", row.videoId)
        assertNull(row.why?.ifEmpty { null })
        assertEquals(1, net.otherSearches)
    }

    /** Owner, 2026-11-13: "if the song has a video it should have a canvas". Its only official video is a lyric video: that one. */
    @Test fun anOfficialLyricVideoIsUsedWhenThereIsNoOther() = runBlocking {
        val lyric = video("lyric", title = "Wallows - Pleaser (Lyric Video)")
        for (net in listOf(FakeNet("lyric", mapOf(lyric)), FakeNet("lyric", mapOf(lyric, video("audio", title = "Pleaser (Official Audio)")), others = listOf("lyric", "audio")))) {
            val row = finder(net).find(song, null, 1080, now = 5)
            assertEquals("lyric", row.videoId)
            assertEquals(1, net.otherSearches)
        }
        // A still picture with the words on it is still a still.
        val still = finder(FakeNet("lyric", mapOf(video("lyric", grids = still(120), title = "Wallows - Pleaser (Lyric Video)")))).find(song, null, 1080, now = 5)
        assertNull(still.videoId)
    }

    /** The universal rule (owner, 2026-11-13): a song's own video makes a canvas whatever its title or type says. */
    @Test fun theSongsOwnVideoIsUsedWhateverItIsCalledWhenThereIsNoBetter() = runBlocking {
        for (title in listOf("Wallows - Pleaser (Live at Coachella)", "Pleaser (Visual) [Official Audio]", "Pleaser karaoke with lyrics")) {
            assertEquals(title, "v", finder(FakeNet("v", mapOf(video("v", title = title)))).find(song, null, 1080, now = 5).videoId)
        }
        // Not marked as an official music video by YouTube Music: still the song's video.
        assertEquals("v", finder(FakeNet("v", mapOf(video("v", type = "MUSIC_VIDEO_TYPE_UGC")))).find(song, null, 1080, now = 5).videoId)
        // A better one, when the search has it, comes first.
        val both = FakeNet("live", mapOf(video("live", title = "Pleaser (Live)"), video("real")), others = listOf("real"))
        assertEquals("real", finder(both).find(song, null, 1080, now = 5).videoId)
        // What can't make a loop is still refused: a still picture (most audio uploads), a video that can't be played.
        assertEquals(NoClip.STILL.name, finder(FakeNet("v", mapOf(video("v", grids = still(120), title = "Pleaser (Official Audio)")))).find(song, null, 1080, now = 5).why)
        assertNull(finder(FakeNet("v", mapOf(video("v", title = "Pleaser (Live)", status = "LOGIN_REQUIRED")))).find(song, null, 1080, now = 5).videoId)
        // The words are still what the search for a better one goes by.
        assertTrue(ClipGate.titleSays("Pleaser", "Wallows - Pleaser (Lyric Video)"))
    }

    @Test fun aGoodFirstVideoIsNotSearchedPast() = runBlocking {
        val net = FakeNet("v1", mapOf(video("v1")), others = listOf("v2"))
        assertEquals("v1", finder(net).find(song, null, 1080, now = 5).videoId)
        assertEquals(0, net.otherSearches)
    }

    @Test fun noVideoFound() = runBlocking {
        val row = finder(FakeNet(null, emptyMap())).find(song, null, 1080, now = 5)
        assertEquals(NoClip.NO_VIDEO.name, row.why)
        assertEquals(5L, row.checkedAt)
    }

    @Test fun aSongThatIsAVideoUsesItsOwnPictureWithoutASearch() = runBlocking {
        val net = FakeNet("other", mapOf(video("self")))
        val row = finder(net).find(song.copy(id = "self", isVideo = true), null, 720, now = 5)
        assertEquals("self", row.videoId)
        assertEquals(0, net.searches)
    }

    /** An audio upload (YouTube's "art track"): a better video is searched for, and its own still picture is refused as a still. */
    @Test fun anArtTrackIsAStillNotAMatterOfType() = runBlocking {
        val net = FakeNet("v1", mapOf(video("v1", grids = still(120), type = "MUSIC_VIDEO_TYPE_ATV")))
        val row = finder(net).find(song, null, 1080, now = 5)
        assertEquals(NoClip.STILL.name, row.why)
        assertEquals("ATV", row.videoType)
        assertEquals(1, net.otherSearches)
    }

    @Test fun aVideoWithNoTypeAtAllIsStillTheSongsVideo() = runBlocking {
        assertEquals("v1", finder(FakeNet("v1", mapOf(video("v1", type = null)))).find(song, null, 1080, now = 5).videoId)
    }

    @Test fun anAudioUploadThatIsAStillIsTurnedAwayAsAStill() = runBlocking {
        val row = finder(FakeNet("v1", mapOf(video("v1", grids = still(120), title = "Shakira - Dai Dai (Official Audio)")))).find(song, null, 1080, now = 5)
        assertEquals(NoClip.STILL.name, row.why)
        assertEquals(OFFICIAL, row.videoType)
    }

    @Test fun anUnplayableVideoIsRestricted() = runBlocking {
        for (status in listOf("LOGIN_REQUIRED", "UNPLAYABLE", "ERROR")) {
            val row = finder(FakeNet("v1", mapOf(video("v1", status = status)))).find(song, null, 1080, now = 5)
            assertEquals(status, NoClip.RESTRICTED.name, row.why)
        }
    }

    @Test fun aStillUploadIsTurnedAway() = runBlocking {
        val net = FakeNet("v1", mapOf(video("v1", grids = still(120))))
        val row = finder(net).find(song, null, 1080, now = 5)
        assertEquals(NoClip.STILL.name, row.why)
        assertNull(row.videoId)
    }

    @Test fun aMovingOfficialVideoGetsAnUnrefinedLoopAtTheAskedHeight() = runBlocking {
        val row = finder(FakeNet("v1", mapOf(video("v1")))).find(song, null, 720, now = 5)
        assertEquals("", row.why)
        assertEquals("v1", row.videoId)
        assertEquals(OFFICIAL, row.videoType)
        assertEquals(720, row.height)
        assertEquals(0, row.itag)
        assertFalse(row.refined)
        assertFalse(row.picked)
        val clip = row.clip()!!
        assertEquals(FrameJudge.WINDOW_MS, clip.lengthMs)
        assertTrue(clip.startMs >= FrameJudge.HEAD_MS && clip.endMs <= 240_000 - FrameJudge.TAIL_MS)
        assertTrue(row.otherParts().size in 3..4)
        assertTrue(row.otherParts().all { kotlin.math.abs(it - clip.startMs) >= FrameJudge.APART_MS })
    }

    @Test fun noStoryboardGivesTheMiddleOfTheVideo() = runBlocking {
        val row = finder(FakeNet("v1", mapOf(video("v1", spec = null)))).find(song, null, 1080, now = 5)
        val clip = row.clip()!!
        assertEquals(116_000L, clip.startMs)
        assertEquals(124_000L, clip.endMs)
        assertEquals(0.5f, clip.cropX)
        assertEquals(Bars.NONE, clip.bars)
    }

    @Test fun aRejectedVideoIsNeverOfferedAgain() = runBlocking {
        val old = clipRow("song1", null, NoClip.REJECTED, null, 1).copy(rejectedVideos = "v1")
        val net = FakeNet("v1", mapOf(video("v1")))
        val row = finder(net).find(song, old, 1080, now = 5)
        assertEquals(NoClip.REJECTED.name, row.why)
        assertNull(row.videoId)
        assertTrue(net.asked.isEmpty())
        assertEquals(setOf("v1"), row.rejected())
    }

    @Test fun aSongThatIsARejectedVideoHasNoLoop() = runBlocking {
        val old = clipRow("self", null, NoClip.REJECTED, null, 1).copy(rejectedVideos = "self")
        val row = finder(FakeNet(null, mapOf(video("self")))).find(song.copy(id = "self", isVideo = true), old, 1080, now = 5)
        assertEquals(NoClip.REJECTED.name, row.why)
    }

    @Test fun aPickedPartSurvivesARecheck() = runBlocking {
        val old = picked("v1", 40_000, 47_000, refined = false)
        val net = FakeNet("v2", mapOf(video("v1"), video("v2")))
        val row = finder(net).find(song, old, 1080, now = 9)
        assertEquals("v1", row.videoId)
        assertEquals(40_000L, row.startMs)
        assertEquals(47_000L, row.endMs)
        assertTrue(row.picked)
        assertEquals(9L, row.checkedAt)
        // The picked video is asked first; no search needed.
        assertEquals(0, net.searches)
        assertTrue(row.otherParts().all { kotlin.math.abs(it - 40_000) >= FrameJudge.APART_MS })
    }

    @Test fun aRefinedPickedPartAtTheSameHeightIsKeptWhole() = runBlocking {
        val old = picked("v1", 40_100, 46_900, refined = true).copy(cropX = 0.2f, barTop = 0.1f, barBottom = 0.1f)
        val row = finder(FakeNet("v1", mapOf(video("v1")))).find(song, old, 1080, now = 9)
        assertEquals(old.clip(), row.clip())
        assertTrue(row.refined)
        // Asked at another height, the part stays but is cut again.
        val other = finder(FakeNet("v1", mapOf(video("v1")))).find(song, old, 720, now = 9)
        assertEquals(40_100L, other.startMs)
        assertFalse(other.refined)
    }

    @Test fun aPickedPartWhoseVideoNoLongerPassesGivesWayToANewSearch() = runBlocking {
        val old = picked("v1", 40_000, 47_000, refined = false)
        val net = FakeNet("v2", mapOf(video("v1", status = "UNPLAYABLE"), video("v2")))
        val row = finder(net).find(song, old, 1080, now = 9)
        assertEquals("v2", row.videoId)
        assertFalse(row.picked)
        assertEquals(1, net.searches)
    }

    @Test fun theListenersChoicesSurviveEveryAnswer() = runBlocking {
        val old = clipRow("song1", null, NoClip.NO_VIDEO, null, 1).copy(prefer = "cover", rejectedVideos = "bad")
        for (net in listOf(FakeNet("v1", mapOf(video("v1"))), FakeNet(null, emptyMap()), FakeNet("v1", mapOf(video("v1", grids = still(120)))))) {
            val row = finder(net).find(song, old, 1080, now = 5)
            assertEquals("cover", row.prefer)
            assertEquals(setOf("bad"), row.rejected())
        }
    }

    @Test fun theSameVideoGivesTheSameLoopEveryTime() = runBlocking {
        val a = finder(FakeNet("v1", mapOf(video("v1")))).find(song, null, 1080, now = 5)
        val b = finder(FakeNet("v1", mapOf(video("v1")))).find(song, null, 1080, now = 5)
        assertEquals(a, b)
    }

    @Test fun theMostReplayedPeakPicksThePart() = runBlocking {
        // A hot stretch at 160 s on a 240 s video (2.4 s segments).
        val heat = Heatmap(2_400, List(100) { i -> if (i in 66..70) 0.9f else 0.2f })
        val row = finder(FakeNet("v1", mapOf(video("v1", heat = heat)))).find(song, null, 1080, now = 5)
        assertTrue("${row.startMs}", row.startMs in 146_000..176_000)
        // Without the curve the loop is somewhere else: the peak moved it.
        val plain = finder(FakeNet("v1", mapOf(video("v1")))).find(song, null, 1080, now = 5).startMs
        assertTrue("$plain", plain !in 146_000..176_000)
    }

    @Test fun thePlayerAnswerIsReadForTheGate() {
        val res = Json.parseToJsonElement(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"x","title":"A - B (Official Video)","lengthSeconds":"240","isLiveContent":false,"isPrivate":false},
              "storyboards":{"playerStoryboardSpecRenderer":{"spec":"${spec("x").replace("$", "\\u0024")}"}}}""",
        ).jsonObject
        val info = PlayerInfo.parse(res)
        assertTrue(info.playable)
        assertEquals("A - B (Official Video)", info.title)
        assertEquals(240_000L, info.lengthMs)
        val board = Storyboard.parse(info.storyboardSpec, info.lengthMs)!!
        assertEquals(120, board.count)
        assertEquals(2_000L, board.intervalMs)
        assertEquals(5, board.sheets)
        assertEquals("https://i.ytimg.com/sb/x/storyboard3_L1/M3.jpg?sqp=x&sigh=rs%24b", board.sheetUrl(3))
    }

    @Test fun liveStreamsAndPrivateVideosAreNotPlayable() {
        fun parse(details: String) = PlayerInfo.parse(Json.parseToJsonElement("""{"playabilityStatus":{"status":"OK"},"videoDetails":{$details}}""").jsonObject)
        assertFalse(parse(""""lengthSeconds":"0","isLive":true,"isLiveContent":true""").playable)
        assertFalse(parse(""""lengthSeconds":"0","isLiveContent":true""").playable)
        assertTrue(parse(""""lengthSeconds":"3600","isLiveContent":true""").playable)
        assertFalse(parse(""""lengthSeconds":"200","isPrivate":true""").playable)
    }

    private fun picked(video: String, start: Long, end: Long, refined: Boolean): CanvasClipEntity =
        clipRow("song1", VideoClip(video, start, end, 1080, refined = refined), null, null, 1, videoType = OFFICIAL, picked = true)
}
