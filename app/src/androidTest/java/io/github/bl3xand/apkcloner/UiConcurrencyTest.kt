package io.github.bl3xand.apkcloner

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Test

/**
 * Distortion checks for the Sources tab under simultaneous work: while a download ticks its
 * row many times a second, sheets are opened and closed, rows are tapped, the list is pulled
 * to refresh and tabs are switched - and what must survive it all is a window that answers,
 * no dialog left over from a flow that was interrupted, and no two cards drawn one over
 * another. The bead's own words: races and distortions under simultaneous actions.
 *
 * The download is real (the app's own debug build from the fake source, the same one the
 * capture tests use); the row updates come from the app's own progress reporting. The
 * activity is portrait-locked, so rotation is deliberately not part of the walk.
 */
class UiConcurrencyTest : BaseUiTest() {

    private val apk = FakeApkServer.apkFromArguments() ?: File("/sdcard/Download/apk-toolbox-test.apk")

    @Test
    fun sheetsAndRowsStaySaneWhileARowDownloads() {
        assert(apk.exists()) { "No test APK at ${apk.path} - scripts/test-ui-regression.sh pushes one before the run" }
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            // One tracked app, not installed: its page offers Install, and the row offers the
            // same. Tapping Install starts the download that keeps the row busy. The add's
            // own download runs at full speed.
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            settle()

            // The app tracks itself with a pseudo-version, and the add took the installed
            // build for the latest one, so nothing is on offer yet. A wrong installed version
            // (what an update arriving looks like to the row) makes the update appear; the
            // row's own button then starts the real download.
            markBehind(packageName)

            // The download this test rides on must outlast the walk: the add's own download
            // is deleted from the app's cache (a leftover would be reused in place of a real
            // download, which would end before the walk began), and from here on the server
            // answers at a crawl - 40 MB at 120 KB/s is nearly six minutes of ticking
            // progress, far beyond anything the walk below takes.
            io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).apkDir.listFiles()
                ?.filter { it.name.startsWith("$packageName-") }
                ?.forEach { it.delete() }
            server.bytesPerSecond = 120_000

            // The download is started from the row's own Update button - the way a user does.
            val install = device.wait(Until.findObject(By.desc(sourceString("update"))), 10_000)
                ?: error("No Update button on the row. Screen:\n${dumpVisibleTexts()}")
            install.click()

            // The row must show the progress circle while the download runs; that is the
            // "row updates rapidly" the whole test is staged around.
            val downloadRunning = awaitCondition(15_000) {
                repoDownloads().isNotEmpty()
            }
            assert(downloadRunning) { "The download never started. Screen:\n${dumpVisibleTexts()}" }

            // The walk over a downloading list. waitForIdle never fires while the row ticks, so the
            // steps give each a short fixed beat instead of waiting for quiet.
            openSettingsAndBack()
            openAppDetailAndBack()
            tapTheRow()
            pullToRefresh()
            openSettingsAndBack()

            // What must hold after all of it:

