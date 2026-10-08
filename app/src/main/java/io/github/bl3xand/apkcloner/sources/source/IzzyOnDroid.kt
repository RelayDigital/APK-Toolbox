package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails

class IzzyOnDroid : AppSource("IzzyOnDroid") {
    private val fd = FDroid()

    init {
        fixedName = "IzzyOnDroid"
        hosts = listOf("izzysoft.de")
        allowSubDomains = true
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = fd.additionalSourceAppSpecificSettingFormItems

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        if (Url.parse(url).host.startsWith("android.")) {
            standardizeUrlWithRegex(url, subdomainPrefix = "android\\.", pathPattern = "/repo/apk/[^/]+")
        } else {
            standardizeUrlWithRegex(url, subdomainPrefix = "apt\\.", pathPattern = "/fdroid/index/apk/[^/]+")
        }

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? =
        fd.tryInferringAppId(standardUrl)

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
        fd.getAPKUrlsFromFDroidPackagesAPIResponse(
            sourceRequest("https://apt.izzysoft.de/fdroid/api/v1/packages/$appId", additionalSettings),
            "https://android.izzysoft.de/frepo/$appId",
            standardUrl,
            name,
            additionalSettings,
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }
}
