package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.net.Http
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SamsungGalaxyStore : AppSource("SamsungGalaxyStore") {
    override val shortName: String get() = "Galaxy Store"

    init {
        fixedName = "Samsung Galaxy Store"
        hosts = listOf(
            "galaxystore.samsung.com", "apps.samsung.com", "apps.samsung.cn", "galaxyappstore.com",
            "apps.galaxyappstore.com",
        )
        inferAppIdFromUrlPath = false
        showReleaseDateAsVersionToggle = true
    }

    /** The file name of a download carries its upload time as 14 to 17 digits. */
    private fun releaseDateFromUrl(apkUrl: String): Instant? {
        val fileName = Url.parse(apkUrl).pathSegments.last { it.isNotEmpty() }
        val ts = Regex("(\\d{14,17})").find(fileName)?.groupValues?.get(1) ?: return null
        return try {
            LocalDateTime.of(
                ts.substring(0, 4).toInt(), ts.substring(4, 6).toInt(), ts.substring(6, 8).toInt(),
                ts.substring(8, 10).toInt(), ts.substring(10, 12).toInt(), ts.substring(12, 14).toInt(),
                if (ts.length >= 17) ts.substring(14, 17).toInt() * 1_000_000 else 0,
            ).atZone(ZoneId.systemDefault()).toInstant()
        } catch (e: Exception) {
            null
        }
    }

    private fun packageFromUrl(uri: Url): String? =
        uri.queryParameters[SettingKeys.APP_ID] ?: uri.pathSegments.lastOrNull { it.isNotEmpty() }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        val uri = Url.parse(url)
        if (uri.host !in hosts + hosts.map { "www.$it" }) throw InvalidUrlError(name).also { it.url = url }
        val appId = packageFromUrl(uri)?.takeIf { it.isNotEmpty() } ?: throw InvalidUrlError(name).also { it.url = url }
        return "https://apps.galaxyappstore.com/detail/$appId"
    }

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? =
        packageFromUrl(Url.parse(standardUrl))

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(TextItem("deviceId", "deviceModel", required = false, hint = "SM-S948B")),
            listOf(TextItem("csc", "cscCode", required = false, hint = "DBT")),
        )

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        val packageName = packageFromUrl(Url.parse(standardUrl))!!
        val deviceId = additionalSettings["deviceId"]?.toString()?.takeIf { it.isNotEmpty() } ?: "SM-S948B"
        val csc = additionalSettings["csc"]?.toString()?.takeIf { it.isNotEmpty() } ?: "DBT"
        val vasUrl = Url.parse("https://vas.samsungapps.com/stub/stubDownload.as").withQueryParameters(
            linkedMapOf(
                "appId" to packageName, "deviceId" to deviceId, "mcc" to "425", "mnc" to "01", "csc" to csc,
                "sdkVer" to SourceEnv.platform.sdkInt.toString(), "systemId" to "1608665720954",
                "abiType" to "64", "extuk" to "0191d6627f38685f",
            ),
        ).toString()
        val response = sourceRequest(vasUrl, additionalSettings)
        Http.ensureSuccess(response)
        val body = response.body
        if (Regex("<resultCode>(\\d+)</resultCode>").find(body)?.groupValues?.get(1) != "1") {
            throw SourceError(
                Regex("<resultMsg>([^<]*)</resultMsg>").find(body)?.groupValues?.get(1)
                    ?: Tr.get("samsungGalaxyStoreApiError"),
            )
        }
        val apkUrl = Regex("<downloadURI><!\\[CDATA\\[([^\\]]+)\\]\\]></downloadURI>").find(body)?.groupValues?.get(1)
            ?: throw NoApkError().also { it.url = standardUrl }
        val version = Regex("<versionName>([^<]+)</versionName>").find(body)?.groupValues?.get(1)
            ?: throw NoVersionError()
        val appName = Regex("<productName>(?:<!\\[CDATA\\[)?([^<\\]]+)(?:\\]\\]>)?</productName>")
            .find(body)?.groupValues?.get(1)?.trim() ?: packageName
        return ApkDetails(
            version,
            listOf(NamedUrl("$packageName.apk", apkUrl)),
            AppNames(name, appName),
            releaseDate = releaseDateFromUrl(apkUrl),
        )
    }
}
