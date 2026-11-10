package com.prism.music

import com.prism.music.data.meta.ArtistCountryResolver
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArtistCountryResolverTest {

    @Test
    fun flagEmojiGeneratesRegionalIndicators() {
        assertEquals("🇺🇸", ArtistCountryResolver.flagEmoji("US"))
        assertEquals("🇬🇧", ArtistCountryResolver.flagEmoji("GB"))
        assertEquals("🇹🇷", ArtistCountryResolver.flagEmoji("TR"))
        assertEquals("🇯🇵", ArtistCountryResolver.flagEmoji("JP"))
        assertEquals("", ArtistCountryResolver.flagEmoji(""))
        assertEquals("", ArtistCountryResolver.flagEmoji("USA"))
        assertEquals("", ArtistCountryResolver.flagEmoji("12"))
    }

    @Test
    fun countryCodeFromNameMatchesCommonCountries() {
        assertEquals("US", ArtistCountryResolver.countryCodeFromName("United States"))
        assertEquals("GB", ArtistCountryResolver.countryCodeFromName("United Kingdom"))
        assertEquals("DE", ArtistCountryResolver.countryCodeFromName("Germany"))
        assertEquals("FR", ArtistCountryResolver.countryCodeFromName("France"))
        assertEquals("JP", ArtistCountryResolver.countryCodeFromName("Japan"))
    }

    @Test
    fun demonymsCoverMajorMusicMarkets() {
        val map = ArtistCountryResolver.DEMONYMS
        assertEquals("US", map["american"])
        assertEquals("GB", map["british"])
        assertEquals("TR", map["turkish"])
        assertEquals("JP", map["japanese"])
        assertEquals("KR", map["korean"])
        assertEquals("FR", map["french"])
        assertEquals("DE", map["german"])
    }

    @Test
    fun cacheSavesAndReloadsAcrossInstances() {
        val dir = Files.createTempDirectory("country_test").toFile()
        try {
            val file = java.io.File(dir, "artist_countries.json")
            file.writeText("""{"coldplay": "GB", "taylor swift": "US", "tarkan": "TR"}""")

            val resolver = ArtistCountryResolver(dir, OkHttpClient())
            assertEquals("United Kingdom", resolver.countryName("GB"))
            assertEquals("🇺🇸", resolver.flagEmoji("US"))

            // Verify disk cache read
            val json = file.readText()
            assertTrue(json.contains("coldplay"))
            assertTrue(json.contains("taylor swift"))
            assertTrue(json.contains("tarkan"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
