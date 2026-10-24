package io.github.bl3xand.apkcloner

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileOutputStream
import org.junit.Test

/**
 * Walks the key screens of the app and saves, for each, a screenshot (PNG) and a dump of the
 * view hierarchy (XML). This is the capture side of the UI regression guard: scripts/
 * test-ui-regression.sh runs this class, pulls the artefacts out, and scripts/ui-regression/
 * compare.py compares them against the baseline kept in docs/ui-baseline/ - not pixel by
 * pixel, but by row-mean colours and by the shape of the hierarchy, so that real drift in
 * spacing and styling is caught without failing on rendering noise.
 *
 * Determinism is what makes the comparison meaningful, so the screens are set up to be the
 * same on every run: notifications are silenced (a toast carries what happened a moment
 * before), the app's data is wiped (BaseUiTest), the one tracked app is served by a local
 * fake source (its version and size are as stable as the file the script pushes), and each
 * screen is given time to settle before it is captured. Screens that scroll keep whatever
 * state the walk leaves them in; the walk is fixed, so the state is too.
 *
 * The class is written for the light theme, the emulator default; night mode is forced off
 * by the script so a dark emulator does not silently poison the baseline.
 */
class UiBaselineCaptureTest : BaseUiTest() {

    /**
     * Where the artefacts land on the device; the script pulls the whole folder. The external
     * cache is used because the shell can read it back (the external files dir of a freshly
     * installed app is not created until something asks for it on this Android version, and
     * the internal dir only run-as can reach). Created inside the test, after BaseUiTest's
     * wipe has cleaned the storage - a property initializer would run first and be wiped.
     */
    private lateinit var outDir: File

    private val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")
    private lateinit var server: FakeApkServer

