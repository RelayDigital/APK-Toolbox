package io.github.bl3xand.apkcloner.sources.net

import io.github.bl3xand.apkcloner.sources.core.HttpStatusError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.ceil

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

/** Per-request options; these travel in the settings map of the reference implementation. */
data class RequestOptions(
    val allowInsecure: Boolean = false,
    val enableCertificatePinning: Boolean = false,
    val allowInsecureRedirects: Boolean = false,
)

object Http {
    const val MAX_REDIRECTS = 10
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val DEFAULT_USER_AGENT = "Dart/3.12 (dart:io)"

    /** Headers that may follow a redirect to another origin; everything else is dropped. */
    val safeRedirectHeaders = setOf(
        "accept", "accept-charset", "accept-encoding", "accept-language", "cache-control",
        "content-length", "content-type", "if-modified-since", "if-none-match", "if-range", "origin",
        "pragma", "range", "referer", "user-agent", "x-requested-with",
    )

    private val certificatePins: Map<String, List<String>> = mapOf(
        // Release assets redirect to a host served through Let's Encrypt, hence the ISRG roots.
        "github.com" to listOf(
            "sectigo-pub-serv-auth-r46.crt", "sectigo-pub-serv-auth-e46.crt",
            "isrg-root-x1.crt", "isrg-root-x2.crt", "isrg-root-ye.crt", "isrg-root-yr.crt",
        ),
        "codeberg.org" to listOf("isrg-root-x1.crt", "isrg-root-x2.crt", "isrg-root-ye.crt", "isrg-root-yr.crt"),
        "gitlab.com" to listOf("sectigo-pub-serv-auth-r46.crt", "sectigo-pub-serv-auth-e46.crt"),
        "rustore.ru" to listOf(
            "harica-tls-root-2021-rsa.crt", "harica-tls-root-2021-ecc.crt", "russian-mintsifry-root.crt",
        ),
    )

    private val socketFactories = HashMap<String, SSLSocketFactory>()

    private fun loadCertificate(name: String): X509Certificate {
        val stream = Http::class.java.getResourceAsStream("/sources/ca/$name")
            ?: error("Missing bundled certificate $name")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }

