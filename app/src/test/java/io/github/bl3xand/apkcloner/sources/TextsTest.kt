package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.ui.OptionGroups
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks on the texts and the fixed names the screens are built from: every language has
 * the same set, placeholders agree, and what the code refers to exists.
 */
class TextsTest {
    private val main = File("src/main")
    private val languages = listOf("en", "ru", "zh")

    private fun own(language: String) = JSONObject(File(main, "assets/sources/i18n/own_$language.json").readText())
    private fun reference(language: String) = JSONObject(File(main, "assets/sources/i18n/$language.json").readText())
    private fun JSONObject.allKeys(): Set<String> = keys().asSequence().toSet()

    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(main, "res/$folder/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun ownTextsExistInEveryLanguage() {
        val english = own("en").allKeys()
        for (language in languages) assertEquals("own_$language.json", english, own(language).allKeys())
    }

    /** The texts of one key: a single one, or every form of a plural. */
    private fun JSONObject.forms(key: String): List<String> = when (val value = get(key)) {
        is JSONObject -> value.keys().asSequence().map { value.getString(it) }.toList()
        else -> listOf(value.toString())
    }

    @Test
    fun ownTextsKeepTheirPlaceholders() {
        val english = own("en")
        val placeholder = Regex("\\{\\}")
        for (key in english.allKeys()) {
            val expected = placeholder.findAll(english.forms(key).first()).count()
            for (language in languages) {
                for (form in own(language).forms(key)) {
                    assertEquals("$key in $language: $form", expected, placeholder.findAll(form).count())
                }
            }
        }
    }

    @Test
    fun appStringsExistInEveryLanguage() {
        val english = strings("values").keys
        for (folder in listOf("values-ru", "values-zh")) assertEquals(folder, english, strings(folder).keys)
    }

    @Test
    fun appStringsKeepTheirPlaceholders() {
        val english = strings("values")
        val placeholder = Regex("%\\d+\\$[sd]")
        for (folder in listOf("values-ru", "values-zh")) {
            val texts = strings(folder)
            for ((key, value) in english) {
                assertEquals(
                    "$key in $folder",
                    placeholder.findAll(value).map { it.value }.toSortedSet(),
                    placeholder.findAll(texts.getValue(key)).map { it.value }.toSortedSet(),
                )
            }
        }
    }

    /** Every text the code asks for by a literal key is there, in our texts or the reference ones. */
    @Test
    fun textsUsedInCodeExist() {
        val known = own("en").allKeys() + reference("en").allKeys()
        val used = File(main, "java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            Regex("Tr\\.(?:get|plural)\\(\\s*\"([A-Za-z.\\-]+)\"").findAll(file.readText()).map { it.groupValues[1] to file.name }
        }.toList()
        val missing = used.filter { it.first !in known }.map { "${it.first} (${it.second})" }.distinct()
        assertTrue("Texts used but not defined: $missing", missing.isEmpty())
    }

    @Test
    fun everyGroupHasAHeading() {
        val texts = own("en").allKeys()
        for (group in OptionGroups.ORDER) assertTrue(group, group in texts)
    }

    @Test
    fun settingsFallIntoTheirGroups() {
        assertEquals(OptionGroups.UPDATES, OptionGroups.of(SettingKeys.TRACK_ONLY))
        assertEquals(OptionGroups.VERSION, OptionGroups.of(SettingKeys.VERSION_DETECTION))
        assertEquals(OptionGroups.VERSION, OptionGroups.of(SettingKeys.RELEASE_DATE_AS_VERSION))
        assertEquals(OptionGroups.UPDATES, OptionGroups.of(SettingKeys.PRETEND_GOOGLE_PLAY))
        assertEquals(OptionGroups.DISPLAY, OptionGroups.of(SettingKeys.ABOUT))
        assertEquals(OptionGroups.FILES, OptionGroups.of("apkFilterRegEx"))
        // Anything a single source adds belongs to the source's own group.
        assertEquals(OptionGroups.SOURCE, OptionGroups.of("includePrereleases"))
        assertEquals(OptionGroups.SOURCE, OptionGroups.of("somethingNew"))
    }

    /** The stored names are shared with the reference app; a change would orphan saved settings. */
    @Test
    fun settingKeysKeepTheirStoredNames() {
        assertEquals("trackOnly", SettingKeys.TRACK_ONLY)
        assertEquals("versionDetection", SettingKeys.VERSION_DETECTION)
        assertEquals("releaseDateAsVersion", SettingKeys.RELEASE_DATE_AS_VERSION)
        assertEquals("shizukuPretendToBeGooglePlay", SettingKeys.PRETEND_GOOGLE_PLAY)
        assertEquals("exemptFromBackgroundUpdates", SettingKeys.EXEMPT_FROM_BACKGROUND_UPDATES)
        assertEquals("skipUpdateNotifications", SettingKeys.SKIP_UPDATE_NOTIFICATIONS)
        assertEquals("useVersionCodeAsOSVersion", SettingKeys.VERSION_CODE_AS_OS_VERSION)
        assertEquals("includePrereleases", SettingKeys.INCLUDE_PRERELEASES)
        assertEquals("about", SettingKeys.ABOUT)
        assertEquals("appId", SettingKeys.APP_ID)
    }

    /** Described settings and hints refer to settings that some source really defines. */
    @Test
    fun describedSettingsExist() {
        val sources = File(main, "java/io/github/bl3xand/apkcloner/sources").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val orphans = own("en").allKeys().filter { it.startsWith("opt.") || it.startsWith("optTitle.") || it.startsWith("optHint.") }
            .map { it.substringAfter('.') }.distinct()
            .filter { key -> !sources.contains("\"$key\"") && SettingKeys::class.java.declaredFields.none { it.get(null) == key } }
        assertTrue("Descriptions for settings that do not exist: $orphans", orphans.isEmpty())
    }
}
