package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.update.AutoUpdateWorker
import io.github.bl3xand.apkcloner.testing.TestHooks
import java.io.File
import org.json.JSONObject
import org.junit.Test

/** The notification id of the "No updates" answer, the same constant the object itself uses. */
private val UP_TO_DATE_ID = 2

/**
 * "Check now" with nothing to update answers with the "No updates" notification (matrix F2):
 * the settings sheet's Check now button runs the manual pass, and when neither a clone nor a
 * tracked app has anything pending, UpdateNotifications.showUpToDate is the only answer. The
 * notification is asserted through the test hooks' ring (docs/test-hooks.md), which records
 * every posting - no shade-walking, no dumpsys.
 *
 * The tracked app is the app itself, freshly added from the fake source: its installed
 * version IS the latest one, so the check finds nothing, which is exactly the cell.
 */
class CheckNowUpToDateTest : BaseUiTest() {

    @Test
    fun checkNowWithNothingDueAnswersUpToDate() {
        val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")
        assert(apk.exists()) { "No test APK at ${apk.path} - scripts/test-ui.sh pushes one before the run" }
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // One tracked app, at its latest version: the app itself, served by the fake
            // source at exactly the installed build. The add's own check reconciles the
            // installed version with the offered one.
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            val stored = storedTrackedApp().getJSONObject(0)
            check(stored.optString("installedVersion") == stored.optString("latestVersion")) {
                "The tracked app is not at its latest version (${stored.optString("installedVersion")} vs " +
                    "${stored.optString("latestVersion")}); the up-to-date cell is not what this run has"
            }

            // Check now: Settings, then the button. The run is the worker's own manual entry
            // point (KEY_MANUAL), the same one the button queues.
            val settingsButton = device.findObject(By.desc(string("settings_title")))
                ?: error("No Settings button. Screen:\n${dumpVisibleTexts()}")
            settingsButton.click()
            val checkNow = scrollSheetUntil(string("button_check_now"))
                ?: error("No Check now button in Settings. Screen:\n${dumpVisibleTexts()}")
            val runsBefore = TestHooks.backgroundCheckRunsStarted
            val upToDateBefore = TestHooks.hasNotification(UP_TO_DATE_ID)
            checkNow.click()

            // The manual pass ran and finished - its whole story, retries included, is one
            // started/finished pair on the hooks.
            assert(awaitCondition(120_000) { TestHooks.backgroundCheckRunsFinished > runsBefore }) {
                "The manual check pass did not finish"
            }
            // The answer: the up-to-date notification, posted after the pass began.
            assert(awaitCondition(15_000) { upToDateNotificationArrived(upToDateBefore) }) {
                "No up-to-date notification after a check with nothing due. Notifications seen: " +
                    TestHooks.notifications.joinToString()
            }
            val note = TestHooks.notification(UP_TO_DATE_ID)
            assert(note != null && note.title == string("notification_up_to_date")) {
                "The available-id notification is not the up-to-date one: $note"
            }
        } finally {
            server.stop()
        }
    }

    /**
     * Whether the up-to-date notification arrived. The id (ID_AVAILABLE) is shared with the
     * "updates available" note, so an arrival is a NEW record after the pass started - a
     * record that was already there before the check is yesterday's news, not the answer.
     */
    private fun upToDateNotificationArrived(existedBefore: Boolean): Boolean {
        val note = TestHooks.notification(UP_TO_DATE_ID) ?: return false
        return !existedBefore || note.title == string("notification_up_to_date")
    }

    private fun storedTrackedApp(): org.json.JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return org.json.JSONArray(files.map { JSONObject(it.readText()) })
    }

    /** Opens the add sheet, types the URL, presses Add, and waits for the sheet to finish. */
    private fun addAppByUrl(url: String) {
        val add = device.findObject(By.text(string("sources_add")))
            ?: error("No Add app button. Screen:\n${dumpVisibleTexts()}")
        add.click()
        val addAppHeading = string("addApp")
        device.wait(Until.findObject(By.text(addAppHeading)), 10_000)
            ?: error("The add sheet did not open. Screen:\n${dumpVisibleTexts()}")
        val field = device.findObject(By.clazz("android.widget.EditText"))
            ?: error("No URL field in the add sheet. Screen:\n${dumpVisibleTexts()}")
        field.text = url
        device.waitForIdle(1_000)
        device.findObject(By.text(string("add")))!!.click()
        device.wait(Until.gone(By.text(addAppHeading)), 60_000)
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