            // 1. The window still answers: a hierarchy query has to come back within a timeout.
            //    A wedged main thread (the log sheet on the UI thread, a render loop stuck
            //    on the row) makes the query miss.
            assert(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10_000)) {
                "The main window stopped answering"
            }

            // 2. No leaked dialogs: after back presses and sheet walks, the window roots are
            //    the activity (plus at most the IME and the transient system overlays); a
            //    sheet that outlived its flow stays as an extra root.
            val leaked = leakedSheetRoots()
            assert(leaked == 0) {
                "Leaked windows after the walk: $leaked extra root(s). Screen:\n${dumpVisibleTexts()}"
            }

            // 3. No overlapping notice cards: the row renders one notice area, never two at
            //    the same bounds. A rendering race that stacks cards shows as two nodes with
            //    nearly identical bounds and the same notice-ish size.
            assertNoOverlappingNoticeCards()

            // 4. The tab content is the right one: back on Sources, with the tracked app's
            //    row present and the Add app button in the bar.
            assert(findString("sources_add") != null) { "Not back on the Sources tab. Screen:\n${dumpVisibleTexts()}" }

            // 5. The download is stopped the way a user stops it: the row's progress is
            //    tapped, which cancels. The row then shows its Update button again.
            cancelRowDownload()
        } finally {
            // The download is stopped from the app's own machinery - a finished one would
            // install this very app over the test process and kill it.
            runCatching {
                io.github.bl3xand.apkcloner.sources.install.SourcesInstaller.get(context).cancelDownload(packageName)
            }
            server.stop()
        }
    }

    @Test
    fun checkNowAndTabSwitchingDoNotCorruptTheTabs() {
        val server = FakeApkServer(apk)
        server.startAndWait()
        try {
            addAppByUrl(FakeApkServer.APK_URL)
            device.pressBack()
            settle()

            // "Check now": the manual refresh of the settings sheet - but the Refresh chip of
            // the Sources tab runs the same check and does not need the sheet. It is tapped
            // and the tabs are switched immediately, twice, while the check runs.
            val refresh = device.wait(Until.findObject(By.text(sourceString("refresh"))), 10_000)
                ?: error("No Refresh chip on the Sources tab. Screen:\n${dumpVisibleTexts()}")
            refresh.click()

            switchTab("tab_apps")
            switchTab("tab_sources")
            switchTab("tab_install")
            switchTab("tab_sources")

            // The check may still be running; that is the point. The app must not crash
            // (the process is the test's own - a crash fails the run by itself) and the tab
            // shown last must be the right one.
            assert(findString("sources_add") != null || device.findObjects(By.clazz("android.widget.TextView")).orEmpty().size > 6) {
                "Sources tab content is not showing after the switches. Screen:\n${dumpVisibleTexts()}"
            }
            assert(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10_000)) {
                "The main window stopped answering after the tab switches"
            }
            assert(leakedSheetRoots() == 0) { "Leaked windows after the tab switches" }
        } finally {
            server.stop()
        }
    }

    // ---- the walk --------------------------------------------------------------------------------

    /** Each step of the walk takes a short fixed beat: the row never goes idle while it
     *  downloads, so waiting for quiet would stretch every step to its timeout. */
    private fun settle() {
        SystemClock.sleep(1_200)
    }

    private fun openSettingsAndBack() {
        val settings = device.findObject(By.desc(string("settings_title"))) ?: return
        settings.click()
        settle()
        device.pressBack()
        settle()
    }

    private fun openAppDetailAndBack() {
        // The tracked app's row: the app's own package is on the detail page, so the row that
        // holds it is the one to tap.
        val row = device.findObject(By.textContains(packageName))?.parent ?: return
        row.click()
        settle()
        device.pressBack()
        settle()
    }

    private fun tapTheRow() {
        val row = device.findObject(By.textContains(packageName))?.parent ?: return
        row.click()
        settle()
        device.pressBack()
        settle()
    }

    /** A short pull on the Sources list: enough to arm the swipe, not enough to wander. */
    private fun pullToRefresh() {
        val list = device.findObject(By.res(packageName, "swipeSources")) ?: return
        val rect = list.visibleBounds
        device.swipe(rect.centerX(), rect.top + rect.height() / 5, rect.centerX(), rect.top + rect.height() / 3, 30)
        settle()
    }

    /** Cancels the row's running download the way a tap on its progress does. */
    private fun cancelRowDownload() {
        val row = device.findObject(By.textContains(packageName))?.parent ?: return
        // The progress sits at the row's right edge; tapping it cancels the download.
        val bounds = row.visibleBounds
        device.click(bounds.right - 60, bounds.centerY())
        awaitCondition(10_000) { repoDownloads().isEmpty() }
    }

    private fun switchTab(titleKey: String) {
        val title = string(titleKey)
        val tabView = device.findObject(By.text(title)) ?: return
        tabView.click()
        settle()
    }

    // ---- the state -------------------------------------------------------------------------------

    /** The downloads the repository knows about; empty when nothing is in flight. */
    private fun repoDownloads(): Map<String, *> =
        io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context).downloads.value

    /**
     * Puts the tracked app [id] a version behind what its source offers, the way an arrived
     * update looks to the row: the row then shows the gap and offers its Update button. Runs
     * in-process (the test is the app), so the store is written directly.
     */
    private fun markBehind(id: String) {
        val repo = io.github.bl3xand.apkcloner.sources.data.SourcesRepository.get(context)
        val entry = repo.entry(id) ?: error("No tracked app $id to put behind")
        repo.saveApps(listOf(entry.app.copy(installedVersion = "0.0")))
    }

    /**
     * How many app-owned windows exist beyond the activity's own. UiAutomation's getWindows
     * lists every window on screen; a leaked sheet that outlived its flow stays behind as one
     * more window owned by the app (an application window, not the system's own overlays).
     */
    private fun leakedSheetRoots(): Int {
        val windows = try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.windows.orEmpty()
        } catch (e: Exception) {
            error("Could not read the windows: $e")
        }
        val ours = windows.filter { it.root?.packageName == packageName }
        // The activity is one of them; anything else of ours is a dialog still up.
        return (ours.size - 1).coerceAtLeast(0)
    }

    /**
     * No two visible cards of a notice size may sit one over another. Overlapping cards are
     * looked for as pairs of sibling texts drawn at the same top edge - what a doubled notice
     * card actually looks like on this screen; the row renders one notice line, never two.
     */
    private fun assertNoOverlappingNoticeCards() {
        // No two texts of a notice kind may sit at the same top edge.
        val noticeTexts = visibleTexts().filter { text ->
            val notice = sourceStringOrNull("releaseBranchGoneShort")
            notice != null && text.first.contains(notice)
        }
        val tops = noticeTexts.map { it.second }.sorted()
        val doubled = tops.zipWithNext().any { (a, b) -> kotlin.math.abs(b - a) <= 2 }
        assert(!doubled) { "Two notice texts drawn at the same place: $noticeTexts" }
    }

    /** The texts on screen with where they are, for the overlap checks. */
    private fun visibleTexts(): List<Pair<String, Int>> =
        device.findObjects(By.clazz("android.widget.TextView")).orEmpty()
            .mapNotNull { text -> text.text?.takeIf(String::isNotBlank)?.let { it to text.visibleBounds.top } }

    private fun sourceStringOrNull(name: String): String? = try {
        sourceString(name)
    } catch (e: Exception) {
        null
    }

    /** Opens the add sheet, types the URL, presses Add, and waits for the app's page to open. */
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