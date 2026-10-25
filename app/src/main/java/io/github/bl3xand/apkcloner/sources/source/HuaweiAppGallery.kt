package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.net.Http
import kotlin.random.Random

class HuaweiAppGallery : AppSource("HuaweiAppGallery") {
    /** The result of the store handshake, kept for a day as `host|sign|deviceId|createdAtMs`. */
    private class Session(val host: String, val sign: String, val deviceId: String, val createdAt: Long) {
        val isUsable: Boolean get() = System.currentTimeMillis() - createdAt <= MAX_AGE_MS

        fun toBlob(): String = "$host|$sign|$deviceId|$createdAt"

        companion object {
            private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

            fun tryParse(blob: String?): Session? {
                val parts = blob?.split('|') ?: return null
                val createdAt = if (parts.size == 4) parts[3].toLongOrNull() else null
                if (parts.size != 4 || parts.take(3).any { it.isEmpty() } || createdAt == null) return null
                return Session(parts[0], parts[1], parts[2], createdAt)
            }
        }
    }

    override val name: String get() = Tr.get("huaweiAppGallery")
    override val shortName: String get() = "AppGallery"

    init {
        hosts = listOf("appgallery.huawei.com", "appgallery.cloud.huawei.com", "appgallery.huawei.ru")
        trustedApkHosts = listOf("dbankcloud.com", "dbankcloud.ru")
        canSearch = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "(/#)?/(app|appdl)/[^/]+")

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        if (Url.tryParse(url)?.path != API_PATH) return null
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
        )
    }

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? = try {
        fetchAppDetail(standardUrl.split('/').last(), buildMergedSettings(additionalSettings))?.get("package")?.toString()
    } catch (e: Exception) {
        null
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val cId = standardUrl.split('/').last()
        // The store API is the only source of the real version name.
        val info = fetchAppDetail(cId, buildMergedSettings(additionalSettings))
            ?: throw SourceError(Tr.get("huaweiAppGalleryApiError"))
        // The store also lists what is not an Android app: quick apps and wrappers that only run
        // inside another app. Their file is not an APK.
        if ((info["ctype"] as? Number)?.toInt()?.let { it != 0 } == true) throw SourceError(Tr.get("notAndroidApp"))
        val packageName = info["package"]?.toString()
        val developer = info["developer"]?.toString()
        ApkDetails(
            info["versionName"].toString(),
            listOf(NamedUrl("${packageName ?: cId}.apk", info["url"].toString())),
            AppNames(
                if (!developer.isNullOrEmpty()) developer else shortName,
                info["name"]?.toString() ?: packageName ?: Tr.get("app"),
            ),
            releaseDate = Dates.tryParse(info["releaseDate"]?.toString() ?: ""),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val merged = buildMergedSettings(querySettings)
        var lastError: String? = null
        for (attempt in 0 until 2) {
            val session = ensureSession(merged, forceRefresh = attempt > 0)
            val resp = clientApiPost(
                session.host,
                commonParams(session.deviceId) + mapOf(
                    "method" to "client.getTabDetail", "sign" to session.sign, "uri" to "searchApp|$query",
                    "maxResults" to "25", "reqPageNum" to "1", "isSupportPage" to "1",
                ),
                merged,
            )
            if (FDroid.numberText(resp["rtnCode"]) == "0") {
                val results = LinkedHashMap<String, List<String>>()
                for (layout in resp["layoutData"].asList() ?: emptyList()) {
                    for (item in layout.asMap()?.get("dataList").asList() ?: emptyList()) {
                        val entry = item.asMap() ?: continue
                        val app = entry["appInfo"].asMap() ?: entry
                        val appId = (app["appid"] ?: app[SettingKeys.APP_ID])?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                        val appName = app["name"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                        val packageName = app["package"]?.toString()
                        results["https://${hosts[0]}/app/$appId"] =
                            listOfNotNull(appName, packageName?.takeIf { it.isNotEmpty() })
                    }
                }
                return results
            }
            // A non-zero code may mean an expired sign: refresh once and try again.
            lastError = "rtnCode=${resp["rtnCode"]} rtnDesc=${resp["rtnDesc"]}"
        }
        throw SourceError(Tr.get("searchFailed", name, "$lastError"))
    }

    private fun hostForZone(zone: String?): String = when {
        zone == "CN" -> HOST_CN
        zone == "RU" -> HOST_RU
        zone in dr2Zones -> HOST_ASIA
        else -> HOST_EU
    }

    /** POSTs a form body with its keys sorted, as the store client does. */
    private fun clientApiPost(host: String, params: Map<String, String>, merged: Map<String, Any?>): Map<String, Any?> {
        val body = params.keys.sorted().joinToString("&") { key ->
            "${Url.encodeQueryComponent(key)}=${Url.encodeQueryComponent(params.getValue(key)).replace("%20", "+")}"
        }
        val res = sourceRequest("https://$host$API_PATH", merged, postBody = body)
        Http.ensureSuccess(res)
        return JsonValues.parse(String(res.bodyBytes, Charsets.UTF_8)).asMap()
            ?: throw SourceError(Tr.get("unexpectedStoreApiResponse"), unexpected = true)
    }

    private fun commonParams(deviceId: String): Map<String, String> = linkedMapOf(
        "ver" to "1.1", "locale" to "en_US", "serviceType" to "0", "ts" to "${System.currentTimeMillis()}",
        "net" to "1", "brand" to "google", "manufacturer" to "Google", "subBrand" to "0", "deviceId" to deviceId,
        "deviceIdType" to "9",
    )

    /** The "home" call of the store client; slow and large, hence the cached session. */
    private fun front2(host: String, needServiceZone: Int, deviceId: String, merged: Map<String, Any?>) =
        clientApiPost(
            host,
            commonParams(deviceId) + mapOf(
                "method" to "client.front2", "version" to CLIENT_VERSION, "versionCode" to CLIENT_VERSION_CODE,
                "packageName" to "com.huawei.appmarket", "zone" to "1", "phoneType" to "Pixel 8 Pro",
                "firmwareVersion" to "16", "isFirstLaunch" to "1", "oobe" to "0",
                "needServiceZone" to "$needServiceZone",
            ),
            merged,
        )

    private fun ensureSession(merged: Map<String, Any?>, forceRefresh: Boolean = false): Session {
        if (!forceRefresh) {
            val existing = session ?: Session.tryParse(SourceEnv.settings.getString(SESSION_PREFS_KEY))
            if (existing != null && existing.isUsable) {
                session = existing
                return existing
            }
        }
        val deviceId = (1..64).joinToString("") { Random.nextInt(16).toString(16) }
        val probe = front2(HOST_EU, 1, deviceId, merged)
        val host = hostForZone(probe["serviceZone"]?.toString())
        var sign = probe["sign"]?.toString()
        if (forceRefresh || sign.isNullOrEmpty()) sign = front2(host, 0, deviceId, merged)["sign"]?.toString()
        if (sign.isNullOrEmpty()) throw SourceError(Tr.get("storeHandshakeNoSign", host))
        return Session(host, sign, deviceId, System.currentTimeMillis()).also {
            session = it
            SourceEnv.settings.setString(SESSION_PREFS_KEY, it.toBlob())
        }
    }

    /** App details from the store API, or null on any failure. */
    private fun fetchAppDetail(cId: String, merged: Map<String, Any?>): Map<String, Any?>? {
        try {
            for (attempt in 0 until 2) {
                val session = ensureSession(merged, forceRefresh = attempt > 0)
                val resp = clientApiPost(
                    session.host,
                    commonParams(session.deviceId) + mapOf(
                        "method" to "client.appDetailById", "sign" to session.sign, "id" to cId,
                    ),
                    merged,
                )
                if (FDroid.numberText(resp["rtnCode"]) == "0") {
                    val info = resp["detailInfo"].asList()?.firstOrNull().asMap() ?: return null
                    val usable = !info["versionName"]?.toString().isNullOrEmpty() && !info["url"]?.toString().isNullOrEmpty()
                    return if (usable) info else null
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        private const val SESSION_PREFS_KEY = "huaweiAppGallery-session"
        private const val API_PATH = "/hwmarket/api/clientApi"
        private const val USER_AGENT = "HiSpace##16.5.1.301##google##Pixel 8 Pro"
        private const val CLIENT_VERSION = "16.5.1"
        private const val CLIENT_VERSION_CODE = "160501301"
        private const val HOST_CN = "store-drcn.hispace.dbankcloud.com"
        private const val HOST_ASIA = "store-dra.hispace.dbankcloud.com"
        private const val HOST_EU = "store-dre.hispace.dbankcloud.com"
        private const val HOST_RU = "store-drru.hispace.dbankcloud.ru"

        @Volatile
        private var session: Session? = null

        // Countries served by the Asia/Africa/Latin America host. CN and RU have their own;
        // everything else goes to the European one.
        private val dr2Zones = (
            "AE AF AG AI AM AO AQ AR AS AW AZ BB BD BF BH BI BJ BL BM BN BO BR BS BT BV BW BY BZ CC CD CF CG CI " +
                "CK CL CM CO CR CU CV CX DJ DM DO DZ EC EG EH ER ET FJ FK FM GA GD GE GF GH GM GN GP GQ GS GT GU " +
                "GW GY HK HM HN HT ID IN IO IQ JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LK LR LS LY MA " +
                "MG MH ML MM MN MO MP MQ MR MS MU MV MW MX MY MZ NA NC NE NF NG NI NP NR NU OM PA PE PF PG PH PK " +
                "PN PR PS PW PY QA RE RW SA SB SC SD SG SH SL SN SO SR SS ST SV SY SZ TC TD TF TG TH TJ TK TL TM " +
                "TN TO TT TV TW TZ UG UY UZ VE VG VI VN VU WF WS YE YT ZA ZM ZW"
            ).split(' ').toSet()
    }
}
