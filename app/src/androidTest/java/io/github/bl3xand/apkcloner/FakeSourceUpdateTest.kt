package io.github.bl3xand.apkcloner

import android.content.Context
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * End-to-end test of the update pipeline against a fake source: an app is tracked from a direct
 * APK link served by a local HTTP server (NanoHTTPD on the host, reached as 10.0.2.2 from the
 * emulator), an update check runs through the app's own "Check now" button, and the log and the
 * tracked-apps store show what happened.
 *
 * The check itself is driven in-process (the app's own SourcesViewModel machinery is what the
 * background check uses too), because the instrumentation runs inside the app's process: a
 * WorkManager run would work just as well but takes longer to arrive, and the goal here is to
 * catch regressions of the check logic and the UI around it.
 */
class FakeSourceUpdateTest : BaseUiTest() {

    @Test
    fun trackedAppChecksForUpdatesAgainstLocalServer() {
        // The served APK: the app's own debug build, pushed onto the emulator by the script.
        val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")
        assert(apk.exists()) { "No test APK at ${apk.path} - scripts/test-ui.sh pushes one before the run" }
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // 1. Add the app from the URL, the way a user would.
            addAppByUrl(FakeApkServer.APK_URL)

            // The app's page opened from the add sheet: it shows the package (the app itself
            // on a clean emulator) together with the derived version.
            val pageShown = device.wait(Until.findObject(By.textContains(packageName)), 10_000)
            assert(pageShown != null) { "The tracked app's page did not open. Screen:\n${dumpVisibleTexts()}" }

            // 2. The tracked-apps store has the app, with a pseudo-version from the file.
            val stored = storedTrackedApps()
            assert(stored.length() == 1) { "Expected 1 tracked app, got ${stored.length()}" }
            val app = stored.getJSONObject(0)
            assert(app.getString("url") == FakeApkServer.APK_URL) { "Wrong URL stored: ${app.getString("url")}" }
            val firstVersion = app.getString("latestVersion")
            assert(firstVersion.isNotBlank()) { "No version was derived from the APK" }

            // 3. An update check: the button of the Sources tab. The app's page is open over
            // the list; back closes it first.
            device.pressBack()
            device.waitForIdle(2_000)
            val before = System.currentTimeMillis()
            val refresh = device.wait(
                androidx.test.uiautomator.Until.findObject(By.text(sourceString("refresh"))), 10_000,
            )
                ?: error("No Refresh chip on the Sources tab. Screen:\n${dumpVisibleTexts()}")
            refresh.click()

            // 4. The check happened: the server was asked again for the file (a batch check
            // fetches the newest state of every tracked app), and the check time was recorded.
            // A silent batch check writes no INFO log line, so the JSON record is the witness.
            val checkedAfter = awaitCondition(60_000) {
                val after = storedTrackedApps().optJSONObject(0)
                val stamp = after?.optLong("lastUpdateCheck", 0L) ?: 0L
                stamp > 0L && stamp >= before - 2_000L
            }
            assert(checkedAfter) { "The tracked app was not checked again (lastUpdateCheck not updated)" }
            assert(server.hitCount.getValue("range") + server.hitCount.getValue("full") >= 2) {
                "The app did not fetch the served APK again (hits: ${server.hitCount})"
            }

            // The stored record survived the check with the same pseudo-version: the same file,
            // so the same hash.
            val after = storedTrackedApps().getJSONObject(0)
            assert(after.getString("latestVersion") == firstVersion) {
                "Version changed on re-check: $firstVersion -> ${after.getString("latestVersion")}"
            }
        } finally {
            server.stop()
        }
    }

    /** Opens the add sheet, types the URL, presses Add, and waits for the sheet to finish. */
    private fun addAppByUrl(url: String) {
        val add = device.findObject(By.text(string("sources_add")))
            ?: error("No Add app button. Screen:\n${dumpVisibleTexts()}")
        add.click()
        // The sheet's own heading.
        val addAppHeading = string("addApp")
        device.wait(Until.findObject(By.text(addAppHeading)), 10_000)
            ?: error("The add sheet did not open. Screen:\n${dumpVisibleTexts()}")

        // The URL field: the only editable text on the sheet.
        val field = device.findObject(By.clazz("android.widget.EditText"))
            ?: error("No URL field in the add sheet. Screen:\n${dumpVisibleTexts()}")
        field.text = url
        device.waitForIdle(1_000)

        val addButton = device.findObject(By.text(string("add")))
            ?: error("No Add button in the add sheet. Screen:\n${dumpVisibleTexts()}")
        addButton.click()

        // Adding downloads the APK once (to learn its package), then opens the app's page.
        // The sheet goes away; the detail page shows the URL.
        device.wait(Until.gone(By.text(addAppHeading)), 60_000)
    }

    /** Reads the tracked apps straight from the app's own store: filesDir/sources/app_data. */
    private fun storedTrackedApps(): JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return JSONArray(files.map { JSONObject(it.readText()) })
    }

    /** Re-runs [probe] until it returns true, or gives up after [timeoutMs]. */
    private fun awaitCondition(timeoutMs: Long, probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(500)
        }
        return false
    }
}