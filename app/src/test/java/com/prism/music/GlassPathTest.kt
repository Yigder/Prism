package com.prism.music

import com.prism.music.ui.theme.GlassPath
import com.prism.music.ui.theme.glassPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.7.9.2 (1.7.9's check, Android 9): Liquid Glass needs Android 12's blur. Below it, Clear glass showed Home's tiles
 * sharp through the tab bar and mini player; it's drawn frosted there now. Android 12+ is unchanged.
 */
class GlassPathTest {
    @Test fun liquidGlassWithBlur() = assertEquals(GlassPath.LIQUID, glassPath(31, liquid = true, frosted = false, haze = true))

    @Test fun liquidGlassBelowAndroid12IsFrosted() {
        for (sdk in 26..30) assertEquals("sdk $sdk", GlassPath.FROSTED, glassPath(sdk, liquid = true, frosted = false, haze = true))
    }

    @Test fun theRestIsAsBefore() {
        assertEquals(GlassPath.FROSTED, glassPath(28, liquid = false, frosted = true, haze = true))
        assertEquals(GlassPath.FROSTED, glassPath(34, liquid = false, frosted = true, haze = true))
        assertEquals(GlassPath.SOLID, glassPath(34, liquid = false, frosted = false, haze = true))
        assertEquals(GlassPath.SOLID, glassPath(34, liquid = true, frosted = true, haze = false))
    }
}
