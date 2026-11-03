package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.ui.tv.TAG_ACTIONS
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_PLAYER
import com.prism.music.ui.tv.TAG_SETTINGS
import com.prism.music.ui.tv.TAG_SING
import com.prism.music.ui.tv.TvSection
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sing on more TVs (1.8.x): a TV whose check found it too slow for Sing's separation, or whose processor Sing's engine
 * doesn't run on, is offered the quick way, said plainly in Settings → Sing, rather than told it can't sing.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSingTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun setUp() { TvFixture.assumeTv() }

    private val inNav = androidx.compose.ui.test.SemanticsMatcher("in the side navigation") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("$TAG_NAV/") == true
    }

    @Test fun aTvTooSlowForTheSeparationIsOfferedTheQuickWay() {
        Fixture.ready()
        val noEngine = com.prism.music.data.ModelStore.Model.singRuntime() == null
        // An earlier check's answer: too slow (on a TV the engine doesn't run on, that's the answer without a check).
        if (!noEngine) Fixture.context.getSharedPreferences("tv_sing", android.content.Context.MODE_PRIVATE).edit().putString("state", "SLOW").commit()
        TvFixture.launch()
        compose.awaitFocus(15_000)
        for (i in 0 until 6) {
            if (compose.onAllNodes(inNav and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
            TvFixture.left(); Thread.sleep(300)
        }
        repeat(TvSection.SETTINGS.ordinal) { TvFixture.down(); Thread.sleep(200) }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${TvSection.SETTINGS.name}"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        // Down to Sing: Account, Language, Sound, Lyrics, Sing.
        repeat(4) { TvFixture.down(); Thread.sleep(250) }
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/4"))
        val answer = tr(if (noEngine) R.string.tv_sing_no_cpu else R.string.tv_sing_too_slow)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(answer, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // And Sing is offered: it's in the player's ⋯ for both answers.
        org.junit.Assert.assertTrue(com.prism.music.ui.tv.TvSing.state.value.sings)
    }

    /**
     * The owner: Sing on the TV "just doesn't do anything". From the player's ⋯, Sing's page: Sing on, a line saying
     * how the song's got ready (or why not), and Right from the lyrics reaches the key's − and +, which move the key.
     */
    @Test fun singsPageSaysWhatItsDoingAndItsKeyIsReached() {
        Fixture.ready()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val c = Fixture.container
        val noEngine = com.prism.music.data.ModelStore.Model.singRuntime() == null
        if (!noEngine) Fixture.context.getSharedPreferences("tv_sing", android.content.Context.MODE_PRIVATE).edit().putString("state", "SLOW").commit()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        val tone = Fixture.tone("Prism TV sing tone", 90)
        try {
            runBlocking { c.localMusic.scan() }
            val song = c.localMusic.tracks.value.first { it.song.title == "Prism TV sing tone" }.song
            // Lyrics saved for it (as if found earlier): no network needed.
            val lrc = (0 until 10).joinToString("\n") { i -> "[00:%02d.00]Line number ${i + 1} of the tone".format(i * 8) }
            runBlocking { c.db.lyrics().put(com.prism.music.data.db.LyricsEntity(song.id, "LRCLIB", true, lrc, System.currentTimeMillis())) }
            instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.OTHER, "TV sing test")) }
            val end0 = System.currentTimeMillis() + 20_000
            while (!c.player.isPlaying.value && System.currentTimeMillis() < end0) Thread.sleep(100)
            // Paused: the screen is still while it's looked at.
            instrumentation.runOnMainSync { c.player.setPlaying(false) }
            instrumentation.runOnMainSync { c.sing.setSemitones(0) }
            TvFixture.launch()
            compose.awaitFocusOn(hasTestTag("$TAG_HOME/player"), 20_000)
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/playpause"), 10_000)
            TvFixture.down()
            // Along the row to ⋯.
            for (i in 0 until 8) {
                if (compose.onAllNodes(hasTestTag("$TAG_PLAYER/more") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
                TvFixture.right(); Thread.sleep(250)
            }
            compose.awaitFocusOn(hasTestTag("$TAG_PLAYER/more"))
            TvFixture.ok()
            // A tone has no album or artist page: Sing is the ⋯'s first.
            compose.awaitFocusOn(hasTestTag("$TAG_ACTIONS/0"), 10_000)
            // (The label is a child of the button in the unmerged tree.)
            compose.onNode(hasText(tr(R.string.sing)) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("$TAG_ACTIONS/0")), useUnmergedTree = true).assertExists()
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_LYRICS/lines"), 20_000)
            org.junit.Assert.assertTrue("Sing is on", c.sing.on.value)
            // It says what it's doing.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("$TAG_LYRICS/status"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            // Right: the key's −; OK lowers the key; Right, Right: +, raising it again.
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SING/keydown"))
            TvFixture.ok()
            compose.waitUntil(5_000) { c.sing.semitones.value == -1 }
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SING/keyup"))
            TvFixture.ok(); TvFixture.ok()
            compose.waitUntil(5_000) { c.sing.semitones.value == 1 }
            // Back: Sing off again, the song as it is.
            TvFixture.back()
            compose.waitUntil(10_000) { !c.sing.on.value }
        } finally {
            runCatching { instrumentation.runOnMainSync { c.sing.setSemitones(0); c.player.stop() } }
            runCatching { Fixture.context.contentResolver.delete(tone, null, null) }
        }
    }
}
