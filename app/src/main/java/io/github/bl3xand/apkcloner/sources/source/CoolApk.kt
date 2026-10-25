package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
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
import io.github.bl3xand.apkcloner.sources.net.Http
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.random.Random
import org.mindrot.jbcrypt.BCrypt

/**
 * CoolApk. The request headers imitate one specific release of the store client, token
 * included; if the server starts demanding a newer client they have to be refreshed.
 */
class CoolApk : AppSource("CoolApk") {
    override val name: String get() = Tr.get("coolApk")
    override val shortName: String get() = "CoolApk"

    init {
        hosts = listOf("coolapk.com")
        allowSubDomains = true
        naiveStandardVersionDetection = true
        allowOverride = false
        inferAppIdFromUrlPath = true
        canSearch = true
        // The store says the lowest Android its file runs on.
        answersForDevice = true
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest(
            "$API_BASE_URL/v6/search?type=apk&searchValue=${Url.encodeQueryComponent(query)}&page=1", querySettings,
        )
        Http.ensureSuccess(res)
        val results = LinkedHashMap<String, List<String>>()
        for (entry in JsonValues.parse(res.body).asMap()?.get("data").asList() ?: emptyList()) {
            val app = entry.asMap() ?: continue
            val packageName = app["apkname"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            val title = app["title"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            results["https://www.${hosts[0]}/apk/$packageName"] = listOf(title, packageName)
        }
        return results
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/apk/[^/]+")

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
            val res = sourceRequest("$API_BASE_URL/v6/apk/detail?id=$appId", additionalSettings)
            Http.ensureSuccess(res)
            val json = try {
                JsonValues.parse(res.body).asMap()
            } catch (e: Exception) {
                null
            } ?: throw NoReleasesError()
            if ((json["status"] as? Number)?.toInt() == -2) throw NoReleasesError()
            val detail = json["data"].asMap() ?: throw NoReleasesError()
            // Only the current release is kept here, so one that is not for this device is the end of it.
            val minSdk = detail["sdkversion"]?.toString()?.toDoubleOrNull()?.toInt()
            if (minSdk != null && minSdk > SourceEnv.platform.sdkInt) throw notForDeviceHere(shortName)
            val version = detail["apkversionname"]?.toString() ?: ""
            if (version.isEmpty()) throw NoVersionError()
            val lastUpdate = when (val raw = detail["lastupdate"]) {
                is Number -> raw.toLong()
                null -> null
                else -> raw.toString().toLongOrNull()
            }
            val aid = detail["id"]?.let(FDroid::numberText) ?: ""
            if (aid.isEmpty()) throw NoReleasesError()
            // The download endpoint answers with a redirect to the file.
            val download = sourceRequest(
                "$API_BASE_URL/v6/apk/download?pn=$appId&aid=$aid", additionalSettings, followRedirects = false,
            )
            val apkUrl = if (download.statusCode in 300..399) download.headers["location"] ?: "" else ""
            if (apkUrl.isEmpty()) throw NoApkError()
            return ApkDetails(
                version,
                listOf(NamedUrl("${appId}_$version.apk", apkUrl)),
                AppNames(detail["developername"]?.toString() ?: "CoolApk", detail["title"]?.toString() ?: Tr.get("app")),
                releaseDate = lastUpdate?.let { Instant.ofEpochSecond(it) },
                changeLog = detail["changelog"]?.toString() ?: "",
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> {
        val (deviceCode, token) = createToken()
        return linkedMapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9; MI 8 SE MIUI/9.5.9) (#Build; Xiaomi; MI 8 SE; " +
                "PKQ1.181121.001; 9) +CoolMarket/12.4.2-2208241-universal",
            "X-App-Id" to "com.coolapk.market",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sdk-Int" to "30",
            "X-App-Mode" to "universal",
            "X-App-Channel" to "coolapk",
            "X-Sdk-Locale" to "zh-CN",
            "X-App-Version" to "12.4.2",
            "X-Api-Supported" to "2208241",
            "X-App-Code" to "2208241",
            "X-Api-Version" to "12",
            "X-App-Device" to deviceCode,
            "X-Dark-Mode" to "0",
            "X-App-Token" to token,
        )
    }

    /** The client's request token: a bcrypt hash over the time and a random device identity. */
    private fun createToken(): Pair<String, String> {
        val encoder = Base64.getEncoder()
        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.ISO_8859_1))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun base64(text: String): String = encoder.encodeToString(text.toByteArray(Charsets.ISO_8859_1))

        val aid = Random.nextBytes(16).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val mac = Random.nextBytes(6).joinToString(":") { "%02x".format(it.toInt() and 0xff) }
        val deviceCode = base64("$aid; ; ; $mac; Google; Google; Pixel 5a; SQ1D.220105.007")
        val timeStamp = (System.currentTimeMillis() / 1000).toString()
        val token = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c?" +
            "${md5(timeStamp)}$${md5(deviceCode)}&com.coolapk.market"
        val salt = "$2a$10$" + base64(timeStamp).substring(0, 14) + "/" + md5(token).substring(0, 6) + "u"
        val hashed = BCrypt.hashpw(md5(base64(token)), salt)
        return deviceCode to "v2" + base64("$2y" + hashed.substring(3))
    }

    companion object {
        private const val API_BASE_URL = "https://api2.coolapk.com"
    }
}
