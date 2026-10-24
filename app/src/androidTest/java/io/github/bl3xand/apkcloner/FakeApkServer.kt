package io.github.bl3xand.apkcloner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * A local stand-in for a download site, running inside the test (inside the app's own process,
 * on the emulator itself: the guest's own loopback, 127.0.0.1 - never 10.0.2.2, which from the
 * guest is the host the emulator runs on, a different machine entirely). Serves one APK file
 * under a name that makes the app's DirectAPKLink source accept the URL, with support for ranged
 * reads - the app pseudo-versions such files by hashing their first kilobyte, twice over.
 *
 * The served file is the app's own debug build, pushed onto the emulator's shared storage by
 * scripts/test-ui.sh; the guest path is passed through the "served.apk" instrumentation
 * argument.
 */
class FakeApkServer(
    private val apkOnGuest: File,
    private val port: Int = BaseUiTest.SERVER_PORT,
) : NanoHTTPD("0.0.0.0", port) {

    /** Starts and waits until the port answers, or throws. */
    fun startAndWait(timeoutMs: Long = 10_000) {
        start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAlive()) return
            Thread.sleep(100)
        }
        error("FakeApkServer did not become reachable on port $port within ${timeoutMs}ms")
    }

    /** How many times each kind of request was seen; the tests assert on these. */
    val hitCount = mutableMapOf<String, Int>().withDefault { 0 }
    private fun hit(kind: String) {
        hitCount[kind] = (hitCount[kind] ?: 0) + 1
    }

    override fun serve(session: IHTTPSession): Response {
        val range = session.headers["range"]
        hit(if (range == null) "full" else "range")
        val uri = session.uri.trimStart('/')
        return when {
            uri == "app.apk" || uri.endsWith(".apk") -> serveApk(session, range)
            uri == "index.html" || uri.isEmpty() -> newFixedLengthResponse(
                Response.Status.OK, "text/html", "<html><body>test server</body></html>",
            )
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    private fun serveApk(session: IHTTPSession, range: String?): Response {
        val length = apkOnGuest.length()
        if (range == null) {
            // A full-body answer: 200 with the whole file.
            return newChunkedResponse(Response.Status.OK, "application/vnd.android.package-archive", apkOnGuest.inputStream())
                .apply { addHeader("Content-Length", length.toString()) }
        }
        // "bytes=0-1024" and friends: the app hashes file prefixes to version them.
        val match = Regex("bytes=(\\d+)-(\\d+)").find(range)
        if (match == null) {
            // An open-ended range ("bytes=0-") answers with the whole file, as 206.
            val from = Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            return rangedResponse(from, length - 1, length)
        }
        val from = match.groupValues[1].toLong()
        val to = match.groupValues[2].toLong().coerceAtMost(length - 1)
        return rangedResponse(from, to, length)
    }

    private fun rangedResponse(from: Long, to: Long, total: Long): Response {
        val stream = FileInputStream(apkOnGuest).apply { skip(from) }
        val size = (to - from + 1).coerceAtLeast(0)
        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, "application/vnd.android.package-archive",
            stream, size,
        ).apply { addHeader("Content-Range", "bytes $from-$to/$total") }
    }

    companion object {
        /** The URL the app sees: the emulator's own loopback, where this server runs. */
        const val APK_URL = "http://127.0.0.1:${BaseUiTest.SERVER_PORT}/app.apk"

        /**
         * The served APK on the emulator, from the instrumentation arguments; the fallback is
         * where scripts/test-ui.sh pushes it.
         */
        fun apkFromArguments(): File? =
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                ?.getString("served.apk")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
    }
}