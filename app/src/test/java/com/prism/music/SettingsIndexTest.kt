package com.prism.music

import com.prism.music.ui.screens.SettingsIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search (#73) only finds what [SettingsIndex] lists: every setting row and group on the settings pages has to
 * be there, so a new setting without a line in the index fails here. Since 1.7.9 the pages read their words from
 * strings.xml (`tr(R.string.…)`), so each row's words are looked up there (in English, as the tests run).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SettingsIndexTest {
    private val sources = listOf("SettingsScreen.kt", "SoundSettings.kt", "BackupUi.kt", "ConnectSettings.kt", "ColorSettings.kt", "LookSettings.kt")
        .map { File("src/main/java/com/prism/music/ui/screens/$it").readText() }

    /** strings.xml's English: name → text. */
    private val strings: Map<String, String> by lazy {
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to I18nConvert.xmlUnescape(it.groupValues[2]) }
    }

    private fun rows(calls: String) = sources.flatMap { src ->
        Regex("""\b(?:$calls)\(\s*tr\(R\.string\.(\w+)""").findAll(src).map { m ->
            // "Space to use: %1$s" is listed by its fixed start.
            strings.getValue(m.groupValues[1]).substringBefore("%1\$s").trim().removeSuffix(":")
        }.toList()
    }.filter { it.isNotBlank() }.distinct()

    @Test
    fun everyRowIsSearchable() {
        val rows = rows("Toggle|Item|SliderSetting|Label")
        assertTrue("the pages' rows were read (${rows.size})", rows.size > 100)
        val missing = rows.filter { row -> SettingsIndex.entries.none { it.title == row || row.startsWith(it.title) } }
        assertTrue("Not in SettingsIndex: $missing", missing.isEmpty())
    }

    @Test
    fun everyGroupIsSearchable() {
        val groups = rows("Group")
        assertTrue("the pages' groups were read (${groups.size})", groups.size > 20)
        val missing = groups.filter { g -> SettingsIndex.entries.none { it.group == g } }
        assertTrue("Groups not in SettingsIndex: $missing", missing.isEmpty())
    }

    @Test
    fun findsBySynonymAndTitle() {
        assertTrue(SettingsIndex.search("vibration").any { it.title == "Haptics" })
        assertTrue(SettingsIndex.search("crossfade").first().title.startsWith("Crossfade"))
        assertTrue(SettingsIndex.search("eq").any { it.title == "Equalizer" })
        assertTrue(SettingsIndex.search("zzzz").isEmpty())
    }
}
