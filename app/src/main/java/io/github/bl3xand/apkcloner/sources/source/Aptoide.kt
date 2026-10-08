package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
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
        private const val API_BASE_URL = "https://ws2.aptoide.com/api/7/getApp/app_id"
    }
}
