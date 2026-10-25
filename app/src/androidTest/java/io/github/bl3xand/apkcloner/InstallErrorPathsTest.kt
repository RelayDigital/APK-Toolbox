package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * An install that cannot work out, and the app telling about it like a person would (matrix
 * E3/K6): the fake source serves garbage bytes as the "APK", the row's Update button runs
 * the download, and what must follow is a readable ERROR line in AppLog (never a blank one -
 * the second half of the owner's log complaint), the row going back to offering Update, the
 * app alive and well. The garbage is served with an .apk name and APK content type, so the
 * app cannot tell it apart before reading it.
 */
class InstallErrorPathsTest : BaseUiTest() {

    @Test
    fun corruptedApkYieldsReadableErrorAndNoCrash() {
        val server = FakeApkServer(garbageApk())
        server.startAndWait()
        try {
            // Track "the app" from the garbage-serving source. The add itself must survive
            // the garbage: it downloads the file, cannot read it as an APK, and says so -
            // the first readable-error assertion of the test.
            addAppByUrl(FakeApkServer.APK_URL)

            // The add failed: no app was stored, and the error is on the sheet (the URL
            // field's note) and in the log - readable, not blank.
            awaitCondition(30_000) { errorLineInLog() != null }
            val errorLine = errorLineInLog()!!
            assert(errorLine.message.isNotBlank()) {
                "The error line for a corrupted APK is blank - the upx.46 blank-message bug"
            }
            assert(errorLine.message.length > 10) {
                "The error line for a corrupted APK says too little: \"${errorLine.message}\""
            }
            // No tracked app came of it.
            assert(storedTrackedApp().length() == 0) {
                "A corrupted APK was tracked anyway: ${storedTrackedApp()}"
            }
            // The app survived - the sheet is still up and answering (the test process IS the
            // app; being alive to assert is the crash check).
            assert(device.findObject(By.text(string("addApp"))) != null || findString("sources_add") != null) {
                "The add sheet is gone after the corrupted download. Screen:\n${dumpVisibleTexts()}"
            }
            device.pressBack()
            device.waitForIdle(1_000)

            // The second way in: a real tracked app whose update is garbage. The app itself
            // is tracked from the good build first, then the source turns bad.
            server.serveFile(servedApkOnDevice())
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            val appId = storedTrackedApp().getJSONObject(0).getString("id")
            markBehind(appId)
            // Delete the cached copy so the "update" is really downloaded, and turn the
            // source bad from here on.
            SourcesRepository.get(context).apkDir.listFiles()
                ?.filter { it.name.startsWith("$appId-") }
                ?.forEach { it.delete() }
            server.serveFile(garbageApk())

            val update = device.wait(Until.findObject(By.desc(sourceString("update"))), 10_000)
                ?: error("No Update button on the row. Screen:\n${dumpVisibleTexts()}")
            update.click()

            // The install failed and said so: a new ERROR line naming the app (the row's
            // update, not the earlier add).
            awaitCondition(60_000) { appErrorLineInLog(appId) != null }
            val updateError = appErrorLineInLog(appId)!!
            assert(updateError.message.isNotBlank()) { "The update's error line is blank" }
            // The row is back to offering Update - the failure did not wedge the state.
            assert(device.wait(Until.hasObject(By.desc(sourceString("update"))), 30_000)) {
                "The row does not offer Update again after the failed install. Screen:\n${dumpVisibleTexts()}"
            }
            // The repository holds no stuck download state.
            assert(SourcesRepository.get(context).downloads.value.isEmpty()) {
                "A download state outlived the failed install"
            }
        } finally {
            runCatching { io.github.bl3xand.apkcloner.sources.install.SourcesInstaller.get(context).cancelDownload(packageName) }
            server.stop()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A file of garbage bytes where the APK should be. */
    private fun garbageApk(): File {
        val file = File(context.cacheDir, "garbage.apk")
        file.writeBytes(ByteArray(64 * 1024) { (it * 31 + 7).toByte() })
        return file
    }

    /** The first ERROR line in the log, if any. */
    private fun errorLineInLog(): io.github.bl3xand.apkcloner.log.LogEntry? {
        AppLog.flush()
        return AppLog.query().lastOrNull { it.level == LogLevel.ERROR }
    }

    /** The first ERROR line naming [appId]. */
    private fun appErrorLineInLog(appId: String): io.github.bl3xand.apkcloner.log.LogEntry? {
        AppLog.flush()
        return AppLog.query().lastOrNull { it.level == LogLevel.ERROR && it.message.contains(appId) }
    }

    private fun storedTrackedApp(): org.json.JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return org.json.JSONArray(files.map { JSONObject(it.readText()) })
    }

    private fun markBehind(id: String) {
        val repo = SourcesRepository.get(context)
        val entry = repo.entry(id) ?: error("No tracked app $id to put behind")
        repo.saveApps(listOf(entry.app.copy(installedVersion = "0.0")))
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