package com.prism.music.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.FontChoice
import com.prism.music.data.prefs.TitleSize
import java.io.File

/**
 * The look & layout knobs every screen reads: how round things are, how much room they get,
 * how wide cards are and how big page titles are. Built from [AppSettings] by [PrismTheme].
 */
@Immutable
data class PrismUi(
    val corner: Float = 1f,
    val density: Float = 1f,
    val cardWidth: Dp = 152.dp,
    val titleSize: TitleSize = TitleSize.LARGE,
    val reduceMotion: Boolean = false,
) {
    /** A corner radius designed at [base] for the default "Round" setting. */
    fun r(base: Dp): Dp = base * corner
    fun shape(base: Dp): RoundedCornerShape = RoundedCornerShape(r(base))

    /** Vertical room designed at [base] for "Comfortable". */
    fun gap(base: Dp): Dp = base * density

    val tile: RoundedCornerShape get() = shape(16.dp)
    val card: RoundedCornerShape get() = shape(22.dp)
    val art: RoundedCornerShape get() = shape(12.dp)
    val smallArt: RoundedCornerShape get() = shape(9.dp)

    val materialShapes: Shapes
        get() = Shapes(
            extraSmall = shape(4.dp), small = shape(8.dp), medium = shape(12.dp),
            large = shape(18.dp), extraLarge = shape(28.dp),
        )

    companion object {
        fun from(s: AppSettings) = PrismUi(
            corner = s.corners.scale,
            density = s.uiDensity.scale,
            cardWidth = s.cardSize.widthDp.dp,
            titleSize = s.titleSize,
            reduceMotion = s.reduceMotion,
        )
    }
}

val LocalUi = staticCompositionLocalOf { PrismUi() }

/** The app-wide typefaces. Variable device fonts are set up at every weight the UI uses. */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private val cache = HashMap<FontChoice, FontFamily>()
    private val weights = listOf(300, 400, 500, 600, 700, 800, 900)
    private val sansFlex = arrayOf("/product/fonts/GoogleSansFlex-Regular.ttf", "/system/fonts/GoogleSansFlex-Regular.ttf")
    private val googleSans = arrayOf("/product/fonts/GoogleSans-Regular.ttf", "/system/fonts/GoogleSans-Regular.ttf")
    private val robotoFlex = arrayOf("/system/fonts/RobotoFlex-Regular.ttf")

    private fun file(vararg paths: String): File? = paths.map(::File).firstOrNull { it.canRead() }

    private fun variable(f: File, vararg extra: FontVariation.Setting): FontFamily = FontFamily(
        weights.map { w -> Font(f, FontWeight(w), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w), *extra)) }
    )

    private fun build(choice: FontChoice): FontFamily = when (choice) {
        FontChoice.SYSTEM -> FontFamily.Default
        FontChoice.GOOGLE_SANS -> file(*sansFlex)?.let { variable(it) }
            ?: file(*googleSans)?.let { FontFamily(Font(it)) }
            ?: FontFamily.SansSerif
        FontChoice.ROUNDED -> file(*sansFlex)?.let { variable(it, FontVariation.Setting("ROND", 100f)) }
            ?: FontFamily.SansSerif
        FontChoice.CONDENSED -> file(*robotoFlex)?.let { variable(it, FontVariation.width(78f)) }
            ?: FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        FontChoice.SERIF -> FontFamily.Serif
        FontChoice.MONO -> FontFamily.Monospace
    }

    fun family(choice: FontChoice): FontFamily = synchronized(cache) {
        cache.getOrPut(choice) { runCatching { build(choice) }.getOrDefault(FontFamily.Default) }
    }
}
