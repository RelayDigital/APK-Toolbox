package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.SettingKeys

/** An APK at a fixed URL; versions are pseudo-versions (a hash of the file, or its ETag). */
class DirectAPKLink : AppSource("DirectAPKLink") {
    private val html = HTML()

    override val name: String get() = Tr.get("directAPKLink")

    init {
        versionDetectionDisallowed = true
        excludeCommonSettingKeys = listOf(
            "versionExtractionRegEx", "matchGroupToUse", "versionDetection", "useVersionCodeAsOSVersion",
            "apkFilterRegEx", "autoApkFilterByArch",
        )
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(HTML.requestHeaderFormItem()),
            listOf(
                DropdownItem(
                    "defaultPseudoVersioningMethod", "defaultPseudoVersioningMethod",
                    listOf("partialAPKHash" to "partialAPKHash", "ETag" to "ETag"),
                    value = "partialAPKHash",
                ),
            ),
            listOf(
                TextItem(
                    "zippedApkFilterRegEx", "zippedApkFilterRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
        )

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        if (!forSelection) return url
        val uri = Url.tryParse(url)
        if (uri == null || !isApkOrContainerFile(uri.path, includeArchives = true, includeTarballs = true)) {
            throw InvalidUrlError(name)
        }
        return url
    }

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? = html.getRequestHeaders(additionalSettings, url, forAPKDownload)

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val settings = defaultValuesOf(html.combinedAppSpecificSettingFormItems)
        for ((key, value) in additionalSettings) {
            if (settings.containsKey(key)) settings[key] = value
        }
        settings["directAPKLink"] = true
        settings[SettingKeys.VERSION_DETECTION] = false
        html.getLatestAPKDetails(standardUrl, settings)
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }
}
