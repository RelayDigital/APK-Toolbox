package io.github.bl3xand.apkcloner.sources.net

import io.github.bl3xand.apkcloner.sources.core.Url
import java.io.InputStream
import java.net.HttpURLConnection

/** An open response whose body has not been read yet. The caller must [close] it. */
class HttpStream(
    val url: Url,
    val connection: HttpURLConnection,
    val statusCode: Int,
) : AutoCloseable {
    val reasonPhrase: String get() = connection.responseMessage ?: ""

    fun header(name: String): String? = connection.getHeaderField(name)

    /** Null when the server did not announce a length. */
    val contentLength: Long? get() = connection.contentLengthLong.takeIf { it > 0 }

    val body: InputStream
        get() = (if (statusCode >= 400) connection.errorStream else connection.inputStream)
            ?: ByteArray(0).inputStream()

    override fun close() {
        try {
            connection.disconnect()
        } catch (_: Exception) {
        }
    }
}
