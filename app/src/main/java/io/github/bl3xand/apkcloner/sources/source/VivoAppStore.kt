package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
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
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

class VivoAppStore : AppSource("VivoAppStore") {
    override val name: String get() = Tr.get("vivoAppStore")

    init {
        hosts = listOf("h5.appstore.vivo.com.cn", "h5coml.vivo.com.cn", "detail-browser.vivo.com.cn")
        naiveStandardVersionDetection = true
        canSearch = true
        allowOverride = false
        // The download and detail endpoints redirect to plain-HTTP CDN URLs.
        allowInsecureRedirects = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        "$APP_DETAIL_URL${parseVivoAppId(url)}"

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? =
        getDetailJson(standardUrl, additionalSettings)["package_name"] as? String

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val json = getDetailJson(standardUrl, additionalSettings)
        val versionName = json["version_name"]?.toString() ?: throw NoVersionError()
        val id = json["id"]?.let(FDroid::numberText) ?: throw NoApkError()
        val packageName = json["package_name"]?.toString() ?: ""
        val versionCode = json["version_code"]?.let(FDroid::numberText) ?: ""
        ApkDetails(
            versionName,
            listOf(NamedUrl("${packageName}_$versionCode.apk", "$APK_DOWNLOAD_URL${Url.encodeQueryComponent(id)}")),
            AppNames(json["developer"]?.toString() ?: name, json["title_zh"]?.toString() ?: Tr.get("app")),
            releaseDate = Dates.tryParse(json["upload_time"]?.toString()),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest("$APP_SEARCH_URL${Url.encodeQueryComponent(query)}", emptyMap())
        Http.ensureSuccess(res)
        val json = JsonValues.parse(res.body)
        if ((json.dig("code") as? Number)?.toInt() != 0 || json.dig("data", "appSearchResponse", "result") != true) {
            throw NoReleasesError()
        }
        val results = LinkedHashMap<String, List<String>>()
        for (item in json.dig("data", "appSearchResponse", "value").asList() ?: emptyList()) {
            results["$APP_DETAIL_URL${FDroid.numberText(item.dig("id"))}"] =
                listOf(item.dig("title_zh").toString(), item.dig("developer").toString())
        }
        return results
    }

    private fun getDetailJson(standardUrl: String, additionalSettings: Map<String, Any?>): Map<String, Any?> {
        val res = sourceRequest(
            "$APP_DETAIL_JSON_URL${Url.encodeComponent(parseVivoAppId(standardUrl))}", additionalSettings,
        )
        Http.ensureSuccess(res)
        val json = JsonValues.parse(res.body).asMap()
        if (json?.get("id") == null) throw NoReleasesError()
        return json
    }

    private fun parseVivoAppId(url: String): String =
        Url.parse(url.replaceFirst("/#", "")).queryParameters[SettingKeys.APP_ID]?.takeIf { it.isNotEmpty() }
            ?: throw InvalidUrlError(name)

    companion object {
        private const val APP_DETAIL_URL = "https://detail-browser.vivo.com.cn/v115/index.html?appId="
        private const val APP_DETAIL_JSON_URL = "https://h5-api.appstore.vivo.com.cn/detailInfo?appId="
        private const val APK_DOWNLOAD_URL = "https://appstore.vivo.com.cn/appinfo/downloadApkFile?id="
        private const val APP_SEARCH_URL = "https://h5-api.appstore.vivo.com.cn/h5appstore/search/result-list?" +
            "app_version=2100&page_index=1&apps_per_page=20&target=local&cfrom=2&key="
    }
}
