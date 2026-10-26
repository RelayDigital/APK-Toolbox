package io.github.bl3xand.apkcloner

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.bl3xand.apkcloner.compat.installerOf
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.update.AutoUpdateWorker
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * End-to-end test of the "only on Wi-Fi" / "only while charging" background-update
 * restrictions, on a real emulator with real network and battery state driven from the
 * instrumentation shell.
 *
 * What is under test is the exact path of a scheduled background pass:
 * SourcesBackground.run (its own network/charging restriction checks), AutoUpdateWorker
 * (restrictionsMet plus the "auto-update-waiting" WorkManager constraints), and the silent
 * install itself. The worker is driven by its public runNow() entry point (the same one the
 * "Check now" button uses); its KEY_MANUAL flag only forces a full check and an answer
 * notification, it does not bypass any restriction.
 *
 * The app under update is a tiny third-party package (io.github.bl3xand.e2etarget), tracked
 * from a DirectAPKLink served by FakeApkServer on the emulator's own loopback. The script
 * installs it with the app under test as its installer of record, which is the platform's
 * condition for a silent update. Two builds of it stand in for the two versions: v1, the
 * installed one, and v2, the same APK with one rewritten zip entry (a different
 * first-1024-bytes hash, so the source's partial-APK-hash pseudo-version sees a new version)
 * re-signed with the same key (so the platform performs the update in place, same
 * versionCode). Both are built and pushed by scripts/test-constraints.sh and arrive through
 * instrumentation arguments.
 *
 * Emulator facts these tests rely on (verified on an API 37 image):
 * - the emulator's "Wi-Fi" is a real WIFI-transport, NOT_METERED network;
 * - `svc wifi disable` leaves a CELLULAR network as the default (not wifi, not unmetered);
 * - `dumpsys battery set ac 1` + `set status 2` makes BatteryManager report charging,
 *   `dumpsys battery reset` restores the unpowered default.
 */
class ConstraintsUpdateTest : BaseUiTest() {

    private val appSettings get() = AppSettings(context)

    /**
     * The app under update: a tiny third-party app (io.github.bl3xand.e2etarget), installed
     * by the script with the app under test as its installer of record - the platform's
     * condition for updating it silently. The app under test cannot update itself silently:
     * it refuses that on purpose (canInstallSilently), so a separate package is needed.
     */
    private val targetPackage = "io.github.bl3xand.e2etarget"

    // ---- helpers -----------------------------------------------------------------------------

    /** Drives the background update pass the way the app itself schedules it. */
    private fun runBackgroundPass() {
        AutoUpdateWorker.runNow(context)
    }

    /**
     * Puts the target back to v1, with the app under test as its installer of record. Each
     * test starts from the same state: v1 installed, v2 offered by the source. Without this,
     * a test that ends with v2 installed leaves the next one trying to "update" v2 with the
     * very same v2 bytes - a no-op update the platform only performs after a confirmation,
     * which a silent background install never gets.
     */
    private fun resetTargetToV1() {
        device.executeShellCommand("pm uninstall $targetPackage")
        val size = apkOnDevice("target.v1").length()
        val session = device.executeShellCommand("cmd package install-create -r -t -i $packageName --install-reason 0")
            .trim().substringAfter("[").substringBefore("]").let { it.ifBlank { null } }
        check(session != null) { "Could not create an install session: $session" }
        device.executeShellCommand("cmd package install-write -S $size $session base ${apkOnDevice("target.v1").path}")
        device.executeShellCommand("cmd package install-commit $session")
        awaitCondition(30_000, "the target to be installed at v1") {
            context.packageManager.packageInfoOrNull(targetPackage, 0) != null
        }
    }

    /** The two APK builds, pushed by the script. */
    private fun apkOnDevice(name: String): File {
        val arg = InstrumentationRegistry.getArguments().getString(name)
            ?: error("Missing instrumentation argument $name (scripts/test-constraints.sh pushes the files)")
        return File(arg).also { check(it.exists()) { "No file at ${it.path}" } }
    }

    private fun serveVersion(server: FakeApkServer, version: Int) {
        val file = apkOnDevice(if (version == 1) "target.v1" else "target.v2")
        server.serveFile(file)
    }

    /**
     * What the system reports about the network the app would use: transport names and the
     * charging state, for readable failures.
     */
    private fun networkState(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val names = buildList {
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            }
        }
        return "transports=$names charging=${isCharging()}"
    }

    private fun isCharging(): Boolean =
        context.getSystemService(BatteryManager::class.java).isCharging

    private fun isOnWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        // VALIDATED, not just the transport: WorkManager holds the waiting work until the
        // network is usable, and on newer system images a freshly re-enabled wi-fi claims its
        // transport seconds before validation. A test that calls the transport "wi-fi back"
        // races the work it is waiting for.
        val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return usable && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
    }

    /** The system's own lastUpdateTime of the app, in millis: it moves on every real update. */
    private fun installedUpdateTime(): Long {
        val info = context.packageManager.packageInfoOrNull(targetPackage, 0) ?: error("App not installed")
        return info.lastUpdateTime
    }

    /**
     * Whether the tracked app's record says the installed version is the latest one - the
     * app's own notion of "the update went in", written after a successful silent install.
     */
    private fun recordIsInstalledAtLatest(): Boolean {
        val app = storedTrackedApps().optJSONObject(0) ?: return false
        val installed = app.optString("installedVersion")
        return installed.isNotEmpty() && installed == app.optString("latestVersion")
    }

    /** Reads the tracked apps straight from the app's own store: filesDir/sources/app_data. */
    private fun storedTrackedApps(): JSONArray {
        val dir = File(context.filesDir, "sources/app_data")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return JSONArray(files.map { JSONObject(it.readText()) })
    }

    /** Re-runs [probe] until it returns true, or gives up after [timeoutMs]. */
    private fun awaitCondition(timeoutMs: Long, probe: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun awaitCondition(timeoutMs: Long, what: String, probe: () -> Boolean) {
        check(awaitCondition(timeoutMs, probe)) { "Timed out waiting for: $what\nState: ${networkState()}" }
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
        val addButton = device.findObject(By.text(string("add")))
            ?: error("No Add button in the add sheet. Screen:\n${dumpVisibleTexts()}")
        addButton.click()
        device.wait(Until.gone(By.text(addAppHeading)), 60_000)
    }

    /**
     * Sets the app up as the one tracked app: a fresh data wipe happened in setUp, so the app
     * is added by URL, its page is closed, and the store has exactly one entry. The version
     * served at this point decides what "installed" means: the app is on the device at v1, and
     * v1 is what the source currently offers, so nothing is pending yet.
     */
    private fun trackTargetAtV1(server: FakeApkServer) {
        // Back to v1 with the app as the installer of record, whatever the last test left.
        resetTargetToV1()
        check(context.packageManager.installerOf(targetPackage) == packageName) {
            "Installer of record for $targetPackage is ${context.packageManager.installerOf(targetPackage)}, " +
                "not the app under test - the session install above sets it for exactly this"
        }
        serveVersion(server, 1)
        addAppByUrl(FakeApkServer.APK_URL)
        // The app's own page opened; close it to get back to the list.
        device.pressBack()
        device.waitForIdle(2_000)
        val stored = storedTrackedApps()
        check(stored.length() == 1) { "Expected 1 tracked app, got ${stored.length()}" }
        check(stored.getJSONObject(0).getString("url") == FakeApkServer.APK_URL) { "Wrong URL stored" }
    }

    /** Makes v2 the version the source offers. */
    private fun publishV2(server: FakeApkServer) = serveVersion(server, 2)

    private fun restoreEmulatorState() {
        device.executeShellCommand("svc wifi enable")
        device.executeShellCommand("dumpsys battery reset")
    }

    /** The state of the deferred "auto-update-waiting" work, for assertions about deferral. */
    private fun waitingWork(): WorkInfo? =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("auto-update-waiting").get()
            .firstOrNull()

    private fun assertNeverInstalled(after: Long, withinMs: Long, what: String) {
        val moved = awaitCondition(withinMs) { installedUpdateTime() != after }
        check(!moved) {
            "$what: the app was updated although it must not have been " +
                "(lastUpdateTime moved from $after to ${installedUpdateTime()})"
        }
    }

    // ---- the matrix --------------------------------------------------------------------------

    /**
     * Charging-only ON, not charging: the background pass reports the update but must not
     * install it. The deferral has to be real at the WorkManager level too: a waiting job
     * with a charging constraint is standing by.
     */
    @Test
    fun chargingOnly_notCharging_reportsButDoesNotInstall() {
        val server = FakeApkServer(apkOnDevice("target.v1")).also { it.startAndWait() }
        try {
            trackTargetAtV1(server)
            appSettings.apply {
                checkUpdates = true
                autoInstall = true
                checkClones = false
                checkSources = true
                wifiOnly = false
                chargingOnly = true
            }
            device.executeShellCommand("dumpsys battery reset")
            awaitCondition(5_000, "battery settles to not charging") { !isCharging() }
            publishV2(server)

            val before = installedUpdateTime()
            runBackgroundPass()

            // The check ran and found the update: the tracked app's latest version changed.
            awaitCondition(60_000, "the check to see v2") {
                val app = storedTrackedApps().optJSONObject(0)
                app?.optString("latestVersion").orEmpty().isNotBlank() &&
                    app.optString("latestVersion") != app.optString("installedVersion")
            }
            // ...but nothing was installed.
            assertNeverInstalled(before, 20_000, "charging-only while not charging")

            // The deferral is at the system level: the waiting work carries the constraint.
            val waiting = waitingWork()
            check(waiting != null) { "No auto-update-waiting work was scheduled" }
            check(waiting.state == WorkInfo.State.ENQUEUED) { "Waiting work state: ${waiting.state}" }
            val job = device.executeShellCommand("dumpsys jobscheduler")
            check(job.contains("io.github.bl3xand.apkcloner") &&
                Regex("Requires: .*charging=true", RegexOption.IGNORE_CASE).containsMatchIn(job)) {
                "No charging-constrained job found for the app in dumpsys jobscheduler"
            }
        } finally {
            restoreEmulatorState()
            server.stop()
        }
    }

    /**
     * Charging-only ON, charging: the same setup, now with the emulator plugged in. The
     * background pass downloads v2 and silently updates the app in place.
     */
    @Test
    fun chargingOnly_charging_installs() {
        val server = FakeApkServer(apkOnDevice("target.v1")).also { it.startAndWait() }
        try {
            trackTargetAtV1(server)
            appSettings.apply {
                checkUpdates = true
                autoInstall = true
                checkClones = false
                checkSources = true
                wifiOnly = false
                chargingOnly = true
            }
            device.executeShellCommand("dumpsys battery set ac 1")
            device.executeShellCommand("dumpsys battery set status 2")
            awaitCondition(5_000, "battery settles to charging") { isCharging() }
            publishV2(server)

            runBackgroundPass()

            // The record is the witness: the app marks the tracked app installed at its
            // latest version once the silent update landed (the system's lastUpdateTime moves
            // a moment earlier - asserting on it races the app's own bookkeeping).
            awaitCondition(180_000, "the silent update while charging") {
                recordIsInstalledAtLatest()
            }
        } finally {
            restoreEmulatorState()
            server.stop()
        }
    }

    /**
     * Wi-Fi-only ON, on the emulator's Wi-Fi (a real WIFI-transport, unmetered network): the
     * restriction does not hold the update back.
     */
    @Test
    fun wifiOnly_onWifi_installs() {
        val server = FakeApkServer(apkOnDevice("target.v1")).also { it.startAndWait() }
        try {
            trackTargetAtV1(server)
            appSettings.apply {
                checkUpdates = true
                autoInstall = true
                checkClones = false
                checkSources = true
                wifiOnly = true
                chargingOnly = false
            }
            device.executeShellCommand("svc wifi enable")
            awaitCondition(30_000, "wi-fi to be the active network") { isOnWifi() }
            publishV2(server)

            runBackgroundPass()
            awaitCondition(180_000, "the silent update on wi-fi") { recordIsInstalledAtLatest() }
        } finally {
            restoreEmulatorState()
            server.stop()
        }
    }

    /**
     * Wi-Fi-only ON, no wi-fi (the emulator falls back to its CELLULAR network): the update is
     * found, not installed, and the waiting work carries an UNMETERED network constraint.
     * When wi-fi returns, that work fires by itself and the install happens - the assertion
     * is that it happens strictly after the network came back.
     */
    @Test
    fun wifiOnly_noWifi_defersUntilWifiReturns() {
        val server = FakeApkServer(apkOnDevice("target.v1")).also { it.startAndWait() }
        try {
            trackTargetAtV1(server)
            appSettings.apply {
                checkUpdates = true
                autoInstall = true
                checkClones = false
                checkSources = true
                wifiOnly = true
                chargingOnly = false
            }
            device.executeShellCommand("svc wifi disable")
            awaitCondition(15_000, "the fallback network without wi-fi") { !isOnWifi() }
            publishV2(server)

            val before = installedUpdateTime()
            runBackgroundPass()

            // Found, but not installed.
            awaitCondition(60_000, "the check to see v2 without wi-fi") {
                val app = storedTrackedApps().optJSONObject(0)
                app != null && app.optString("latestVersion") != app.optString("installedVersion")
            }
            assertNeverInstalled(before, 15_000, "wi-fi-only without wi-fi")

            val waiting = waitingWork()
            check(waiting != null && waiting.state == WorkInfo.State.ENQUEUED) {
                "No waiting work scheduled (state: ${waiting?.state})"
            }

            // Wi-Fi returns; the waiting work must fire and install on its own.
            device.executeShellCommand("svc wifi enable")
            val wifiBackAt = System.currentTimeMillis()
            awaitCondition(30_000, "wi-fi to return") { isOnWifi() }
            awaitCondition(180_000, "the deferred install after wi-fi returned") { recordIsInstalledAtLatest() }
            check(installedUpdateTime() >= wifiBackAt) {
                "The install happened before the network returned ($wifiBackAt)"
            }
        } finally {
            restoreEmulatorState()
            server.stop()
        }
    }

    /**
     * Both restrictions ON and neither satisfied: nothing installs. Both satisfied: installs.
     * The first half doubles as the "neither" cell of the matrix; the second half runs right
     * after, on the same tracked app, so the pair also proves the waiting work picks up the
     * update once both conditions turn good.
     */
    @Test
    fun bothRestrictions_blockThenAllow() {
        val server = FakeApkServer(apkOnDevice("target.v1")).also { it.startAndWait() }
        try {
            trackTargetAtV1(server)
            appSettings.apply {
                checkUpdates = true
                autoInstall = true
                checkClones = false
                checkSources = true
                wifiOnly = true
                chargingOnly = true
            }
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("dumpsys battery reset")
            awaitCondition(15_000, "neither wi-fi nor charging") { !isOnWifi() && !isCharging() }
            publishV2(server)

            val before = installedUpdateTime()
            runBackgroundPass()
            awaitCondition(60_000, "the check to see v2 with neither condition") {
                val app = storedTrackedApps().optJSONObject(0)
                app != null && app.optString("latestVersion") != app.optString("installedVersion")
            }
            assertNeverInstalled(before, 15_000, "both restrictions, neither satisfied")

            val waiting = waitingWork()
            check(waiting != null && waiting.state == WorkInfo.State.ENQUEUED) {
                "No waiting work scheduled (state: ${waiting?.state})"
            }
            val job = device.executeShellCommand("dumpsys jobscheduler")
            check(Regex("Requires: .*charging=true", RegexOption.IGNORE_CASE).containsMatchIn(job)) {
                "No charging constraint on the waiting job"
            }

            // Both conditions turn good; the deferred install fires.
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("dumpsys battery set ac 1")
            device.executeShellCommand("dumpsys battery set status 2")
            awaitCondition(30_000, "both conditions to hold") { isOnWifi() && isCharging() }
            awaitCondition(180_000, "the deferred install once both hold") { recordIsInstalledAtLatest() }
        } finally {
            restoreEmulatorState()
            server.stop()
        }
    }
}