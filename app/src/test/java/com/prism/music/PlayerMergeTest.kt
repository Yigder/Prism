package com.prism.music

import com.prism.music.data.prefs.CanvasKinds
import com.prism.music.data.prefs.CanvasStored
import com.prism.music.data.prefs.LyricsAlign
import com.prism.music.ui.screens.settings.CoverAnimation
import com.prism.music.ui.screens.settings.EdgeToEdge
import com.prism.music.ui.screens.settings.LineOnPlayer
import com.prism.music.ui.screens.settings.PanelChip
import com.prism.music.ui.screens.settings.QueueChip
import com.prism.music.ui.screens.settings.VideoControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.4, Settings → Player's merged controls (plan §2.5): every combination of the stored values that was possible
 * before maps to the one control and back unchanged, and every value of the control can be picked from any of them.
 */
class PlayerMergeTest {
    private val bools = listOf(false, true)
    private val pairs = bools.flatMap { a -> bools.map { b -> a to b } }

    private fun flags(n: Int): List<List<Boolean>> = (0 until (1 shl n)).map { bits -> List(n) { i -> bits shr i and 1 == 1 } }

    @Test fun coverAnimationKeepsAllFourStoredCombinations() {
        assertEquals(4, pairs.size)
        for (stored in pairs) assertEquals("$stored", stored, CoverAnimation.toMerged(stored).toStored(stored))
        assertEquals(CoverAnimation.OFF, CoverAnimation.toMerged(false to false))
        assertEquals(CoverAnimation.OPENING, CoverAnimation.toMerged(true to false))
        assertEquals(CoverAnimation.OPENING_AND_CLOSING, CoverAnimation.toMerged(true to true))
        // "Back" stored without "flight" did nothing before, and shows as Off.
        assertEquals(CoverAnimation.OFF, CoverAnimation.toMerged(false to true))
        // Any value can be picked from any stored combination, and is then what the control shows.
        for (was in pairs) for (pick in CoverAnimation.entries) assertEquals("$was → $pick", pick, CoverAnimation.toMerged(pick.toStored(was)))
        assertEquals(true to false, CoverAnimation.OPENING.toStored(false to true))
    }

    @Test fun edgeToEdgeIsTheFourCombinations() {
        for (stored in pairs) assertEquals("$stored", stored, EdgeToEdge.toMerged(stored).toStored())
        assertEquals(EdgeToEdge.entries.toSet(), pairs.map { EdgeToEdge.toMerged(it) }.toSet())
        assertEquals(EdgeToEdge.OFF, EdgeToEdge.toMerged(false to false))
        assertEquals(EdgeToEdge.COVER, EdgeToEdge.toMerged(true to false))
        assertEquals(EdgeToEdge.VIDEO, EdgeToEdge.toMerged(false to true))
        assertEquals(EdgeToEdge.BOTH, EdgeToEdge.toMerged(true to true))
    }

    /** Owner, 2026-11-13: Animated covers and Canvas, two switches over the three stored flags (enabled, covers, videos). */
    @Test fun animatedCoversAndCanvasOverAllEightStoredCombinations() {
        val triples = flags(3).map { CanvasStored(it[0], it[1], it[2]) }
        assertEquals(8, triples.size)
        val kinds = pairs.map { CanvasKinds(it.first, it.second) }
        for (was in triples) {
            // Shown: each on only while Canvas as a whole is on.
            val shown = CanvasKinds.shown(was)
            assertEquals("$was", CanvasKinds(was.enabled && was.covers, was.enabled && was.videos), shown)
            // Storing what is shown changes nothing that shows; with something on, nothing stored at all.
            assertEquals("$was", shown, CanvasKinds.shown(shown.toStored(was)))
            if (shown.any) assertEquals("$was", was, shown.toStored(was))
            for (want in kinds) {
                val to = want.toStored(was)
                // Any pair can be had from any stored combination, and is then what shows; the rule holds.
                assertEquals("$was → $want", want, CanvasKinds.shown(to))
                assertEquals("$was → $want", want.covers || want.videos, to.enabled)
                // Both off: Canvas off, the two flags as they were.
                if (!want.any) assertEquals("$was → $want", was.copy(enabled = false), to)
                else assertEquals("$was → $want", CanvasStored(true, want.covers, want.videos), to)
            }
        }
        // Stored off with both flags on (Canvas switched off before 1.8.6): both show off.
        val offBoth = CanvasStored(false, true, true)
        assertEquals(CanvasKinds(false, false), CanvasKinds.shown(offBoth))
        // Animated covers on from there: the loops stay off.
        assertEquals(CanvasStored(true, true, false), CanvasKinds(true, false).toStored(offBoth))
        // The last one off turns Canvas off.
        assertEquals(CanvasStored(false, true, false), CanvasKinds(false, false).toStored(CanvasStored(true, true, false)))
        assertEquals(CanvasStored(false, false, true), CanvasKinds(false, false).toStored(CanvasStored(true, false, true)))
    }

    @Test fun controlsOverVideosKeepsTheFourthCombination() {
        for (stored in pairs) assertEquals("$stored", stored, VideoControls.toMerged(stored).toStored())
        assertEquals(VideoControls.ALWAYS, VideoControls.toMerged(false to false))
        assertEquals(VideoControls.HIDE_CONTROLS, VideoControls.toMerged(true to false))
        assertEquals(VideoControls.HIDE_CONTROLS_AND_BUTTONS, VideoControls.toMerged(true to true))
        // The buttons wait, the controls stay: neither of the plan's three, kept as it is and said in its own words.
        val fourth = VideoControls.toMerged(false to true)
        assertEquals(VideoControls.BUTTONS_WAIT, fourth)
        assertEquals(false to true, fourth.toStored())
        // It is only offered while it is the one stored; the plan's three always are.
        val three = listOf(VideoControls.ALWAYS, VideoControls.HIDE_CONTROLS, VideoControls.HIDE_CONTROLS_AND_BUTTONS)
        for (now in three) assertEquals(three, VideoControls.choices(now))
        assertEquals(three + fourth, VideoControls.choices(fourth))
    }

    @Test fun theQueueChipsAreTheFiveSwitches() {
        val all = flags(5)
        assertEquals(32, all.size)
        for (stored in all) assertEquals("$stored", stored, QueueChip.toStored(QueueChip.toMerged(stored)))
        assertEquals(32, all.map { QueueChip.toMerged(it) }.toSet().size)
        assertEquals(setOf(QueueChip.HEADINGS, QueueChip.DJ), QueueChip.toMerged(listOf(true, true, false, false, false)))
    }

    @Test fun theLyricsAndQueueChipsAreTheThreeSwitches() {
        val all = flags(3)
        assertEquals(8, all.size)
        for (stored in all) assertEquals("$stored", stored, PanelChip.toStored(PanelChip.toMerged(stored)))
        assertEquals(8, all.map { PanelChip.toMerged(it) }.toSet().size)
    }

    @Test fun theLineOnThePlayerIsOnePickOfAlignmentAndLines() {
        val stored = LyricsAlign.entries.flatMap { a -> bools.map { a to it } }
        assertEquals(4, stored.size)
        for (s in stored) assertEquals("$s", s, LineOnPlayer.toMerged(s).toStored())
        assertEquals(LineOnPlayer.all.toSet(), stored.map { LineOnPlayer.toMerged(it) }.toSet())
        assertTrue(LineOnPlayer.all.all { it.lines in 1..2 })
    }
}