    @Test
    fun captureKeyScreens() {
        assert(apk.exists()) { "No test APK at ${apk.path} - scripts/test-ui-regression.sh pushes one before the run" }

        // Nothing may pop over the screens while they are captured: a notification shade or a
        // heads-up banner would cover part of the layout and the comparison would fail.
        device.executeShellCommand("cmd notification set_dnd priority")
        // The capture's own folder, made after the wipe in setUp cleaned the storage.
        outDir = File(context.externalCacheDir, "ui-baseline").apply { deleteRecursively(); mkdirs() }
        server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // The one tracked app, as a user would add it. Its page opens when the add finishes.
            addAppByUrl(FakeApkServer.APK_URL)

            capture("app-detail")
            device.pressBack()
            settle()

            capture("sources-list")
            openSettings()
            capture("settings-sheet")
            openLogFromSettings()
            capture("log-sheet")
            device.pressBack() // closes the log sheet, back on the settings sheet
            settle()
            device.pressBack() // closes the settings sheet
            settle()

            // The Cloning tab has two sides; "Clones" is a toggle inside it, not a strip tab.
            captureTab("cloning-apps-tab", "tab_apps")
            switchToClonesSide()
            capture("clones-tab")

            // The clone sheet of a chosen app: the left side (installed apps) has rows to tap.
            device.findObject(By.text(string("cloning_installed")))!!.click()
            settle()
            openCloneSheet()
            capture("clone-sheet")
            device.pressBack()
            settle()

            captureTab("sai-tab", "tab_install")
            captureTab("antisplit-tab", "tab_split")
        } finally {
            server.stop()
            device.executeShellCommand("cmd notification set_dnd off")
        }
    }

    // ---- the walk --------------------------------------------------------------------------------

    /** Opens the add sheet, types [url], presses Add, and waits for the app's page to open. */
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
        // Adding downloads the file once; then the app's page opens with the URL on it.
        device.wait(Until.gone(By.text(addAppHeading)), 60_000)
        device.wait(Until.findObject(By.textContains(packageName)), 15_000)
    }

    private fun openSettings() {
        val settings = device.findObject(By.desc(string("settings_title")))
            ?: error("No settings button. Screen:\n${dumpVisibleTexts()}")
        settings.click()
        device.wait(Until.findObject(By.text(string("settings_title"))), 10_000)
        settle()
    }

    private fun openLogFromSettings() {
        val showLog = scrollSheetUntil(string("button_show_log"))
            ?: error("No 'Show the log' button in the settings sheet. Screen:\n${dumpVisibleTexts()}")
        showLog.click()
        device.wait(Until.findObject(By.text(string("settings_log"))), 10_000)
        settle()
    }

    /** Switches to the tab titled by [titleKey] and waits for its content. */
    private fun captureTab(name: String, titleKey: String) {
        val title = string(titleKey)
        val tabView = device.findObject(By.text(title))
            ?: error("Tab \"$title\" not found. Screen:\n${dumpVisibleTexts()}")
        tabView.click()
        device.waitForIdle(2_000)
        settle()
        capture(name)
    }

    /** The Cloning tab's right side: what was already cloned, with its own chips and list. */
    private fun switchToClonesSide() {
        val clones = device.findObject(By.text(string("cloning_clones")))
            ?: error("Clones side button not found. Screen:\n${dumpVisibleTexts()}")
        clones.click()
        device.waitForIdle(2_000)
        settle()
    }

    /**
     * The clone sheet of a chosen app on the Cloning tab's installed-apps side. The first app
     * in the list is tapped (emulators come with a stable set of preinstalled apps); the sheet
     * that opens is the one a clone is configured in. The rows are CardViews - in the
     * accessibility tree they keep their own class name, so they are looked for by shape: a
     * clickable thing of a row's height with a package-name text line inside. With no rows to
     * tap the sheet is simply skipped.
     */
    private fun openCloneSheet() {
        val empty = device.findObject(By.text(string("empty_list")))
        if (empty != null && empty.visibleBounds.height() > 0) return
        val texts = device.findObjects(By.clazz("android.widget.TextView")).orEmpty()
        val rows = device.findObjects(By.clickable(true)).orEmpty()
            .filter { it.className != "android.widget.Button" && it.className != "android.widget.EditText" }
        val row = rows.firstOrNull { holder ->
            val bounds = holder.visibleBounds
            bounds.height() in 100..600 && bounds.width() > 400 &&
                texts.any {
                    it.visibleBounds.centerY() in bounds.top..bounds.bottom &&
                        it.visibleBounds.top > bounds.top + 8 && it.text?.contains('.') == true
                }
        } ?: run {
            // Nothing that looks like an app row; skip the sheet rather than fail the capture.
            return
        }
        row.click()
        // The clone sheet shows the Clone button and the package-name field.
        device.wait(Until.findObject(By.text(string("button_clone"))), 10_000) ?: return
        settle()
    }

    // ---- the capture -----------------------------------------------------------------------------

    /**
     * Saves the current screen as [name].png and [name].xml. The screenshot comes from
     * UiAutomation itself (the same frame the tests see); the hierarchy dump is written by
     * UiDevice's own dumpWindowHierarchy - the shell's uiautomator binary cannot be used
     * here, because the instrumentation's UiAutomation already holds the one slot the
     * platform gives a process. The dump's XML carries every node's bounds - what the
     * comparator reads spacing from.
     */
    private fun capture(name: String) {
        settle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not take a screenshot of $name")
        val png = File(outDir, "$name.png")
        FileOutputStream(png).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()

        // The dump lands in the app's own cache first (a world-readable place the shell can
        // pull from), then is copied under the capture's name.
        val staging = File(context.cacheDir, "window_dump.xml")
        staging.delete()
        device.dumpWindowHierarchy(staging)
        val xml = File(outDir, "$name.xml")
        staging.inputStream().use { input -> xml.outputStream().use { input.copyTo(it) } }
        staging.delete()

        assert(xml.length() > 0) { "Empty hierarchy dump for $name" }
    }

    /** Waits until nothing on screen changes any more, so animations cannot split a capture. */
    private fun settle() {
        device.waitForIdle(2_000)
        // waitForIdle can return while a snackbar or progress bar is still animating; a plain
        // beat of quiet time after it keeps the captured frame the same on every run.
        SystemClock.sleep(600)
        device.waitForIdle(1_000)
    }
}