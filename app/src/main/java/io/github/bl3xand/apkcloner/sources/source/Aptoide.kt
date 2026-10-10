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
        Http.ensureSuccess(res)
        val id = Regex("\"app\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*([0-9]+)").find(res.body)?.groupValues?.get(1)
            ?: throw NoReleasesError()
        val res2 = sourceRequest("$API_BASE_URL/$id", additionalSettings)
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
            throw notForDeviceHere(name)
        }
        ApkDetails(
            version,
            ApkFilter.apkUrlsFromUrls(listOf(apkUrl)),
            AppNames(
                (details.dig("developer", "name") as? String) ?: name,
                (details["name"] as? String) ?: Tr.get("app"),
            ),
            releaseDate = Dates.tryParse(details["updated"] as? String),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    companion object {
        private const val SEARCH_URL = "https://ws75.aptoide.com/api/7/apps/search"
        private const val API_BASE_URL = "https://ws2.aptoide.com/api/7/getApp/app_id"
    }
}
