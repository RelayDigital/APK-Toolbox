package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.notForDeviceHere
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

class Aptoide : AppSource("Aptoide") {
    init {
        fixedName = "Aptoide"
        hosts = listOf("aptoide.com")
        allowSubDomains = true
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        canSearch = true
        // The store says which Android and which processors its file is for.
        answersForDevice = true
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest("$SEARCH_URL?query=${Url.encodeQueryComponent(query)}&limit=30", querySettings)
        Http.ensureSuccess(res)
        val results = LinkedHashMap<String, List<String>>()
        for (entry in JsonValues.parse(res.body).dig("datalist", "list").asList() ?: emptyList()) {
            val app = entry.asMap() ?: continue
            // The page of an app is a subdomain of its own.
            val page = app["uname"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            val title = app["name"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            results["https://$page.en.${hosts[0]}/app"] = listOf(title, app["package"]?.toString().orEmpty())
        }
        return results
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "([^\\.]+\\.)+", pathPattern = "")

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? =
        getAppDetailsJson(standardUrl, additionalSettings)["package"] as? String

    private fun getAppDetailsJson(standardUrl: String, additionalSettings: Map<String, Any?>): Map<String, Any?> {
        val res = sourceRequest(standardUrl, additionalSettings)
        val id = if (res.statusCode != 200) null else Regex("\"app\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*([0-9]+)").find(res.body)?.groupValues?.get(1)
        // Not every app has a page that can be read, or one that names it. The store can also be asked by the name of the
        // page, which may answer with an older build than the page has - so only then.
        val url = if (id != null) "$API_BASE_URL/$id" else "$API_ROOT/package_uname/${Url.parse(standardUrl).host.substringBefore('.')}"
        val res2 = sourceRequest(url, additionalSettings)
        Http.ensureSuccess(res2)
        return JsonValues.parse(res2.body).dig("nodes", "meta", "data").asMap() ?: throw NoReleasesError()
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val details = getAppDetailsJson(standardUrl, additionalSettings)
        val version = details.dig("file", "vername") as? String
        val apkUrl = details.dig("file", "path") as? String
        if (version.isNullOrEmpty()) throw NoVersionError()
        if (apkUrl == null) throw NoApkError()
        // Only the current release is kept here, so one that is not for this device is the end of it.
        val minSdk = (details.dig("file", "hardware", "sdk") as? Number)?.toInt()
        val processors = (details.dig("file", "hardware", "cpus").asList() ?: emptyList()).map { it.toString() }
        if ((minSdk != null && minSdk > SourceEnv.platform.sdkInt) ||
            (processors.isNotEmpty() && processors.none { it in SourceEnv.platform.supportedAbis })
        ) {
            throw notForDeviceHere(shortName)
        }
        ApkDetails(
            version,
            ApkFilter.apkUrlsFromUrls(listOf(apkUrl)),
            AppNames(
                (details.dig("developer", "name") as? String) ?: shortName,
                (details["name"] as? String) ?: Tr.get("app"),
            ),
            releaseDate = Dates.tryParse(details["updated"] as? String),
            changeLog = (details.dig("media", "news") as? String)?.trim()?.takeIf { it.isNotEmpty() },
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    companion object {
        private const val SEARCH_URL = "https://ws75.aptoide.com/api/7/apps/search"
        private const val API_ROOT = "https://ws2.aptoide.com/api/7/getApp"
        private const val API_BASE_URL = "$API_ROOT/app_id"
    }
}
