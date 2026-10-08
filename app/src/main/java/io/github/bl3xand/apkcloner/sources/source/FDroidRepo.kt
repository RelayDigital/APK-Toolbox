package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import java.time.Instant
import org.jsoup.Jsoup

/** A third-party F-Droid repository (index-v2.json, or the older index.xml). */
class FDroidRepo : AppSource("FDroidRepo") {
    private class Version(
        val versionName: String,
        val versionCode: Long,
        val apkName: String,
        val nativecode: List<String> = emptyList(),
        val added: Instant? = null,
        /** index-v2 release channels such as "Beta"; empty for v1. */
        val releaseChannels: List<String> = emptyList(),
    )

    private class IndexEntry(
        val id: String,
        val name: String,
        val summary: String = "",
        val author: String? = null,
        val changelog: String? = null,
        /** The "suggested" version code; only index.xml has it. */
        val marketVersionCode: Long? = null,
        /** Newest first. */
        val versions: List<Version>,
    )

    private class Index(val baseUrl: String, val entries: List<IndexEntry>)

    override val name: String get() = Tr.get("fdroidThirdPartyRepo")

    init {
        canSearch = true
        includeAdditionalOptsInMainSearch = true
        neverAutoSelect = true
        showReleaseDateAsVersionToggle = true
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(TextItem("appIdOrName", "appIdOrName", hint = Tr.get("reposHaveMultipleApps"), required = true)),
            listOf(SwitchItem("pickHighestVersionCode", "pickHighestVersionCode", value = false)),
            listOf(SwitchItem("trySelectingSuggestedVersionCode", "trySelectingSuggestedVersionCode", value = true)),
        )

    private fun removeQueryParams(url: String, keep: List<String> = emptyList()): String {
        val uri = Url.parse(url)
        val kept = uri.queryParameters.filterKeys { it in keep }
        return uri.withQuery(null).let { if (kept.isEmpty()) it else it.withQueryParameters(kept) }.toString()
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        var uri = Url.parse(url)
        val segments = uri.pathSegments
        if (segments.isNotEmpty() && (segments.last() == "index.xml" || segments.last() == "index-v2.json")) {
            uri = uri.withPathSegments(segments.dropLast(1))
        }
        return removeQueryParams(uri.toString(), keep = listOf("appId"))
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val url = (querySettings["url"] as? String) ?: throw NoReleasesError()
        val index = fetchIndex(removeQueryParams(standardizeUrl(url)), emptyMap())
        val results = LinkedHashMap<String, List<String>>()
        for (entry in index.entries) {
            if (query.isEmpty() || entry.id.contains(query) || entry.name.contains(query) ||
                entry.summary.contains(query)
            ) {
                results["${index.baseUrl}?appId=${entry.id}"] = listOf(entry.name, entry.summary)
            }
        }
        return results
    }

    override fun runOnAddAppInputChange(inputUrl: String): Map<String, Any?> {
        val appId = try {
            Url.parse(inputUrl).queryParameters[SettingKeys.APP_ID]
        } catch (e: Exception) {
            null
        }
        return if (appId != null) mapOf("appIdOrName" to appId) else emptyMap()
    }

    /** Writes the real app id into the URL, the settings and the app id itself. */
    override fun postProcessApp(app: TrackedApp): TrackedApp {
        val uri = Url.parse(app.url)
        val appId = if (!app.hasTempId) app.id else uri.queryParameters[SettingKeys.APP_ID]
        appId ?: return app
        return app.copy(
            url = uri.withQueryParameters(uri.queryParameters + ("appId" to appId)).toString(),
            additionalSettings = LinkedHashMap(app.additionalSettings).also { it["appIdOrName"] = appId },
            id = appId,
        )
    }

    private fun requestIndexXml(url: String, additionalSettings: Map<String, Any?>): HttpResponse {
        var res = sourceRequest("$url${if (url.endsWith("/index.xml")) "" else "/index.xml"}", additionalSettings)
        if (res.statusCode != 200) {
            val base = if (url.endsWith("/index.xml")) stripLastPathSegment(url) else url
            res = sourceRequest("$base/repo/index.xml", additionalSettings)
            if (res.statusCode != 200) res = sourceRequest("$base/fdroid/repo/index.xml", additionalSettings)
        }
        return res
    }

