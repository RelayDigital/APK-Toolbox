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
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

class Tencent : AppSource("Tencent") {
    override val name: String get() = Tr.get("tencentAppStore")
    override val shortName: String get() = "Tencent"

    init {
        hosts = listOf("sj.qq.com")
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        inferAppIdFromUrlPath = true
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
