package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

/** Track-only: the site no longer exposes its downloads in a form that can be read. */
class RockMods : AppSource("RockMods") {
    init {
        fixedName = "RockMods"
        hosts = listOf("rockmods.net")
        enforceTrackOnly = true
        naiveStandardVersionDetection = true
        inferAppIdFromUrlPath = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/apps/[^/]+")

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val res = sourceRequest(standardUrl, additionalSettings)
        Http.ensureSuccess(res)
        val appJson = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .findAll(res.body)
            .mapNotNull { JsonValues.parse(it.groupValues[1]).asMap() }
            .firstOrNull { it["@type"] == "SoftwareApplication" }
        var appName = (appJson?.get("name") as? String)?.trim()
        val appVersion = (appJson?.get("softwareVersion") as? String)?.trim()
        val appAuthor = appJson?.get("author").asMap()?.get("name") as? String
        if (appName.isNullOrEmpty()) {
            appName = Jsoup.parse(res.body).selectFirst("h1")?.text()?.trim() ?: standardUrl.split('/').last()
        }
        if (appVersion.isNullOrEmpty()) throw NoVersionError()
        ApkDetails(appVersion, emptyList(), AppNames(appAuthor ?: shortName, appName))
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }
}
