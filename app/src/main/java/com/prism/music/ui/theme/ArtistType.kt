package com.prism.music.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * How an artist's name is set on their page. Like Apple Music, headline artists get a
 * signature typeface that suits their music; everyone else gets the standard bold.
 */
data class ArtistType(
    val family: FontFamily,
    val weight: FontWeight = FontWeight.Bold,
    val italic: Boolean = false,
    val caps: Boolean = false,
    /** Letter spacing in em. */
    val tracking: Float = -0.01f,
    /** Base size in sp before long names are shrunk to fit. */
    val size: Float = 40f,
    val lineHeight: Float = 1.0f,
)

@OptIn(ExperimentalTextApi::class)
object ArtistTypography {
    /** Fonts that ship with the phone (Pixel has a good set); each falls back if missing. */
    private fun file(vararg paths: String): File? = paths.map(::File).firstOrNull { it.canRead() }

    private fun family(fallback: FontFamily, vararg paths: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal, axes: FontVariation.Settings? = null): FontFamily {
        val f = file(*paths) ?: return fallback
        return runCatching {
            FontFamily(if (axes != null) Font(f, weight, style, axes) else Font(f, weight, style))
        }.getOrDefault(fallback)
    }

    private val flex = arrayOf("/system/fonts/RobotoFlex-Regular.ttf")
    private val sansFlex = arrayOf("/product/fonts/GoogleSansFlex-Regular.ttf", "/system/fonts/GoogleSansFlex-Regular.ttf")

    /** Tall, tightly packed black capitals (Roboto Flex at its narrowest). */
    val condensed by lazy {
        ArtistType(
            family(FontFamily.SansSerif, *flex, weight = FontWeight.Black, axes = FontVariation.Settings(
                FontVariation.weight(1000), FontVariation.width(25f), FontVariation.Setting("opsz", 144f),
            )),
            FontWeight.Black, caps = true, tracking = 0f, size = 58f, lineHeight = 0.92f,
        )
    }

    /** Wide, heavy capitals (Roboto Flex at its widest). */
    val extended by lazy {
        ArtistType(
            family(FontFamily.SansSerif, *flex, weight = FontWeight.Black, axes = FontVariation.Settings(
                FontVariation.weight(1000), FontVariation.width(151f), FontVariation.Setting("opsz", 144f),
            )),
            FontWeight.Black, caps = true, tracking = -0.02f, size = 34f,
        )
    }

    /** Industrial poster capitals. */
    private val shoulders by lazy {
        ArtistType(
            family(FontFamily.SansSerif, "/product/fonts/BigShouldersText-ExtraBold.ttf", "/system/fonts/BigShouldersText-ExtraBold.ttf", weight = FontWeight.ExtraBold),
            FontWeight.ExtraBold, caps = true, tracking = 0.01f, size = 56f, lineHeight = 0.92f,
        )
    }

    /** A soft, characterful display serif. */
    val serif by lazy {
        ArtistType(
            family(FontFamily.Serif, "/product/fonts/Fraunces-SemiBold.ttf", "/system/fonts/Fraunces-SemiBold.ttf", weight = FontWeight.SemiBold),
            FontWeight.SemiBold, tracking = -0.02f, size = 46f,
        )
    }

    /** Refined, airy serif capitals. */
    private val elegant by lazy {
        ArtistType(
            family(FontFamily.Serif, "/product/fonts/Lustria-Regular.ttf", "/system/fonts/Lustria-Regular.ttf"),
            FontWeight.Normal, caps = true, tracking = 0.08f, size = 34f, lineHeight = 1.05f,
        )
    }

    /** Bold italic serif. */
    private val italicSerif by lazy {
        ArtistType(
            family(FontFamily.Serif, "/system/fonts/NotoSerif-BoldItalic.ttf", weight = FontWeight.Bold, style = FontStyle.Italic),
            FontWeight.Bold, italic = true, tracking = -0.02f, size = 44f,
        )
    }

    /** Friendly, fully rounded heavy sans. */
    private val rounded by lazy {
        ArtistType(
            family(FontFamily.SansSerif, *sansFlex, weight = FontWeight.ExtraBold, axes = FontVariation.Settings(
                FontVariation.weight(850), FontVariation.Setting("ROND", 100f),
            )),
            FontWeight.ExtraBold, tracking = -0.03f, size = 46f,
        )
    }

    /** Sturdy slab serif. */
    private val slab by lazy {
        ArtistType(
            family(FontFamily.Serif, "/product/fonts/ZillaSlab-SemiBold.ttf", "/system/fonts/ZillaSlab-SemiBold.ttf", weight = FontWeight.SemiBold),
            FontWeight.SemiBold, tracking = -0.01f, size = 48f,
        )
    }

    /** Small capitals, slightly spaced. */
    private val smallCaps by lazy {
        ArtistType(
            family(FontFamily.SansSerif, "/system/fonts/CarroisGothicSC-Regular.ttf"),
            FontWeight.Normal, tracking = 0.04f, size = 44f,
        )
    }

    /** The everyday headline: Apple's standard heavy sans. */
    val standard by lazy {
        ArtistType(
            family(FontFamily.SansSerif, *sansFlex, weight = FontWeight.Bold, axes = FontVariation.Settings(FontVariation.weight(760))),
            FontWeight.Bold, tracking = -0.02f, size = 38f,
        )
    }

    private fun byGenre(genre: String?): List<ArtistType> = when (genre) {
        "Hip-hop" -> listOf(condensed, shoulders, extended)
        "Pop" -> listOf(serif, rounded, extended, italicSerif)
        "R&B & soul" -> listOf(elegant, italicSerif, serif)
        "Rock" -> listOf(shoulders, condensed, slab)
        "Metal" -> listOf(shoulders, condensed)
        "Indie & alternative" -> listOf(smallCaps, serif, elegant)
        "Dance & electronic" -> listOf(extended, rounded, condensed)
        "Country & Americana", "Folk & acoustic", "Blues" -> listOf(slab, serif)
        "Jazz", "Classical" -> listOf(elegant, italicSerif)
        null -> listOf(serif, rounded, condensed, extended)
        else -> listOf(rounded, serif)
    }

    /** Artists this big get a signature typeface. */
    private const val HEADLINER = 2_000_000L

    fun forArtist(id: String, genre: String?, audience: Long): ArtistType {
        if (audience < HEADLINER) return standard
        val options = byGenre(genre)
        return options[Math.floorMod(id.hashCode(), options.size)]
    }

    /** "444M monthly audience", "63.5M subscribers", "1.2K" -> a number. */
    fun parseAudience(text: String?): Long {
        val m = Regex("([\\d.,]+)\\s*([KMB])?", RegexOption.IGNORE_CASE).find(text ?: return 0) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (n * when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
    }
}
