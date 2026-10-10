package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.notForProcessor
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

class Farsroid : AppSource("Farsroid") {
    init {
        hosts = listOf("farsroid.com")
        fixedName = "Farsroid"
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(SwitchItem("useFirstApkOfVersion", "useFirstApkOfVersion", value = true)),
            listOf(SwitchItem("releaseTitleAsVersion", "releaseTitleAsVersion", value = false)),
        )

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "([^\\.]+\\.)", pathPattern = "/[^/]+")

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appName = Url.parse(standardUrl).pathSegments.last()
            val res = sourceRequest(standardUrl, additionalSettings)
            Http.ensureSuccess(res)
            val box = Jsoup.parse(res.body).selectFirst(".download-links") ?: throw NoReleasesError()
            val postId = box.attr("data-post-id")
            var version = box.attr("data-post-version")
            if (postId.isEmpty() || version.isEmpty()) throw NoVersionError()

            val res2 = sourceRequest(
                "https://${hosts[0]}/api/download-box/?post_id=$postId&post_version=$version", additionalSettings,
            )
            Http.ensureSuccess(res2)
            val content = try {
                JsonValues.parse(res2.body).dig("data", "content") as? String
            } catch (e: Exception) {
                null
            }
            if (content.isNullOrEmpty()) throw NoApkError()
            var apkLinks = grabLinksCommon(
                content, res2.requestUrl, LinkedHashMap(additionalSettings).also { it["skipSort"] = true },
            ).map { NamedUrl(Url.parse(it.url).pathSegments.last(), it.url) }
            apkLinks = ApkFilter.filterApks(
                apkLinks,
                additionalSettings["apkFilterRegEx"] as? String,
                additionalSettings["invertAPKFilter"] as? Boolean,
            )
            if (apkLinks.isEmpty()) throw NoApkError()
            if (additionalSettings["autoApkFilterByArch"] == true) {
                apkLinks = ApkFilter.filterApksByArch(apkLinks, SourceEnv.platform.supportedAbis)
                if (apkLinks.isEmpty()) throw notForProcessor()
            }
            if (additionalSettings["useFirstApkOfVersion"] == true) apkLinks = listOf(apkLinks.first())
            if (additionalSettings["releaseTitleAsVersion"] == true) {
                if (apkLinks.size != 1) throw NoVersionError()
                version = apkLinks.single().name
            }
            return ApkDetails(version, apkLinks, AppNames(shortName, appName))
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }
}
