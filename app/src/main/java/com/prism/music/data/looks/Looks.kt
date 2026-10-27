package com.prism.music.data.looks

import com.prism.music.data.prefs.LookValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The icon style a Look carries (Appearance → Icons & shapes; kept apart from the settings, see `IconStyle`). */
@Serializable
data class LookIcons(val pack: String = "PRISM", val weight: String = "REGULAR", val rounded: Boolean = true, val likeStar: Boolean = false)

/**
 * A Look (1.7.3): a named snapshot of how Prism looks, every setting in `SettingsRepository.lookSpec` as tagged text
 * ([LookValue]; one it doesn't hold is at Prism's default), plus the icon style and, when it uses one, the listener's
 * own font. Photos it uses as backgrounds are kept with it as `PHOTO|asset:<hash>` ([LookStore] keeps the files), so a
 * Look never points at a file that can go away.
 */
@Serializable
data class Look(
    val id: String,
    val name: String,
    val values: Map<String, String> = emptyMap(),
    val icons: LookIcons = LookIcons(),
    /** The app font file's asset hash, when the Look uses the listener's own font. */
    val font: String? = null,
    val created: Long = 0,
    /** One of Prism's own ([BuiltInLooks]): it can be applied and copied, not changed or deleted. */
    val builtIn: Boolean = false,
    /** What it is, in a few words (Prism's own Looks). */
    val note: String = "",
    val format: Int = 1,
) {
    /** The asset hashes this Look's backgrounds use. */
    val photoAssets: List<String> get() = Backgrounds.parse(values[BACKDROPS]).values.mapNotNull(Backgrounds::assetOf)

    companion object {
        const val BACKDROPS = "backdrops"
        const val FONT_CHOICE = "font_choice"
        const val APP_FONT_NAME = "app_font_name"
        const val LYRICS_FONT = "lyrics_font"
        const val MAX_NAME = 40
    }
}

/** The per-screen backgrounds a Look keeps, stored as one JSON map ("HOME" → "AURORA" or "PHOTO|…"). */
object Backgrounds {
    private val json = Json { ignoreUnknownKeys = true }
    const val ASSET = "asset:"

    fun parse(tagged: String?): Map<String, String> =
        LookValue.text(tagged)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    fun tag(map: Map<String, String>): String = "s:" + json.encodeToString<Map<String, String>>(map)

    /** The asset hash a background's photo is, or null when it isn't a kept photo. */
    fun assetOf(v: String): String? = v.takeIf { it.startsWith("PHOTO|$ASSET") }?.substringAfter(ASSET)?.takeIf(::isHash)

    fun isHash(s: String) = s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * Looks as text to share (`PRISMLOOK1:` and the Look deflated, in URL-safe Base64), like Blend codes: small enough for a
 * QR code when it has no photo or font of its own ([MAX_QR_CHARS]). Only the name, the look settings and the icon style
 * travel; reading one keeps only settings a Look may hold, each with the right kind of value ([sanitized]).
 */
object LookCode {
    const val PREFIX = "PRISMLOOK1:"
    /** What fits in a QR code at medium error correction, with room to spare. */
    const val MAX_QR_CHARS = 1_800
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Wire(val n: String, val v: Map<String, String> = emptyMap(), val i: LookIcons = LookIcons(), val f: String? = null)

    fun encode(look: Look): String {
        val raw = json.encodeToString(Wire.serializer(), Wire(look.name, look.values, look.icons, look.font)).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The Look code somewhere in [text] (a message, a scanned QR code), or null. */
    fun findIn(text: String?): String? {
        val t = text ?: return null
        val at = t.indexOf(PREFIX)
        if (at < 0) return null
        val body = t.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (body.isEmpty()) null else PREFIX + body
    }

    /** The Look in [text], checked against [kinds] (what a Look may set, and as what), or null if there isn't one. */
    fun decode(text: String?, kinds: Map<String, Char>): Look? = runCatching {
        val code = findIn(text) ?: return null
        val bytes = Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) return null
        }
        inflater.end()
        val w = json.decodeFromString(Wire.serializer(), out.toString(Charsets.UTF_8.name()))
        sanitized(Look(id = "", name = w.n, values = w.v, icons = w.i, font = w.f?.takeIf(Backgrounds::isHash)), kinds)
    }.getOrNull()

