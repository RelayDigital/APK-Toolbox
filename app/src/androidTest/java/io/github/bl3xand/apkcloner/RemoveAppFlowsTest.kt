package io.github.bl3xand.apkcloner

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * Removing a tracked app, every answer of its prompt (matrix A14): cancelling the app's own
 * sheet changes nothing; the list-only removal drops the record but keeps the install; the
 * removal with uninstall goes through the SYSTEM's uninstall prompt - cancelled nothing
 * happens, confirmed the app is gone from the device and the record with it. The phone log
 * of 2026-10-24 (21:19, 23:08) walked all three by hand; this test walks them on an emulator.
 *
 * The tracked app is the e2e target (built and pushed by scripts/test-constraints.sh), so
 * the system's uninstall prompt has a safe package to act on - a removal of the app under
 * test itself would aim the prompt at the test's own process.
 */
class RemoveAppFlowsTest : BaseUiTest() {

    private val targetPackage = "io.github.bl3xand.e2etarget"

    @Test
    fun listOnlyRemovalUninstallRemovalAndCancelledPrompt() {
        val v1 = apkOnDevice("target.v1")
        val server = FakeApkServer(v1)
        server.startAndWait()
        try {
            // The target is installed with the app as its installer of record and tracked
            // from the fake source, at its latest version.
            installTargetAsOwnInstaller(v1)
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            val stored = storedTrackedApp()
            check(stored.length() == 1) { "Expected 1 tracked app, got ${stored.length()}" }
            check(stored.getJSONObject(0).getString("id") == targetPackage) {
                "The tracked app is ${stored.getJSONObject(0).getString("id")}, not the target"
            }

            // ---- 1. Cancelled prompt: nothing happens ------------------------------------------
            openRemoveSheet()
            waitForRemoveSheet()
            // Both switches are on by default; Cancel leaves everything as it was.
            device.findObject(By.text(string("cancel")))!!.click()
            device.waitForIdle(1_000)
            assert(repoHasApp(targetPackage)) { "Cancelling the remove sheet removed the tracked app" }
            assert(context.packageManager.packageInfoOrNull(targetPackage, 0) != null) {
                "Cancelling the remove sheet uninstalled the app"
            }

            // ---- 2. List-only removal ----------------------------------------------------------
            openRemoveSheet()
            waitForRemoveSheet()
            setRemoveSwitches(uninstall = false, removeFromList = true)
            device.findObject(By.text(string("continue")))!!.click()
            assert(awaitCondition(15_000) { !repoHasApp(targetPackage) }) {
                "The list-only removal did not drop the tracked app"
            }
            assert(context.packageManager.packageInfoOrNull(targetPackage, 0) != null) {
                "The list-only removal uninstalled the app from the device"
            }

            // ---- 3. Removal with uninstall: the system prompt, cancelled first ----------------
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            device.waitForIdle(2_000)
            check(storedTrackedApp().length() == 1) { "The re-add left ${storedTrackedApp().length()} apps" }
            openRemoveSheet()
            waitForRemoveSheet()
            setRemoveSwitches(uninstall = true, removeFromList = true)
            device.findObject(By.text(string("continue")))!!.click()
            // The system's own uninstall prompt (the package installer's window). Its Cancel
            // is the framework's cancel text; cancelling keeps both the app and the record.
            val cancelText = frameworkText("cancel")
            val systemCancel = device.wait(Until.findObject(By.text(cancelText)), 20_000)
                ?: error("The system uninstall prompt did not open. Screen:\n${dumpVisibleTexts()}")
            // The click is re-tried while the window settles (a stale node is re-found), and
            // the prompt is waited OUT before anything else is driven over it.
            var cancelled = false
            repeat(6) {
                if (cancelled) return@repeat
                cancelled = runCatching { systemCancel.click(); true }.getOrDefault(false) ||
                    runCatching {
                        device.findObject(By.text(cancelText))?.click() != null
                    }.getOrDefault(false)
                if (!cancelled) device.waitForIdle(500)
            }
            device.wait(Until.gone(By.text(cancelText)), 10_000)
            assert(awaitCondition(15_000) { repoHasApp(targetPackage) }) {
                "Cancelling the system prompt dropped the tracked app"
            }
            assert(context.packageManager.packageInfoOrNull(targetPackage, 0) != null) {
                "Cancelling the system prompt uninstalled the app"
            }

            // ---- 4. The same removal, prompt confirmed -----------------------------------------
            // A system prompt still standing from the cancelled answer would swallow the
            // next steps' clicks.
            if (device.findObject(By.text(cancelText)) != null) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            openRemoveSheet()
            waitForRemoveSheet()
            setRemoveSwitches(uninstall = true, removeFromList = true)
            device.findObject(By.text(string("continue")))!!.click()
            // The prompt again; the confirming button is the one that is not Cancel.
            device.wait(Until.findObject(By.text(cancelText)), 20_000)
                ?: error("The system uninstall prompt did not open (second time). Screen:\n${dumpVisibleTexts()}")
            val buttons = device.findObjects(By.clazz("android.widget.Button"))
            val confirm = buttons.firstOrNull { it.text != cancelText && it.text.isNotBlank() }
                ?: error("No confirming button on the uninstall prompt. Screen:\n${dumpVisibleTexts()}")
            confirm.click()
            // The app is removed from the device and from the list.
            assert(awaitCondition(30_000) { !repoHasApp(targetPackage) }) {
                "The removal with uninstall did not drop the tracked app"
            }
            assert(awaitCondition(30_000) { context.packageManager.packageInfoOrNull(targetPackage, 0) == null }) {
                "The removal with uninstall left the app installed"
            }
            // The removal is written down: one INFO line naming what stopped being tracked.
            AppLog.flush()
            assert(
                AppLog.query().any {
                    it.level == LogLevel.INFO && it.message.contains("No longer tracked") &&
                        it.message.contains(targetPackage)
                },
            ) { "No \"No longer tracked\" INFO line for the removal. Log:\n${AppLog.query().joinToString("\n")}" }
        } finally {
            server.stop()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Long-press the row's card (the view that owns the long click), then Remove. */
    private fun openRemoveSheet() {
        val text = device.findObject(By.textContains(targetPackage))
            ?: error("No row for $targetPackage. Screen:\n${dumpVisibleTexts()}"
        )
        // The text's parent chain leads to the card: the view with the long-click listener.
        var card = text.parent
        while (card != null && !card.isLongClickable) card = card.parent
        check(card != null) { "No long-clickable card above the row of $targetPackage" }
        card.longClick()
        device.waitForIdle(1_000)
        val remove = scrollSheetUntil(sourceString("remove"))
            ?: error("No Remove action in the row menu. Screen:\n${dumpVisibleTexts()}"
            )
        remove.click()
        device.waitForIdle(1_000)
    }

    /** Waits for the remove-confirmation sheet: its title asks the remove question. */
    private fun waitForRemoveSheet() {
        val question = sourcePlural("removeAppQuestion", 1)
        device.wait(Until.findObject(By.text(question)), 10_000)
            ?: error("The remove sheet did not open. Screen:\n${dumpVisibleTexts()}"
        )
    }

    /** One form of a plural of the sources' catalogues. */
    private fun sourcePlural(name: String, count: Int): String {
        val locale = context.resources.configuration.locales[0].language
        val catalogue =
            if (locale in listOf("ru", "zh", "de", "fr", "es", "tr", "ko", "ja", "ar")) locale else "en"
        val json = context.assets.open("sources/i18n/$catalogue.json").bufferedReader().use { it.readText() }
        val forms = JSONObject(json).getJSONObject(name)
        return forms.optString(if (count == 1) "one" else "other").ifBlank { forms.optString("other") }
    }

    /** Sets the two switches of the remove sheet to the answers under test. */
    private fun setRemoveSwitches(uninstall: Boolean, removeFromList: Boolean) {
        // The switches are labelled rows; tapping one flips it. Both are on by default.
        val uninstallRow = scrollSheetUntil(sourceString("uninstallFromDevice"))
            ?: error("No \"uninstall from the device\" switch. Screen:\n${dumpVisibleTexts()}"
            )
        val removeRow = scrollSheetUntil(sourceString("removeFromObtainium"))
            ?: error("No \"remove from the list\" switch. Screen:\n${dumpVisibleTexts()}"
            )
        if (!uninstall) uninstallRow.click()
        if (!removeFromList) removeRow.click()
        device.waitForIdle(500)
    }

    /** The target's APK, pushed by scripts/test-constraints.sh. */
    private fun apkOnDevice(name: String): File {
        val arg = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(name)
            ?: error("Missing instrumentation argument $name (scripts/test-constraints.sh pushes the files)")
        return File(arg).also { check(it.exists()) { "No file at ${it.path}" } }
    }

    /** Installs the target with the app as its installer of record (the shell session trick). */
    private fun installTargetAsOwnInstaller(apk: File) {
        device.executeShellCommand("pm uninstall $targetPackage")
        val size = apk.length()
        val session = device.executeShellCommand("cmd package install-create -r -t -i $packageName --install-reason 0")
            .trim().substringAfter("[").substringBefore("]").let { it.ifBlank { null } }
        check(session != null) { "Could not create an install session" }
        device.executeShellCommand("cmd package install-write -S $size $session base ${apk.path}")
        val commit = device.executeShellCommand("cmd package install-commit $session")
        check(!commit.contains("Failure", ignoreCase = true)) { "The shell install session failed: $commit" }
        awaitCondition(30_000) { context.packageManager.packageInfoOrNull(targetPackage, 0) != null }
    }

    /** A framework string ("cancel", "ok"), read the way the system's own dialogs show it. */
    private fun frameworkText(name: String): String {
        val id = android.R.string::class.java.getField(name).getInt(null)
        return context.getString(id)
    }

    private fun repoHasApp(id: String): Boolean =
        io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).entry(id) != null

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