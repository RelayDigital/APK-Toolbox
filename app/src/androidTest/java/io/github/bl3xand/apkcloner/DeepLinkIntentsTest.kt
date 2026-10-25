package io.github.bl3xand.apkcloner

import android.content.Intent
import android.net.Uri
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Every intent the app answers beyond a plain launch (matrix A18-A23, I2): the reference
 * app's deep links, a link or a linkless text shared in, and APK files shared in. All are
 * driven in-process - the app is the test, so context.startActivity is the same door the
 * system's resolver would use - and MainActivity's singleTop handling brings each intent to
 * the already-running instance through onNewIntent, which is the path a real share takes
 * when the app is already open.
 *
 * The deep-link payloads:
 * - obtainium://add?url=<the fake source's URL> - the add sheet opens with the URL filled in;
 * - obtainium://app/<json> - a confirmation sheet, then one app imported;
 * - obtainium://apps/<array> - the same, for several;
 * - obtainium://refresh?id=<a tracked app> - a check of that one app;
 * - ACTION_SEND text/plain with a URL - the add sheet, prefilled;
 * - ACTION_SEND text/plain without a URL - the "no source matches" toast;
 * - ACTION_SEND of an APK file - the "install as-is or merge" sheet.
 */
class DeepLinkIntentsTest : BaseUiTest() {

    private val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")

    @Test
    fun addAndRefreshDeepLinksAndSharedText() {
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // ---- obtainium://add - the add sheet opens prefilled -------------------------------
            sendIntent(Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://add?url=${FakeApkServer.APK_URL}")))
            // The sheet's heading, then the URL in its field.
            device.wait(Until.findObject(By.text(string("addApp"))), 10_000)
                ?: error("The add sheet did not open from obtainium://add. Screen:\n${dumpVisibleTexts()}")
            val field = device.findObject(By.clazz("android.widget.EditText"))
                ?: error("No URL field in the add sheet. Screen:\n${dumpVisibleTexts()}")
            assert(field.text.toString() == FakeApkServer.APK_URL) {
                "The add sheet's field says \"${field.text}\", not the linked URL"
            }
            device.pressBack()
            device.waitForIdle(1_000)

            // ---- ACTION_SEND of a text with a URL - the same sheet, prefilled ------------------
            sendIntent(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Look at this ${FakeApkServer.APK_URL} please"),
            )
            device.wait(Until.findObject(By.text(string("addApp"))), 10_000)
                ?: error("The add sheet did not open from a shared link. Screen:\n${dumpVisibleTexts()}")
            val sharedField = device.findObject(By.clazz("android.widget.EditText"))
                ?: error("No URL field in the add sheet (shared text). Screen:\n${dumpVisibleTexts()}")
            assert(sharedField.text.toString() == FakeApkServer.APK_URL) {
                "The shared URL did not land in the field: \"${sharedField.text}\""
            }
            // Fill the sheet through: Add runs the download, the app's page opens - the deep
            // link is not a dead end.
            device.waitForIdle(1_000)
            device.findObject(By.text(string("add")))!!.click()
            assert(device.wait(Until.hasObject(By.textContains(packageName)), 60_000)) {
                "The tracked app's page did not open after adding through the shared link"
            }
            device.pressBack()
            device.waitForIdle(2_000)

            // ---- ACTION_SEND of a text with no URL - the "no source matches" toast -------------
            sendIntent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "just words"))
            // A toast: transient, so its text is waited for and allowed to leave again.
            val toast = sourceString("urlMatchesNoSource")
            assert(device.wait(Until.hasObject(By.text(toast)), 10_000)) {
                "No \"${toast}\" toast for a linkless shared text. Screen:\n${dumpVisibleTexts()}"
            }