    /**
     * [look] with only what a Look may hold: known settings with the right kind of value, backgrounds that are designs
     * or kept photos (never a path on someone's phone), a name of a sensible length and a known icon style.
     */
    fun sanitized(look: Look, kinds: Map<String, Char>): Look {
        val values = look.values.filter { (k, v) -> kinds[k]?.let { LookValue.untag(v, it) } != null }.toMutableMap()
        values[Look.BACKDROPS]?.let { b ->
            val kept = Backgrounds.parse(b).filter { (screen, v) ->
                screen.length <= 16 && (v.startsWith("PHOTO|").not() && v.length <= 32 || Backgrounds.assetOf(v) != null)
            }
            if (kept.isEmpty()) values.remove(Look.BACKDROPS) else values[Look.BACKDROPS] = Backgrounds.tag(kept)
        }
        val icons = look.icons.let { i ->
            LookIcons(
                pack = i.pack.takeIf { p -> p in setOf("PRISM", "CUPERTINO") } ?: "PRISM",
                weight = i.weight.takeIf { w -> w in setOf("THIN", "REGULAR", "BOLD") } ?: "REGULAR",
                rounded = i.rounded, likeStar = i.likeStar,
            )
        }
        val name = look.name.filter { !it.isISOControl() }.trim().take(Look.MAX_NAME).ifBlank { "A Look" }
        return look.copy(name = name, values = values, icons = icons)
    }
}

/** Prism's own Looks, each showing off what can be changed. The Prism Look is how Prism ships. */
object BuiltInLooks {
    private fun color(argb: Long) = "i:${argb.toInt()}"
    private fun everywhere(style: String) = Backgrounds.tag(listOf("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS").associateWith { style })

    val all: List<Look> = listOf(
        Look("prism", "Prism", builtIn = true, note = "How Prism comes: Outfit, the split light, soft corners"),
        Look(
            "paper", "Paper", builtIn = true, note = "Light, serif type, ink instead of color, warm paper",
            values = mapOf(
                "theme_mode" to "s:LIGHT", "font_choice" to "s:SERIF", "light_style" to "s:WHITE", "color_source" to "s:CUSTOM",
                "accent" to color(0xFF8A5A3B), "surface_tint" to "s:CUSTOM", "surface_color" to color(0xFFD9B98C),
                "surface_tint_strength" to "f:1.6", "corners" to "s:SUBTLE", "backdrops" to everywhere("SAND"),
            ),
        ),
        Look(
            "midnight", "Midnight", builtIn = true, note = "Pure black, thin icons, a deep blue",
            values = mapOf(
                "theme_mode" to "s:DARK", "pure_black" to "b:1", "color_source" to "s:CUSTOM", "accent" to color(0xFF2F5BFF),
                "spectrum_preset" to "s:OCEAN",
            ),
            icons = LookIcons(weight = "THIN"),
        ),
        Look(
            "neon", "Neon", builtIn = true, note = "Mono type, neon light, sharp corners",
            values = mapOf(
                "theme_mode" to "s:DARK", "font_choice" to "s:MONO", "spectrum_preset" to "s:NEON", "corners" to "s:SHARP",
                "color_source" to "s:CUSTOM", "accent" to color(0xFFFF2E93), "accent_exact" to "b:1", "backdrops" to everywhere("NEON"),
            ),
        ),
        Look(
            "soft", "Soft", builtIn = true, note = "Rounded type, extra-round corners, pastels",
            values = mapOf(
                "font_choice" to "s:ROUNDED", "corners" to "s:EXTRA", "spectrum_preset" to "s:CANDY", "color_source" to "s:CUSTOM",
                "accent" to color(0xFFB79CFF), "backdrops" to everywhere("ROSE"),
            ),
        ),
        Look(
            "studio", "Studio", builtIn = true, note = "White light, no accent, compact, classic controls",
            values = mapOf(
                "light_style" to "s:WHITE", "color_source" to "s:CUSTOM", "accent" to color(0xFFFFFFFF), "ui_density" to "s:COMPACT",
                "transport_style" to "s:GLYPHS", "font_choice" to "s:CLEAN", "corners" to "s:SUBTLE",
            ),
        ),
        // Waited for 1.7.4's Vinyl player (planned with the Looks in 1.7.3).
        Look(
            "vinyl", "Vinyl", builtIn = true, note = "A record on the player, warm amber, serif titles",
            values = mapOf(
                "theme_mode" to "s:DARK", "font_choice" to "s:SERIF", "spectrum_preset" to "s:GOLD", "color_source" to "s:CUSTOM",
                "accent" to color(0xFFE0A15A), "player_layout" to "s:VINYL", "vinyl_tonearm" to "b:1", "player_background" to "s:BLUR",
                "player_title_align" to "s:CENTER", "scrubber_color" to "s:ACCENT", "transport_style" to "s:OUTLINE", "player_times" to "s:BOTH",
            ),
        ),
    )

