package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

class Tencent : AppSource("Tencent") {
    override val name: String get() = Tr.get("tencentAppStore")
    override val shortName: String get() = "Tencent"

    init {
        hosts = listOf("sj.qq.com")
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        inferAppIdFromUrlPath = true
        canSearch = true
    }

    /** The search page carries what it shows as data; the results are read from there. */
    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest("https://${hosts[0]}/search?q=${Url.encodeQueryComponent(query)}", querySettings)
        Http.ensureSuccess(res)
        val data = Jsoup.parse(res.body).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyMap()
        val results = LinkedHashMap<String, List<String>>()
        val cards = JsonValues.parse(data).dig("props", "pageProps", "dynamicCardResponse", "data", "components").asList() ?: emptyList()
        for (card in cards) {
            for (entry in card.dig("data", "itemData").asList() ?: emptyList()) {
                val app = entry.asMap() ?: continue
                val packageName = app["pkg_name"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                val title = app["name"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                results["https://${hosts[0]}/appdetail/$packageName"] = listOf(title, app["developer"]?.toString().orEmpty().ifEmpty { packageName })
            }
        }
        return results
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "", pathPattern = "/appdetail/[^/]+")

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
            val baseHost = Url.parse(standardUrl).host.split('.').takeLast(2).joinToString(".")
            val res = sourceRequest(
                "https://a.app.$baseHost/o/simple.jsp?pkgname=$appId", additionalSettings, followRedirects = false,
            )
            if (res.statusCode != 200) throw Http.errorFor(res)
            val jsVar = "window.systemData="
            val json = try {
                JsonValues.parse(
                    res.body.split('\n').map { it.trim() }.first { it.startsWith(jsVar) }.substring(jsVar.length),
                ).dig("appDetail").asMap()
            } catch (e: Exception) {
                null
            } ?: throw NoReleasesError()
            val version = json["versionName"]?.toString()
            val apkUrl = (json["apkUrl64"] ?: json["apkUrl"])?.toString() ?: throw NoApkError()
            if (version.isNullOrEmpty()) throw NoVersionError()
            val apkName = Url.parse(apkUrl).queryParameters["fsname"] ?: "${appId}_$version.apk"
            return ApkDetails(
                version,
                listOf(NamedUrl(apkName, apkUrl)),
                AppNames(json["author"]?.toString() ?: name, json["appName"]?.toString() ?: Tr.get("app")),
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }
}
