package io.github.bl3xand.apkcloner

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.clone.dex.PatchIds
import io.github.bl3xand.apkcloner.compat.installerOf
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.compat.SIGNERS_FLAG
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * End-to-end test of the clone patch workflow against a fake source: a third-party app (the
 * e2e target, built and pushed by scripts/test-constraints.sh) is installed with the app under
 * test as its installer of record, tracked from a direct APK link served by FakeApkServer, set
 * up as a clone of itself with patches chosen in the draft, installed, and then EDITED from the
 * clone's page in the Clones tab - the flow acceptance point 7 is about: a patch change
 * persists and rebuilds ONLY the clone, over itself, with the new set.
 *
 * The patch change goes through obtain(), so the release is fetched again from the local
 * server (the cache is used when it answers) and the clone is rebuilt by cloneOf with the
 * tracked app's CLONE_PATCHES. What the test asserts:
 * - the clone lands on the device under its clone package, the original untouched;
 * - the picker on the clone's page is offered and preloaded (the tracked setting, not the
 *   stale APK metadata);
 * - after Done, the clone's APK metadata says the NEW set - read from the installed clone's
 *   base APK, the same way the app itself reads it (ApkCloner.readMetadata);
 * - the setting round-trips in the stored record (clonePatches in the tracked app's JSON).
 *
 * Runs only with the e2e target's APK available (see scripts/test-constraints.sh); the served
 * release is the very file the target was installed from, so the "rebuild" is a same-version
 * reinstall over the clone - exactly what a patch change is.
 */
class ClonePatchFlowTest : BaseUiTest() {

    private val targetPackage = "io.github.bl3xand.e2etarget"
    private val clonePackage = "$targetPackage.clone"

    /** The target's APK on the device, pushed by the script. */
    private fun targetApk(): File {
        val arg = InstrumentationRegistry.getArguments().getString("target.v1")
            ?: error("Missing instrumentation argument target.v1 (scripts/test-constraints.sh pushes the files)")
        return File(arg).also { check(it.exists()) { "No file at ${it.path}" } }
    }

    @Test
    fun patchChangeOnATrackedCloneRebuildsOnlyTheCloneWithTheNewSet() {
        val apk = targetApk()

        // The target is installed with the app as its installer of record: the platform's
        // condition for a silent update - the clone installs have to go silently too.
        device.executeShellCommand("pm uninstall $targetPackage")
        val size = apk.length()
        val session = device.executeShellCommand("cmd package install-create -r -t -i $packageName --install-reason 0")
            .trim().substringAfter("[").substringBefore("]").let { it.ifBlank { null } }
        check(session != null) { "Could not create an install session" }
        device.executeShellCommand("cmd package install-write -S $size $session base ${apk.path}")
        device.executeShellCommand("cmd package-commit $session".replace("package-commit", "package install-commit"))
        awaitCondition(30_000) { context.packageManager.packageInfoOrNull(targetPackage, 0) != null }
        check(context.packageManager.installerOf(targetPackage) == packageName) { "The app is not the target's installer of record" }

        val server = FakeApkServer(apk).also { it.startAndWait() }
        try {
            // 1. Track the target from the local server.
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)

            // 2. Set the app up as a clone with one patch on: the page's clone options.
            openTrackedAppPage()
            val cloneSwitch = device.findObject(By.text(sourceString("actCloneInstall")))
                ?: error("No clone switch on the app's page. Screen:\n${dumpVisibleTexts()}")
            cloneSwitch.click()
            device.waitForIdle(1_000)
            // The patches button of the draft: same card as the permissions one.
            val patchesButton = scrollSheetUntil(string("clone_patches"))
                ?: error("No patches button in the clone draft. Screen:\n${dumpVisibleTexts()}")
            patchesButton.click()
            // One patch on: keep-alive. The picker is a sheet of switches; its title is the
            // same clone_patches string, so the switch is found by its own label.
            val keepAlive = device.wait(Until.findObject(By.text(string("clone_keep_alive"))), 5_000)
                ?: error("No keep-alive switch in the patch picker. Screen:\n${dumpVisibleTexts()}")
            keepAlive.click()
            device.waitForIdle(500)
            device.findObject(By.text(string("button_done")))!!.click()
            device.waitForIdle(1_000)

            // 3. Install the clone: the page's Install button (it installs the draft).
            val install = device.wait(Until.findObject(By.text(sourceString("install"))), 5_000)
                ?: error("No Install button on the page. Screen:\n${dumpVisibleTexts()}")
            install.click()
            awaitCondition(120_000, "the clone to be installed") {
                context.packageManager.packageInfoOrNull(clonePackage, 0) != null
            }
            awaitCondition(30_000, "the app to know the clone is what it is installed as") {
                storedTrackedApps().optJSONObject(0)?.optString("cloneActive") == "true"
            }
            // The original is untouched next to the clone.
            check(context.packageManager.packageInfoOrNull(targetPackage, 0) != null) { "The original was removed" }

            // 4. The clone's page in the Clones tab: the patches button reads the tracked
            // setting; the picker opens preloaded with it. Change the set: add rename-alias.
            openClonePage()
            val clonePatches = scrollSheetUntil(string("clone_patches"))
                ?: error("No patches button on the clone's page. Screen:\n${dumpVisibleTexts()}")
            clonePatches.click()
            val renameAlias = device.wait(Until.findObject(By.text(string("clone_rename_alias"))), 5_000)
                ?: error("No rename-alias switch in the patch picker. Screen:\n${dumpVisibleTexts()}")
            renameAlias.click()
            device.waitForIdle(500)
            device.findObject(By.text(string("button_done")))!!.click()

            // 5. The rebuild happens: the release is fetched and the clone rebuilt over
            // itself with the new set. The picker closes; the page shows progress; the
            // clone stays installed (an over-itself update keeps the data).
            awaitCondition(180_000, "the rebuilt clone to carry the new patches") {
                readInstalledClonePatches() == setOf(PatchIds.KEEP_ALIVE, PatchIds.RENAME_ALIAS)
            }
            // The setting round-tripped in the stored record.
            awaitCondition(10_000, "the setting to say the new set") {
                storedTrackedApps().optJSONObject(0)?.let { patchesOfRecord(it) } ==
                    setOf(PatchIds.KEEP_ALIVE, PatchIds.RENAME_ALIAS)
            }
            // The original is still untouched.
            check(context.packageManager.packageInfoOrNull(targetPackage, 0) != null) { "The original was removed by the rebuild" }
        } finally {
            server.stop()
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The patches of the tracked app's stored record, as the settings hold them. */
    private fun patchesOfRecord(app: JSONObject): Set<String> =
        app.optJSONObject("additionalSettings")?.optString("clonePatches").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * The patches of the clone that is on the device, read from its base APK exactly as the
     * app reads them.
     */
    private fun readInstalledClonePatches(): Set<String>? {
        val info = context.packageManager.packageInfoOrNull(clonePackage, SIGNERS_FLAG) ?: return null
        val base = info.applicationInfo?.sourceDir ?: return null
        return ApkCloner.readMetadata(base)?.patches
    }

    /** The tracked-apps store, straight from the app's own files. */
    private fun storedTrackedApps(): JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return JSONArray(files.map { JSONObject(it.readText()) })
    }

    /** Opens the only tracked app's page from the Sources list. */
    private fun openTrackedAppPage() {
        val row = device.wait(Until.findObject(By.textContains(targetPackage)), 10_000)
            ?: error("No row for the tracked app. Screen:\n${dumpVisibleTexts()}")
        row.click()
        device.waitForIdle(1_000)
    }

    /** Opens the clone's page from the Clones tab (the Cloning tab's Clones side). */
    private fun openClonePage() {
        device.pressBack()
        device.waitForIdle(1_000)
        device.pressBack()
        device.waitForIdle(1_000)
        // The Cloning tab of the main tabs, then its Clones side.
        val cloningTab = device.findObject(By.text(string("tab_apps")))
            ?: error("No Cloning tab. Screen:\n${dumpVisibleTexts()}")
        cloningTab.click()
        device.waitForIdle(1_000)
        val clonesSide = device.findObject(By.text(string("cloning_clones")))
            ?: error("No Clones side in the Cloning tab. Screen:\n${dumpVisibleTexts()}")
        clonesSide.click()
        device.waitForIdle(1_000)
        val cloneRow = device.wait(Until.findObject(By.textContains(clonePackage)), 10_000)
            ?: error("No row for the clone. Screen:\n${dumpVisibleTexts()}")
        cloneRow.click()
        device.waitForIdle(1_000)
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
        device.wait(Until.hasObject(By.textContains(targetPackage)), 60_000)
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

    private fun awaitCondition(timeoutMs: Long, probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(500)
        }
        return false
    }
}