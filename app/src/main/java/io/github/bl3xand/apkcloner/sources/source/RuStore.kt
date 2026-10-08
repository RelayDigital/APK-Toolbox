package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.RuStoreAggregatedAppError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
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
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class RuStore : AppSource("RuStore") {
    private class SecureSession(val deviceId: String, val signature: String)

    init {
        hosts = listOf("rustore.ru")
        fixedName = "RuStore"
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        changeLogIfAnyIsMarkDown = false
        inferAppIdFromUrlPath = true
        canSearch = true
    }

    private fun deviceType(): String = if (SourceEnv.platform.isTv) "TV" else "mobile"

    /** RuStore needs all three fields before it returns split APK URLs. */
    private fun deviceDownloadProfile(): Map<String, Any?> = mapOf(
        "supportedAbis" to SourceEnv.platform.supportedAbis.ifEmpty { listOf("arm64-v8a") },
        "sdkVersion" to SourceEnv.platform.sdkInt,
        "screenDensity" to SourceEnv.platform.screenDensityDpi,
    )

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> {
        val needsSignature = url.startsWith(APP_INFO_URL) || url.startsWith(DOWNLOAD_LINK_URL)
        val current = if (needsSignature) getSecureSession() else session
        // Search needs no signature but still expects a device id.
        return deviceHeaders(current?.deviceId ?: randomDeviceId(), if (needsSignature) current?.signature else null)
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/catalog/app/+[^/]+")

    private fun decodeJsonBody(response: HttpResponse): Any? = JsonValues.parse(String(response.bodyBytes, Charsets.UTF_8))

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
            val infoResponse = sourceRequestWithSessionRetry("$APP_INFO_URL/$appId", additionalSettings)
            Http.ensureSuccess(infoResponse)
            val details = decodeJsonBody(infoResponse).dig("body").asMap()
            if (details?.get("appId") == null) throw NoReleasesError()
            val version = details["versionName"] as? String
            if (version.isNullOrEmpty()) throw NoVersionError()

            val linksResponse = sourceRequestWithSessionRetry(
                DOWNLOAD_LINK_URL,
                additionalSettings,
                followRedirects = false,
                postBody = mapOf(
                    "appId" to details[SettingKeys.APP_ID], "firstInstall" to true, "withoutSplits" to false,
                ) + deviceDownloadProfile(),
            )
            Http.ensureSuccess(linksResponse)
            val urls = (decodeJsonBody(linksResponse).dig("downloadUrls").asList() ?: emptyList())
                .mapNotNull { it.asMap()?.get("url")?.toString() }
                .filter { it.isNotEmpty() }
            if (urls.isEmpty()) {
                // A card generated from another catalogue: RuStore has no APK for it.
                if (details["aggregatorInfo"] is Map<*, *>) throw RuStoreAggregatedAppError()
                throw NoApkError()
            }
            return ApkDetails(
                version,
                apkUrlsFromDownloadUrls(urls),
                AppNames((details["companyName"] as? String) ?: name, (details["appName"] as? String) ?: Tr.get("app")),
                releaseDate = Dates.tryParse(details["appVerUpdatedAt"] as? String),
                changeLog = details["whatsNew"] as? String,
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val url = Url.parse(SEARCH_URL)
            .withQueryParameters(mapOf("query" to query, "pageNumber" to "0", "pageSize" to "20")).toString()
        val response = sourceRequest(url, querySettings)
        Http.ensureSuccess(response)
        val results = LinkedHashMap<String, List<String>>()
        for (entry in decodeJsonBody(response).dig("body", "content").asList() ?: emptyList()) {
            val app = entry.asMap() ?: continue
            val packageName = app["packageName"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            val appName = app["appName"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            results["https://${hosts[0]}/catalog/app/$packageName"] = listOf(appName, packageName)
        }
        return results
    }

    private fun deviceHeaders(deviceId: String?, signature: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        if (deviceId != null) headers["deviceId"] = deviceId
        headers["firmwareVer"] = FIRMWARE_VER
        headers["androidSdkVer"] = ANDROID_SDK_VER
        headers["deviceManufacturerName"] = DEVICE_MANUFACTURER
        headers["deviceModelName"] = DEVICE_MODEL_NAME
        headers["deviceModel"] = "$DEVICE_MANUFACTURER $DEVICE_MODEL_NAME"
        headers["firmwareLang"] = FIRMWARE_LANG
        headers["ruStoreVerCode"] = RUSTORE_VER_CODE
        headers["deviceType"] = deviceType()
        headers["User-Agent"] = USER_AGENT
        if (signature != null) headers["X-Client-Signature"] = signature
        return headers
    }

    private fun getSecureSession(): SecureSession? = session ?: generateSecureSession()

    /** signature = base64(HMAC-SHA256(key, nonce bytes + certificate hash)). */
    private fun generateSecureSession(): SecureSession? = try {
        val deviceId = randomDeviceId()
        val response = Http.request(
            NONCE_URL, deviceHeaders(deviceId, null), requestOptions(emptyMap()), postBody = "",
        )
        val nonce = if (response.statusCode == 200) decodeJsonBody(response).dig("nonce") as? String else null
        if (nonce == null) {
            null
        } else {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
            val digest = mac.doFinal(Base64.getDecoder().decode(nonce) + apkCertSha256)
            SecureSession(deviceId, Base64.getEncoder().encodeToString(digest)).also { session = it }
        }
    } catch (e: Exception) {
        null
    }

    private fun randomDeviceId(): String {
        // The suffix the store client derives from the device's build fields (Java hashes).
        val m = DEVICE_MANUFACTURER.hashCode()
        val mo = DEVICE_MODEL_NAME.hashCode()
        val h = DEVICE_HARDWARE.hashCode()
        val d = DEVICE_HARDWARE.hashCode()
        val suffix = d + (h + (mo + m * 31) * 31) * 31
        val androidId = Random.nextBytes(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$androidId-$suffix"
    }

    /** A 419 means the session was rejected: build a new one and repeat once. */
    private fun sourceRequestWithSessionRetry(
        url: String,
        additionalSettings: Map<String, Any?>,
        followRedirects: Boolean = true,
        postBody: Any? = null,
    ): HttpResponse {
        val response = sourceRequest(url, additionalSettings, followRedirects, postBody)
        if (response.statusCode == 419 && generateSecureSession() != null) {
            return sourceRequest(url, additionalSettings, followRedirects, postBody)
        }
        return response
    }

    companion object {
        private const val APP_INFO_URL = "https://backapi.rustore.ru/applicationData/overallInfo"
        private const val SEARCH_URL = "https://backapi.rustore.ru/applicationData/apps"
        private const val DOWNLOAD_LINK_URL = "https://backapi.rustore.ru/v3/showcase/apps/download-link"
        private const val NONCE_URL = "https://api.rustore.ru/v1/secure/nonce"

        // Taken from the store client; the same across its versions.
        private val hmacKey = Base64.getDecoder().decode("K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU=")
        private val apkCertSha256 = Base64.getDecoder().decode("Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ=")

        private const val DEVICE_MANUFACTURER = "Google"
        private const val DEVICE_MODEL_NAME = "Pixel 8 Pro"
        private const val DEVICE_HARDWARE = "husky"
        private const val FIRMWARE_VER = "16"
        private const val ANDROID_SDK_VER = "36"
        private const val FIRMWARE_LANG = "ru"
        private const val RUSTORE_VER_CODE = "1105002"
        private const val USER_AGENT = "RuStore/1.105.0.2 (Android $FIRMWARE_VER; SDK $ANDROID_SDK_VER; " +
            "arm64-v8a; $DEVICE_MANUFACTURER $DEVICE_MODEL_NAME; $FIRMWARE_LANG)"

        @Volatile
        private var session: SecureSession? = null

        /**
         * One download URL is an APK served beside its ".zip" container; several are a base APK
         * plus configuration splits and become a single entry installed as one set.
         */
        fun apkUrlsFromDownloadUrls(urls: List<String>): List<NamedUrl> {
            if (urls.size == 1) {
                return ApkFilter.apkUrlsFromUrls(listOf(urls.first().replace(Regex("\\.zip$"), ".apk")))
            }
            return listOf(NamedUrl(ApkFilter.apkUrlsFromUrls(listOf(urls.first())).first().name, ApkFilter.joinMultiApkUrl(urls)))
        }
    }
}
