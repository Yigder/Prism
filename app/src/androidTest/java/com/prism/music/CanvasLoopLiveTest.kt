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

    /**
     * The long run for heat and battery (`canvas-heat.ps1` reads the phone meanwhile): one song on repeat for
     * `minutes=<n>`, the player open, with `cond=loop` (Full screen, its music video's loop) or `cond=still` (Canvas off).
     */
    @Test fun aLongRunForHeat() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val loop = args.getString("cond") == "loop"
        val minutes = args.getString("minutes")?.toDoubleOrNull() ?: 20.0
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(loop); c.settings.setCanvasFillsBackground(loop); c.settings.setCanvasUseVideos(loop)
        c.settings.setCanvasVideoFirst(true); c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        val words = (args.getString("song") ?: "Flowers_Miley_Cyrus").replace('_', ' ')
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        CanvasStats.showing = null
        val before = CanvasStats.rounds to CanvasStats.errors
        withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        delay(3_000)
        repeat(3) { if (c.player.repeatMode.value != androidx.media3.common.Player.REPEAT_MODE_ONE) { withContext(Dispatchers.Main) { c.player.cycleRepeat() }; delay(700) } }
        val until = System.currentTimeMillis() + (minutes * 60_000).toLong()
        if (loop) while (System.currentTimeMillis() < until && CanvasStats.showing?.startsWith("file://") != true) delay(200)
        android.util.Log.i("CanvasHeat", "start ${if (loop) "loop" else "still"}: \"${song.title}\", repeat ${c.player.repeatMode.value}, showing ${CanvasStats.showing}, row ${c.canvasClips.row(song.id)?.height}p")
        while (System.currentTimeMillis() < until) delay(1_000)
        android.util.Log.i(
            "CanvasHeat",
            "end ${if (loop) "loop" else "still"}: rounds ${CanvasStats.rounds - before.first}, covered ${CanvasStats.covered}, errors ${CanvasStats.errors - before.second} " +
                "(${CanvasStats.lastError}), showing ${CanvasStats.showing}, playing ${c.player.isPlaying.value}",
        )
        if (loop) assertEquals("no loop failed in the long run", before.second, CanvasStats.errors)
    }

    /**
     * The by-eye round on real loops: `songs=Words_of_one+Words_of_another` (underscores for spaces), each played until
     * its loop shows (or 45 s), with three pictures two seconds apart, the controls away. Nothing is asserted but that
     * it ran: the pictures and the log line per song ("CanvasEye") are what is looked at.
     */
    @Test fun manySongsForTheEye() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        val list = args.getString("songs")?.split('+')?.map { it.replace('_', ' ') }?.filter { it.isNotBlank() }
        assumeTrue("give songs=A+B", !list.isNullOrEmpty())
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false); c.settings.setImmersivePlayer(false); c.settings.setFullBleedArtwork(false)
        c.settings.setCanvasEnabled(true); c.settings.setCanvasFillsBackground(true); c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(true); c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        list!!.forEachIndexed { i, words ->
            val song = runCatching { withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song } }.getOrNull()
            if (song == null) { android.util.Log.i("CanvasEye", "$i \"$words\": no song found"); return@forEachIndexed }
            CanvasStats.showing = null
            val asked = System.currentTimeMillis()
            withContext(Dispatchers.Main) { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, "test")) }
            if (i == 0) Fixture.context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                    .setClass(Fixture.context, PrismActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            while (System.currentTimeMillis() < asked + 45_000 && CanvasStats.showing?.startsWith("file://") != true) delay(100)
            val took = System.currentTimeMillis() - asked
            val row = c.canvasClips.row(song.id)
            val shown = CanvasStats.showing?.startsWith("file://") == true
            // A song with no loop: which video was looked at (the row keeps none), to see why by its title.
            if (!shown) android.util.Log.i("CanvasEye", "$i looked at video ${runCatching { withContext(Dispatchers.IO) { c.ytm.findMusicVideo(song) } }.getOrNull()} for song ${song.id}")
            android.util.Log.i(
                "CanvasEye",
                "$i \"${song.title}\" by ${song.artistText}: ${if (shown) "shown after $took ms" else "NO LOOP (${row?.why})"}; video ${row?.videoId} ${row?.videoType} " +
                    "${row?.height}p ${row?.startMs}-${row?.endMs} crop ${row?.cropX} scenes ${row?.crops}; ${c.canvasClips.lastTimes}; ${com.prism.music.data.canvas.ClipDecode.last?.substringBefore(" [")}",
            )
            if (shown) {
                // Past the controls' four seconds, then three moments of the loop.
                delay(5_000)
                for (k in 1..3) { Fixture.screenshot("CanvasEye-${i.toString().padStart(2, '0')}-$k"); delay(2_000) }
            } else Fixture.screenshot("CanvasEye-${i.toString().padStart(2, '0')}-none")
        }
    }

    @Test fun aSongsMusicVideoLoopsBehindThePlayer() = runBlocking {
        assumeTrue("live test: run with record=true", args.getString("record") == "true")
        Fixture.ready()
        val c = Fixture.container
        c.settings.setPerformanceMode(false)
        c.settings.setReduceMotion(false)
        c.settings.setImmersivePlayer(false)
        c.settings.setFullBleedArtwork(args.getString("bleed") == "true")
        c.settings.setCanvasEnabled(true)
        c.settings.setCanvasFillsBackground(true)
        c.settings.setCanvasUseVideos(true)
        c.settings.setCanvasVideoFirst(true)
        c.settings.setCanvasKeepControls(false)
        Fixture.launch()
        val words = args.getString("song") ?: "Rick Astley Never Gonna Give You Up"
        val song = withContext(Dispatchers.IO) { c.ytm.search(words, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }
        val before = CanvasStats.rounds to CanvasStats.errors
        val played = System.currentTimeMillis()
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
        android.util.Log.i("CanvasLoop", "times: ${c.canvasClips.lastTimes}; asked ${asked - played} ms after play")
        android.util.Log.i("CanvasLoop", "second look:${com.prism.music.data.canvas.ClipDecode.last}; kept ${c.clipFiles.recentBytes / 1024} KB")
        assertTrue("the loop was looked at on its real frames (${com.prism.music.data.canvas.ClipDecode.last})", row?.refined == true)
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
