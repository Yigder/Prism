package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.screens.settings.MobileDataStored
import com.prism.music.ui.screens.settings.MobileUse
import com.prism.music.ui.screens.settings.mobileDataToMerged
import com.prism.music.ui.screens.settings.mobileDataToStored
import com.prism.music.ui.screens.settings.mobileUsesShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, Downloads & data → "Use mobile data for" (plan §2.5): one row of chips over the eight "… on mobile data"
 * switches of before. Every combination of the eight stored values maps to the chips and back unchanged, a chip is one
 * stored switch, and the chips that only show under another switch keep what they hold while hidden. Data saver is not
 * one of them (the ledger: a mode, its own switch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DownloadsDataMergeTest {
    private fun stored(bits: Int) = MobileDataStored(
        noVideoOnData = bits and 1 != 0, preloadCellular = bits and 2 != 0, smartDownloadsCellular = bits and 4 != 0, lyricsOnMobileData = bits and 8 != 0,
        canvasOnCellular = bits and 16 != 0, canvasWithDownloadsCellular = bits and 32 != 0, mixTempoOnCellular = bits and 64 != 0, singOnCellular = bits and 128 != 0,
    )

    @Test fun everyStoredCombinationGoesToTheChipsAndBackUnchanged() {
        val seen = HashSet<Set<MobileUse>>()
        for (bits in 0 until 256) {
            val before = stored(bits)
            val chips = mobileDataToMerged(before)
            assertEquals("stored combination $bits", before, mobileDataToStored(chips))
            seen += chips
        }
        assertEquals("256 stored combinations are 256 different sets of chips", 256, seen.size)
    }

    @Test fun everySetOfChipsGoesToTheStoreAndBackUnchanged() {
        val all = MobileUse.entries
        assertEquals(8, all.size)
        for (bits in 0 until 256) {
            val chips = all.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
            assertEquals(chips, mobileDataToMerged(mobileDataToStored(chips)))
        }
    }

    /** One chip, one stored switch: ticking a chip changes that switch and no other. "Videos" is stored the other way round. */
    @Test fun aChipIsOneStoredSwitch() {
        val none = mobileDataToStored(emptySet())
        assertEquals(MobileDataStored(true, false, false, false, false, false, false, false), none)
        assertEquals(none.copy(noVideoOnData = false), mobileDataToStored(setOf(MobileUse.VIDEOS)))
        assertEquals(none.copy(preloadCellular = true), mobileDataToStored(setOf(MobileUse.LOAD_AHEAD)))
        assertEquals(none.copy(smartDownloadsCellular = true), mobileDataToStored(setOf(MobileUse.SMART_DOWNLOADS)))
        assertEquals(none.copy(lyricsOnMobileData = true), mobileDataToStored(setOf(MobileUse.LYRICS)))
        assertEquals(none.copy(canvasOnCellular = true), mobileDataToStored(setOf(MobileUse.ANIMATED_COVERS)))
        assertEquals(none.copy(canvasWithDownloadsCellular = true), mobileDataToStored(setOf(MobileUse.SAVING_COVERS)))
        assertEquals(none.copy(mixTempoOnCellular = true), mobileDataToStored(setOf(MobileUse.MEASURING)))
        assertEquals(none.copy(singOnCellular = true), mobileDataToStored(setOf(MobileUse.SING)))
    }

    /** As Prism comes: lyrics and animated covers may use mobile data, videos aren't held back, the rest wait for Wi-Fi. */
    @Test fun aFreshInstallReadsAsBefore() {
        val d = AppSettings()
        assertEquals(setOf(MobileUse.VIDEOS, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS), mobileDataToMerged(MobileDataStored.of(d)))
        // Data saver and Smart Downloads are off: their two chips aren't shown (as their switches weren't).
        assertEquals(
            listOf(MobileUse.LOAD_AHEAD, MobileUse.LYRICS, MobileUse.ANIMATED_COVERS, MobileUse.SAVING_COVERS, MobileUse.MEASURING, MobileUse.SING),
            mobileUsesShown(d),
        )
    }

    /** Each of the five conditional chips shows exactly when its old switch did. */
    @Test fun aChipShowsOnlyWhileItsSwitchDid() {
        val d = AppSettings()
        fun shown(s: AppSettings, use: MobileUse) = use in mobileUsesShown(s)
        for (use in listOf(MobileUse.LOAD_AHEAD, MobileUse.LYRICS, MobileUse.SING)) {
            assertTrue(shown(d.copy(dataSaver = true, smartDownloads = true), use))
            assertTrue(shown(d.copy(canvasEnabled = false, canvasWithDownloads = false, normalize = false, mix = false), use))
        }
        assertFalse(shown(d.copy(dataSaver = false), MobileUse.VIDEOS))
        assertTrue(shown(d.copy(dataSaver = true), MobileUse.VIDEOS))
        assertFalse(shown(d.copy(smartDownloads = false), MobileUse.SMART_DOWNLOADS))
        assertTrue(shown(d.copy(smartDownloads = true), MobileUse.SMART_DOWNLOADS))
        assertFalse(shown(d.copy(canvasEnabled = false), MobileUse.ANIMATED_COVERS))
        assertTrue(shown(d.copy(canvasEnabled = true), MobileUse.ANIMATED_COVERS))
        assertFalse(shown(d.copy(canvasEnabled = true, canvasWithDownloads = false), MobileUse.SAVING_COVERS))
        assertFalse(shown(d.copy(canvasEnabled = false, canvasWithDownloads = true), MobileUse.SAVING_COVERS))
        assertTrue(shown(d.copy(canvasEnabled = true, canvasWithDownloads = true), MobileUse.SAVING_COVERS))
        assertFalse(shown(d.copy(normalize = false, mix = false), MobileUse.MEASURING))
        assertTrue(shown(d.copy(normalize = true, mix = false), MobileUse.MEASURING))
        assertTrue(shown(d.copy(normalize = false, mix = true), MobileUse.MEASURING))
        // 1.8.6: loops from music videos on mobile data is not a chip (its switch is in Canvas's sheet, Player page).
        assertTrue(MobileUse.entries.none { it.label == tr(R.string.canvas_from_videos) })
    }

    /** Data saver is a mode beside the row, not a chip: no chip reads it or writes it. */
    @Test fun dataSaverIsNotAChip() {
        val d = AppSettings()
        assertEquals(mobileDataToMerged(MobileDataStored.of(d.copy(dataSaver = false))), mobileDataToMerged(MobileDataStored.of(d.copy(dataSaver = true))))
        assertTrue(MobileUse.entries.none { it.label == tr(R.string.data_saver) })
    }
}
