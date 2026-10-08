package io.github.bl3xand.apkcloner.install

import java.io.File

/** Experimental: installing with `su`. Not tested on a rooted device. */
object Root {
    private fun run(script: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    fun isAvailable(): Boolean = try {
        run("id -u").second.trim() == "0"
    } catch (e: Exception) {
        false
    }

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
        val (code, output) = run(script)
        if (code == 0) null else output.trim().ifEmpty { "pm install failed ($code)" }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
