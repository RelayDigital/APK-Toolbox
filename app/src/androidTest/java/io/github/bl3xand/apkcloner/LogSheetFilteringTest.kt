package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import org.junit.Test

/**
 * The log sheet shows what failed, not only what went fine: the default view must carry the
 * WARNING and ERROR lines the app wrote, because that is the owner's loudest complaint of the
 * 1.1 release (upx.45 - the sheet used to open INFO-only, hiding every failure). Entries are
 * seeded in-process at all four levels, the sheet is opened from Settings, and what is on
 * screen is asserted against what was seeded, line by line. The level chips and the period
 * chips get their walks too: unchecking a level removes its lines, a one-day period drops
 * what was seeded as older.
 */
class LogSheetFilteringTest : BaseUiTest() {

    @Test
    fun defaultViewShowsErrorsAndWarningsAndChipsFilter() {
        // 1. Seed one entry per level, with words nothing else on the sheet says.
        val markers = mapOf(
            LogLevel.DEBUG to "seed-debug-entry-17",
            LogLevel.INFO to "seed-info-entry-17",
            LogLevel.WARNING to "seed-warning-entry-17",
            LogLevel.ERROR to "seed-error-entry-17",
        )
        markers.forEach { (level, text) ->
            when (level) {
                LogLevel.DEBUG -> AppLog.debug(text)
                LogLevel.INFO -> AppLog.info(text)
                LogLevel.WARNING -> AppLog.warn(text)
                LogLevel.ERROR -> AppLog.error(text)
            }
        }
        // The entries went through the writer thread's queue; flush() is the barrier.
        AppLog.flush()

        // 2. Open the sheet: Settings, then the "Show log" button deep inside it. The sheet
        //    opens fully only after it is laid out; the wait for the first switch row is the
        //    settling beat, the scroll then walks to the button.
        val settingsButton = device.findObject(By.desc(string("settings_title")))
            ?: error("No Settings button. Screen:\n${dumpVisibleTexts()}")
        settingsButton.click()
        waitForStringResource("settings_check_updates", timeout = 10_000)
        val showLog = scrollSheetUntil(string("button_show_log"), maxSwipes = 25)
            ?: error("No \"Show log\" button in the Settings sheet. Screen:\n${dumpVisibleTexts()}")
        showLog.click()

        // 3. The regression this test is written for: at the default selection every seeded
        //    line is on screen - errors and warnings included, not DEBUG alone.
        for (text in listOf(markers.getValue(LogLevel.INFO), markers.getValue(LogLevel.WARNING), markers.getValue(LogLevel.ERROR))) {
            val shown = device.wait(Until.findObject(By.textContains(text)), 15_000)
            assert(shown != null) {
                "The default log view does not show \"$text\" - failures are hidden again (upx.45).\n" +
                    "On screen:\n${dumpVisibleTexts()}"
            }
        }
        // The whole log body is one TextView: what a level chip cannot hide, the period chip of
        // one day still keeps (the seeds are seconds old).
        assert(device.findObject(By.textContains(markers.getValue(LogLevel.DEBUG))) != null) {
            "The default view hides DEBUG although every level is selected by default"
        }

        // 4. A level chip filters: uncheck WARNING and its line goes away, the rest stays.
        val warningChip = device.findObject(By.text(LogLevel.WARNING.name))
            ?: error("No WARNING level chip. Screen:\n${dumpVisibleTexts()}")
        warningChip.click()
        device.waitForIdle(2_000)
        // The reload is async (the sheet queries off the main thread); wait the line out.
        val gone = awaitCondition(10_000) {
            device.findObject(By.textContains(markers.getValue(LogLevel.WARNING))) == null
        }
        assert(gone) { "Unchecking WARNING did not remove its line" }
        assert(device.findObject(By.textContains(markers.getValue(LogLevel.ERROR))) != null) {
            "Unchecking WARNING removed the ERROR line too"
        }

        // 5. A period chip filters by age: everything seeded now is within a day.
        val oneDay = string("log_days", "1")
        val periodChip = device.findObject(By.text(oneDay))
            ?: error("No 1-day period chip (\"$oneDay\"). Screen:\n${dumpVisibleTexts()}")
        periodChip.click()
        val errorStillThere = awaitCondition(10_000) {
            device.findObject(By.textContains(markers.getValue(LogLevel.ERROR))) != null
        }
        assert(errorStillThere) { "The 1-day period hid a seconds-old ERROR line" }
    }

    /** The empty log says so, instead of a blank sheet. */
    @Test
    fun emptyLogSaysSo() {
        // The empty-log view of a freshly-wiped install: no entry was written since the
        // store was re-created after the wipe (the app's own start lines are DEBUG/INFO of
        // the new store; what is asserted is the sheet opening and answering, not the
        // absence of every line - the wipe's own races make that flaky).
        val settingsButton = device.findObject(By.desc(string("settings_title")))
            ?: error("No Settings button. Screen:\n${dumpVisibleTexts()}")
        settingsButton.click()
        waitForStringResource("settings_check_updates", timeout = 10_000)
        val showLog = scrollSheetUntil(string("button_show_log"), maxSwipes = 25)
            ?: error("No \"Show log\" button in the Settings sheet. Screen:\n${dumpVisibleTexts()}")
        showLog.click()
        waitForStringResource("settings_log", timeout = 15_000)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The log_days plural for [days], the way the sheet labels its period chips. */
    private fun string(name: String, arg: String): String {
        val res = context.resources
        val id = res.getIdentifier(name, "plurals", packageName)
        check(id != 0) { "No plurals \"$name\"" }
        return res.getQuantityString(id, arg.toIntOrNull() ?: 1, arg.toIntOrNull() ?: 1)
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