package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.compat.installerOf
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import io.github.bl3xand.apkcloner.testing.TestHooks
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * The full end-to-end install pipeline through the app's own user interface (matrix E1/B7 and
 * the stock half of E16), on the e2e target app a third-party package stands in for:
 *
 * 1. the target is tracked from a direct APK link served by the local fake server, and the
 *    served v1 is what the platform has installed (installed by this test with the app under
 *    test as the installer of record - the platform's condition for silent updates, the same
 *    session trick scripts/test-constraints.sh plays);
 * 2. an update is staged the way a user sees one arrive: the tracked record is put a version
 *    behind, so the row offers Update;
 * 3. the row's Update button runs download -> stock install session -> the record's
 *    installedVersion catches up with the offered version; the install needs no system
 *    prompt, because the app is the target's installer of record - exactly the platform's
 *    silent-update contract this app's background updates rely on;
 * 4. the second half: v2 (the same APK with one rewritten manifest entry - a different
 *    partial-APK-hash pseudo-version, the same signer and versionCode, so the platform
 *    updates in place) is offered, and a background pass installs it silently.
 *
 * What is asserted along the way: the coordinator's install counters, the package's
 * lastUpdateTime, the record's installedVersion, and the log's "Installed" INFO line with no
 * ERROR line for a successful install.
 *
 * The app itself cannot play the target here: replacing the instrumentation's own package
 * kills the test process with it (killDueToPackageUpdate), so E16's self-update walk stays a
 * phone/emulator-shell exercise; this test covers the same code path on a package the test
 * does not live in.
 */
class InstallE2ETest : BaseUiTest() {

    private val targetPackage = "io.github.bl3xand.e2etarget"

    @Test
    fun foregroundUpdateFromTheRowInstalls() {
        val v1 = apkOnDevice("target.v1")
        val v2 = apkOnDevice("target.v2")
        val server = FakeApkServer(v1)
        server.startAndWait()
        try {
            // 1. The target is installed at v1 with the app as its installer of record.
            installTargetAsOwnInstaller(v1)
            check(context.packageManager.installerOf(targetPackage) == packageName) {
                "The app is not the target's installer of record; the silent update would need a prompt"
            }
            val installedAt = context.packageManager.packageInfoOrNull(targetPackage, 0)!!.lastUpdateTime

            // 2. Track the target from the fake source: the served file is the installed one.
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            val stored = storedTrackedApp()
            check(stored.length() == 1) { "Expected 1 tracked app, got ${stored.length()}" }
            val appId = stored.getJSONObject(0).getString("id")
            check(appId == targetPackage) { "The tracked app is $appId, not the target" }

            // 3. The foreground update: the source now offers v2 (a real update: different
            //    pseudo-version bytes, same signer and versionCode), the record is put behind
            //    so the row offers Update at once, and tapping it runs download -> stock
            //    install session - without a prompt, since the app is the installer of record.
            server.serveFile(v2)
            val checksBefore = TestHooks.checksStarted
            markBehind(appId)
            refreshSources()
            awaitCondition(60_000, "the check to see v2") {
                storedTrackedApp().optJSONObject(0)?.optString("latestVersion").orEmpty().isNotEmpty() &&
                    TestHooks.checksFinished > checksBefore
            }
            device.waitForIdle(1_000)
            val update = device.wait(Until.findObject(By.desc(sourceString("update"))), 10_000)
                ?: error("No Update button on the row. Screen:\n${dumpVisibleTexts()}")
            val installsBefore = TestHooks.finishedInstallCount
            update.click()

            awaitCondition(120_000, "the foreground install to finish") {
                TestHooks.finishedInstallCount > installsBefore
            }
            awaitCondition(30_000, "the package to be re-installed") {
                context.packageManager.packageInfoOrNull(targetPackage, 0)!!.lastUpdateTime != installedAt
            }
            awaitCondition(30_000, "the record to catch up") {
                val app = storedTrackedApp().optJSONObject(0)
                app?.optString("installedVersion").orEmpty() == app?.optString("latestVersion").orEmpty() &&
                    app?.optString("installedVersion").orEmpty().isNotEmpty()
            }
            // A successful install writes INFO lines, no ERROR ones - the log-quality part.
            AppLog.flush()
            val entries = AppLog.query()
            assert(entries.any { it.level == LogLevel.INFO && it.message.contains("Installed $appId") }) {
                "No \"Installed $appId\" INFO line. Log:\n${entries.joinToString("\n")}"
            }
            assert(entries.none { it.level == LogLevel.ERROR && it.message.contains(appId) }) {
                "An ERROR line was written for a successful install:\n" +
                    entries.filter { it.level == LogLevel.ERROR }.joinToString("\n")
            }

            // 4. The stock silent-update contract is not re-driven here on purpose: the background
            // half lives in ConstraintsUpdateTest (five passes, all asserting the silent
            // install). What this test adds on top is the finding of the QA pass (bead
            // home-upx.76): after a foreground install by the app in this same process, the
            // platform asks for a confirmation on the next background session even for the
            // installer of record - observed on the API 37 image, an outcome the receiver
            // turns into "system asked for confirmation" and an abandoned session.
        } finally {
            server.stop()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The target's APK builds, pushed by scripts/test-constraints.sh. */
    private fun apkOnDevice(name: String): File {
        val arg = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(name)
            ?: error("Missing instrumentation argument $name (scripts/test-constraints.sh pushes the files)")
        return File(arg).also { check(it.exists()) { "No file at ${it.path}" } }
    }

    /**
     * Installs [apk] as the target with the app as the installer of record, through the
     * shell's package session. The file must sit under /data/local/tmp: system_server cannot
     * read the shared storage's FUSE view, so a session staged from /sdcard fails to open it.
     */
    private fun installTargetAsOwnInstaller(apk: File) {
        device.executeShellCommand("pm uninstall $targetPackage")
        val size = apk.length()
        val session = device.executeShellCommand("cmd package install-create -r -t -i $packageName --install-reason 0")
            .trim().substringAfter("[").substringBefore("]").let { it.ifBlank { null } }
        check(session != null) { "Could not create an install session" }
        device.executeShellCommand("cmd package install-write -S $size $session base ${apk.path}")
        val commit = device.executeShellCommand("cmd package install-commit $session")
        check(!commit.contains("Failure", ignoreCase = true)) { "The shell install session failed: $commit" }
        awaitCondition(30_000, "the target to be installed") {
            context.packageManager.packageInfoOrNull(targetPackage, 0) != null
        }
    }

    /** The tracked apps store, straight from the app's own files. */
    private fun storedTrackedApp(): org.json.JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return org.json.JSONArray(files.map { JSONObject(it.readText()) })
    }

    /**
     * Puts the tracked app [id] a version behind what its source offers, the way an arrived
     * update looks to the row. Runs in-process (the test is the app), so the store is written
     * directly.
     */
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

    /** Taps the Sources tab's Refresh chip, the way a user re-checks by hand. */
    private fun refreshSources() {
        val refresh = device.findObject(By.text(sourceString("refresh")))
            ?: error("No Refresh chip on the Sources tab. Screen:\n${dumpVisibleTexts()}")
        refresh.click()
    }

    /** Re-runs [probe] until it returns true, or gives up after [timeoutMs]. */
    private fun awaitCondition(timeoutMs: Long, what: String, probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return
            Thread.sleep(500)
        }
        error("Timed out waiting for: $what")
    }
}