package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

class APKCombo : AppSource("APKCombo") {
    init {
        fixedName = "APKCombo"
        hosts = listOf("apkcombo.com")
        showReleaseDateAsVersionToggle = true
        inferAppIdFromUrlPath = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/+[^/]+/+[^/]+")

    // A curl-style User-Agent passes the site's protection. No Host header: the file itself is
    // served from another host through a signed URL, which a foreign Host would break.
    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> = mapOf("User-Agent" to "curl/8.0.1", "Accept" to "*/*", "Connection" to "keep-alive")

    fun getApkUrls(standardUrl: String, additionalSettings: Map<String, Any?>): List<NamedUrl> {
        val res = sourceRequest("$standardUrl/download/apk", additionalSettings)
        Http.ensureSuccess(res)
        return Jsoup.parse(res.body).select("#variants-tab > div > ul > li").mapNotNull { li ->
            val arch = li.selectFirst("code")?.text()?.trim()?.replace(",", "")?.replace(":", "-")?.replace(" ", "-")
            // Each variant lists its current build first and older builds after it.
            for (a in li.select("a")) {
                var url = a.attr("href").ifEmpty { null } ?: continue
                // "/r2?u=<encoded signed URL>" redirectors are unwrapped to the file URL.
                val parsed = Url.parse(url)
                if (parsed.path == "/r2") parsed.queryParameters["u"]?.let { url = it }
                if (!isApkOrContainerFile(Url.parse(url).path)) continue
                val verCode = a.selectFirst(".info .header .vercode")?.text()?.trim() ?: ""
                val fallbackName = Url.tryParse(url)?.pathSegments?.lastOrNull() ?: "app-$verCode.apk"
                return@mapNotNull NamedUrl(if (arch != null) "$arch-$verCode.apk" else fallbackName, url)
            }
            null
        }
    }

    /** Signed URLs expire, so fresh ones are read at download time and matched by path. */
    override fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String {
        val wanted = Url.parse(assetUrl).path
        return getApkUrls(standardUrl, additionalSettings).firstOrNull { Url.parse(it.url).path == wanted }?.url
            ?: throw NoApkError()
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
        val page = sourceRequest(standardUrl, additionalSettings)
        Http.ensureSuccess(page)
        val html = Jsoup.parse(page.body)
        val version = html.selectFirst("div.version")?.text()?.trim()
        if (version.isNullOrEmpty()) throw NoVersionError()
        val appName = html.selectFirst("div.app_name")?.text()?.trim() ?: appId
        val info = html.select("div.information-table > .item > div.value").map { it.text().trim() }
        ApkDetails(
            version,
            getApkUrls(standardUrl, additionalSettings),
            AppNames(html.selectFirst("div.author")?.text()?.trim() ?: appName, appName),
            releaseDate = info.getOrNull(1)?.let { Dates.tryParseLocalDate(it, "MMM d, yyyy") },
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }
}
