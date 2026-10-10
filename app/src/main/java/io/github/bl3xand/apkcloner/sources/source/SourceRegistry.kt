package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.notForProcessor
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.MinUpdateAgeError
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.UnsupportedUrlError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.extractVersion
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.preStandardizeUrl
import io.github.bl3xand.apkcloner.sources.core.sha256Hex
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

const val DEFAULT_FETCH_CONCURRENCY = 4

/** Knows every source, picks the one for a URL and builds apps from it. */
object SourceRegistry {
    // In auto-detection order: sources with hosts are matched by host, then host-less sources
    // are tried in this order. HTML accepts anything and therefore stays last.
    private val sourceFactories: List<() -> AppSource> = listOf(
        { GitHub() },
        { GitLab() },
        { Codeberg() },
        { FDroid() },
        { FDroidRepo() },
        { IzzyOnDroid() },
        { SourceHut() },
        { APKPure() },
        { Aptoide() },
        { Uptodown() },
        { ItchIO() },
        { HuaweiAppGallery() },
        { Tencent() },
        { VivoAppStore() },
        { RuStore() },
        { Farsroid() },
        { SamsungGalaxyStore() },
        { Apk4Free() },
        { CoolApk() },
        { SourceForge() },
        { Jenkins() },
        { APKMirror() },
        { RockMods() },
        { TelegramApp() },
        { TelegramChannel() },
        { NeutronCode() },
        { DirectAPKLink() },
        { HTML() },
    )

    /** Shared, read-only instances. */
    val sources: List<AppSource> by lazy { sourceFactories.map { it() } }

    private val factoriesById: Map<String, () -> AppSource> by lazy {
        sourceFactories.associateBy { it().sourceIdentifier }
    }

    val massUrlSources: List<MassAppUrlSource> by lazy { listOf<MassAppUrlSource>(GitHubStars()) }

    fun getSource(url: String, overrideSource: String? = null): AppSource {
        val prepared = preStandardizeUrl(url)
        if (overrideSource != null) {
            val factory = factoriesById[overrideSource] ?: throw UnsupportedUrlError().also { it.url = prepared }
            // The override changes the host of the chosen source, so it gets its own instance.
            val source = factory()
            val originalHosts = source.hosts
            val newHost = Url.parse(prepared).host
            source.hosts = listOf(newHost)
            source.hostChanged = true
            if (originalHosts.contains(newHost)) source.hostIdenticalDespiteAnyChange = true
            return source
        }
        val host = Url.parse(prepared).host
        sources.firstOrNull { it.hosts.isNotEmpty() && it.matchesHost(host) }?.let { return it }
        for (source in sources.filter { it.hosts.isEmpty() && !it.neverAutoSelect }) {
            try {
                source.sourceSpecificStandardizeURL(prepared, forSelection = true)
                return source
            } catch (_: SourceError) {
                // Not this one; a rejection is the normal way to say so.
            }
        }
        throw UnsupportedUrlError().also { it.url = prepared }
    }

    /** A placeholder id used until the real package name is known. */
    fun generateTempId(standardUrl: String, additionalSettings: Map<String, Any?>): String =
        sha256Hex(standardUrl + additionalSettings.toString()).substring(0, 12)

    private fun resolveAppId(
        source: AppSource,
        currentApp: TrackedApp?,
        additionalSettings: Map<String, Any?>,
        trackOnly: Boolean,
        standardUrl: String,
        inferAppIdIfOptional: Boolean,
    ): String {
        if (currentApp != null) return currentApp.id
        val explicitId = additionalSettings[SettingKeys.APP_ID] as? String
        if (!explicitId.isNullOrBlank()) return explicitId
        if ((!trackOnly || source.inferAppIdEvenWhenTrackOnly) &&
            (!source.appIdInferIsOptional || inferAppIdIfOptional)
        ) {
            source.tryInferringAppId(standardUrl, additionalSettings)?.let { return it }
        }
        return generateTempId(standardUrl, additionalSettings)
    }

    /** Asks [source] for the newest release behind [url] and builds the app record from it. */
    fun getApp(
        source: AppSource,
        url: String,
        settings: Map<String, Any?>,
        currentApp: TrackedApp? = null,
        trackOnlyOverride: Boolean = false,
        sourceIsOverriden: Boolean = false,
        inferAppIdIfOptional: Boolean = false,
    ): TrackedApp {
        val additionalSettings = LinkedHashMap(settings)
        if (trackOnlyOverride || source.enforceTrackOnly) additionalSettings[SettingKeys.TRACK_ONLY] = true
        val trackOnly = additionalSettings[SettingKeys.TRACK_ONLY] == true
        val standardUrl = try {
            source.standardizeUrl(url)
        } catch (e: SourceError) {
            throw e.withUrlContext(url)
        }
        var apk = try {
            source.getLatestAPKDetails(standardUrl, additionalSettings)
        } catch (e: SourceError) {
            throw e.withUrlContext(standardUrl)
        }

        // Adding an app honours the minimum update age too: a source that cannot look back
        // would otherwise install a release that is still too young.
        if (currentApp == null && !trackOnly) {
            val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)
            if (isReleaseTooYoung(apk.releaseDate, minAgeDays)) {
                throw MinUpdateAgeError(apk.releaseDate!!, minAgeDays).also { it.url = standardUrl }
            }
        }

