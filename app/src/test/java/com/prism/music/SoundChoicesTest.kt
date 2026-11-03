package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AutoplaySource
import com.prism.music.data.prefs.GlassKind
import com.prism.music.ui.screens.Crossfade
import com.prism.music.ui.screens.GlassChoice
import com.prism.music.ui.screens.LookChoices
import com.prism.music.ui.screens.ScreenChange
import com.prism.music.ui.screens.QueueEnd
import com.prism.music.ui.screens.SoundChoices
import com.prism.music.ui.screens.TrimSilence
import com.prism.music.ui.screens.VideoPolicy
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.0 (#210): Playback & sound's merged choices are faces over the settings Prism already keeps. Every old combination
 * of those settings shows as the right choice (nobody's setup changes on the update), and every choice written reads
 * back as itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SoundChoicesTest {
    private val base = AppSettings()

    /**
     * A merged choice is two or three writes; quick taps one after another land in the order they were made (the
     * repository writes one at a time). Everywhere then Play once read back as Radios when the writes interleaved.
     */
    @Test fun quickChoicesLandInOrder() {
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        val was = kotlinx.coroutines.runBlocking { repo.flow.first() }
        try {
            repeat(20) {
                SoundChoices.setVideoPolicy(repo, VideoPolicy.EVERYWHERE)
                SoundChoices.setVideoPolicy(repo, VideoPolicy.PLAY)
                SoundChoices.setTrimSilence(repo, TrimSilence.ALL)
                SoundChoices.setTrimSilence(repo, TrimSilence.PODCASTS)
            }
            val settled = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    repo.flow.first { SoundChoices.videoPolicy(it) == VideoPolicy.PLAY && SoundChoices.trimSilence(it) == TrimSilence.PODCASTS }
                }
            }
            // Nothing written after it: still there a moment later.
            Thread.sleep(300)
            val now = kotlinx.coroutines.runBlocking { repo.flow.first() }
            assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(now))
            assertEquals(TrimSilence.PODCASTS, SoundChoices.trimSilence(now))
            assertEquals(SoundChoices.videoPolicy(settled), SoundChoices.videoPolicy(now))
        } finally {
            SoundChoices.setVideoPolicy(repo, SoundChoices.videoPolicy(was))
            SoundChoices.setTrimSilence(repo, SoundChoices.trimSilence(was))
            Thread.sleep(300)
        }
    }

    @Test fun whenTheQueueEndsFollowsAutoplayAndItsSource() {
        assertEquals(QueueEnd.STOP, SoundChoices.queueEnd(base.copy(autoplay = false, autoplaySource = AutoplaySource.YOURS)))
        assertEquals(QueueEnd.YOURS, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.YOURS)))
        assertEquals(QueueEnd.MIXED, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.MIXED)))
        assertEquals(QueueEnd.YOUTUBE, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.YOUTUBE)))
        for (q in QueueEnd.entries) {
            val (on, source) = SoundChoices.queueEndValues(q)
            val after = base.copy(autoplay = on, autoplaySource = source ?: base.autoplaySource)
            assertEquals(q, SoundChoices.queueEnd(after))
        }
        // Stop keeps the source, so turning autoplay back on gives the old one.
        assertEquals(null, SoundChoices.queueEndValues(QueueEnd.STOP).second)
    }

    @Test fun musicVideosFollowSongsNotVideosAndEverywhere() {
        assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(base.copy(radioSongsOnly = false, songsEverywhere = false)))
        // An old "Everywhere" left on under a switched-off "Songs, not videos" did nothing: it still reads as Play them.
        assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(base.copy(radioSongsOnly = false, songsEverywhere = true)))
        assertEquals(VideoPolicy.RADIOS, SoundChoices.videoPolicy(base.copy(radioSongsOnly = true, songsEverywhere = false)))
        assertEquals(VideoPolicy.EVERYWHERE, SoundChoices.videoPolicy(base.copy(radioSongsOnly = true, songsEverywhere = true)))
        for (v in VideoPolicy.entries) {
            val (radios, everywhere) = SoundChoices.videoPolicyValues(v)
            assertEquals(v, SoundChoices.videoPolicy(base.copy(radioSongsOnly = radios, songsEverywhere = everywhere)))
        }
    }

    @Test fun crossfadeFollowsMixAndAutomaticLength() {
        assertEquals(Crossfade.OFF, SoundChoices.crossfade(base.copy(mix = false, mixAuto = true)))
        assertEquals(Crossfade.OFF, SoundChoices.crossfade(base.copy(mix = false, mixAuto = false)))
        assertEquals(Crossfade.AUTO, SoundChoices.crossfade(base.copy(mix = true, mixAuto = true)))
        assertEquals(Crossfade.FIXED, SoundChoices.crossfade(base.copy(mix = true, mixAuto = false)))
        for (x in Crossfade.entries) {
            val (on, auto) = SoundChoices.crossfadeValues(x)
            assertEquals(x, SoundChoices.crossfade(base.copy(mix = on, mixAuto = auto ?: base.mixAuto)))
        }
        // Off keeps automatic/fixed, so turning crossfade back on gives the old way.
        assertEquals(null, SoundChoices.crossfadeValues(Crossfade.OFF).second)
    }

    @Test fun homeTopButtonsFollowBothSwitches() {
        fun s(p: Boolean, cz: Boolean) = base.copy(homePodcastsButton = p, homeCustomizeTopButton = cz)
        assertEquals(com.prism.music.ui.screens.TopButtons.BOTH, com.prism.music.ui.screens.HomeChoices.topButtons(s(true, true)))
        assertEquals(com.prism.music.ui.screens.TopButtons.PODCASTS, com.prism.music.ui.screens.HomeChoices.topButtons(s(true, false)))
        assertEquals(com.prism.music.ui.screens.TopButtons.CUSTOMIZE, com.prism.music.ui.screens.HomeChoices.topButtons(s(false, true)))
        assertEquals(com.prism.music.ui.screens.TopButtons.NONE, com.prism.music.ui.screens.HomeChoices.topButtons(s(false, false)))
        for (t in com.prism.music.ui.screens.TopButtons.entries) {
            val (p, cz) = com.prism.music.ui.screens.HomeChoices.topButtonsValues(t)
            assertEquals(t, com.prism.music.ui.screens.HomeChoices.topButtons(s(p, cz)))
        }
    }

    @Test fun glassReadsAsGlassPathDoes() {
        fun s(liquid: Boolean, kind: GlassKind, frosted: Boolean) = base.copy(liquidGlass = liquid, glassKind = kind, frostedGlass = frosted)
        assertEquals(GlassChoice.OFF, LookChoices.glass(s(false, GlassKind.REGULAR, false)))
        assertEquals(GlassChoice.FROSTED, LookChoices.glass(s(false, GlassKind.CLEAR, true)))
        // Liquid wins over frosted, as glassPath does.
        assertEquals(GlassChoice.LIQUID_REGULAR, LookChoices.glass(s(true, GlassKind.REGULAR, true)))
        assertEquals(GlassChoice.LIQUID_CLEAR, LookChoices.glass(s(true, GlassKind.CLEAR, false)))
        for (g in GlassChoice.entries) {
            val v = LookChoices.glassValues(g)
            assertEquals(g, LookChoices.glass(s(v.liquid, v.kind ?: GlassKind.REGULAR, v.frosted ?: false)))
        }
    }

    @Test fun changingScreensFollowsFadesAndSlide() {
        fun s(fades: Boolean, slide: Boolean) = base.copy(behavior = base.behavior.copy(screenFades = fades, pageSlide = slide))
        assertEquals(ScreenChange.NONE, LookChoices.screenChange(s(false, false)))
        // Slide never showed with fades off: still None.
        assertEquals(ScreenChange.NONE, LookChoices.screenChange(s(false, true)))
        assertEquals(ScreenChange.FADE, LookChoices.screenChange(s(true, false)))
        assertEquals(ScreenChange.SLIDE, LookChoices.screenChange(s(true, true)))
        for (c in ScreenChange.entries) {
            val (fades, slide) = LookChoices.screenChangeValues(c)
            assertEquals(c, LookChoices.screenChange(s(fades, slide)))
        }
    }

    @Test fun trimSilenceFollowsSongsAndPodcasts() {
        fun s(songs: Boolean, episodes: Boolean) = base.copy(skipSilence = songs, sound = base.sound.copy(skipSilenceEpisodes = episodes))
        assertEquals(TrimSilence.OFF, SoundChoices.trimSilence(s(false, false)))
        assertEquals(TrimSilence.PODCASTS, SoundChoices.trimSilence(s(false, true)))
        assertEquals(TrimSilence.ALL, SoundChoices.trimSilence(s(true, true)))
        // Songs on with podcasts off (possible before 1.8.0) reads as both: what songs got stays the same.
        assertEquals(TrimSilence.ALL, SoundChoices.trimSilence(s(true, false)))
        for (t in TrimSilence.entries) {
            val (songs, episodes) = SoundChoices.trimSilenceValues(t)
            assertEquals(t, SoundChoices.trimSilence(s(songs, episodes)))
        }
    }
}