    fun of(id: String): Look? = all.firstOrNull { it.id == id }
}

/** How a look setting and its value read in the undo bar and History ("Corners → Sharp"). */
object LookLabels {
    private val titles = mapOf(
        "theme_mode" to "Theme", "pure_black" to "Pure black", "color_source" to "Accent from", "accent" to "Accent color",
        "light_style" to "Light", "accent_exact" to "Exactly as picked", "accent_light" to "Light mode accent", "spectrum_preset" to "Spectrum",
        "spectrum_stops" to "Your light's colors", "spectrum_bands" to "Spectrum in bands", "surface_tint" to "Page background",
        "surface_tint_strength" to "Background strength", "surface_color" to "Background color", "contrast_level" to "Contrast",
        "artwork_swatch" to "Cover color", "artwork_vivid" to "Always colorful", "artwork_change" to "Between songs",
        "page_picture_accent" to "Pages in their picture's color", "liquid_glass" to "Liquid Glass", "glass_kind" to "Glass style",
        "frosted_glass" to "Frosted Glass", "frosted_pages" to "Frosted pages", "backdrops" to "Backgrounds", "backdrop_dim" to "Background softness",
        "backdrop_motion" to "Gentle motion", "reduce_motion" to "Reduce motion", "font_choice" to "Font", "app_font_name" to "Your font",
        "lyrics_font" to "Lyrics font", "text_scale" to "Text size", "corners" to "Corners", "ui_density" to "Spacing", "title_size" to "Page titles",
        "card_size" to "Cards", "nav_style" to "Tab bar", "nav_labels" to "Tab labels", "nav_hide_on_scroll" to "Shrink while scrolling",
        "nav_tabs" to "Tabs", "tab_names" to "Tab names", "nav_bar_size" to "Tab bar height", "nav_bar_fill" to "Tab bar background",
        "mini_player_joined" to "Mini player joined to the tab bar", "start_tab" to "Start tab", "mini_player_style" to "Mini player", "mini_skip_buttons" to "Mini player's Next",
        "mini_prev_button" to "Mini player's Previous", "mini_progress" to "Mini player progress", "player_background" to "Player background",
        "player_backdrop_show" to "Player background showing", "player_art_corners" to "Cover corners", "transport_style" to "Controls",
        "cover_size" to "Cover size", "scrubber_style" to "Progress bar", "art_shrink_on_pause" to "Cover shrinks on pause",
        "show_output_device" to "Output device", "full_bleed_artwork" to "Edge-to-edge cover", "video_full_bleed" to "Edge-to-edge video","hide_volume_bar" to "Hide volume bar",
        "show_playing_from" to "Playing from", "player_shuffle_repeat" to "Shuffle & repeat", "immersive_art" to "Immersive cover",
        "immersive_lyrics" to "Immersive lyrics", "immersive_controls" to "Immersive controls", "lyrics_align" to "Lyrics alignment",
        "lyrics_blur" to "Lyrics focus", "lyrics_progress_line" to "Lyrics progress line", "lyrics_scale" to "Lyrics size",
        "library_view" to "Library view", "library_views" to "Library tabs' views", "library_columns" to "Library columns",
        "library_subtitles" to "Library details", "home_header" to "Top of Home", "home_header_height" to "Top of Home's height",
        "playlist_hero_cover" to "Playlist picture", "replay_theme_colors" to "Replay in your colors",
        "replay_card" to "Replay card",
        "player_layout" to "Player layout", "vinyl_tonearm" to "Tonearm", "player_deck" to "Player's order", "player_buttons" to "Player buttons",
        "scrubber_color" to "Progress bar color", "player_title_align" to "Title alignment", "player_title_size" to "Title size",
        "player_title_scroll" to "Scrolling titles", "player_times" to "Times", "mesh_speed" to "Mesh motion", "mesh_intensity" to "Mesh colors",
        "player_blur" to "Blur strength", "canvas_fills_background" to "Animated cover fills the player",
        "scrubber_thickness" to "Progress bar thickness", "scrubber_thumb" to "Progress bar knob", "visualizer" to "Visualizer",
        "visualizer_color" to "Visualizer color",
        "lyrics_style" to "Lyrics style", "lyrics_blur_amount" to "Lyrics blur", "lyrics_spacing" to "Lyrics line spacing",
        "lyrics_others_alpha" to "Other lines' brightness", "lyrics_sung_color" to "Sung line color", "lyrics_sung_custom" to "Sung line's own color",
        "lyrics_other_color" to "Other lines' color", "lyrics_other_custom" to "Other lines' own color", "lyrics_weight" to "Lyrics weight",
        "lyrics_backing_size" to "Background vocals size", "lyrics_shadow" to "Lyrics shadow", "lyrics_uppercase" to "Lyrics in capitals",
        "lyrics_ahead" to "Lines ahead", "lyrics_position" to "Sung line's place", "lyrics_extra_size" to "Translation size",
        "lyrics_extra_color" to "Translation color", "lyrics_extra_italic" to "Translation in italics", "lyrics_extra_place" to "Translation place",
        "lyrics_background" to "Lyrics background", "immersive_background" to "Immersive background", "lyrics_bg_color" to "Lyrics background color",
        "lyrics_bg_color2" to "Lyrics gradient's second color", "lyrics_everywhere" to "Same lyrics look everywhere",
        "lyrics_strip_align" to "Lyric line on the player", "lyrics_strip_two_lines" to "Lyric line up to two lines",
        "hide_status_bar" to "Hide the status bar",
    )