        if (!source.suppressStandardVersionExtraction) {
            extractVersion(
                additionalSettings["versionExtractionRegEx"] as? String,
                additionalSettings["matchGroupToUse"] as? String,
                apk.version,
            )?.let { apk = apk.copy(version = it) }
        }
        if (additionalSettings[SettingKeys.RELEASE_DATE_AS_VERSION] == true && apk.releaseDate != null) {
            apk = apk.copy(version = TrackedApp.toMicros(apk.releaseDate!!).toString())
        }
        apk = apk.copy(
            apkUrls = ApkFilter.filterApks(
                apk.apkUrls,
                (additionalSettings["apkFilterRegEx"] as? String) ?: SourceEnv.settings.globalApkFilterRegEx,
                additionalSettings["invertAPKFilter"] as? Boolean,
            ),
        )
        if (apk.apkUrls.isEmpty() && !trackOnly) throw NoApkError().also { it.url = standardUrl }
        if (additionalSettings["autoApkFilterByArch"] == true) {
            apk = apk.copy(apkUrls = ApkFilter.filterApksByArch(apk.apkUrls, SourceEnv.platform.supportedAbis))
            if (apk.apkUrls.isEmpty() && !trackOnly) throw notForProcessor().also { it.url = standardUrl }
        }
        // A package name standing in for the name gives way as soon as the source tells the name.
        val name = currentApp?.name?.trim()?.takeIf { it.isNotEmpty() && it != currentApp.id } ?: apk.names.name
        val app = TrackedApp(
            id = resolveAppId(source, currentApp, additionalSettings, trackOnly, standardUrl, inferAppIdIfOptional),
            url = standardUrl,
            author = apk.names.author,
            name = name,
            installedVersion = currentApp?.installedVersion,
            latestVersion = apk.version,
            apkUrls = apk.apkUrls,
            preferredApkIndex = currentApp?.preferredApkIndex
                ?: (if (apk.apkUrls.isNotEmpty()) apk.apkUrls.size - 1 else 0),
            additionalSettings = additionalSettings,
            lastUpdateCheck = Instant.now(),
            pinned = currentApp?.pinned ?: false,
            categories = currentApp?.categories ?: emptyList(),
            releaseDate = apk.releaseDate,
            changeLog = apk.changeLog,
            releaseUrl = apk.releaseUrl,
            overrideSource = if (sourceIsOverriden) source.sourceIdentifier else currentApp?.overrideSource,
            allowIdChange = currentApp?.allowIdChange
                ?: (trackOnly || (source.appIdInferIsOptional && inferAppIdIfOptional)),
            otherAssetUrls = apk.allAssetUrls.filter { asset -> apk.apkUrls.none { it.name == asset.name } },
        )
        return source.postProcessApp(app)
    }

    /** Adds many URLs with default settings; failures are returned per URL instead of thrown. */
    fun getAppsByUrlNaive(
        urls: List<String>,
        alreadyAddedUrls: Set<String> = emptySet(),
        sourceOverride: AppSource? = null,
    ): Pair<List<TrackedApp>, Map<String, Any>> {
        val apps = mutableListOf<TrackedApp>()
        val errors = LinkedHashMap<String, Any>()
        val pool = Executors.newFixedThreadPool(DEFAULT_FETCH_CONCURRENCY)
        try {
            val futures = urls.map { url ->
                pool.submit(Callable<Any> {
                    try {
                        if (alreadyAddedUrls.contains(url)) throw SourceError("${Tr.get("appAlreadyAdded")} ($url)")
                        val source = sourceOverride ?: getSource(url)
                        getApp(
                            source, url, defaultValuesOf(source.combinedAppSpecificSettingFormItems),
                            sourceIsOverriden = sourceOverride != null,
                        )
                    } catch (e: Throwable) {
                        e
                    }
                })
            }
            futures.forEachIndexed { index, future ->
                when (val result = future.get()) {
                    is TrackedApp -> apps.add(result)
                    else -> errors[urls[index]] = result
                }
            }
        } finally {
            pool.shutdown()
        }
        return apps to errors
    }
}
