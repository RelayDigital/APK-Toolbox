package io.github.bl3xand.apkcloner

import androidx.test.platform.app.InstrumentationRegistry
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.compat.SIGNERS_FLAG
import io.github.bl3xand.apkcloner.compat.packageInfoOrNull
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One row of the per-patch matrix on a real app, at runtime: clones the app with the patch set
 * given as an instrumentation argument (comma-separated PatchIds), installs the result the way
 * the app itself installs clones - through its own Installer, from its own cache, no shell
 * staging - then launches the clone's launcher activity and reports whether the process stayed
 * up without crashing. The per-patch and combination behaviour PatchCombinationMatrixTest
 * asserts at dex level, here on a device.
 *
 * Driven from the shell, one patch set per emulator:
 *   am instrument -e class ...PatchPerSetRuntimeTest -e patches keepAlive,spoofSignature \
 *     -e matrix.base /data/local/tmp/nsg-base.apk -e matrix.package com.qtrun.QuickTest \
 *     -e matrix.launcher com.qtrun.nsg.LauncherActivity
 * The clone package is the original plus ".matrix" so repeated runs replace each other. A
 * multi-APK app passes matrix.split too (all splits are cloned and installed in one session).
 */
class PatchPerSetRuntimeTest : BaseUiTest() {

    private fun shell(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd),
        ).use { it.readBytes().decodeToString() }.trim()

    @Test
    fun cloneWithTheGivenPatchSetBootsToUi() {
        val args = InstrumentationRegistry.getArguments()
        val originalPackage = args.getString("matrix.package") ?: "com.qtrun.QuickTest"
        val launcher = args.getString("matrix.launcher")
            ?: "com.qtrun.nsg.LauncherActivity" // resolved per app below when not given
        val clonePackage = "$originalPackage.matrix"
        val base = File(args.getString("matrix.base") ?: "/data/local/tmp/nsg-base.apk")
        val split = args.getString("matrix.split")?.let(::File) // optional, a multi-APK app
        // Any further splits of a multi-APK app, comma-separated paths; cloned and installed
        // in the same session after the first split.
        val moreSplits = (args.getString("matrix.splits") ?: "").split(',').filter { it.isNotBlank() }.map(::File)
        val patches = (args.getString("patches") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        assertTrue("No patch set given (-e patches ...)", patches.isNotEmpty())
        assertTrue("No source APK at ${base.path}", base.exists())

        device.executeShellCommand("pm uninstall $clonePackage")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.packageInfoOrNull(originalPackage, SIGNERS_FLAG)
            ?: error("Original $originalPackage is not installed")
        val sources = SourcesRepository.get(context)
        if (sources.entry(originalPackage) == null) {
            sources.saveApps(
                listOf(
                    TrackedApp(
                        id = originalPackage, url = "https://example.com/$originalPackage",
                        author = "author", name = originalPackage, latestVersion = info.versionName ?: "0",
                        preferredApkIndex = 0, additionalSettings = emptyMap(), allowIdChange = false,
                    ),
                ),
                onlyIfExists = false,
            )
        }
        val appInfo = info.applicationInfo ?: error("No applicationInfo on $originalPackage")

        val output = File(context.cacheDir, "matrix-clone")
        val request = io.github.bl3xand.apkcloner.data.ApkSource(
            packageName = originalPackage,
            label = appInfo.loadLabel(context.packageManager).toString(),
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            apkPaths = listOf(base.path) + listOfNotNull(split?.path) + moreSplits.map { it.path },
            isSystem = false,
            appInfo = appInfo,
        ).cloneRequest(newPackage = clonePackage, newLabel = "Matrix", badgeIcon = false, patches = patches)
        val apks = ApkCloner(context).clone(request, output) { _, _, _ -> }
        assertTrue("The clone produced no APKs", apks.isNotEmpty())

        // Installed the way the app itself installs: its own Installer, from its own cache -
        // the production path, so the staging problems of a shell session never arise. The
        // system's confirmation is handled by the instrumentation's UiAutomation adopting
        // the prompts, the same way the app's own install tests click them.
        val outcome = runBlocking {
            Installer.choose(context, background = false).install(
                context, apks, background = false, label = "Matrix", installerPackage = context.packageName,
            )
        }
        assertTrue("Install did not go through: $outcome", outcome is InstallOutcome.Pending || outcome is InstallOutcome.Success)

        // A new package through the system installer asks for its confirmation - the
        // system's own window, answered the way a user would (the FilesInstallTest
        // pattern): the non-Cancel button, re-found while the window re-layouts.
        fun frameworkText(name: String): String {
            val id = android.R.string::class.java.getField(name).getInt(null)
            return context.getString(id)
        }
        // The confirmation's buttons follow the SYSTEM locale, not the app's - so no text to match,
        // just geometry: the positive button is the right-most one of the prompt's button row,
        // and the prompt itself is the packageinstaller's window. Clicked as soon as it shows.
        val promptDeadline = System.currentTimeMillis() + 60_000
        var clicked = false
        while (System.currentTimeMillis() < promptDeadline && !clicked) {
            val prompt = runCatching {
                device.findObject(androidx.test.uiautomator.By.pkg("com.google.android.packageinstaller"))
            }.getOrNull()
            if (prompt != null) {
                val buttons = runCatching {
                    device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.Button"))
                }.getOrNull().orEmpty().filter { it.text.isNotBlank() }
                if (buttons.isNotEmpty()) {
                    val install = buttons.maxByOrNull { it.visibleBounds.left } ?: buttons.first()
                    runCatching { install.click() }
                    clicked = true
                }
            }
            if (!clicked) Thread.sleep(500)
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (context.packageManager.packageInfoOrNull(clonePackage, 0) != null) break
            Thread.sleep(500)
        }
        assertTrue(
            "The clone never appeared installed",
            context.packageManager.packageInfoOrNull(clonePackage, 0) != null,
        )

        // Launch by the clone's own launcher activity (found when not handed in), and give it
        // a moment: a working set reaches its first screen; a broken one crashes out.
        val launchComponent = launcher.takeIf { it.contains('.') && !it.startsWith("com.") || it.count { c -> c == '.' } > 1 }
            ?: device.executeShellCommand(
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $clonePackage",
            ).trim().substringAfter("/").ifBlank { error("No launcher activity found for $clonePackage") }
        device.executeShellCommand("logcat -c")
        device.executeShellCommand("am start -n $clonePackage/$launchComponent")
        // A 16 KB-page compatibility notice can cover the freshly launched clone on newer
        // emulators; it is a dialog, not a crash - dismissed so the clone runs on.
        Thread.sleep(3_000)
        if (device.findObject(androidx.test.uiautomator.By.textContains("16 KB")) != null ||
            device.findObject(androidx.test.uiautomator.By.textContains("compatib")) != null
        ) {
            device.pressBack()
        }
        Thread.sleep(20_000)
        val running = shell("pidof $clonePackage").isNotBlank()
        val crashes = shell("logcat -d -b crash -t 60").contains(clonePackage)
        assertTrue(
            "The clone with patches [${patches.joinToString()}] did not stay up (running=$running, crashed=$crashes)",
            running && !crashes,
        )
    }
}