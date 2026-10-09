package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.ApkPeek
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NotImplementedSourceError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceSettings
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.preStandardizeUrl
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.sourceRegex
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SliderItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.form.cloneItems
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import io.github.bl3xand.apkcloner.sources.net.RequestOptions
import java.io.File

/** Day counts offered for the minimum update age; 0 turns the delay off. */
val minimumUpdateAgeOptions = listOf(0, 1, 2, 3, 5, 7, 14, 30)

/** A place apps can be tracked from. Subclasses describe one site or one kind of site. */
abstract class AppSource(
    /** Stable id stored with apps that override their source. */
    val sourceIdentifier: String,
) {
    var hosts: List<String> = emptyList()
    var trustedApkHosts: List<String> = emptyList()
    var hostChanged = false
    var hostIdenticalDespiteAnyChange = false
    open val name: String get() = fixedName ?: sourceIdentifier

    /** What of the site is supported, where its name alone does not say; shown in the list of sources. */
    open val supportedNote: String? get() = null
    protected var fixedName: String? = null
    var enforceTrackOnly = false
    var changeLogIfAnyIsMarkDown = true
    var changeLogPageIsStandardUrl = false
    var appIdInferIsOptional = false
    var inferAppIdFromUrlPath = false
    var inferAppIdEvenWhenTrackOnly = false
    var allowSubDomains = false
    var naiveStandardVersionDetection = false
    var allowOverride = true
    var neverAutoSelect = false
    var showReleaseDateAsVersionToggle = false
    var versionDetectionDisallowed = false
    var suppressStandardVersionExtraction = false
    var excludeCommonSettingKeys: List<String> = emptyList()
    var urlsAlwaysHaveExtension = false
    var allowInsecureRedirects = false
    var allowIncludeZips = false
    var allowIncludeTarballs = false
    var canSearch = false
    var includeAdditionalOptsInMainSearch = false

    private var hostMatchRegex: Regex? = null

    /** Whether [host] is one of this source's hosts (with or without sub-domains). */
    fun matchesHost(host: String): Boolean {
        val regex = hostMatchRegex ?: Regex(
            "^${if (allowSubDomains) "([^\\.]+\\.)*" else "(www\\.)?"}(${sourceRegex(hosts)})$",
        ).also { hostMatchRegex = it }
        return regex.containsMatchIn(host)
    }

    open fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean = false,
    ): Map<String, String>? = null

    fun standardizeUrl(url: String): String {
        val prepared = preStandardizeUrl(url)
        return if (!hostChanged) sourceSpecificStandardizeURL(prepared) else prepared
    }

    open fun postProcessApp(app: TrackedApp): TrackedApp = app

    /** Per-app settings with the source-level configuration merged in. */
    fun buildMergedSettings(
        additionalSettings: Map<String, Any?>,
        settings: SourceSettings = SourceEnv.settings,
    ): MutableMap<String, Any?> {
        val merged = LinkedHashMap(additionalSettings)
        merged.putAll(getSourceConfigValues(additionalSettings, settings))
        return merged
    }

    fun requestOptions(additionalSettings: Map<String, Any?>): RequestOptions = RequestOptions(
        allowInsecure = additionalSettings["allowInsecure"] == true,
        enableCertificatePinning = SourceEnv.settings.enableCertificatePinning,
        allowInsecureRedirects = allowInsecureRedirects,
    )

    /** A request on behalf of this source: its headers, its URL rewriting, its TLS rules. */
    fun sourceRequest(
        url: String,
        additionalSettings: Map<String, Any?>,
        followRedirects: Boolean = true,
        postBody: Any? = null,
    ): HttpResponse {
        val merged = buildMergedSettings(additionalSettings)
        val finalUrl = generalReqPrefetchModifier(url, merged)
        merged["url"] = finalUrl
        return Http.request(
            finalUrl,
            getRequestHeaders(merged, finalUrl),
            requestOptions(merged),
            followRedirects,
            postBody,
        )
    }

    /** Values to pre-fill in the add form from the typed URL. */
    open fun runOnAddAppInputChange(inputUrl: String): Map<String, Any?> = emptyMap()

    /**
     * Matches `^https?://<prefix><hosts><path>` against [url] and returns the match, or throws
     * [InvalidUrlError].
     */
    protected fun standardizeUrlWithRegex(url: String, subdomainPrefix: String, pathPattern: String): String {
        val regex = Regex("^https?://$subdomainPrefix${sourceRegex(hosts)}$pathPattern", RegexOption.IGNORE_CASE)
        return regex.find(url)?.value ?: throw InvalidUrlError(name).also { it.url = url }
    }

    open fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean = false): String =
        throw NotImplementedSourceError()

    open fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails =
        throw NotImplementedSourceError()

    /** Settings only this source has (shown before the common ones). */
    open val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>> get() = emptyList()

    private val commonAppSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(SwitchItem("trackOnly", "trackOnly")),
            listOf(
                TextItem(
                    "versionExtractionRegEx", "trimVersionString", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(
                TextItem("matchGroupToUse", "matchGroupToUseForX", required = false, hint = "$0").also {
                    it.labelOverride = { Tr.get("matchGroupToUseForX", Tr.get("trimVersionString")) }
                },
            ),
            listOf(SwitchItem("versionDetection", "versionDetectionExplanation", value = true)),
            listOf(SwitchItem("useVersionCodeAsOSVersion", "useVersionCodeAsOSVersion", value = false)),
            listOf(
                TextItem(
                    "apkFilterRegEx", "filterAPKsByRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(
                SwitchItem("invertAPKFilter", "invertRegEx", value = false).also {
                    it.labelOverride = { "${Tr.get("invertRegEx")} (${Tr.get("filterAPKsByRegEx")})" }
                },
            ),
            listOf(SwitchItem("autoApkFilterByArch", "autoApkFilterByArch", value = true)),
            listOf(
                SliderItem(
                    "minimumUpdateAgeDays", "minimumUpdateAgeDays",
                    listOf("" to "useGlobalDefault") + minimumUpdateAgeOptions.map {
                        it.toString() to (if (it == 0) "none" else it.toString())
                    },
                    value = "",
                ),
            ),
            listOf(TextItem("appName", "appName", required = false)),
            listOf(TextItem("appAuthor", "author", required = false)),
            listOf(SwitchItem("shizukuPretendToBeGooglePlay", "shizukuPretendToBeGooglePlay", value = false)),
            listOf(SwitchItem("allowInsecure", "allowInsecure", value = false)),
            listOf(
                TextItem(
                    "allowedSigningCertHashes", "allowedSigningCertHashes", required = false, maxLines = 4,
                    hint = "AA:BB:CC:…", helpUrl = "https://developer.android.com/tools/apksigner",
                    validators = listOf { value ->
                        if (CertHashes.isValidList(value)) null else Tr.get("invalidSigningCertHash")
                    },
                ),
            ),
            listOf(SwitchItem("exemptFromBackgroundUpdates", "exemptFromBackgroundUpdates")),
            listOf(SwitchItem("skipUpdateNotifications", "skipUpdateNotifications")),
            listOf(TextItem("about", "about", required = false)),
            listOf(SwitchItem("refreshBeforeDownload", "refreshBeforeDownload")),
        )

    /**
     * The full per-app form: this source's own settings, then the common ones (minus excluded
     * keys), then the conditional archive options. Built anew on every access so the caller may
     * change the items freely.
     */
    open val combinedAppSpecificSettingFormItems: List<List<SettingItem>>
        get() {
            var agnostic = commonAppSettingFormItems.toMutableList()
            val versionDetectionIndex = agnostic.indexOfFirst { row -> row.any { it.key == "versionDetection" } }
            if (showReleaseDateAsVersionToggle && versionDetectionIndex >= 0) {
                agnostic.add(
                    versionDetectionIndex + 1,
                    listOf(
                        SwitchItem("releaseDateAsVersion", "releaseDateAsVersion", value = false).also {
                            it.labelOverride = { "${Tr.get("releaseDateAsVersion")} (${Tr.get("pseudoVersion")})" }
                        },
                    ),
                )
            }
            agnostic = agnostic.map { row -> row.filter { it.key !in excludeCommonSettingKeys } }
                .filter { it.isNotEmpty() }.toMutableList()

            val conditional = mutableListOf<List<SettingItem>>()
            if (allowIncludeZips) {
                conditional.add(listOf(SwitchItem("includeZips", "includeZips", value = false)))
                conditional.add(
                    listOf(
                        TextItem(
                            "zippedApkFilterRegEx", "zippedApkFilterRegEx", required = false,
                            validators = listOf(::regExValidator),
                        ),
                    ),
                )
            }
            if (allowIncludeTarballs) {
                conditional.add(listOf(SwitchItem("includeTarballs", "includeTarballs", value = false)))
                conditional.add(
                    listOf(
                        TextItem(
                            "tarballedApkFilterRegEx", "tarballedApkFilterRegEx", required = false,
                            validators = listOf(::regExValidator),
                        ),
                    ),
                )
            }
            if (versionDetectionDisallowed) {
                for (item in agnostic.flatten()) {
                    if (item.key == "versionDetection" || item.key == "useVersionCodeAsOSVersion") {
                        (item as SwitchItem).disabled = true
                        item.defaultValue = false
                    }
                }
            }
            return cloneItems(additionalSourceAppSpecificSettingFormItems) + agnostic + conditional
        }

    val hasAppSpecificSettings: Boolean get() = combinedAppSpecificSettingFormItems.isNotEmpty()

    val flatCombinedFormItems: List<SettingItem> get() = combinedAppSpecificSettingFormItems.flatten()

    /** Source-level settings (tokens and the like), stored globally. */
    open val sourceConfigSettingFormItems: List<SettingItem> get() = emptyList()

    /**
     * Values of the source-level settings: a per-app value wins when set; on an overridden host
     * only the per-app value counts, so a token never leaks to another server.
     */
    fun getSourceConfigValues(
        additionalSettings: Map<String, Any?>,
        settings: SourceSettings = SourceEnv.settings,
    ): Map<String, String> {
        val results = LinkedHashMap<String, String>()
        for (item in sourceConfigSettingFormItems) {
            val perApp = additionalSettings[item.key]
            val value: Any? = if (hostChanged && !hostIdenticalDespiteAnyChange) {
                perApp
            } else if (perApp is String && perApp.isNotEmpty()) {
                perApp
            } else if (item is SwitchItem) {
                settings.getBool(item.key).toString()
            } else {
                settings.getString(item.key)
            }
            if (value != null) results[item.key] = value.toString()
        }
        return results
    }

    open fun changeLogPageFromStandardUrl(standardUrl: String): String? =
        if (changeLogPageIsStandardUrl) standardUrl else null

    /** Best-effort download size for sources whose apps carry no direct APK URL. */
    open fun resolveDownloadSize(
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        releaseUrl: String? = null,
    ): Long? = null

    open fun getSourceNote(): String? = null

    /**
     * Fetches an asset in a way of the source's own instead of over HTTP, into [destination].
     * Null - the default - leaves the asset to the downloader.
     */
    open fun downloadAsset(
        assetUrl: String,
        destination: File,
        onProgress: ProgressListener?,
        isCancelled: () -> Boolean,
    ): File? = null

    /**
     * What an APK says about itself - its package and signer - when the source can find that out
     * without downloading the file. Null leaves it to be learnt from a download.
     */
    open fun peekAsset(assetUrl: String, additionalSettings: Map<String, Any?>): ApkPeek? = null

    /**
     * Whether the build of [app] on the device - known by its [versionName] and [versionCode] - is
     * the latest release. Null, the default, is for sources that go by the version alone; a
     * source whose versions are not the app's own answers when it knows the release's build.
     */
    open fun isLatestBuildInstalled(app: TrackedApp, versionName: String?, versionCode: Long): Boolean? = null

    /** The size of an asset when the source knows it itself; null leaves it to be asked over HTTP. */
    open fun assetSize(assetUrl: String, additionalSettings: Map<String, Any?>): Long? = null

    /**
     * What the user has to choose before [app] can be tracked, if anything. [typedUrl] is the
     * link as it was given, which may say more than the app's own URL keeps.
     */
    open fun trackingChoice(app: TrackedApp, typedUrl: String): TrackingChoice? = null

    /** Last-minute change of an asset URL right before it is downloaded. */
    open fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String = assetUrl

    /** Rewrites every request URL of this source (for example through a proxy). */
    open fun generalReqPrefetchModifier(reqUrl: String, additionalSettings: Map<String, Any?>): String = reqUrl

    open val searchQuerySettingFormItems: List<SettingItem> get() = emptyList()

    /** Search options for one instance URL; a source may vary defaults by host. */
    open fun searchQuerySettingItemsForUrl(url: String): List<SettingItem> = searchQuerySettingFormItems

    /** Maps a result URL to [title, description]. */
    open fun search(query: String, querySettings: Map<String, Any?> = emptyMap()): Map<String, List<String>> =
        throw NotImplementedSourceError()

    open fun tryInferringAppId(
        standardUrl: String,
        additionalSettings: Map<String, Any?> = emptyMap(),
    ): String? = if (inferAppIdFromUrlPath) inferAppIdFromLastPathSegment(standardUrl) else null

    companion object {
        fun fallbackToOlderReleasesFormItem(): List<SettingItem> =
            listOf(SwitchItem("fallbackToOlderReleases", "fallbackToOlderReleases", value = true))

        fun isApkOrContainerFile(
            name: String,
            includeArchives: Boolean = false,
            includeTarballs: Boolean = false,
        ): Boolean = ApkFilter.isApkOrContainerFile(name, includeArchives, includeTarballs)

        fun stripLastPathSegment(url: String): String {
            val uri = Url.parse(url)
            return uri.withPathSegments(uri.pathSegments.dropLast(1)).toString()
        }

        fun inferAppIdFromLastPathSegment(standardUrl: String): String? =
            Url.parse(standardUrl).pathSegments.lastOrNull { it.isNotEmpty() }
    }
}