    /** Settings whose stored names are enum names, with each name's label. */
    private val choices: Map<String, Map<String, String>> by lazy {
        fun <E : Enum<E>> e(entries: List<E>, label: (E) -> String) = entries.associate { it.name to label(it) }
        val p = com.prism.music.data.prefs.ThemeMode.entries
        mapOf(
            "theme_mode" to e(p) { it.label },
            "color_source" to e(com.prism.music.data.prefs.ColorSource.entries) { it.label },
            "light_style" to e(com.prism.music.data.prefs.LightStyle.entries) { it.label },
            "surface_tint" to e(com.prism.music.data.prefs.SurfaceTint.entries) { it.label },
            "contrast_level" to e(com.prism.music.data.prefs.ContrastLevel.entries) { it.label },
            "artwork_swatch" to e(com.prism.music.data.prefs.ArtworkSwatch.entries) { it.label },
            "artwork_change" to e(com.prism.music.data.prefs.ArtworkChange.entries) { it.label },
            "glass_kind" to e(com.prism.music.data.prefs.GlassKind.entries) { it.label },
            "font_choice" to e(com.prism.music.data.prefs.FontChoice.entries) { it.label },
            "lyrics_font" to e(com.prism.music.data.prefs.FontChoice.entries) { it.label } + ("" to "App font"),
            "corners" to e(com.prism.music.data.prefs.Corners.entries) { it.label },
            "ui_density" to e(com.prism.music.data.prefs.UiDensity.entries) { it.label },
            "title_size" to e(com.prism.music.data.prefs.TitleSize.entries) { it.label },
            "card_size" to e(com.prism.music.data.prefs.CardSize.entries) { it.label },
            "nav_style" to e(com.prism.music.data.prefs.NavBarStyle.entries) { it.label },
            "nav_labels" to e(com.prism.music.data.prefs.NavLabels.entries) { it.label },
            "nav_bar_size" to e(com.prism.music.data.prefs.NavBarSize.entries) { it.label },
            "nav_bar_fill" to e(com.prism.music.data.prefs.NavBarFill.entries) { it.label },
            "start_tab" to e(com.prism.music.data.prefs.NavTab.entries) { it.label },
            "mini_player_style" to e(com.prism.music.data.prefs.MiniPlayerStyle.entries) { it.label },
            "mini_progress" to e(com.prism.music.data.prefs.MiniProgress.entries) { it.label },
            "player_background" to e(com.prism.music.data.prefs.PlayerBackground.entries) { it.label },
            "transport_style" to e(com.prism.music.data.prefs.TransportStyle.entries) { it.label },
            "cover_size" to e(com.prism.music.data.prefs.CoverSize.entries) { it.label },
            "scrubber_style" to e(com.prism.music.data.prefs.ScrubberStyle.entries) { it.label },
            "immersive_art" to e(com.prism.music.data.prefs.ImmersiveArt.entries) { it.label },
            "immersive_lyrics" to e(com.prism.music.data.prefs.ImmersiveLyrics.entries) { it.label },
            "lyrics_align" to e(com.prism.music.data.prefs.LyricsAlign.entries) { it.label },
            "lyrics_strip_align" to e(com.prism.music.data.prefs.LyricsAlign.entries) { it.label },
            "library_view" to e(com.prism.music.data.prefs.LibraryView.entries) { it.label },
            "library_columns" to e(com.prism.music.data.prefs.LibraryColumns.entries) { it.label },
            "home_header" to e(com.prism.music.data.prefs.HomeHeader.entries) { it.label },
            "home_header_height" to e(com.prism.music.data.prefs.HomeHeaderHeight.entries) { it.label },
            "player_layout" to e(com.prism.music.data.prefs.PlayerLayout.entries) { it.label },
            "scrubber_color" to e(com.prism.music.data.prefs.ScrubberColor.entries) { it.label },
            "player_title_align" to e(com.prism.music.data.prefs.PlayerTitleAlign.entries) { it.label },
            "player_title_size" to e(com.prism.music.data.prefs.PlayerTitleSize.entries) { it.label },
            "player_times" to e(com.prism.music.data.prefs.PlayerTimes.entries) { it.label },
            "player_deck" to e(com.prism.music.data.prefs.DeckPart.entries) { it.label },
            "player_buttons" to e(com.prism.music.data.prefs.PlayerButton.entries) { it.label },
            "scrubber_thumb" to e(com.prism.music.data.prefs.ScrubberThumb.entries) { it.label },
            "visualizer" to e(com.prism.music.data.prefs.VisualizerStyle.entries) { it.label },
            "visualizer_color" to e(com.prism.music.data.prefs.ScrubberColor.entries) { it.label },
            "lyrics_style" to e(com.prism.music.data.prefs.LyricsStyle.entries) { it.label },
            "lyrics_sung_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_other_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_extra_color" to e(com.prism.music.data.prefs.LyricsColor.entries) { it.label },
            "lyrics_weight" to e(com.prism.music.data.prefs.LyricsWeight.entries) { it.label },
            "lyrics_ahead" to e(com.prism.music.data.prefs.LyricsAhead.entries) { it.label },
            "lyrics_position" to e(com.prism.music.data.prefs.LyricsPosition.entries) { it.label },
            "lyrics_extra_place" to e(com.prism.music.data.prefs.LyricsExtraPlace.entries) { it.label },
            "lyrics_background" to e(com.prism.music.data.prefs.LyricsBackground.entries) { it.label },
            "immersive_background" to e(com.prism.music.data.prefs.LyricsBackground.entries) { it.label },
            "spectrum_preset" to com.prism.music.ui.theme.SpectrumPresets.all.associate { it.key to it.label } + ("CUSTOM" to "Your own"),
        )
    }

