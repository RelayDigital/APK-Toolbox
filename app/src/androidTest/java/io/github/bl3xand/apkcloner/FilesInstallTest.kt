package io.github.bl3xand.apkcloner

import android.content.Intent
import android.net.Uri
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.log.LogLevel
import java.io.File
import org.junit.Test

/**
 * The SAI side of the files flow (matrix I3): an APK file shared into the app, the
 * "install as-is" answer of the fork, and the file landing on the device through the app's
 * own install path. The file is the e2e target app (built and pushed by
 * scripts/test-constraints.sh), a package nothing else owns - so the assertion is simply the
 * package appearing, with this app as its installer.
 *
 * The merge answer of the fork (I2's merge half) needs a real split set and its own walk;
 * here the fork itself and the install-as-is path are the test, plus the SAI tab's own
 * picker opening for the shared file.
 */
class FilesInstallTest : BaseUiTest() {

    private val targetPackage = "io.github.bl3xand.e2etarget"

    @Test
    fun sharedApkInstallsAsIsThroughTheFork() {
        val apk = targetApk()
        // Start clean: whatever an earlier run left installed is gone first.
        device.executeShellCommand("pm uninstall $targetPackage")

        // Share the APK file in: the fork asks what to do with it.
        sendShare(apk)
        waitForStringResource("files_received_title", timeout = 15_000)
        val install = device.findObject(By.text(string("files_action_install")))
            ?: error("No Install action in the files-received sheet. Screen:\n${dumpVisibleTexts()}"
            )
        install.click()

        // The file loads and the split sheet shows the package it holds; its own Install
        // button commits the install.
        awaitCondition(30_000, "the split sheet to show the package") {
            device.findObject(By.textContains(targetPackage)) != null
        }
        val commit = scrollSheetUntil(string("button_install"))
            ?: error("No Install button in the split sheet. Screen:\n${dumpVisibleTexts()}"
            )
        commit.click()

        // A new package through the system installer asks for its confirmation - the
        // system's own window, with the framework's Install/Cancel buttons. The test
        // confirms it the way a user would.
        val cancelText = frameworkText("cancel")
        if (device.wait(Until.hasObject(By.text(cancelText)), 30_000)) {
            val buttons = device.findObjects(By.clazz("android.widget.Button"))
            val confirm = buttons.firstOrNull { it.text != cancelText && it.text.isNotBlank() }
                ?: error("No confirming button on the install prompt. Screen:\n${dumpVisibleTexts()}")
            confirm.click()
        }

        // The app landed on the device.
        awaitCondition(120_000, "the shared APK to be installed") {
            context.packageManager.packageInfoOrNull(targetPackage, 0) != null
        }
        // The system's own words are the second witness: the receiver's "installed" line
        // lands a moment after the package appears (the broadcast's own arrival time).
        val lineLanded = awaitCondition(15_000, "the installed line to be logged") {
            AppLog.flush()
            AppLog.query().any {
                it.level == LogLevel.INFO && it.message.contains("System installer: installed $targetPackage")
            }
        }
        check(lineLanded) {
            "No \"System installer: installed\" line for the install. Log:\n${AppLog.query().joinToString("\n")}"
        }

        // Leave the emulator clean for the next test.
        device.executeShellCommand("pm uninstall $targetPackage")
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A framework string ("cancel", "ok"), read the way the system's own dialogs show it. */
    private fun frameworkText(name: String): String {
        val id = android.R.string::class.java.getField(name).getInt(null)
        return context.getString(id)
    }

    /** The target app's APK on the device, pushed by scripts/test-constraints.sh. */
    private fun targetApk(): File {
        val arg = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("target.v1")
            ?: error("Missing instrumentation argument target.v1 (scripts/test-constraints.sh pushes the files)")
        return File(arg).also { check(it.exists()) { "No file at ${it.path}" } }
    }

    /** Shares [file] into the app as one APK, the way a file manager's Share does. */
    private fun sendShare(file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.component = launch.component
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
        device.waitForIdle(2_000)
    }

    /** Re-runs [probe] until it returns true; gives up with [what] after [timeoutMs]. */
    private fun awaitCondition(timeoutMs: Long, what: String, probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(500)
        }
        error("Timed out waiting for: $what")
    }
}