package io.github.bl3xand.apkcloner.install.methods

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Experimental: installing with `su`. Not tested on a rooted device. Every command is
 * bounded, and one probe's answer stands for a while: a root manager that never answers -
 * its prompt sitting unattended, its daemon dead - counts as no root instead of hanging
 * whoever asked.
 */
object Root {
    /** How long `su` gets to answer the availability probe. */
    private const val PROBE_TIMEOUT_MS = 15_000L

    /** How long a whole install script gets; the update worker is stopped around ten minutes in anyway. */
    private const val INSTALL_TIMEOUT_MS = 10 * 60_000L

    /** How long a probe that found root stands: root does not go away on its own. */
    private const val GRANTED_STANDS_MS = 5 * 60_000L

    /** How long a probe that found none stands: a grant can arrive at any moment. */
    private const val DENIED_STANDS_MS = 15_000L

    @Volatile
    private var probedAvailable: Boolean? = null

    @Volatile
    private var probedAt: Long = 0

    private fun run(script: String, timeoutMs: Long): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        // Waited for before the pipe is drained: a su that hangs without a word cannot hold
        // this thread past the timeout, and what a finished process printed waits in the pipe.
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("su did not answer within ${timeoutMs / 1000}s")
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    fun isAvailable(): Boolean {
        // A recent probe stands: repeated installs do not fork su one after another, and a
        // root manager that asks per session is not made to ask from the background each time.
        val probed = probedAvailable
        if (probed != null && answerStands(probed, System.currentTimeMillis() - probedAt)) return probed
        val available = try {
            run("id -u", PROBE_TIMEOUT_MS).second.trim() == "0"
        } catch (e: Exception) {
            // Nothing answered, or nothing granted: for the moment, root is simply not there.
            false
        }
        probedAvailable = available
        probedAt = System.currentTimeMillis()
        return available
    }

    /** How long a probe's answer stands before the next install asks `su` again (tests). */
    internal fun answerStands(probed: Boolean, ageMs: Long): Boolean =
        if (probed) ageMs < GRANTED_STANDS_MS else ageMs < DENIED_STANDS_MS

    /** Returns null on success, otherwise what `pm` said. */
    fun install(apks: List<File>, installerPackage: String?): String? = try {
        fun quote(path: String) = "'${path.replace("'", "'\\''")}'"
        // The files are staged where the package manager can read them; base first.
        val copies = apks.mapIndexed { i, apk -> "cp ${quote(apk.path)} \"\$d/apk$i.apk\"" }.joinToString("\n")
        val staged = apks.indices.joinToString(" ") { "\"\$d/apk$it.apk\"" }
        val script = listOf(
            "d=\"\$(mktemp -d /data/local/tmp/apktoolbox.XXXXXX)\" || exit 1",
            "trap 'rm -rf \"\$d\"' EXIT",
            copies,
            "uid=\"\$(am get-current-user 2>/dev/null)\"; uid=\"\${uid:-0}\"",
            "pm install -r ${installerPackage?.let { "-i '$it' " } ?: ""}--user \"\$uid\" $staged",
        ).joinToString("\n")
        val (code, output) = run(script, INSTALL_TIMEOUT_MS)
        installFailureOf(code, output)
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Returns null when the install succeeded, otherwise what `pm` said (tests). */
    internal fun installFailureOf(code: Int, output: String): String? {
        // `pm install` exits 0 while saying it failed: the words decide, not the code alone.
        val said = output.trim()
        if (code == 0 && said.lineSequence().none { it.startsWith("Failure") || it.startsWith("Error") }) return null
        return said.ifEmpty { "pm install failed ($code)" }
    }
}
