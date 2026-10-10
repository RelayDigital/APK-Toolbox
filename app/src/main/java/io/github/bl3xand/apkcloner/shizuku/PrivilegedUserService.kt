package io.github.bl3xand.apkcloner.shizuku

import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.TimeUnit

/**
 * Instantiated by Shizuku inside a separate, privileged (shell UID) process. Installs by
 * shelling out to `pm`, the same way `adb install-multiple` does: a session is created, each APK
 * is streamed into it over stdin (the shell cannot read this app's private files), then the
 * session is committed.
 */
class PrivilegedUserService : IPrivilegedService.Stub() {

    override fun install(
        apks: Array<ParcelFileDescriptor>,
        names: Array<String>,
        sizes: LongArray,
        installerPackage: String,
        userId: Int,
    ): String {
        var session: String? = null
        try {
            // -r replace, -t allow test-only builds, and no refusal of apps targeting an old SDK:
            // the same set Obtainium uses. Keeping this app as the installer of record lets the
            // stock installer update the clone silently later, too.
            // The block on apps that target an old SDK came with Android 14, and so did the
            // option that lifts it: an older `pm` refuses an option it does not know.
            val liftTargetBlock = listOf("--bypass-low-target-sdk-block").filter { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
            val created = run(
                "pm", "install-create", "-r", "-t", *liftTargetBlock.toTypedArray(),
                "-i", installerPackage, "--user", userId.toString(),
            )
            session = SESSION_ID.find(created)?.groupValues?.get(1) ?: return created.ifBlank { "install-create failed" }

            for (i in apks.indices) {
                val write = ProcessBuilder("pm", "install-write", "-S", sizes[i].toString(), session, names[i], "-")
                    .redirectErrorStream(true)
                    .start()
                ParcelFileDescriptor.AutoCloseInputStream(apks[i]).use { input ->
                    write.outputStream.use { input.copyTo(it, 1 shl 16) }
                }
                val result = finish(write)
                if (!result.startsWith("Success")) return result.ifBlank { "install-write failed" }
            }

            val committed = run("pm", "install-commit", session)
            if (committed.startsWith("Success")) {
                session = null
                return ""
            }
            return committed.ifBlank { "install-commit failed" }
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            apks.forEach { runCatching { it.close() } }
            session?.let { runCatching { run("pm", "install-abandon", it) } }
        }
    }

    private fun run(vararg command: String): String =
        finish(ProcessBuilder(*command).redirectErrorStream(true).start())

    /** Bounds how long a stuck `pm` child process can block this Binder call. */
    private fun finish(process: Process): String {
        // Drained on this thread first: pm prints a line or two, far below the pipe's capacity.
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return "Timed out"
        }
        return output
    }

    private companion object {
        val SESSION_ID = Regex("\\[(\\d+)]")
    }
}
