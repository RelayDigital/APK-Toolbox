package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * itch.io. Downloads go through several steps: a "name your price" box may hide the download
 * page, and the files themselves sit behind short-lived signed URLs.
 */
class ItchIO : AppSource("ItchIO") {
    private class Upload(val name: String, val id: String, val isAndroid: Boolean)

    init {
        hosts = listOf("itch.io")
        fixedName = "itch.io"
        allowSubDomains = true
        appIdInferIsOptional = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "[a-z0-9-]+\\.", pathPattern = "/[^/]+")

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        val extra = additionalSettings["extraHeaders"] as? Map<*, *> ?: return null
        return extra.entries.associate { it.key.toString() to it.value.toString() }.ifEmpty { null }
    }

    private fun findCsrf(body: String): String? =
        Regex("name=\"csrf_token\" value=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("csrf_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun extractUploads(body: String): List<Upload> = Jsoup.parse(body).select("div.upload").mapNotNull { div ->
        val id = div.selectFirst("a.download_btn")?.attr("data-upload_id")?.ifEmpty { null } ?: return@mapNotNull null
        Upload(
            div.selectFirst("div.upload_name strong.name")?.attr("title")?.ifEmpty { null } ?: "App title",
            id,
            div.selectFirst("span.download_platforms span.icon-android") != null,
        )
    }

    /** itch.io has no version field; the highest version-looking text of the page is used. */
    private fun parseVersion(document: Document): String? {
        val searchArea = document.selectFirst("div.page_widget")?.html() ?: return null
        val matches = LinkedHashSet<String>()
        for (pattern in listOf("[vV](\\d+\\.\\d+(?:\\.\\d+)*)", "Version (\\d+\\.\\d+(?:\\.\\d+)*)")) {
            Regex(pattern).findAll(searchArea).forEach { matches.add(it.groupValues[1]) }
        }
        if (matches.isEmpty()) return null
        fun compare(v1: String, v2: String): Int {
            val c1 = v1.split('.').map { it.toIntOrNull() ?: 0 }
            val c2 = v2.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(c1.size, c2.size)) {
                val p1 = c1.getOrElse(i) { 0 }
                val p2 = c2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
        return matches.reduce { a, b -> if (compare(a, b) > 0) a else b }
    }

    /** The newest "updated" date of the page as YYYYMMDD. */
    private fun dateVersion(document: Document): String? {
        val format = DateTimeFormatter.ofPattern("dd MMMM yyyy '@' HH:mm 'UTC'", Locale.ENGLISH)
        val latest = document.select("abbr").mapNotNull { abbr ->
            try {
                LocalDateTime.parse(abbr.attr("title"), format)
            } catch (e: Exception) {
                null
            }
        }.maxOrNull() ?: return null
        return "%d%02d%02d".format(latest.year, latest.monthValue, latest.dayOfMonth)
    }

    // The page title reads "<game> by <author>".
    private fun parseTitle(document: Document): String =
        document.selectFirst("title")?.text()?.split(" by ")?.first()?.trim() ?: ""

    private fun parseAuthor(document: Document, standardUrl: String): String {
        document.selectFirst("span.on_follow span.full_label")?.let { span ->
            Regex("Follow (.+)").find(span.text())?.groupValues?.get(1)?.trim()?.let { return it }
        }
        return Url.parse(standardUrl).host.split('.').first()
    }

    /** A fresh CSRF token and cookies for the follow-up requests. */
    private fun setupDownload(standardUrl: String, additionalSettings: Map<String, Any?>): Pair<String?, String?> {
        val res = sourceRequest(standardUrl.trimEnd('/'), additionalSettings)
        if (res.statusCode != 200) return null to null
        return findCsrf(res.body) to res.headers["set-cookie"]
    }

    private fun withHeaders(settings: Map<String, Any?>, vararg headers: Pair<String, String?>): Map<String, Any?> =
        LinkedHashMap(settings).also { map ->
            map["extraHeaders"] = headers.filter { it.second != null }.associate { it.first to it.second }
        }

    private fun downloadPageBody(
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        initialBody: String,
        initialCsrf: String?,
        initialCookies: String?,
    ): String {
        val baseUrl = standardUrl.trimEnd('/')
        // Easy case: the download buttons are on the first page.
        if (extractUploads(initialBody).isNotEmpty()) return initialBody
        var csrf = initialCsrf
        var cookies = initialCookies
        if (csrf == null || cookies == null) {
            val fresh = setupDownload(standardUrl, additionalSettings)
            csrf = fresh.first
            cookies = fresh.second
        }
        // Ask for a tokenised download page instead of paying.
        val bypass = sourceRequest(
            "$baseUrl/download_url",
            withHeaders(additionalSettings, "X-Requested-With" to "XMLHttpRequest", "Cookie" to cookies),
            postBody = mapOf("csrf_token" to csrf),
        )
        if (bypass.statusCode == 200) {
            val tokenizedUrl = JsonValues.parse(bypass.body).dig("url") as? String
            if (tokenizedUrl != null) {
                val page = sourceRequest(tokenizedUrl, withHeaders(additionalSettings, "Cookie" to cookies))
                if (page.statusCode == 200) return page.body
            }
        }
        return initialBody
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val baseUrl = standardUrl.trimEnd('/')
            val res = sourceRequest(standardUrl, additionalSettings)
            Http.ensureSuccess(res)
            val csrf = findCsrf(res.body)
            val cookies = res.headers["set-cookie"]
            val storePage = Jsoup.parse(res.body)
            val downloadBody = downloadPageBody(standardUrl, additionalSettings, res.body, csrf, cookies)
            val downloadPage = Jsoup.parse(downloadBody)
            // A real version if one is written anywhere, else the date of the last update.
            val version = parseVersion(storePage) ?: parseVersion(downloadPage)
                ?: dateVersion(storePage) ?: dateVersion(downloadPage) ?: "latest"

            val apkLinks = extractUploads(downloadBody).filter { it.isAndroid }.map { upload ->
                val realName = resolveRealFileName(upload.id, standardUrl, additionalSettings, csrf, cookies)
                NamedUrl(realName ?: upload.name, "$baseUrl/download/${upload.id}")
            }
            if (apkLinks.isEmpty()) throw NoApkError()
            return ApkDetails(version, apkLinks, AppNames(parseAuthor(storePage, standardUrl), parseTitle(storePage)))
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    /** The signed storage URL of an upload. */
    private fun retrieveFileUrl(
        uploadId: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        csrfIn: String?,
        cookiesIn: String?,
    ): String? {
        val baseUrl = standardUrl.trimEnd('/')
        var csrf = csrfIn
        var cookies = cookiesIn
        if (csrf == null || cookies == null) {
            val fresh = setupDownload(standardUrl, additionalSettings)
            csrf = fresh.first ?: return null
            cookies = fresh.second ?: return null
        }
        val res = sourceRequest(
            "$baseUrl/file/$uploadId?as_props=1&source=game_download",
            withHeaders(
                additionalSettings,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$baseUrl/download/$uploadId",
                "Cookie" to cookies,
            ),
            postBody = mapOf("csrf_token" to csrf),
        )
        if (res.statusCode != 200) return null
        return JsonValues.parse(res.body).dig("url") as? String
    }

    /** The file name the storage server announces for an upload. */
    private fun resolveRealFileName(
        uploadId: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        csrf: String?,
        cookies: String?,
    ): String? {
        val directUrl = retrieveFileUrl(uploadId, standardUrl, additionalSettings, csrf, cookies) ?: return null
        val disposition = Http.requestStream(
            "GET", directUrl, mapOf("Referer" to "${standardUrl.trimEnd('/')}?download"), requestOptions(additionalSettings),
        ).use { it.header("content-disposition") } ?: return null
        return Regex("filename=\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
    }

    /** The stored URL ends with the upload id; the signed URL is requested when downloading. */
    override fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String = retrieveFileUrl(assetUrl.split('/').last(), standardUrl, additionalSettings, null, null) ?: assetUrl
}
