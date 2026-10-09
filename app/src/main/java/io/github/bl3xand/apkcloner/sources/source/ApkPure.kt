package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.htmlToText
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.sourceRegex
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

class APKPure : AppSource("APKPure") {
    init {
        fixedName = "APKPure"
        hosts = listOf("apkpure.net", "apkpure.com")
        allowSubDomains = true
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        inferAppIdFromUrlPath = true
        changeLogIfAnyIsMarkDown = false
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            fallbackToOlderReleasesFormItem(),
            listOf(SwitchItem("stayOneVersionBehind", "stayOneVersionBehind", value = false)),
            listOf(SwitchItem("useFirstApkOfVersion", "useFirstApkOfVersion", value = true)),
        )

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        var result = url
        // The mobile site is the same app on the main host.
        val mobile = Regex("^https?://m.${sourceRegex(hosts)}(/+[^/]{2})?/+[^/]+/+[^/]+", RegexOption.IGNORE_CASE)
        if (mobile.containsMatchIn(result)) {
            val uri = Url.parse(result)
            result = "https://${uri.host.substring(2)}${uri.path}"
        }
        return Regex("^https?://(www\\.)?${sourceRegex(hosts)}(/+[^/]{2})?/+[^/]+/+[^/]+", RegexOption.IGNORE_CASE)
            .find(result)?.value ?: throw InvalidUrlError(name)
    }

    private fun detailsForVersion(
        variants: List<Map<String, Any?>>,
        supportedArchs: List<String>,
        additionalSettings: Map<String, Any?>,
    ): ApkDetails {
        var apkUrls = variants.mapNotNull { e ->
            val appId = e["package_name"]?.toString() ?: return@mapNotNull null
            val versionCode = e["version_code"]?.let(FDroid::numberText) ?: return@mapNotNull null
            var architectures = (e["native_code"] as? List<*>)?.map { it.toString() } ?: emptyList()
            val architectureString = architectures.joinToString(",")
            if ("universal" in architectures || "unlimited" in architectures) architectures = emptyList()
            if (additionalSettings["autoApkFilterByArch"] == true && architectures.isNotEmpty() &&
                architectures.none { it in supportedArchs }
            ) {
                return@mapNotNull null
            }
            val asset = e["asset"].asMap()
            val type = asset?.get("type")?.toString() ?: return@mapNotNull null
            val downloadUri = asset["url"]?.toString() ?: return@mapNotNull null
            val archSuffix = if (architectureString.isNotEmpty()) "-$architectureString" else ""
            NamedUrl("$appId-$versionCode$archSuffix.${type.lowercase()}", downloadUri)
        }.distinctBy { it.name }
        if (apkUrls.isEmpty()) throw NoApkError()

        val first = variants.first()
        val version = first["version_name"]?.toString()
        if (version.isNullOrEmpty()) throw NoVersionError()
        if (additionalSettings["useFirstApkOfVersion"] == true) apkUrls = listOf(apkUrls.first())
        return ApkDetails(
            version,
            apkUrls,
            AppNames(first["developer"]?.toString() ?: name, first["title"]?.toString() ?: Tr.get("app")),
            releaseDate = Dates.tryParse(first["update_date"]?.toString()),
            // The notes come as a piece of the store's page, <br> and all.
            changeLog = (first["whatsnew"] as? String)?.let(::htmlToText)?.takeIf { it.isNotEmpty() },
        )
    }

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        if (forAPKDownload) return null
        return mapOf(
            "Ual-Access-Businessid" to "projecta",
            "Ual-Access-ProjectA" to "{\"device_info\":{\"os_ver\":\"${SourceEnv.platform.sdkInt}\"}}",
        )
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
            val supportedArchs = SourceEnv.platform.supportedAbis
            val res = sourceRequest("$API_BASE_URL=$appId&hl=en", additionalSettings)
            Http.ensureSuccess(res)
            val apks = try {
                JsonValues.parse(res.body).dig("version_list").asList()!!.map { it.asMap()!! }
            } catch (e: Exception) {
                throw NoReleasesError()
            }
            // Variants of one version stay together, versions keep the order of the API.
            val versions = apks.groupBy { (it["version_name"] as? String) ?: "" }.values.toList()
            if (versions.isEmpty()) throw NoReleasesError()

            val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)
            val fallback = additionalSettings["fallbackToOlderReleases"] == true
            var tooYoung: List<Map<String, Any?>>? = null
            for ((i, variants) in versions.withIndex()) {
                try {
                    if (i == 0 && additionalSettings["stayOneVersionBehind"] == true) {
                        if (!fallback && versions.size < 2) throw NoReleasesError()
                        continue
                    }
                    if (isReleaseTooYoung(Dates.tryParse(variants.first()["update_date"]?.toString()), minAgeDays)) {
                        if (tooYoung == null) tooYoung = variants
                        continue
                    }
                    return detailsForVersion(variants, supportedArchs, additionalSettings)
                } catch (e: Exception) {
                    if (!fallback || i == versions.size - 1) throw e
                }
            }
            // Nothing is old enough: return the newest so that it can be held back.
            tooYoung?.let { return detailsForVersion(it, supportedArchs, additionalSettings) }
            throw NoApkError()
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    companion object {
        private const val API_BASE_URL = "https://tapi.pureapk.com/v3/get_app_his_version?package_name"
    }
}
