package io.github.bl3xand.apkcloner

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileOutputStream
import org.junit.Test

/**
 * Captures the two README screenshots of the patch picker: the sheet as it opens (nothing on)
 * and the sheet with a working set of patches switched on. Not part of the UI baseline - the
 * picker's rows carry the app's own notes, which is exactly what the README wants to show.
 *
 * Run from the shell (an installed app is needed for the clone draft):
 *   am instrument -e class io.github.bl3xand.apkcloner.PatchPickerScreenshotsTest \
 *     io.github.bl3xand.apkcloner.test/androidx.test.runner.AndroidJUnitRunner
 * The PNGs land in the app's external cache, one adb pull away.
 */
class PatchPickerScreenshotsTest : BaseUiTest() {

    @Test
    fun captureThePatchPicker() {
        // Nothing may pop over the sheet while it is captured.
        device.executeShellCommand("cmd notification set_dnd priority")
        val outDir = File(context.externalCacheDir, "picker-shots").apply { deleteRecursively(); mkdirs() }
        try {
            // The clone draft of a chosen app: the Cloning tab, its installed-apps side, the
            // first row's sheet, then its patch button (a clone sheet offers the patches
            // directly - the "install as a clone" draft switch is the tracked app's page, not here).
            device.findObject(By.text(string("tab_apps")))?.click() ?: error("No Cloning tab. Screen:\n${dumpVisibleTexts()}")
            device.waitForIdle(2_000)
            searchFor("")
            device.findObject(By.text(string("cloning_installed")))!!.click()
            settle()
            openCloneSheet()
            val patchesPrefix = string("clone_patches")
            val patchesButton = device.wait(Until.findObject(By.textStartsWith(patchesPrefix)), 10_000)
                ?: error("No patches button. Screen:\n${dumpVisibleTexts()}")
            patchesButton.click()
            device.wait(Until.findObject(By.text(string("clone_patches_hint"))), 10_000)
                ?: error("The picker did not open. Screen:\n${dumpVisibleTexts()}")
            settle()
            shot(outDir, "patch-picker-empty")

            // A working set a user would pick: keep-alive and the signature substitution -
            // the set the README's cloning story tells about. The signature row is held on
            // by mimicry, so it stays free here.
            val keepAlive = device.wait(Until.findObject(By.text(string("clone_keep_alive"))), 5_000)
                ?: error("No keep-alive row. Screen:\n${dumpVisibleTexts()}")
            keepAlive.click()
            device.waitForIdle(500)
            val spoof = device.wait(Until.findObject(By.text(string("clone_spoof_signature"))), 5_000)
                ?: error("No signature row. Screen:\n${dumpVisibleTexts()}")
            spoof.click()
            device.waitForIdle(800)
            settle()
            shot(outDir, "patch-picker-on")
        } finally {
            device.executeShellCommand("cmd notification set_dnd off")
        }
    }

    // The same reading-the-frame discipline the baseline capture uses: two frames that agree.

    private fun shot(outDir: File, name: String) {
        val screenshot = stableScreenshot() ?: error("The screen never settled for $name")
        FileOutputStream(File(outDir, "$name.png")).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private fun stableScreenshot(): Bitmap? {
        var previous: Bitmap? = null
        repeat(8) {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return null
            val before = previous
            if (before != null && rowsAgree(before, shot)) {
                before.recycle()
                return shot
            }
            before?.recycle()
            previous = shot
            SystemClock.sleep(400)
        }
        val last = previous
        previous = null
        return last
    }

    private fun rowsAgree(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height step 8) {
            var ra = 0L; var ga = 0L; var ba = 0L
            var rb = 0L; var gb = 0L; var bb = 0L
            var n = 0
            for (x in 0 until a.width step 8) {
                val pa = a.getPixel(x, y); val pb = b.getPixel(x, y)
                ra += (pa shr 16) and 0xff; ga += (pa shr 8) and 0xff; ba += pa and 0xff
                rb += (pb shr 16) and 0xff; gb += (pb shr 8) and 0xff; bb += pb and 0xff
                n++
            }
            val dist = Math.sqrt(((ra - rb) * (ra - rb) + (ga - gb) * (ga - gb) + (ba - bb) * (ba - bb)).toDouble()) / n
            if (dist > 6.0) return false
        }
        return true
    }

    private fun settle() {
        device.waitForIdle(2_000)
        SystemClock.sleep(600)
        device.waitForIdle(1_000)
    }

    /** The clone sheet of the first installed app, the way the baseline walk opens it. */
    private fun openCloneSheet() {
        val empty = device.findObject(By.text(string("empty_list")))
        check(empty == null || empty.visibleBounds.height() == 0) { "No installed apps to clone" }
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
        } ?: error("No app row to open the clone sheet. Screen:\n${dumpVisibleTexts()}")
        row.click()
        device.wait(Until.findObject(By.text(string("button_clone"))), 10_000)
            ?: error("The clone sheet did not open. Screen:\n${dumpVisibleTexts()}")
        settle()
    }

    private fun searchFor(text: String) {
        val field = device.findObject(By.res(packageName, "editSearch"))
            ?: error("No search field. Screen:\n${dumpVisibleTexts()}")
        field.text = text
        device.waitForIdle(500)
    }
}