    private fun trustManagersFor(certificates: List<String>, withSystemRoots: Boolean): Array<TrustManager> {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, name -> keyStore.setCertificateEntry("pin$index", loadCertificate(name)) }
        val pinned = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        if (!withSystemRoots) return arrayOf(pinned)
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        // Accept a chain that either set of roots accepts.
        return arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: Exception) {
                    pinned.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + pinned.acceptedIssuers
        })
    }

    private fun socketFactory(key: String, managers: () -> Array<TrustManager>): SSLSocketFactory =
        synchronized(socketFactories) {
            socketFactories.getOrPut(key) {
                SSLContext.getInstance("TLS").apply { init(null, managers(), SecureRandom()) }.socketFactory
            }
        }

    private val trustEverything = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    private fun pinnedKeyFor(host: String): String? = when {
        certificatePins.containsKey(host) -> host
        certificatePins.containsKey(Url.rootHost(host)) -> Url.rootHost(host)
        else -> null
    }

    private fun configureTls(connection: HttpsURLConnection, host: String, options: RequestOptions) {
        val pinKey = if (options.enableCertificatePinning) pinnedKeyFor(host) else null
        when {
            // A pinned site keeps rejecting bad certificates even in insecure mode.
            pinKey != null -> connection.sslSocketFactory =
                socketFactory("pin:$pinKey") { trustManagersFor(certificatePins.getValue(pinKey), false) }
            options.allowInsecure -> {
                connection.sslSocketFactory = socketFactory("insecure") { trustEverything }
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            // RuStore partly moved to a state CA that neither Android nor browsers trust.
            !options.enableCertificatePinning && Url.rootHost(host) == "rustore.ru" ->
                connection.sslSocketFactory = socketFactory("rustore") {
                    trustManagersFor(listOf("russian-mintsifry-root.crt"), true)
                }
        }
    }

    /**
     * Sends a request and follows redirects by hand: credentials and other caller headers are
     * not forwarded to another origin, and a downgrade to plain HTTP is refused unless allowed.
     * [postBody] is sent as is when it is a String, as JSON otherwise.
     */
    fun requestStream(
        method: String,
        url: String,
        headers: Map<String, String>?,
        options: RequestOptions = RequestOptions(),
        followRedirects: Boolean = true,
        postBody: Any? = null,
    ): HttpStream {
        var currentUrl = Url.parse(url)
        var requestHeaders = headers
        var cookies: List<String> = emptyList()
        var redirectCount = 0
        while (redirectCount < MAX_REDIRECTS) {
            val connection = Url.toJavaUri(currentUrl.toString()).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = method
                if (connection is HttpsURLConnection) configureTls(connection, currentUrl.host, options)
                // Transparent compression would hide the content length a download needs.
                connection.setRequestProperty("Accept-Encoding", "identity")
                // Sites tell clients apart by this header; the reference's default is kept so
                // that they answer the same way.
                connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                requestHeaders?.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.joinToString("; "))
                if (postBody != null) {
                    connection.doOutput = true
                    val bytes = if (postBody is String) {
                        postBody.toByteArray(Charsets.UTF_8)
                    } else {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        JsonValues.stringify(postBody).toByteArray(Charsets.UTF_8)
                    }
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (followRedirects && status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        val nextUrl = Url.parse(Url.ensureAbsolute(location, currentUrl))
                        if (currentUrl.scheme == "https" && nextUrl.scheme == "http" &&
                            !options.allowInsecure && !options.allowInsecureRedirects
                        ) {
                            throw SourceError(Tr.get("insecureRedirect"))
                        }
                        if (!Url.sameOrigin(currentUrl, nextUrl)) {
                            requestHeaders = requestHeaders?.filterKeys { it.lowercase() in safeRedirectHeaders }
                            cookies = emptyList()
                        } else {
                            cookies = connection.headerFields.entries
                                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                                .flatMap { it.value }
                                .map { it.substringBefore(';') }
                        }
                        currentUrl = nextUrl
                        redirectCount++
                        connection.disconnect()
                        continue
                    }
                }
                return HttpStream(currentUrl, connection, status)
            } catch (e: Throwable) {
                connection.disconnect()
                throw e
            }
        }
        throw SourceError(Tr.get("tooManyRedirects"))
    }

    fun readFully(stream: HttpStream): HttpResponse = stream.use {
        val buffer = ByteArrayOutputStream()
        try {
            it.body.use { input -> input.copyTo(buffer) }
        } catch (e: java.io.IOException) {
            if (it.statusCode in 200..299) throw e
        }
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in it.connection.headerFields) {
            if (name != null) headers[name.lowercase()] = values.joinToString(", ")
        }
        HttpResponse(it.statusCode, it.reasonPhrase, headers, buffer.toByteArray(), it.url)
    }

    fun request(
        url: String,
        headers: Map<String, String>? = null,
        options: RequestOptions = RequestOptions(),
        followRedirects: Boolean = true,
        postBody: Any? = null,
    ): HttpResponse = readFully(
        requestStream(if (postBody == null) "GET" else "POST", url, headers, options, followRedirects, postBody),
    )

    /** The error for a failed response: 404 means no releases, 403/429 a rate limit. */
    fun errorFor(response: HttpResponse): SourceError {
        if (response.statusCode == 404) return NoReleasesError()
        if (response.statusCode == 429 || response.statusCode == 403) {
            val seconds = response.headers["retry-after"]?.toIntOrNull()
            return RateLimitError(if (seconds != null) ceil(seconds / 60.0).toInt() else 1)
        }
        return SourceError(
            response.reasonPhrase.ifEmpty { Tr.get("errorWithHttpStatusCode", response.statusCode.toString()) },
            code = "HTTP_ERROR",
        )
    }

    fun ensureSuccess(response: HttpResponse) {
        if (response.statusCode != 200) throw errorFor(response)
    }

    fun statusError(stream: HttpStream, url: String): HttpStatusError = HttpStatusError(
        stream.statusCode,
        stream.reasonPhrase.ifEmpty { Tr.get("errorWithHttpStatusCode", stream.statusCode.toString()) },
    ).also { it.url = url }
}
