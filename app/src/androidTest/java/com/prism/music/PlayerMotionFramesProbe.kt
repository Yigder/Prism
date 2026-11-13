package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.6, a new install's motion (the cover flying between the mini player and the player, the player's background fading
 * into the next cover): what they cost in frames on this phone, off / on / on / off (both orders), from `dumpsys gfxinfo`
 * over the same openings, closings and skips. Real songs with real covers, paused and muted. Numbers to logcat
 * (PrismMotion). Run with record=true.
 */
@RunWith(AndroidJUnit4::class)
class PlayerMotionFramesProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val c get() = Fixture.container

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }

    @After fun cleanUp() { runCatching { main { c.player.stop() } } }

    private fun open() = Fixture.context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
            .setClass(Fixture.context, PrismActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private fun round(label: String, on: Boolean): String {
        runBlocking { c.settings.setCoverFlight(on); c.settings.setCoverFlightBack(on); c.settings.setBackdropFade(on) }
        Thread.sleep(1_500)
        val device = UiDevice.getInstance(instrumentation)
        val pkg = Fixture.context.packageName
        shell("dumpsys gfxinfo $pkg reset")
        repeat(6) {
            open(); Thread.sleep(1_600)
            Thread { device.pressBack() }.start(); Thread.sleep(1_600)
        }
        open(); Thread.sleep(1_600)
        repeat(6) { main { c.player.next() }; Thread.sleep(1_800) }
        Thread { device.pressBack() }.start(); Thread.sleep(1_600)
        val g = shell("dumpsys gfxinfo $pkg")
        fun num(name: String) = Regex("$name\\s*:\\s*([\\d.]+)ms").find(g)?.groupValues?.get(1) ?: "?"
        val frames = Regex("Total frames rendered:\\s*(\\d+)").find(g)?.groupValues?.get(1) ?: "?"
        val janky = Regex("Janky frames:\\s*(\\d+)\\s*\\(([\\d.]+)%\\)").find(g)?.let { "${it.groupValues[1]} (${it.groupValues[2]}%)" } ?: "?"
        return "%-8s frames %5s  p50 %s  p90 %s  p95 %s  p99 %s  janky %s".format(
            label, frames, num("50th percentile"), num("90th percentile"), num("95th percentile"), num("99th percentile"), janky,
        )
    }

    @Test fun framesWithAndWithoutTheMotion() = runBlocking<Unit> {
        assumeTrue("live probe: run with record=true", args.getString("record") == "true")
        Fixture.ready()
        shell("cmd media_session volume --stream 3 --set 0")
        // Only the motion differs: Canvas off, so no clip decodes behind either round.
        c.settings.setCanvasEnabled(false); c.settings.setPerformanceMode(false); c.settings.setReduceMotion(false)
        Fixture.launch()
        val songs = withContext(Dispatchers.IO) {
            listOf("Flowers Miley Cyrus", "Blinding Lights The Weeknd", "As It Was Harry Styles", "Espresso Sabrina Carpenter", "Houdini Dua Lipa", "Anti-Hero Taylor Swift")
                .mapNotNull { q -> runCatching { c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().first().song }.getOrNull() }
        }
        assumeTrue("songs found", songs.size >= 4)
        // A long queue of them, so every round's skips meet a new cover.
        main { c.player.playQueue(songs + songs + songs + songs + songs, 0, QueueSource(QueueKind.OTHER, "Motion probe")) }
        Thread.sleep(6_000)
        main { c.player.setPlaying(false) }
        Thread.sleep(1_000)
        // A round to warm everything (covers in memory, the player composed once), not counted.
        round("warm", on = false)
        for ((label, on) in listOf("off-1" to false, "on-1" to true, "on-2" to true, "off-2" to false)) {
            android.util.Log.i("PrismMotion", round(label, on))
        }
    }
}