    /** index-v2.json is preferred: some repositories publish nothing else. */
    private fun fetchIndex(url: String, additionalSettings: Map<String, Any?>): Index =
        tryFetchIndexV2(url, additionalSettings) ?: fetchIndexV1(url, additionalSettings)

    private fun tryFetchIndexV2(url: String, additionalSettings: Map<String, Any?>): Index? {
        val base = if (url.endsWith("/index-v2.json")) stripLastPathSegment(url) else url
        val candidates = listOf(
            "$url${if (url.endsWith("/index-v2.json")) "" else "/index-v2.json"}",
            "$base/repo/index-v2.json",
            "$base/fdroid/repo/index-v2.json",
        )
        for (candidate in candidates) {
            try {
                val res = sourceRequest(candidate, additionalSettings)
                if (res.statusCode != 200) continue
                parseIndexV2(res)?.let { return it }
            } catch (_: Exception) {
                // Not an index at this path.
            }
        }
        return null
    }

    private fun parseIndexV2(res: HttpResponse): Index? = try {
        val packages = JsonValues.parse(res.body).asMap()?.get("packages").asMap()
        val entries = mutableListOf<IndexEntry>()
        packages?.forEach { (id, rawPackage) ->
            val pkg = rawPackage.asMap() ?: return@forEach
            val versions = pkg["versions"].asMap() ?: return@forEach
            val versionList = mutableListOf<Version>()
            for (rawVersion in versions.values) {
                val v = rawVersion.asMap() ?: continue
                val manifest = v["manifest"].asMap() ?: continue
                val file = v["file"].asMap() ?: continue
                val versionName = manifest["versionName"]?.toString() ?: continue
                val versionCode = (manifest["versionCode"] as? Number)?.toLong() ?: continue
                val apkName = file["name"]?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                versionList.add(
                    Version(
                        versionName, versionCode, apkName.removePrefix("/"),
                        (manifest["nativecode"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        parseV2Timestamp(v["added"]),
                        (v["releaseChannels"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    ),
                )
            }
            if (versionList.isEmpty()) return@forEach
            versionList.sortByDescending { it.versionCode }
            val metadata = pkg["metadata"].asMap()
            entries.add(
                IndexEntry(
                    id = id,
                    name = localizedString(metadata?.get("name")) ?: id,
                    summary = localizedString(metadata?.get("summary")) ?: "",
                    author = localizedString(metadata?.get("authorName")),
                    changelog = localizedString(metadata?.get("changelog")),
                    versions = versionList,
                ),
            )
        }
        if (entries.isEmpty()) null else Index(stripLastPathSegment(res.requestUrl.toString()), entries)
    } catch (e: Exception) {
        null
    }

    private fun fetchIndexV1(url: String, additionalSettings: Map<String, Any?>): Index {
        val res = requestIndexXml(url, additionalSettings)
        if (res.statusCode != 200) throw Http.errorFor(res)
        val entries = mutableListOf<IndexEntry>()
        for (app in Jsoup.parse(res.body).select("application")) {
            val id = app.attr("id").ifEmpty { continue }
            val versions = mutableListOf<Version>()
            for (pkg in app.select("package")) {
                val versionName = pkg.selectFirst("version")?.html() ?: continue
                val versionCode = pkg.selectFirst("versioncode")?.html()?.trim()?.toLongOrNull() ?: continue
                val apkName = pkg.selectFirst("apkname")?.html()?.takeIf { it.isNotEmpty() } ?: continue
                versions.add(
                    Version(
                        versionName, versionCode, apkName,
                        (pkg.selectFirst("nativecode")?.html() ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                        Dates.tryParse(pkg.selectFirst("added")?.html()),
                    ),
                )
            }
            versions.sortByDescending { it.versionCode }
            entries.add(
                IndexEntry(
                    id = id,
                    name = app.selectFirst("name")?.html() ?: id,
                    summary = app.selectFirst("summary")?.html() ?: "",
                    author = app.selectFirst("author")?.html(),
                    changelog = app.selectFirst("changelog")?.html(),
                    marketVersionCode = app.selectFirst("marketvercode")?.html()?.trim()?.toLongOrNull(),
                    versions = versions,
                ),
            )
        }
        return Index(stripLastPathSegment(res.requestUrl.toString()), entries)
    }

    /** index-v2 texts are locale maps or plain strings: English, else the first one. */
    private fun localizedString(value: Any?): String? {
        if (value is String) return value.ifEmpty { null }
        val map = value.asMap() ?: return null
        for (key in listOf("en-US", "en")) {
            (map[key] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return map.values.firstOrNull { it is String && it.isNotEmpty() } as? String
    }

    private fun parseV2Timestamp(added: Any?): Instant? = when (added) {
        is Number -> Instant.ofEpochMilli(if (added.toDouble() > 1e12) added.toLong() else added.toLong() * 1000)
        is String -> Dates.tryParse(added)
        else -> null
    }

    private fun findIndexEntry(entries: List<IndexEntry>, appIdOrName: String): IndexEntry? =
        entries.firstOrNull { it.id == appIdOrName }
            ?: entries.firstOrNull { it.name.lowercase() == appIdOrName.lowercase() }
            ?: entries.firstOrNull { it.name.lowercase().contains(appIdOrName.lowercase()) }

    /** Keeps the builds the device can run; a build without native code runs anywhere. */
    private fun filterVersionsByArch(versions: List<Version>): List<Version> {
        if (versions.size <= 1) return versions
        if (versions.all { it.nativecode.isEmpty() }) return versions
        val abis = SourceEnv.platform.supportedAbis
        val compatible = versions.filter { v -> v.nativecode.isEmpty() || v.nativecode.any { it in abis } }
        return compatible.ifEmpty { versions }
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            var appIdOrName = additionalSettings["appIdOrName"] as? String
            Url.parse(standardUrl).queryParameters[SettingKeys.APP_ID]?.let { appIdOrName = it }
            val repoUrl = removeQueryParams(standardUrl)
            val pickHighestVersionCode = additionalSettings["pickHighestVersionCode"] == true
            val trySuggested = additionalSettings["trySelectingSuggestedVersionCode"] == true
            val wanted = appIdOrName ?: throw NoReleasesError()
            // Remembered with the app, as the reference does.
            @Suppress("UNCHECKED_CAST")
            (additionalSettings as? MutableMap<String, Any?>)?.set("appIdOrName", wanted)
            val index = fetchIndex(repoUrl, additionalSettings)
            val entry = findIndexEntry(index.entries, wanted) ?: throw SourceError(Tr.get("appWithIdOrNameNotFound"))
            val releases = entry.versions
            if (releases.isEmpty()) throw NoReleasesError()
            var selected: List<Version> = emptyList()
            if (trySuggested && entry.marketVersionCode != null) {
                selected = releases.filter { it.versionCode == entry.marketVersionCode }
            }
            var candidates = releases
            if (selected.isEmpty() && trySuggested) {
                // index-v2 has no suggested version; the switch then means "prefer stable".
                val nonStable = releases.filter { v ->
                    v.releaseChannels.isNotEmpty() && v.releaseChannels.none { it.lowercase() == "stable" }
                }
                if (nonStable.isNotEmpty() && nonStable.size < releases.size) {
                    candidates = releases.filter { it !in nonStable }
                }
            }
            if (selected.isEmpty()) {
                selected = if (pickHighestVersionCode) {
                    listOf(candidates.first())
                } else {
                    candidates.filter { it.versionName == candidates.first().versionName }
                }
            }
            if (selected.isEmpty()) throw NoReleasesError()
            selected = filterVersionsByArch(selected)
            if (selected.isEmpty()) throw NoReleasesError()
            val useVersionCode = additionalSettings[SettingKeys.VERSION_CODE_AS_OS_VERSION] == true
            return ApkDetails(
                if (useVersionCode) selected.first().versionCode.toString() else selected.first().versionName,
                ApkFilter.apkUrlsFromUrls(selected.map { "${index.baseUrl}/${it.apkName}" }),
                AppNames(entry.author ?: name, entry.name),
                releaseDate = selected.first().added,
                changeLog = entry.changelog,
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }
}
