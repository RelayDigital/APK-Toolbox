package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test

/**
 * The smoke test: the main window opens, its four tabs are there, the Settings sheet opens and
 * closes. Everything is looked up through the app's own string resources, so it runs on any
 * locale.
 */
class MainTabsSmokeTest : BaseUiTest() {

    @Test
    fun tabsSwitchAndSettingsSheetOpens() {
        // The app starts on the Sources tab; its bar button says "Add app".
        assert(findString("sources_add") != null) { "Sources tab is not showing" }

        // The strip: Sources / Cloning / SAI / AntiSplit. Tapping each tab in turn.
        for (tab in listOf("tab_apps", "tab_install", "tab_split", "tab_sources")) {
            val title = string(tab)
            val tabView = device.findObject(By.text(title))
                ?: error("Tab \"$title\" not found in the tab strip. Screen:\n${dumpVisibleTexts()}")
            tabView.click()
            device.waitForIdle(2_000)
            // Some content of the tab: its empty-state text or, on Sources, the add button.
            val settled = findString("sources_add") != null ||
                listOf("empty_list", "empty_clones", "empty_split").any { findString(it) != null }
            assert(settled) { "Tab \"$title\" did not settle. Screen:\n${dumpVisibleTexts()}" }
        }

        // The Settings sheet, by its button's content description.
        val settingsDesc = string("settings_title")
        val settingsButton = device.findObject(By.desc(settingsDesc))
            ?: error("No Settings button (content-desc \"$settingsDesc\")")
        settingsButton.click()
        // The sheet's own heading is the same string as the button's description.
        waitForStringResource("settings_title", timeout = 10_000)

        // Back dismisses the sheet; the app stays and the Sources tab is still there.
        device.pressBack()
        device.waitForIdle(2_000)
        assert(device.wait(By.pkg(packageName).depth(0).let(Until::hasObject), 3_000)) {
            "The app closed when the Settings sheet was dismissed"
        }
        assert(findString("sources_add") != null) { "Sources tab did not come back after Settings closed" }
    }
}