package io.github.bl3xand.apkcloner

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.bl3xand.apkcloner.testing.TestHooks
import org.junit.After
import org.junit.Before
import org.json.JSONObject
import java.io.File

/**
 * Common ground of the UI tests: the app installed with a clean slate, its permissions granted,
 * and MainActivity on the Sources tab (the one the app starts on). Permission grants go through
 * `pm grant` and `appops` instead of the UI, so that the tests care only about what they test.
 *
 * The tests run inside the app's own process, so nothing that stops the app (`am force-stop`,
 * `pm clear`) may be executed through the instrumentation shell while a test is going: it kills
 * the tests with it. A clean slate comes from wiping the app's data directories through `run-as`
 * (possible because these tests run against a debug build) right after the test process was
 * started - the app is not running yet at that point.
 *
 * The test hooks are on from here down: the instrumentation enables them (a debug-only switch,
 * docs/test-hooks.md), and setUp resets the app's in-memory state to fresh after the file wipe -
 * most of all the "self" app's once-only tracking, which would otherwise race the test's own
 * add with its own (home-upx.49).
 */
abstract class BaseUiTest {

    protected lateinit var device: UiDevice
        private set

    protected val packageName: String get() = BuildConfig.APPLICATION_ID

    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    open fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // The hooks are the app's own debug-build feature; the instrumentation APK builds
        // against the same variant, so its BuildConfig.DEBUG says the same thing.
        TestHooks.enable(BuildConfig.DEBUG)
        // An update check started by an earlier test may still be saving what it fetched; it
        // would write the wiped data right back. The tests run in the app's process, so the
        // hooks' counter of started and finished checks is reachable directly: wait until
        // none is in flight (docs/test-hooks.md).
        val deadline = System.currentTimeMillis() + 30_000
        while (TestHooks.checksStarted != TestHooks.checksFinished && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        // The instrumentation process has just started and the app is not running yet: safe to
        // clear whatever a previous run left. The wipe runs as the app itself (the tests are
        // the app); databases go too, so the app's log starts empty and a test can assert on
        // the lines it wrote itself.
        wipeAppData()
        // What the wipe on disk cannot reach: the in-memory state of the still-running
        // process - tracked apps, downloads, check errors, the hooks' own records, and the
        // once-only self-tracking flag (gone with the wiped prefs, so it is set to "done"
        // here: a reload must not add the app itself and race the test's own add).
        TestHooks.resetAppState(context)
        launchMainActivity()
        // The app starts on the Sources tab; wait for its empty-state text to appear.
        waitForStringResource("sources_empty", timeout = 15_000)
    }

    @After
    open fun tearDown() {
        // Nothing to stop: the app keeps running between tests (stopping it would kill the
        // tests, see the class comment). Each test's data was wiped before it started.
    }

    /** Removes everything the app stored. The tests run as the app itself, so plain file
     *  operations do what `run-as` would - and work where UiAutomation's shell cannot. */
    private fun wipeAppData() {
        // Everything under /data/data of the app, except the profile the JIT keeps.
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.getDatabasePath("sources_logs.db").parentFile?.let { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        java.io.File(context.filesDir.parentFile, "shared_prefs").deleteRecursively()
        java.io.File(context.filesDir.parentFile, "no_backup").deleteRecursively()
        // What the app keeps in external storage of its own; the shell user may write there.
        context.getExternalFilesDir(null)?.parentFile?.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
        // File access (Android 11+): a special app-operation, not a runtime permission.
        device.executeShellCommand("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        // Installing other apps (Android 8+): likewise an app-operation on a manifest
        // permission. Without it the app only offers to open the system settings.
        device.executeShellCommand("appops set $packageName REQUEST_INSTALL_PACKAGES allow")
        // The one runtime permission the app asks for.
        device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
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
        if (id != 0) return res.getString(id)
        // The Sources tab keeps its texts not in resources but in the JSON catalogues under
        // assets/sources/i18n (en.json and own_en.json), shared with the reference app's format.
        return sourceString(name)
    }

    /** A text of the sources' JSON catalogues, in the app's current language. */
    protected fun sourceString(name: String): String {
        val locale = resLocale()
        val own = catalogue("own_$locale")
        val main = catalogue(locale)
        val fallback = catalogue("en")
        val text = own.opt(name) ?: main.opt(name) ?: fallback.opt(name)
        return text?.toString() ?: error("String \"$name\" not found in resources or i18n catalogues")
    }

    private fun resLocale(): String = when (context.resources.configuration.locales[0].language) {
        "ru" -> "ru"
        "zh" -> "zh"
        else -> "en"
    }

    private fun catalogue(name: String): JSONObject {
        val cached = catalogues[name]
        if (cached != null) return cached
        val json = context.assets.open("sources/i18n/$name.json").bufferedReader().use { it.readText() }
        return JSONObject(json).also { catalogues[name] = it }
    }

    private val catalogues = HashMap<String, JSONObject>()

    /** Waits until the app shows the given app string on screen. */
    protected fun waitForStringResource(name: String, timeout: Long = 10_000): UiObject2 {
        val text = string(name)
        return device.wait(Until.findObject(By.text(text)), timeout)
            ?: error("Timed out waiting for \"$text\" (string \"$name\"). Screen:\n${dumpVisibleTexts()}")
    }

    protected fun findString(name: String): UiObject2? = device.findObject(By.text(string(name)))

    /** One page of what is on screen, for readable failures. */
    protected fun dumpVisibleTexts(): String =
        device.findObjects(By.clazz("android.widget.TextView"))
            ?.mapNotNull { it.text?.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.joinToString("\n")
            .orEmpty()

    /** Scrolls a bottom-sheet dialog until its content shows [text], or gives up. */
    protected fun scrollSheetUntil(text: String, maxSwipes: Int = 15): UiObject2? {
        val sheet = device.findObject(By.clazz("android.widget.FrameLayout").res(packageName, "design_bottom_sheet"))
            ?: return device.findObject(By.text(text))
        repeat(maxSwipes) {
            device.findObject(By.text(text))?.let { return it }
            // A swipe over the sheet's handle: up on the visible half.
            val rect = sheet.visibleBounds
            device.swipe(
                rect.centerX(), rect.centerY(),
                rect.centerX(), rect.top + rect.height() / 6,
                40,
            )
            device.waitForIdle(500)
        }
        return device.findObject(By.text(text))
    }

    /**
     * The test APK on the device: pushed there by scripts/test-ui.sh before the run. The fake
     * update source serves this file, so the "update" the tests work with is a real, installable
     * build of the app itself.
     */
    protected fun servedApkOnDevice(): File {
        val candidates = listOf(
            File("/sdcard/Download/apk-toolbox-test.apk"),
            File("/data/local/tmp/apk-toolbox-test.apk"),
        )
        return candidates.firstOrNull(File::exists)
            ?: error("No test APK on the device. scripts/test-ui.sh pushes one before the run.")
    }

    companion object {
        /** The port of the test HTTP server, inside the emulator's own loopback. */
        const val SERVER_PORT = 18099
    }
}