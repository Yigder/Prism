package com.prism.music.ui.player

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.AppContainer
import com.prism.music.closeAfterTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.8.6 Canvas, seen once on the A16 (canvas-next-take1): song A's loop full screen, Next, song B's loop starts, and
 * when B's loop first went round (~8 s later) a still frame of A stood over it. Next takes A's canvas off the screen
 * while B's is looked up, so A's player is parked still holding A's clip; B's canvas then takes that same player. When
 * B's new video view gets the player, the player draws A's frame onto it and says "first frame rendered"; that word
 * reaches the app after B's clip was set, so [CanvasPlayer]'s check (the player's current item is B) lets it through, and
 * A's frame is kept as B's restart picture ([RestartCover]), shown at each of B's restarts.
 *
 * A parked player handed out for another clip must therefore hold nothing of the clip before: stopped, so it can draw
 * no frame of it (and send no word about one) once it has a surface again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CanvasPlayersHandOverTest {
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made
    private var player: ExoPlayer? = null
    @After fun close() { player?.release(); made.closeAfterTest() }

    private val songA = "file:///data/canvas/clips/aaaa.mp4#12000-20000"
    private val songB = "file:///data/canvas/clips/bbbb.mp4#30000-38000"

    private fun parkedWith(url: String): CanvasPlayers.Held {
        val app = RuntimeEnvironment.getApplication()
        requireNotNull(made.value) // made before the player, so nothing it sets up runs between parking and taking
        val p =ExoPlayer.Builder(app).build().also { player = it }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        val held = CanvasPlayers.Held(p, CanvasPlayers.Source().apply { clip = url })
        // The canvas leaves the screen (Next: the old song's canvas goes while the new one's is looked up).
        CanvasPlayers.give(held)
        return held
    }

    @Test fun aPlayerHandedToAnotherClipHoldsNothingOfTheOldOne() {
        val parked = parkedWith(songA)
        val taken = CanvasPlayers.take(RuntimeEnvironment.getApplication(), c, songB)
        assertSame("the parked player is reused (#343)", parked, taken)
        assertEquals(
            "stopped: nothing of song A's clip is left to draw on song B's new video view",
            Player.STATE_IDLE, taken.player.playbackState,
        )
    }

    @Test fun thePlayerOpenedAgainOnItsOwnClipCarriesOn() {
        val parked = parkedWith(songA)
        val taken = CanvasPlayers.take(RuntimeEnvironment.getApplication(), c, songA)
        assertSame(parked, taken)
        assertEquals("#343: the same clip goes on, no new loading or decoder", songA, taken.player.currentMediaItem?.localConfiguration?.uri?.toString())
        assertNotEquals("the same clip is not stopped", Player.STATE_IDLE, taken.player.playbackState)
    }
}
