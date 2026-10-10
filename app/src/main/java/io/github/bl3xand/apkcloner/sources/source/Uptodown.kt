package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.notForDevice
import io.github.bl3xand.apkcloner.sources.core.androidSdkOf
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.jsoup.Jsoup

class Uptodown : AppSource("Uptodown") {
    private class Session(val token: String, val expiresAt: Long)

    @Volatile
    private var session: Session? = null

    init {
        fixedName = "Uptodown"
        hosts = listOf("uptodown.com")
        allowSubDomains = true
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        urlsAlwaysHaveExtension = true
        canSearch = true
        answersForDevice = true
    }

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        val uri = Url.parse(url)
        if (uri.host == API_HOST) {
            val headers = linkedMapOf(
                "User-Agent" to USER_AGENT,
                "Identificador" to "Uptodown_Android",
                "Identificador-Version" to CLIENT_VERSION,
            )
            val token = session?.token
            if (uri.path == AUTH_PATH) {
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            } else if (token != null) {
                headers["Authorization"] = "Bearer $token"
            }
            return headers
        }
        if (url == SEARCH_URL) {
            return mapOf("User-Agent" to USER_AGENT, "Content-Type" to "application/x-www-form-urlencoded")
        }
        return if (forAPKDownload) mapOf("User-Agent" to USER_AGENT) else null
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        // Every language sub-site shows the same app; the English one is parsed.
        val english = url.replaceFirst(Regex("\\.([a-z]{2,3})\\.uptodown\\.", RegexOption.IGNORE_CASE), ".en.uptodown.")
        return standardizeUrlWithRegex(english, subdomainPrefix = "([^\\\\.]+\\.)+", pathPattern = "") +
            "/android/download"
    }

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? =
        getAppDetailsFromPage(standardUrl, additionalSettings)[SettingKeys.APP_ID]

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        try {
            val res = sourceRequest(
                SEARCH_URL, querySettings, postBody = "queryString=${Url.encodeQueryComponent(query)}",
            )
            if (res.statusCode != 200) throw Http.errorFor(res)
            val body = JsonValues.parse(res.body).asMap()
            if (body == null || (body["success"] as? Number)?.toInt() != 1) {
                throw SourceError(Tr.get("uptodownSearchError"))
            }
            val results = LinkedHashMap<String, List<String>>()
            for (entry in body.dig("data", "apps").asList() ?: emptyList()) {
                val app = entry.asMap() ?: continue
                if (app["platformURL"] != "/android") continue
                val url = app["url"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                val name = app["name"]?.toString()?.replace(Regex("<[^>]+>"), "")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val author = app["author"]?.toString()?.trim()
                results[url] = listOf(name, if (!author.isNullOrEmpty()) author else Tr.get("noDescription"))
            }
            return results
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val details = getAppDetailsFromPage(standardUrl, additionalSettings)
        val version = details["version"]
        if (version.isNullOrEmpty()) throw NoVersionError()
        // "Android 8.0 or higher required", in the words of the app's own page; the download
        // page, which the rest is read from, leaves that out. A page that cannot be read
        // changes nothing.
        val needed = runCatching {
            REQUIREMENT.find(sourceRequest(standardUrl.removeSuffix("/download"), additionalSettings).body)?.groupValues?.get(1)
        }.getOrNull()?.let(::androidSdkOf)
        if (needed != null && needed > SourceEnv.platform.sdkInt) throw notForDevice(shortName)
        val fileId = details["fileId"] ?: throw NoApkError()
        val appId = details[SettingKeys.APP_ID] ?: throw NoReleasesError()
        val extension = details["extension"]?.takeIf { it.isNotEmpty() } ?: "apk"
        ApkDetails(
            version,
            listOf(NamedUrl("$appId.$extension", "$standardUrl/$fileId-x")),
            AppNames(details["author"] ?: shortName, details["name"] ?: Tr.get("app")),
            releaseDate = parseDate(details["dateStr"]),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    /** The stored URL is a page; the real file URL comes from the app API at download time. */
    override fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String {
        val res = sourceRequest(assetUrl, additionalSettings)
        Http.ensureSuccess(res)
        val html = Jsoup.parse(res.body)
        val button = html.selectFirst("#detail-download-button")
        val heading = html.selectFirst("#detail-app-name")
        val appId = button?.attr("data-app-id")?.ifEmpty { null } ?: heading?.attr("data-code")?.ifEmpty { null }
        val fileId = button?.attr("data-file-id")?.ifEmpty { null } ?: heading?.attr("data-file-id")?.ifEmpty { null }
            ?: html.selectFirst("[data-file-id]")?.attr("data-file-id")?.ifEmpty { null }
        if (appId == null || fileId == null) throw NoApkError()
        return resolveDownload(appId, fileId, additionalSettings)
    }

    private fun getAppDetailsFromPage(standardUrl: String, additionalSettings: Map<String, Any?>): Map<String, String?> {
        val res = sourceRequest(standardUrl, additionalSettings)
        Http.ensureSuccess(res)
        val html = Jsoup.parse(res.body)
        val appNameElement = html.selectFirst("#detail-app-name")
        // Values are looked up by their row label; positions change whenever a row is added.
        val info = LinkedHashMap<String, String>()
        for (row in html.select("#technical-information tr")) {
            val label = row.selectFirst("th")?.text()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
            val value = row.select("td").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            info[label] = value
        }
        // Positional fallback for older layouts.
        val cells = html.select("#technical-information td").map { it.text().trim() }.filter { it.isNotEmpty() }
        return mapOf(
            "version" to html.selectFirst("div.version")?.text()?.trim(),
            "appId" to (info["package name"] ?: cells.lastOrNull()),
            "name" to appNameElement?.text()?.trim(),
            "author" to html.selectFirst("#author-link")?.text()?.trim(),
            "dateStr" to (info["date"] ?: cells.getOrNull(cells.size - 5)),
            "fileId" to (
                html.selectFirst("#detail-download-button")?.attr("data-file-id")?.ifEmpty { null }
                    ?: appNameElement?.attr("data-file-id")?.ifEmpty { null }
                    // Some pages carry it on another element than the button.
                    ?: html.selectFirst("[data-file-id]")?.attr("data-file-id")?.ifEmpty { null }
                ),
            "extension" to (info["file type"] ?: cells.getOrNull(cells.size - 4))?.lowercase(),
        )
    }

    private fun getSession(settings: Map<String, Any?>, forceRefresh: Boolean): Session {
        val now = System.currentTimeMillis() / 1000
        val current = session
        if (!forceRefresh && current != null && now < current.expiresAt - 60) return current
        return authenticate(settings)
    }

    private fun authenticate(settings: Map<String, Any?>): Session {
        val identifier = Random.nextBytes(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val signature = mac.doFinal(timestamp.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val form = linkedMapOf(
            "identifier" to identifier, "id_plataforma" to "13", "lang" to "en", "unixtime" to timestamp,
            "hmac" to signature,
        ).entries.joinToString("&") { "${Url.encodeQueryComponent(it.key)}=${Url.encodeQueryComponent(it.value)}" }
        val res = sourceRequest("https://$API_HOST$AUTH_PATH?identifier=$identifier", settings, postBody = form)
        if (res.statusCode != 200) throw Http.errorFor(res)
        val token = JsonValues.parse(res.body).dig("token") as? String
        if (token == null || token.split('.').size != 3) throw SourceError(Tr.get("uptodownInvalidAuthResponse"))
        val claims = JsonValues.parse(String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8))
        val expiresAt = (claims.dig("exp") as? Number)?.toLong()
            ?: throw SourceError(Tr.get("uptodownInvalidAuthResponse"))
        return Session(token, expiresAt).also { session = it }
    }

    private fun resolveDownload(appId: String, fileId: String, settings: Map<String, Any?>): String {
        for (attempt in 0 until 2) {
            getSession(settings, forceRefresh = attempt > 0)
            val res = sourceRequest("https://$API_HOST/eapi/apps/$appId/file/$fileId/downloadUrl", settings)
            if (res.statusCode == 401) continue
            if (res.statusCode != 200) throw Http.errorFor(res)
            val body = JsonValues.parse(res.body).asMap()
            if (body == null || (body["success"] as? Number)?.toInt() != 1) {
                throw SourceError(Tr.get("uptodownDownloadError"))
            }
            return body.dig("data", "downloadURL") as? String ?: throw NoApkError()
        }
        throw SourceError(Tr.get("uptodownDownloadError"))
    }

    private fun parseDate(text: String?) =
        Dates.tryParseLocalDate(text, "MMM d, yyyy") ?: Dates.tryParseLocalDate(text, "MMMM d, yyyy")

    companion object {
        private val REQUIREMENT = Regex("Android ([0-9][0-9.]*L?) or higher", RegexOption.IGNORE_CASE)
        private const val API_HOST = "www.uptodown.app"
        private const val AUTH_PATH = "/eapi/auth/token"
        private const val SEARCH_URL = "https://en.uptodown.com/android/en/s"
        private const val CLIENT_VERSION = "739"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 8 Pro Build/BP4A.260205.001)"

        // The anonymous client key. It looks like Base64 but is used as plain text.
        private const val HMAC_KEY = "MDGMXUMdvHJBG/vjdFgmqX6LUdy7ecfwvYNd0gyfOCs="
    }
}
