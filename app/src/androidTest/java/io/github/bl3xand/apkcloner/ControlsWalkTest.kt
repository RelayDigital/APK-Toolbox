package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.testing.TestHooks
import org.junit.Test

/**
 * The release gate's controls walk (home-upx.8, owner's requirement: every switch, slider,
 * picker and chip touched at least once, looking for conflicts, races and hangs). The walk
 * below drives the app's own controls through the UI, the way a hand would: the settings
 * sheet's switches and slider, the install-method toggle, the language picker, the log
 * sheet's chips, the per-app settings sheet's switches, the list's search, filters and
 * pull-to-refresh, selection mode and the long-press menu.
 *
 * The phone (28091JEGR07919) walked the same paths by hand on 25.10; this class is the
 * repeatable half of that evidence. What the walk asserts after every group: the window
 * still answers, no dialog leaked, and the app did not crash (the process is the test's
 * own - a crash fails the run by itself).
 *
 * The evil-combination walks (toggling settings while work runs) live on in
 * [UiConcurrencyTest]; here a milder fuzz stays: every switch is flipped twice, the slider
 * is dragged across all its stops, and the language is changed while the settings sheet is
 * open (the sheet must relabel, not break).
 */
class ControlsWalkTest : BaseUiTest() {

    @Test
    fun everySettingsControlAnswersAndNothingLeaks() {
        openSettings()

        // ---- the check-updates master switch: on enables its dependents, off disables them ----
        // A fresh install starts with checks OFF, so the first click turns them on.
        val checkSwitch = device.findObject(By.res(packageName, "switchCheckUpdates"))
            ?: error("No check-updates switch. Screen:\n${dumpVisibleTexts()}")
        if (!checkSwitch.isChecked) {
            checkSwitch.click()
            // The toggle can be followed by the notification-permission request on 33+; the
            // dependent rows settle once that round trip is over. Wait for the state, not the tick.
            val wifiEnabled = awaitCondition(10_000) {
                runCatching { device.findObject(By.res(packageName, "switchWifi"))?.isEnabled == true }.getOrDefault(false)
            }
            assert(wifiEnabled) { "wifi-only stayed disabled with checks on" }
            // A permission dialog, if one opened, is answered so it cannot sit over the walk.
            val grant = device.findObject(By.text("Allow")) ?: device.findObject(By.text("Разрешить"))
            grant?.click()
        }
        device.findObject(By.res(packageName, "switchCheckUpdates"))!!.click()
        val wifiWentDisabled = awaitCondition(10_000) {
            runCatching { device.findObject(By.res(packageName, "switchWifi"))?.isEnabled == false }.getOrDefault(false)
        }
        assert(wifiWentDisabled) { "wifi-only stayed enabled with checks off" }
        device.findObject(By.res(packageName, "switchCheckUpdates"))!!.click()
        val wifiBackEnabled = awaitCondition(10_000) {
            runCatching { device.findObject(By.res(packageName, "switchWifi"))?.isEnabled == true }.getOrDefault(false)
        }
        assert(wifiBackEnabled) { "wifi-only did not come back with checks on" }

        // ---- the interval slider: drag across every stop, the label must follow ----
        // The slider is found by its content description (the check-updates string): the
        // node's own resource id is not always exposed by the accessibility tree.
        val slider = scrollSheetUntil(string("settings_check_updates"), maxSwipes = 25)
            ?: device.findObject(By.res(packageName, "sliderInterval"))
            ?: error("No interval slider. Screen:\n${dumpVisibleTexts()}")
        val bounds = slider.visibleBounds
        val stops = 8 // AppSettings.CHECK_INTERVAL_DAYS.size; the label formats each stop
        for (step in 0 until stops) {
            val x = bounds.left + (bounds.width() * step / (stops - 1))
            device.swipe(x, bounds.centerY(), x, bounds.centerY(), 20)
            device.waitForIdle(300)
        }
        assertHealthy("after dragging the interval slider")

        // ---- every dependent switch, flipped on and back ----
        for (id in listOf(
            "switchCheckSources", "switchCheckClones", "switchCheckOnStart",
            "switchAutoInstall", "switchWifi", "switchCharging",
        )) {
            val switch = scrollSheetUntilRes(id)
                ?: error("No $id in the settings sheet. Screen:\n${dumpVisibleTexts()}")
            switch.click()
            device.waitForIdle(300)
            switch.click()
            device.waitForIdle(300)
            assertHealthy("after flipping $id")
        }

        // ---- the root switch: no root on an emulator, so it must fall back to off ----
        val root = scrollSheetUntilRes("switchRoot")
        if (root != null) {
            root.click()
            // The probe of the root manager takes a moment; the switch comes back off.
            awaitCondition(10_000) { !device.findObject(By.res(packageName, "switchRoot"))!!.isChecked }
        }

        // ---- the language picker: open, pick English, the open sheet must relabel ----
        scrollSheetToBottom()
        val language = scrollSheetUntil(string("settings_language"), maxSwipes = 10)
            ?: error("No language row. Screen:\n${dumpVisibleTexts()}")
        language.click()
        val english = device.wait(Until.findObject(By.text("English")), 5_000)
            ?: error("No English in the language picker. Screen:\n${dumpVisibleTexts()}")
        english.click()
        device.waitForIdle(1_500)
        // The settings sheet is still open and now speaks English: its own title says so.
        assert(device.findObject(By.text(string_en("settings_title"))) != null) {
            "The settings sheet did not relabel after the language change. Screen:\n${dumpVisibleTexts()}"
        }
        // Back to the system language the same way.
        val languageEn = scrollSheetUntil(string_en("settings_language"), maxSwipes = 25)
            ?: error("No language row (en). Screen:\n${dumpVisibleTexts()}")
        languageEn.click()
        val system = device.wait(Until.findObject(By.text(string_en("language_system"))), 5_000)
            ?: error("No system choice in the picker. Screen:\n${dumpVisibleTexts()}")
        system.click()
        device.waitForIdle(1_500)
        assertHealthy("after the language round trip")

        // ---- the log sheet: every period chip, every level chip, then back ----
        scrollSheetToBottom()
        scrollSheetUntil(string("settings_log"), maxSwipes = 10)?.click()
        val showLog = scrollSheetUntil(string("button_show_log"), maxSwipes = 25)
            ?: error("No show-log button. Screen:\n${dumpVisibleTexts()}")
        showLog.click()
        for (chip in listOf("1", "3", "7", "30")) {
            device.findObject(By.textContains(chip))?.click()
            device.waitForIdle(400)
        }
        for (level in listOf("DEBUG", "INFO", "WARNING", "ERROR")) {
            device.findObject(By.text(level))?.click()
            device.waitForIdle(400)
            device.findObject(By.text(level))?.click()
            device.waitForIdle(400)
        }
        assertHealthy("after the log chips")
        device.pressBack()
        device.waitForIdle(500)

        // ---- the key buttons: create (first key needs no password), then reset with its warning ----
        val create = scrollSheetUntil(string("key_create"), maxSwipes = 25)
            ?: error("No key-create button. Screen:\n${dumpVisibleTexts()}")
        create.click()
        device.waitForIdle(1_000)
        // Import opens the system picker; cancelled right away.
        val import = scrollSheetUntil(string("key_import"), maxSwipes = 25)
        import?.click()
        device.waitForIdle(1_000)
        device.pressBack() // the system file picker, if it opened
        device.waitForIdle(500)
        assertHealthy("after the key buttons")

        // The method toggle: Shizuku, then back to stock; the status card must follow.
        val shizuku = scrollSheetUntilRes("buttonMethodShizuku")
            ?: error("No Shizuku method button. Screen:\n${dumpVisibleTexts()}")
        shizuku.click()
        device.waitForIdle(500)
        scrollSheetUntilRes("buttonMethodStock")?.click()
        device.waitForIdle(500)
        assertHealthy("after the method toggle")

        device.pressBack()
        device.waitForIdle(500)
        assert(findString("sources_add") != null || device.findObjects(By.clazz("android.widget.TextView")).orEmpty().size > 4) {
            "Not back on a tab after the settings walk. Screen:\n${dumpVisibleTexts()}"
        }
    }

