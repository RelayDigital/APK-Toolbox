package io.github.bl3xand.apkcloner.sources.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A lenient URL value: it accepts anything a user or a web page can throw at it (the strict
 * java.net.URI rejects spaces, brackets and the like) and exposes the parts the sources need.
 */
class Url private constructor(
    val scheme: String,
    val userInfo: String,
    val host: String,
    /** Explicit port, or -1. */
    val explicitPort: Int,
    val path: String,
    /** Raw query without '?', or null when there is none. */
    val query: String?,
    val fragment: String?,
    private val hasAuthority: Boolean,
) {
    val port: Int
        get() = when {
            explicitPort >= 0 -> explicitPort
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> 0
        }

    val isAbsolute: Boolean get() = scheme.isNotEmpty() && fragment == null

    val hasScheme: Boolean get() = scheme.isNotEmpty()

    /** scheme://host[:port] */
    val origin: String
        get() = buildString {
            append(scheme).append("://").append(host)
            if (explicitPort >= 0 && !isDefaultPort()) append(':').append(explicitPort)
        }

    private fun isDefaultPort() =
        (scheme == "https" && explicitPort == 443) || (scheme == "http" && explicitPort == 80)

    /** Decoded segments; a trailing slash yields a final empty segment, a bare "/" none. */
    val pathSegments: List<String>
        get() {
            val trimmed = path.removePrefix("/")
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.split('/').map(::decodeComponent)
        }

    /** Decoded query parameters; the last value wins for a repeated key. */
    val queryParameters: Map<String, String>
        get() {
            val q = query ?: return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                result[decodeQueryComponent(key)] = decodeQueryComponent(value)
            }
            return result
        }

    fun withPath(newPath: String): Url =
        Url(scheme, userInfo, host, explicitPort, newPath, query, fragment, hasAuthority)

    fun withPathSegments(segments: List<String>): Url {
        val joined = segments.joinToString("/") { encodePathSegment(it) }
        return withPath(if (hasAuthority && joined.isNotEmpty()) "/$joined" else joined)
    }

    fun withQuery(newQuery: String?): Url =
        Url(scheme, userInfo, host, explicitPort, path, newQuery, fragment, hasAuthority)

    fun withQueryParameters(parameters: Map<String, String>): Url =
        withQuery(
            if (parameters.isEmpty()) "" else parameters.entries.joinToString("&") {
                "${encodeQueryComponent(it.key)}=${encodeQueryComponent(it.value)}"
            },
        )

    fun withoutFragment(): Url =
        Url(scheme, userInfo, host, explicitPort, path, query, null, hasAuthority)

    /** Resolves [reference] against this URL the way a browser resolves a link. */
    fun resolve(reference: String): Url {
        val ref = parse(reference)
        if (ref.scheme.isNotEmpty()) return ref
        if (ref.hasAuthority) {
            return Url(scheme, ref.userInfo, ref.host, ref.explicitPort, removeDotSegments(ref.path), ref.query, ref.fragment, true)
        }
        if (ref.path.isEmpty()) {
            return Url(scheme, userInfo, host, explicitPort, path, ref.query ?: query, ref.fragment, hasAuthority)
        }
        val merged = if (ref.path.startsWith("/")) {
            ref.path
        } else if (hasAuthority && path.isEmpty()) {
            "/" + ref.path
        } else {
            path.substring(0, path.lastIndexOf('/') + 1) + ref.path
        }
        return Url(scheme, userInfo, host, explicitPort, removeDotSegments(merged), ref.query, ref.fragment, hasAuthority)
    }

    override fun toString(): String = buildString {
        if (scheme.isNotEmpty()) append(scheme).append(':')
        if (hasAuthority) {
            append("//")
            if (userInfo.isNotEmpty()) append(userInfo).append('@')
            append(host)
            if (explicitPort >= 0 && !isDefaultPort()) append(':').append(explicitPort)
        }
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    override fun equals(other: Any?): Boolean = other is Url && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        private val parts = Regex("^(?:([a-zA-Z][a-zA-Z0-9+.\\-]*):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.DOT_MATCHES_ALL)

        fun parse(text: String): Url {
            val match = parts.find(text.trim()) ?: return Url("", "", "", -1, text, null, null, false)
            val scheme = match.groups[1]?.value?.lowercase() ?: ""
            val authority = match.groups[2]?.value
            var userInfo = ""
            var host = ""
            var port = -1
            if (authority != null) {
                var hostPort = authority
                val at = authority.lastIndexOf('@')
                if (at >= 0) {
                    userInfo = authority.substring(0, at)
                    hostPort = authority.substring(at + 1)
                }
                val closingBracket = hostPort.lastIndexOf(']')
                val colon = hostPort.lastIndexOf(':')
                if (colon > closingBracket) {
                    port = hostPort.substring(colon + 1).toIntOrNull() ?: -1
                    hostPort = hostPort.substring(0, colon)
                }
                host = hostPort.lowercase()
            }
            return Url(
                scheme, userInfo, host, port,
                match.groups[3]?.value ?: "",
                match.groups[4]?.value,
                match.groups[5]?.value,
                authority != null,
            )
        }

        fun tryParse(text: String?): Url? = if (text == null) null else try {
            parse(text)
        } catch (e: Exception) {
            null
        }

        private fun removeDotSegments(path: String): String {
            if (!path.contains("/.") && !path.startsWith(".")) return path
            val output = ArrayDeque<String>()
            val segments = path.split('/')
            for ((index, segment) in segments.withIndex()) {
                when (segment) {
                    "." -> if (index == segments.lastIndex) output.addLast("")
                    ".." -> {
                        if (output.size > 1 || (output.size == 1 && output.first() != "")) output.removeLast()
                        if (index == segments.lastIndex) output.addLast("")
                    }
                    else -> output.addLast(segment)
                }
            }
            val joined = output.joinToString("/")
            return if (path.startsWith("/") && !joined.startsWith("/")) "/$joined" else joined
        }

        fun decodeComponent(text: String): String = try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            text
        }

        fun decodeQueryComponent(text: String): String = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }

        /** Percent-decodes everything except characters that have a meaning in a URL. */
        fun decodeFull(text: String): String {
            val reserved = ";/?:@&=+\$,#%"
            return Regex("(?:%[0-9a-fA-F]{2})+").replace(text) { m ->
                val decoded = try {
                    URLDecoder.decode(m.value, "UTF-8")
                } catch (e: Exception) {
                    return@replace m.value
                }
                if (decoded.any { it in reserved }) {
                    // Keep reserved characters encoded, decode the rest one sequence at a time.
                    Regex("%[0-9a-fA-F]{2}").replace(m.value) { single ->
                        val c = single.value.substring(1).toInt(16).toChar()
                        if (c in reserved || c.code >= 0x80) single.value else c.toString()
                    }
                } else decoded
            }
        }

        /** Space becomes "+". */
        fun encodeQueryComponent(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("%7E", "~")

        /** Space becomes "%20". */
        fun encodeComponent(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%7E", "~")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
                .replace("%2A", "*")

        private fun encodePathSegment(text: String): String =
            encodeComponent(text).replace("%40", "@").replace("%3A", ":").replace("%24", "$")
                .replace("%26", "&").replace("%2B", "+").replace("%2C", ",").replace("%3B", ";")
                .replace("%3D", "=")

        /** [ambiguous] as an absolute URL, resolved against [reference] when it is relative. */
        fun ensureAbsolute(ambiguous: String, reference: Url): String {
            val trimmed = ambiguous.trim()
            val parsed = tryParse(trimmed)
            if (parsed != null && parsed.isAbsolute) return trimmed
            return reference.resolve(trimmed).toString()
        }

        /** The last two labels of a host name ("api.github.com" -> "github.com"). */
        fun rootHost(host: String): String {
            val labels = host.split('.')
            return if (labels.size > 2) labels.takeLast(2).joinToString(".") else host
        }

        fun sameOrigin(a: Url, b: Url): Boolean =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port

        /** A java.net.URI for the HTTP stack; illegal characters are percent-encoded first. */
        fun toJavaUri(text: String): URI = try {
            URI(text)
        } catch (e: Exception) {
            val sb = StringBuilder()
            for (c in text) {
                if (c.code <= 0x20 || c.code >= 0x7f || c in "\"<>\\^`{|}") {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append("%02X".format(b.toInt() and 0xff))
                    }
                } else sb.append(c)
            }
            URI(sb.toString())
        }
    }
}