            // ---- obtainium://refresh?id=<the tracked app> - that app is checked -----------------
            // A single-app check goes through checkUpdate, which the hooks do not count
            // (only whole passes are); the check's own witness is the server: it is asked
            // for the file again after the link fires.
            val appId = storedTrackedApp().getJSONObject(0).getString("id")
            val hitsBefore = server.hitCount.getValue("full") + server.hitCount.getValue("range")
            sendIntent(Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://refresh?id=$appId")))
            assert(awaitCondition(60_000) {
                server.hitCount.getValue("full") + server.hitCount.getValue("range") > hitsBefore
            }) {
                "The refresh deep link did not run a check of $appId (server hits did not grow)"
            }

            // ---- obtainium://app/<json> - a confirmation, then an import of one app -------------
            // The link carries the app's own JSON object (the reference app's export entry
            // shape), URL-encoded - the way these links are built, since raw braces and
            // quotes do not survive a Uri intact.
            val payload = appConfig("app.from.link.one", "https://example.com/one.apk").toString()
            sendIntent(Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://app/${android.net.Uri.encode(payload)}")))
            // The confirmation sheet shows the payload before importing anything.
            device.wait(Until.findObject(By.textContains("app.from.link.one")), 10_000)
                ?: error("The app deep link did not show its payload. Screen:\n${dumpVisibleTexts()}")
            device.findObject(By.text(string("continue")))!!.click()
            assert(awaitCondition(30_000) { repoHasApp("app.from.link.one") }) {
                "The app deep link did not import the app after confirming"
            }

            // ---- obtainium://apps/<array> - several at once -------------------------------------
            // The link carries a JSON array of app objects.
            val array = JSONArray()
                .put(appConfig("app.from.link.two", "https://example.com/two.apk"))
                .put(appConfig("app.from.link.three", "https://example.com/three.apk"))
                .toString()
            sendIntent(Intent(Intent.ACTION_VIEW, Uri.parse("obtainium://apps/${android.net.Uri.encode(array)}")))
            device.wait(Until.findObject(By.textContains("app.from.link.two")), 10_000)
                ?: error("The apps deep link did not show its payload. Screen:\n${dumpVisibleTexts()}"
                )
            device.findObject(By.text(string("continue")))!!.click()
            assert(awaitCondition(30_000) { repoHasApp("app.from.link.two") && repoHasApp("app.from.link.three") }) {
                "The apps deep link did not import both apps"
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun sharedApkFileOffersInstallOrMerge() {
        // One APK file shared in: the "what to do with these files?" sheet, with an Install
        // action and a Merge action (the merge walk itself is the SAI suite's business; this
        // test stops at the fork being offered, then dismisses).
        sendIntent(
            Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(apk)),
        )
        val title = device.wait(Until.findObject(By.text(string("files_received_title"))), 15_000)
            ?: error("The files-received sheet did not open. Screen:\n${dumpVisibleTexts()}")
        assert(title != null)
        // Both actions of the fork are offered.
        assert(device.findObject(By.text(string("files_action_install"))) != null) {
            "No Install action in the files-received sheet. Screen:\n${dumpVisibleTexts()}"
        }
        assert(device.findObject(By.text(string("files_action_merge"))) != null) {
            "No Merge action in the files-received sheet. Screen:\n${dumpVisibleTexts()}"
        }
        // Dismiss the fork; nothing was loaded.
        device.pressBack()
        device.waitForIdle(1_000)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** One app in the reference app's export format, the shape importJson reads. */
    private fun appConfig(id: String, url: String): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("name", id)
        .put("author", "e2e")
        .put("preferredApkIndex", 0)
        .put("apkUrls", JSONArray().put(JSONArray().put("placeholder").put(url)).toString())
        .put("additionalSettings", "{}")

    private fun repoHasApp(id: String): Boolean =
        io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).entry(id) != null

    private fun storedTrackedApp(): JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return JSONArray(files.map { JSONObject(it.readText()) })
    }

    /**
     * Fires [intent] at the running activity the way the system would resolve it: the app's
     * own action for the launch, the given data and type. MainActivity is singleTop and the
     * app is running, so the intent arrives through onNewIntent - the path a share from a
     * file manager takes.
     */
    private fun sendIntent(intent: Intent) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.component = launch.component
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
        device.waitForIdle(2_000)
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