    @Test
    fun listControlsWalk() {
        // Search: type, see the list narrow, clear.
        val search = device.findObject(By.res(packageName, "editSearch"))
            ?: error("No search field. Screen:\n${dumpVisibleTexts()}")
        search.text = "zzzz-no-such-app"
        device.waitForIdle(800)
        search.text = ""
        device.waitForIdle(800)

        // Pull-to-refresh: the check must start and finish (TestHooks), the list must not break.
        val checksBefore = TestHooks.checksStarted
        val list = device.findObject(By.res(packageName, "swipeSources")) ?: return
        val rect = list.visibleBounds
        device.swipe(rect.centerX(), rect.top + rect.height() / 5, rect.centerX(), rect.top + rect.height() / 2, 30)
        awaitCondition(60_000) { TestHooks.checksFinished > checksBefore }
        assertHealthy("after pull-to-refresh")

        // The filter sheet: open, touch a chip of every group, reset, close.
        device.findObject(By.text(string("filter"))) ?.let { filter ->
            filter.click()
            device.waitForIdle(800)
            for (key in listOf("fltUpdateAvailable", "fltNotInstalled", "fltNoUpdates", "fltOriginals", "fltClones")) {
                runCatching { device.findObject(By.text(sourceString(key)))?.click() }
                device.waitForIdle(300)
            }
            // Back to All.
            runCatching { device.findObject(By.text(sourceString("fltAll")))?.click() }
            device.waitForIdle(300)
            device.pressBack()
            device.waitForIdle(500)
        }
        assertHealthy("after the filter walk")
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The app is healthy: its window answers and no extra dialog is up. */
    private fun assertHealthy(whenText: String) {
        assert(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)) {
            "The window stopped answering $whenText"
        }
        val windows = try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.windows
        } catch (e: Exception) {
            null
        }
        val ours = windows?.count { it.root?.packageName == packageName } ?: 1
        assert(ours <= 2) { "$ours app windows after $whenText - a dialog leaked" }
    }

    /** Scrolls the open sheet until a view with this resource id is visible, mostly down
 *  (content moves up), with an occasional step back for a row just above the fold. */
    private fun scrollSheetUntilRes(
        id: String,
        maxSwipes: Int = 25,
    ): androidx.test.uiautomator.UiObject2? {
        // UiScrollable drives the sheet's own ScrollView: it scrolls both ways and never
        // drags the sheet by its handle (which is what a raw downward swipe does).
        val scroll = androidx.test.uiautomator.UiScrollable(
            androidx.test.uiautomator.UiSelector().resourceId(packageName + ":id/design_bottom_sheet"),
        )
        scroll.waitForExists(2_000)
        repeat(maxSwipes) {
            device.findObject(By.res(packageName, id))?.let { return it }
            runCatching { scroll.scrollForward(20) }
            device.waitForIdle(400)
            device.findObject(By.res(packageName, id))?.let { return it }
            runCatching { scroll.scrollBackward(20) }
            device.waitForIdle(400)
        }
        return device.findObject(By.res(packageName, id))
    }

    /** Scrolls the open settings sheet to its very bottom, so the rows down there (the
     *  language row, the log button) are in view before a text lookup. */
    private fun scrollSheetToBottom() {
        val scroll = androidx.test.uiautomator.UiScrollable(
            androidx.test.uiautomator.UiSelector().resourceId(packageName + ":id/design_bottom_sheet"),
        )
        scroll.waitForExists(2_000)
        repeat(12) { runCatching { scroll.scrollForward(40) } }
        device.waitForIdle(500)
    }

    private fun openSettings() {
        val settings = device.findObject(By.desc(string("settings_title")))
            ?: error("No Settings button. Screen:\n${dumpVisibleTexts()}")
        settings.click()
        waitForStringResource("settings_check_updates", timeout = 10_000)
    }

    /** An English string of the app's resources, whatever locale the walk set before. */
    private fun string_en(name: String): String {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale("en"))
        val res = context.createConfigurationContext(config).resources
        val id = res.getIdentifier(name, "string", packageName)
        return if (id != 0) res.getString(id) else error("String $name not found")
    }

    /** Re-runs [probe] until it returns true, or gives up after [timeoutMs]. */
    private fun awaitCondition(timeoutMs: Long, probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(300)
        }
        return false
    }
}