package io.github.bl3xand.apkcloner

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import java.io.File

/**
 * Common ground of the UI tests: the app installed, its data wiped, its permissions granted, and
 * MainActivity on the Sources tab (the one the app starts on). Permission grants go through
 * `pm grant` and `appops` instead of the UI, so that the tests care only about what they test.
 */
abstract class BaseUiTest {

    protected lateinit var device: UiDevice
        private set
    protected val packageName: String get() = BuildConfig.APPLICATION_ID

    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    open fun setUp() {
        device = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        // Nothing of an earlier run must decide this one.
        resetAppState()
        launchMainActivity()
        // The app starts on the Sources tab; wait for its empty-state text to appear.
        waitForStringResource("sources_empty", timeout = 15_000)
    }

    @After
    open fun tearDown() {
        // Leave nothing mid-flight: kill the app, not the emulator.
        runCatching {
            device.executeShellCommand("am force-stop $packageName")
        }
    }

    protected fun resetAppState() {
        device.executeShellCommand("pm clear $packageName")
        // File access (Android 11+): invisible to pm grant, set through appops.
        device.executeShellCommand("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        // Install permission is granted with the one runtime permission of the app.
        device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        // The system may still be un-parceling "pm clear" while the activity starts.
        device.executeShellCommand("am force-stop $packageName")
    }

    protected fun launchMainActivity() {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launch intent for $packageName - is the app installed?")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10_000)
    }

    /** Resolves the app's own strings by name, so the tests run on any locale. */
    protected fun string(name: String): String {
        val res = context.resources
        val id = res.getIdentifier(name, "string", packageName)
        if (id == 0) error("String resource \"$name\" not found in $packageName")
        return res.getString(id)
    }

    /** Waits until the app shows the given app string on screen. */
    protected fun waitForStringResource(name: String, timeout: Long = 10_000): androidx.test.uiautomator.UiObject2 {
        val text = string(name)
        return device.wait(Until.findObject(By.text(text)), timeout)
            ?: error("Timed out waiting for \"$text\" (string \"$name\"). Screen:\n${dumpVisibleTexts()}")
    }

    protected fun findString(name: String): androidx.test.uiautomator.UiObject2? = device.findObject(By.text(string(name)))

    protected fun waitGoneString(name: String, timeout: Long = 10_000): Boolean {
        val text = string(name)
        return device.wait(Until.gone(By.text(text)), timeout)
    }

    /** Steps back until the app's own UI is gone, or the launcher is reached. */
    protected fun pressBackUntilHome(maxTries: Int = 5) {
        repeat(maxTries) {
            if (!device.pressBack()) return
            if (!device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 1_500)) return
        }
    }

    /** One page of what is on screen, for readable failures. */
    protected fun dumpVisibleTexts(): String =
        device.findObjects(By.clazz("android.widget.TextView"))
            ?.mapNotNull { it.text?.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.joinToString("\n")
            .orEmpty()

    /**
     * The test APK on the device: pushed there by scripts/test-ui.sh before the run. The fake
     * update source serves this file, so the "update" the tests work with is a real, installable
     * build of the app itself.
     */
    protected fun servedApkOnDevice(): File {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "apk-toolbox-test.apk"),
            File("/sdcard/Download/apk-toolbox-test.apk"),
            File("/data/local/tmp/apk-toolbox-test.apk"),
        )
        return candidates.firstOrNull(File::exists)
            ?: error("No test APK on the device. scripts/test-ui.sh pushes one before the run.")
    }

    companion object {
        /** The host port of the test HTTP server, as the emulator's guest sees it. */
        const val SERVER_HOST = "10.0.2.2"
        const val SERVER_PORT = 18099
    }
}