    private val percent = setOf(
        "text_scale", "lyrics_scale", "backdrop_dim", "player_backdrop_show", "surface_tint_strength", "mesh_speed", "mesh_intensity", "player_blur",
        "scrubber_thickness", "lyrics_blur_amount", "lyrics_spacing", "lyrics_others_alpha", "lyrics_backing_size", "lyrics_extra_size",
    )
    private val colors = setOf("accent", "accent_light", "surface_color", "lyrics_sung_custom", "lyrics_other_custom", "lyrics_bg_color", "lyrics_bg_color2")

    fun title(name: String): String = titles[name] ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Whether [name] has its own words (LookTest: every look setting does). */
    fun named(name: String): Boolean = name in titles

    /** [tagged] as it reads ("Sharp", "On", "110%", "#3A86FF"); null is the setting's default. */
    fun value(name: String, tagged: String?): String {
        val v = LookValue.text(tagged) ?: return "Default"
        return when (tagged!![0]) {
            'b' -> if (v == "1") "On" else "Off"
            'f' -> v.toFloatOrNull()?.let { if (name in percent) "${(it * 100).toInt()}%" else v } ?: v
            'i' -> v.toIntOrNull()?.let { if (name in colors) "#%06X".format(it and 0xFFFFFF) else if (name == "player_art_corners") "$it dp" else v } ?: v
            else -> when (name) {
                "backdrops" -> "Changed"
                "nav_tabs" -> v.split(',').joinToString(", ") { t -> choices["start_tab"]?.get(t) ?: t }
                "library_views" -> com.prism.music.data.prefs.parseLibraryViews(v).entries.joinToString(", ") { (t, view) ->
                    "${t.lowercase().replaceFirstChar { it.uppercase() }}: ${view.label}"
                }.ifEmpty { "Default" }
                "tab_names" -> com.prism.music.data.prefs.parseTabNames(v).entries.joinToString(", ") { (t, n) -> "${t.label}: $n" }.ifEmpty { "Their own" }
                "player_deck", "player_buttons" -> v.split(',').joinToString(", ") { t -> choices[name]?.get(t) ?: t }
                "spectrum_stops" -> "${v.split(',').size} colors"
                "replay_card", "app_font_name" -> v.lowercase().replaceFirstChar { it.uppercase() }
                else -> choices[name]?.get(v) ?: v
            }
        }
    }

