package com.prism.music

import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.PlaybackServices
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9's night check on the owner's phone: Android took the audio decoder away mid-song (screen off, Prism in the
 * background) and Prism skipped to the next song. A lost decoder now plays the same song on from where it stopped.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryTest {
    private val tones = mutableListOf<android.net.Uri>()

    @After fun cleanUp() {
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { Fixture.container.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    @Test fun aLostDecoderPlaysTheSameSongOn() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Prism recovery tone one", 90)
        tones += Fixture.tone("Prism recovery tone two", 90)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val songs = listOf("one", "two").map { w -> local.tracks.value.first { it.song.title == "Prism recovery tone $w" }.song }

        val player = Fixture.container.player
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Recovery test")) }
        waitFor(20_000) { PlaybackServices.failForTest != null && player.currentSong.value?.id == songs[0].id && position(player) > 3_000 }
        val before = position(player)

        // What the phone's codec release gave the player: a decoding failure whose cause is the codec gone.
        val released = IllegalStateException("releaseOutputBuffer() is valid only at Executing states; currently at Released state")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PlaybackServices.failForTest!!.invoke(PlaybackException("Audio codec error", released, PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
        Thread.sleep(3_000)
        assertEquals("the song was skipped", songs[0].id, player.currentSong.value?.id)
        val after = position(player)
        assertTrue("it started over or went back (${before} → ${after} ms)", after >= before - 1_000)
    }

    private fun position(player: com.prism.music.playback.PlayerConnection): Long {
        var p = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync { p = player.position }
        return p
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not ready after $timeoutMs ms")
            Thread.sleep(200)
        }
    }
}
