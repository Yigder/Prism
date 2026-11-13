package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.CanvasStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.6 Canvas, live (only with `record=true`: it reaches YouTube and plays a real song, muted by the phone): a song
 * with an official music video gets a loop cut from it, the loop plays full screen from the file kept on the phone,
 * goes round with its first frame over the restart, and nothing fails. The pictures it takes are looked at by eye:
 * the loop behind the player, the controls faded away, and back after a tap.
 *
 * The song can be changed with `song=<search words>`; `seconds=<n>` is how long it watches (default 40).
 */
@Live
@RunWith(AndroidJUnit4::class)
class CanvasLoopLiveTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
    }

    @Test fun aSongsMusicVideoLoopsBehindThePlayer() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false)
        c.settings.setReduceMotion(false)
        c.settings.setImmersivePlayer(false)
        c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(true)
        c.settings.setCanvasFillsBackground(true)
        c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(true)
        c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        val words = args.getString("song") ?: "Rick Astley Never Gonna Give You Up"
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        val before = CanvasStats.rounds to CanvasStats.errors
        withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        // The loop is found, fetched and on screen (the budget for a song met for the first time on Wi-Fi is 3 s; the
        // time it took is printed, the limit here is only for a slow emulator).
        val asked = System.currentTimeMillis()
        val end = asked + 60_000
        while (System.currentTimeMillis() < end && CanvasStats.showing?.startsWith("file://") != true) delay(100)
        val took = System.currentTimeMillis() - asked
        val row = c.canvasClips.row(song.id)
        println("CanvasLoop: ${song.title} (${song.id}) row=$row shown after $took ms")
        android.util.Log.i("CanvasLoop", "${song.title} (${song.id}) shown after $took ms, row $row")
        assertNotNull("a row for the song", row)
        assertNotNull("a music video for it (${row?.why})", row?.videoId)
        assertTrue("the loop shows from its kept file (showing ${CanvasStats.showing}, error ${CanvasStats.lastError})", CanvasStats.showing?.startsWith("file://") == true)
        delay(1_500)
        Fixture.screenshot("CanvasLoopLiveTest-1-loop")

        // It goes round, covered, and keeps drawing; the controls fade meanwhile.
        val watch = (args.getString("seconds")?.toLongOrNull() ?: 40L) * 1000
        val framesAt = CanvasStats.frames
        delay(6_000)
        Fixture.screenshot("CanvasLoopLiveTest-2-controls-away")
        delay(watch - 6_000)
        val rounds = CanvasStats.rounds - before.first
        android.util.Log.i("CanvasLoop", "rounds $rounds, covered ${CanvasStats.covered}, frames ${CanvasStats.frames - framesAt}, errors ${CanvasStats.errors - before.second} ${CanvasStats.lastError}")
        assertEquals("nothing failed (${CanvasStats.lastError})", before.second, CanvasStats.errors)
        assertTrue("the loop went round ($rounds times in ${watch / 1000} s)", rounds >= 2)
        assertTrue("frames kept coming (${CanvasStats.frames - framesAt})", CanvasStats.frames - framesAt > watch / 1000 * 10)
        assertTrue("its first frame covered the restarts after the first (${CanvasStats.covered} of $rounds)", CanvasStats.covered >= rounds - 1)

        // A tap brings the controls back.
        val device = UiDevice.getInstance(instrumentation)
        device.click(device.displayWidth / 2, device.displayHeight / 3)
        delay(700)
        Fixture.screenshot("CanvasLoopLiveTest-3-controls-back")
    }
}