    /** The parts of Prism's look, in the order they're named, and the settings each is made of. */
    private val areaOf: List<Pair<String, Set<String>>> = listOf(
        "theme" to setOf("theme_mode", "pure_black"),
        "colors" to setOf(
            "color_source", "accent", "light_style", "accent_exact", "accent_light", "spectrum_preset", "spectrum_stops", "spectrum_bands",
            "surface_tint", "surface_tint_strength", "surface_color", "contrast_level", "artwork_swatch", "artwork_vivid", "artwork_change", "page_picture_accent",
        ),
        "type" to setOf("font_choice", "app_font_name", "lyrics_font", "text_scale"),
        "backgrounds" to setOf("liquid_glass", "glass_kind", "frosted_glass", "frosted_pages", "backdrops", "backdrop_dim", "backdrop_motion", "reduce_motion"),
        "layout" to setOf(
            "corners", "ui_density", "title_size", "card_size", "nav_style", "nav_labels", "nav_hide_on_scroll", "nav_tabs", "start_tab",
            "mini_player_style", "mini_skip_buttons", "mini_prev_button", "mini_progress", "library_view", "library_views", "library_columns", "library_subtitles", "home_header", "home_header_height", "playlist_hero_cover", "replay_theme_colors", "replay_card",
            "hide_status_bar", "tab_names", "nav_bar_size", "nav_bar_fill", "mini_player_joined",
        ),
        "player" to setOf(
            "player_background", "player_backdrop_show", "player_art_corners", "transport_style", "cover_size", "scrubber_style", "art_shrink_on_pause",
            "show_output_device", "full_bleed_artwork", "video_full_bleed", "hide_volume_bar", "show_playing_from", "player_shuffle_repeat", "immersive_art", "immersive_lyrics", "immersive_controls",
            "player_layout", "vinyl_tonearm", "player_deck", "player_buttons", "scrubber_color", "player_title_align", "player_title_size",
            "player_title_scroll", "player_times", "mesh_speed", "mesh_intensity", "player_blur", "canvas_fills_background",
            "scrubber_thickness", "scrubber_thumb", "visualizer", "visualizer_color",
        ),
        "lyrics" to setOf(
            "lyrics_align", "lyrics_blur", "lyrics_progress_line", "lyrics_scale", "lyrics_style", "lyrics_blur_amount", "lyrics_spacing",
            "lyrics_others_alpha", "lyrics_sung_color", "lyrics_sung_custom", "lyrics_other_color", "lyrics_other_custom", "lyrics_weight",
            "lyrics_backing_size", "lyrics_shadow", "lyrics_uppercase", "lyrics_ahead", "lyrics_position", "lyrics_extra_size", "lyrics_extra_color",
            "lyrics_extra_italic", "lyrics_extra_place", "lyrics_background", "immersive_background", "lyrics_bg_color", "lyrics_bg_color2", "lyrics_everywhere",
            "lyrics_strip_align", "lyrics_strip_two_lines",
        ),
    )

    /**
     * What a switch would change, in a line ("Changes your theme, colors and icons"), from the settings that differ
     * ([keys]) and whether the icon style does; null when nothing would.
     */
    fun summary(keys: Collection<String>, icons: Boolean, lead: String = "Changes your"): String? {
        val parts = areaOf.filter { (_, set) -> keys.any { it in set } }.map { it.first }.toMutableList()
        // Anything not in a part above (a setting added later) still counts as the look.
        if (keys.any { k -> areaOf.none { k in it.second } } && parts.isEmpty()) parts += "look"
        if (icons) parts += "icons"
        if (parts.isEmpty()) return null
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "$lead $list"
    }

    /** One change as it reads: "Corners → Sharp", or "Corners → Sharp and 2 more". */
    fun change(before: Map<String, String>, after: Map<String, String>, order: List<String>): String? {
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val first = changed.firstOrNull() ?: return null
        val one = "${title(first)} → ${value(first, after[first])}"
        return if (changed.size == 1) one else "$one and ${changed.size - 1} more"
    }
}
