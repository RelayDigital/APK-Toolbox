package io.github.bl3xand.apkcloner.sources.net

import io.github.bl3xand.apkcloner.sources.core.Url
import java.nio.charset.Charset

/** A fully read response. Header names are lower-case; repeated headers are joined with ", ". */
class HttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    /** The URL that produced this response (after redirects). */
    val requestUrl: Url,
) {
    val body: String by lazy { String(bodyBytes, charset()) }

    private fun charset(): Charset {
        val contentType = headers["content-type"] ?: return Charsets.UTF_8
        val match = Regex("charset=\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
        return try {
            match?.let { Charset.forName(it.groupValues[1]) } ?: Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }
}
