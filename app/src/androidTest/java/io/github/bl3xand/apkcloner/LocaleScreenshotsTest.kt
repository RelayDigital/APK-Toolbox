package io.github.bl3xand.apkcloner

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Test

/**
 * Screenshots of the branch-gone notice card, taken for each locale the task asks about (ja, ar)
 * and kept in docs/screenshots as locale-*. The state is put in directly, the way the app's own
 * check would leave it: the tracked app's releaseBranchGone flag set, the page re-rendered.
 */
class LocaleScreenshotsTest : BaseUiTest() {

    private val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")

    @Test
    fun captureBranchGoneCard() {
        assert(apk.exists()) { "No test APK at ${apk.path}" }
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            device.executeShellCommand("cmd notification set_dnd priority")
            addAppByUrl(FakeApkServer.APK_URL)

            // The tracked app now has one file; mark its branch gone, as a check would.
            val repo = io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context)
            val entry = repo.entry(packageName) ?: error("No tracked app $packageName")
            repo.saveApps(listOf(entry.app.copy(releaseBranchGone = true)))

            // The page re-renders on its own state changes; give it a moment, then look for
            // the notice card's button (chooseBranch).
            val choose = string("chooseBranch")
            device.wait(Until.findObject(By.text(choose)), 10_000)
                ?: error("No branch-gone card on the page. Screen:\n${dumpVisibleTexts()}")

            val outDir = File(context.externalCacheDir, "locale-shots").apply { deleteRecursively(); mkdirs() }
            settle()
            screenshot(File(outDir, "branch-gone-page.png"))
            device.pressBack()
            settle()
            screenshot(File(outDir, "branch-gone-row.png"))
        } finally {
            server.stop()
            device.executeShellCommand("cmd notification set_dnd off")
        }
    }

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
        device.wait(Until.findObject(By.textContains(packageName)), 15_000)
    }

    private fun settle() {
        device.waitForIdle(2_000)
        Thread.sleep(600)
        device.waitForIdle(1_000)
    }

    private fun screenshot(target: File) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("No screenshot for ${target.name}")
        target.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
}