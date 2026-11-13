package com.prism.music.data.canvas

import com.prism.music.LiveNetwork
import com.prism.music.data.BestPart
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves real storyboards (as 32x18 grids) of videos from the by-eye round (.claude/canvas-study/eye/) for
 * [FrameJudgeFixturesTest]. Run by hand when the fixtures need renewing:
 * `.\gradlew.bat :app:testDebugUnitTest -PliveOnly --tests "com.prism.music.data.canvas.StoryboardFixturesLiveTest"`.
 * Uses the finder's own fetch and parse; only the decoder is the JVM's (no Android here).
 */
@Category(LiveNetwork::class)
class StoryboardFixturesLiveTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var settings = AppSettings()
    private val api = InnerTube(http, { settings }, { settings = settings.copy(visitorData = it) })
    private val ytm = YouTubeMusic(api)

    private val imageIo = SheetDecoder { bytes ->
        javax.imageio.ImageIO.read(bytes.inputStream())?.let { img ->
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            Sheet(img.width, img.height, IntArray(px.size) { SheetDecoder.luma(px[it]) })
        }
    }

    @Test
    fun saveFixtures() = runBlocking {
        val dir = listOf(File("src/test/resources"), File("app/src/test/resources")).first { it.isDirectory }.resolve("canvas").apply { mkdirs() }
        for ((name, id) in VIDEOS) {
            val info = PlayerInfo.parse(api.visionOsPlayer(id))
            val board = Storyboard.parse(info.storyboardSpec, info.lengthMs) ?: error("$name: no storyboard")
            val frames = ClipFinder.fetchFrames(http, board, imageIo)
            val peak = runCatching { ytm.heatmap(id) }.getOrNull()?.let { BestPart.peakMs(it, info.lengthMs) }
            assertTrue("$name: ${frames.grids.size} frames", frames.grids.size >= 8)
            File(dir, "$name.txt").writeText(buildString {
                appendLine("# id=$id interval=${board.intervalMs} length=${info.lengthMs} peak=${peak ?: -1} bytes=${frames.bytes} frames=${frames.grids.size}")
                frames.grids.forEach { appendLine(Grid.encode(it)) }
            })
            println("[canvas] $name $id: ${frames.grids.size} frames, ${frames.bytes} bytes, peak $peak")
        }
    }

    companion object {
        /**
         * Fixture name to video id; the by-eye round's number in the name. #34's "Official Audio" turned out to move (an
         * animated loop: its title turns it away), so the still audio upload is #2. "topbars" is from the study's India
         * chart (coverage.csv: two black rows of nine). #79 ("Blinding Lights") is the dark one: average brightness 32.
         */
        val VIDEOS = listOf(
            "audio-02" to "emCF66u7BHk",
            "visualizer-32" to "7GwLnsVUwHY",
            "bgm-62" to "NxBZboxRPgc",
            "bgm-87" to "bkednCfm3a0",
            "sidebars-41" to "OMOGaugKpzs",
            "topbars-vaaroon" to "SQ4jZ-EAL88",
            "moving-35" to "fcnDmrtj6Sk",
            "dark-79" to "4NRXx6U8ABQ",
        )
    }
}
