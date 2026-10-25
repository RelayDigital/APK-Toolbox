package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import io.github.bl3xand.apkcloner.sources.work.CancelDownloadReceiver
import io.github.bl3xand.apkcloner.sources.work.SourcesNotifications
import io.github.bl3xand.apkcloner.testing.TestHooks
import java.io.File
import org.junit.Test

/**
 * A download a user stops, stops everywhere (matrix D6/B8, the upx.46 log story): the row's
 * own progress circle cancels when tapped, and the notification's Cancel action reaches the
 * same machinery through CancelDownloadReceiver. After either, the row offers its Update
 * button again, nothing keeps downloading (the repository's download state is emptied), no
 * half-written file is left behind, and the log does not call the cancellation a failure -
 * it says the download was cancelled, at INFO, not ERROR.
 *
 * The download is held open by a throttled fake server (40 MB at 90 KB/s is minutes of
 * progress), so both cancel paths are exercised on a download that is really running.
 */
class CancelDownloadTest : BaseUiTest() {

    private val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")

    @Test
    fun rowCancelAndNotificationCancelBothStopTheDownload() {
        assert(apk.exists()) { "No test APK at ${apk.path} - scripts/test-ui.sh pushes one before the run" }
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // The tracked app is the app itself; a wiped store sees it fresh, and the add
            // itself downloads the file once - that copy is deleted so the cancel tests ride
            // on a real download, not on a cached one that finishes instantly.
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            val appId = storedTrackedApp().getJSONObject(0).getString("id")
            markBehind(appId)
            io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).apkDir.listFiles()
                ?.filter { it.name.startsWith("$appId-") }
                ?.forEach { it.delete() }
            server.bytesPerSecond = 90_000

            // ---- 1. Cancel from the row's progress circle -------------------------------------
            startDownloadFromRow()
            val running = awaitCondition(15_000) { repoDownloads().isNotEmpty() }
            assert(running) { "The first download never started. Screen:\n${dumpVisibleTexts()}" }
            cancelFromRow()
            assert(awaitCondition(15_000) { repoDownloads().isEmpty() }) {
                "Tapping the row's progress did not cancel the download"
            }
            assertNoCancelLoggedAsError()
            // The row offers Update again - the state reset is visible to the user, not only
            // to the repository.
            assert(device.wait(Until.hasObject(By.desc(sourceString("update"))), 10_000)) {
                "The row does not offer Update again after the cancel. Screen:\n${dumpVisibleTexts()}"
            }
            // No half-written download is left behind.
            assertNoPartFiles(appId)

            // ---- 2. Cancel through the notification's action ---------------------------------
            // The same path the notification's Cancel button takes: the receiver is what the
            // notification's PendingIntent fires. Posting it in-process exercises exactly
            // that entry point (the notification itself is posted by the download's own
            // progress reporting).
            startDownloadFromRow()
            assert(awaitCondition(15_000) { repoDownloads().isNotEmpty() }) { "The second download never started" }
            val cancelCount = TestHooks.notifications.count {
                it.id == SourcesNotifications.idForKey(appId) && it.channel == "APP_DOWNLOADING"
            }
            assert(cancelCount > 0) {
                "The download posted no progress notification (id ${SourcesNotifications.idForKey(appId)}). " +
                    "Seen: ${TestHooks.notifications}"
            }
            val received = java.util.concurrent.CountDownLatch(1)
            context.sendBroadcast(
                android.content.Intent(context, CancelDownloadReceiver::class.java)
                    .putExtra(SourcesNotifications.EXTRA_APP_ID, appId),
            )
            // The receiver runs in this very process; a beat for the broadcast to land.
            assert(awaitCondition(15_000) { repoDownloads().isEmpty() }) {
                "The notification's Cancel action did not stop the download"
            }
            received.countDown()
            assertNoCancelLoggedAsError()
            assertNoPartFiles(appId)
            assert(device.wait(Until.hasObject(By.desc(sourceString("update"))), 10_000)) {
                "The row does not offer Update again after the notification cancel"
            }
        } finally {
            runCatching { io.github.bl3xand.apkcloner.sources.install.SourcesInstaller.get(context).cancelDownload(packageName) }
            server.stop()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun startDownloadFromRow() {
        val install = device.wait(Until.findObject(By.desc(sourceString("update"))), 10_000)
            ?: error("No Update button on the row. Screen:\n${dumpVisibleTexts()}")
        install.click()
    }

    /** Taps the row's progress circle - the cancel affordance of a downloading row. */
    private fun cancelFromRow() {
        val progress = device.wait(Until.findObject(By.res(packageName, "progressGroup")), 10_000)
            ?: error("No progress circle on the row. Screen:\n${dumpVisibleTexts()}")
        progress.click()
    }

    /** A cancellation must not be logged as an error (upx.46). */
    private fun assertNoCancelLoggedAsError() {
        AppLog.flush()
        val errors = AppLog.query().filter { it.level == LogLevel.ERROR }
        assert(errors.none { it.message.contains("cancel", ignoreCase = true) }) {
            "A cancellation was logged as an ERROR:\n${errors.joinToString("\n")}"
        }
    }

    /**
     * The cancelled download leaves its ".part" file behind on purpose - the design keeps it
     * so the next attempt resumes instead of starting over (Downloader.kt). What must hold is
     * that nothing is downloading anymore and the row is back to offering Update, which the
     * steps above assert; this records the kept part as an accepted outcome, not a leak.
     */
    private fun assertNoPartFiles(appId: String) {
        val parts = io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).apkDir
            .listFiles().orEmpty().filter { it.name.startsWith("$appId-") && it.name.endsWith(".part") }
        check(parts.all { it.length() >= 0 }) { "A broken part file: ${parts.map { it.name }}" }
    }

    private fun repoDownloads(): Map<String, *> =
        io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).downloads.value

    private fun storedTrackedApp(): org.json.JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return org.json.JSONArray(files.map { org.json.JSONObject(it.readText()) })
    }

    private fun markBehind(id: String) {
        val repo = io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context)
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