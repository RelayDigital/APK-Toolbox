package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.compareAlphaNumeric
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.findStandardFormatsForVersion
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.tolerantSort
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.IdentityHashMap
import kotlin.math.ceil

private typealias Release = MutableMap<String, Any?>

class GitHub(hostChanged: Boolean = false) : AppSource("GitHub") {
    init {
        fixedName = "GitHub"
        hosts = listOf("github.com")
        appIdInferIsOptional = true
        showReleaseDateAsVersionToggle = true
        this.hostChanged = hostChanged
        allowIncludeZips = true
        allowIncludeTarballs = true
        canSearch = true
    }

    override val sourceConfigSettingFormItems: List<SettingItem>
        get() = listOf(
            TextItem(
                "github-creds", "githubPATLabel", password = true, required = false,
                helpUrl = "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/" +
                    "managing-your-personal-access-tokens#creating-a-fine-grained-personal-access-token",
            ),
            TextItem(
                "GHReqPrefix", "GHReqPrefix", hint = "gh-proxy.com", required = false,
                helpUrl = "https://github.com/sky22333/hubproxy",
                validators = listOf { value ->
                    // A bare host is expected, not a URL.
                    if (!value.isNullOrEmpty() && (Url.parse(value).hasScheme || value.any { it.isWhitespace() })) {
                        Tr.get("invalidInput")
                    } else null
                },
            ),
            SwitchItem("checkRepoRename", "repoRenamedCheck", value = false),
        )

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(SwitchItem("includePrereleases", "includePrereleases", value = false)),
            fallbackToOlderReleasesFormItem(),
            listOf(
                TextItem(
                    "filterReleaseTitlesByRegEx", "filterReleaseTitlesByRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(
                TextItem(
                    "filterReleaseNotesByRegEx", "filterReleaseNotesByRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(SwitchItem("verifyLatestTag", "verifyLatestTag")),
            listOf(
                DropdownItem(
                    "sortMethodChoice", "sortMethod",
                    listOf(
                        "date" to "releaseDate",
                        "smartname" to "smartname",
                        "none" to "none",
                        "smartname-datefallback" to "smartname x releaseDate",
                        "name" to "name",
                    ),
                    value = "date",
                ),
            ),
            listOf(SwitchItem("useLatestAssetDateAsReleaseDate", "useLatestAssetDateAsReleaseDate", value = false)),
            listOf(SwitchItem("releaseTitleAsVersion", "releaseTitleAsVersion", value = false)),
        )

    override val searchQuerySettingFormItems: List<SettingItem>
        get() = listOf(
            TextItem(
                "minStarCount", "minStarCount", value = "0",
                validators = listOf { value ->
                    if ((value ?: "0").toIntOrNull() == null) Tr.get("invalidInput") else null
                },
            ),
        )

    /** Looks for a single `applicationId` in the usual build.gradle locations. */
    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? {
        val locations = listOf("/app/build.gradle", "android/app/build.gradle", "src/app/build.gradle")
        for (path in locations) {
            try {
                val res = sourceRequest(
                    "${convertStandardUrlToAPIUrl(standardUrl, additionalSettings)}/contents/$path",
                    additionalSettings,
                )
                if (res.statusCode != 200) continue
                val body = JsonValues.parse(res.body)
                val content = body.dig("content").toString().split('\n').joinToString("")
                val trimmedLines = String(Base64.getDecoder().decode(content), Charsets.UTF_8)
                    .split('\n').map { it.trim() }
                val appIds = trimmedLines
                    .filter { it.startsWith("applicationId \"") || it.startsWith("applicationId '") }
                    .map { line ->
                        val parts = line.split(if (line.startsWith("applicationId \"")) "\"" else "'")
                        if (parts.size > 1) parts[1] else ""
                    }
                    .map { appId ->
                        if (appId.startsWith("\${") && appId.endsWith("}")) {
                            val variable = appId.substring(2, appId.length - 1)
                            val varLine = trimmedLines.firstOrNull { it.startsWith("def $variable") }
                                ?: return@map ""
                            val parts = varLine.split(if (varLine.contains('"')) "\"" else "'")
                            if (parts.size > 1) parts[1] else ""
                        } else appId
                    }
                    .filter { it.isNotEmpty() }
                if (appIds.size == 1) return appIds.first()
            } catch (_: Exception) {
                // Try the next location.
            }
        }
        return null
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/[^/]+/[^/]+")

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        // skipAuth marks the retry of a request whose token was rejected for this repository.
        val token = if (additionalSettings["skipAuth"] == true) null else getTokenIfAny(additionalSettings)
        val headers = LinkedHashMap<String, String>()
        if (!token.isNullOrEmpty()) headers["Authorization"] = "Token $token"
        if (forAPKDownload) headers["Accept"] = "application/octet-stream"
        return headers.ifEmpty { null }
    }

    fun getTokenIfAny(additionalSettings: Map<String, Any?>): String? {
        var creds = getSourceConfigValues(additionalSettings)["github-creds"]
        if (!(additionalSettings["GHReqPrefix"] as? String).isNullOrEmpty()) creds = null
        if (creds == null) return null
        // Old inputs were "username:token".
        val userNameEnd = creds.indexOf(':')
        return if (userNameEnd > 0) creds.substring(userNameEnd + 1) else creds
    }

    override fun getSourceNote(): String? {
        if (!hostChanged && getTokenIfAny(emptyMap()) == null) {
            return "${Tr.get("githubSourceNote")} ${Tr.get("addInfoInSettings")}"
        }
        return null
    }

    override fun generalReqPrefetchModifier(reqUrl: String, additionalSettings: Map<String, Any?>): String {
        val prefix = additionalSettings["GHReqPrefix"] as? String
        if (!prefix.isNullOrEmpty()) return "https://$prefix/${reqUrl.substring("https://".length)}"
        return reqUrl
    }

    private fun apiHost(): String = "https://api.${hosts[0]}"

    private fun sourceRequestWithAuthFallback(url: String, additionalSettings: Map<String, Any?>): HttpResponse {
        val res = sourceRequest(url, additionalSettings)
        // A token that is not authorised for this repository must not block a public repo.
        if (isAuthRejection(res)) {
            return sourceRequest(url, LinkedHashMap(additionalSettings).also { it["skipAuth"] = true })
        }
        return res
    }

    fun convertStandardUrlToAPIUrl(standardUrl: String, additionalSettings: Map<String, Any?>): String =
        "${apiHost()}/repos${standardUrl.substring("https://${hosts[0]}".length)}"

    /** A redirect from the repository API means the repository was renamed or transferred. */
    private fun checkForRepositoryRename(
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        sourceConfig: Map<String, String>,
    ) {
        if (sourceConfig["checkRepoRename"] != "true") return
        val host = Url.tryParse(standardUrl)?.host ?: ""
        if (host != hosts[0] && host != "www.${hosts[0]}") return
        val res = sourceRequest(
            convertStandardUrlToAPIUrl(standardUrl, additionalSettings), additionalSettings, followRedirects = false,
        )
        if (res.statusCode !in 300..399) return
        val location = res.headers["location"] ?: return
        val res2 = sourceRequest(location, additionalSettings, followRedirects = false)
        val newUrl = try {
            JsonValues.parse(res2.body).dig("html_url") as? String
        } catch (e: Exception) {
            null
        }
        if (newUrl != null) throw RepositoryRenamedError(standardUrl, newUrl)
    }

    override fun changeLogPageFromStandardUrl(standardUrl: String): String = "$standardUrl/releases"

    private fun findReleaseAssetUrls(
        release: Release,
        includeZips: Boolean,
        includeTarballs: Boolean,
        sourceConfig: Map<String, String>,
    ): List<MutableMap<String, Any?>> = (release["assets"].asList() ?: emptyList()).map { raw ->
        @Suppress("UNCHECKED_CAST")
        val asset = raw as MutableMap<String, Any?>
        val name = asset["name"].toString()
        // APKs are fetched through the API asset URL, which also works for private repos.
        var url = if (!isApkOrContainerFile(name, includeZips, includeTarballs)) {
            asset["browser_download_url"] ?: asset["url"]
        } else {
            asset["url"] ?: asset["browser_download_url"]
        }
        url = (url as? String)?.let { undoGHProxyMod(it, sourceConfig) }
        asset["final_url"] = if (asset["name"] != null && url != null) NamedUrl(name, url) else NamedUrl("", "")
        asset
    }

    private fun publishDate(release: Release?): Instant? {
        (release?.get("published_at") as? String)?.let { return Dates.tryParse(it) }
        (release.dig("commit", "created") as? String)?.let { return Dates.tryParse(it) }
        return null
    }

    private fun newestAssetDate(release: Release): Instant? {
        val assets = release["filteredAssets"].asList() ?: release["assets"].asList()
        return assets?.mapNotNull { Dates.tryParse(it.dig("updated_at") as? String) }?.maxOrNull()
    }

    private fun releaseDate(release: Release, useAssetDate: Boolean): Instant? =
        if (!useAssetDate) publishDate(release) else newestAssetDate(release)

    private fun tagOf(release: Map<String, Any?>): Any? = release["tag_name"] ?: release["name"]

    private fun sortReleases(releases: MutableList<Release>, sortMethod: String, useAssetDate: Boolean) {
        if (sortMethod == "none") return
        val dateOnly = sortMethod == "date"
        val formats = IdentityHashMap<Release, Set<String>>()
        if (!dateOnly) {
            for (r in releases) formats[r] = findStandardFormatsForVersion(tagOf(r)?.toString() ?: "", strict = false)
        }
        fun dateCompare(a: Release, b: Release): Int =
            (releaseDate(a, useAssetDate) ?: Dates.epochZero).compareTo(releaseDate(b, useAssetDate) ?: Dates.epochZero)

        tolerantSort(releases) { a, b ->
            if (dateOnly) return@tolerantSort dateCompare(a, b)
            val nameA = tagOf(a)?.toString() ?: ""
            val nameB = tagOf(b)?.toString() ?: ""
            val stdFormats = formats[a]!!.intersect(formats[b]!!)
            if (sortMethod == "smartname-datefallback" && stdFormats.isEmpty()) return@tolerantSort dateCompare(a, b)
            if (sortMethod != "name" && stdFormats.isNotEmpty()) {
                val regex = Regex(stdFormats.sortedByDescending { it.length }.first())
                val matchA = regex.find(nameA)
                val matchB = regex.find(nameB)
                return@tolerantSort if (matchA == null || matchB == null) {
                    compareAlphaNumeric(nameA, nameB)
                } else {
                    compareAlphaNumeric(matchA.value, matchB.value)
                }
            }
            compareAlphaNumeric(nameA, nameB)
        }
    }

    /** Moves the release GitHub marks as "latest" to the newest position. */
    private fun positionLatestRelease(releases: MutableList<Release>, latestRelease: Release?) {
        if (latestRelease == null || tagOf(latestRelease) == null || releases.isEmpty() ||
            tagOf(latestRelease) == tagOf(releases.last())
        ) {
            return
        }
        val index = releases.indexOfFirst { tagOf(latestRelease) == tagOf(it) }
        if (index >= 0) releases.add(releases.removeAt(index))
    }

    private fun selectTargetRelease(
        releases: List<Release>,
        fallbackToOlderReleases: Boolean,
        includePrereleases: Boolean,
        regexFilter: String?,
        regexNotesFilter: String?,
        includeZips: Boolean,
        includeTarballs: Boolean,
        useAssetDate: Boolean,
        minAgeDays: Int,
        additionalSettings: Map<String, Any?>,
        sourceConfig: Map<String, String>,
    ): Release? {
        var releaseSkipped = 0
        val titleRegex = regexFilter?.let(::Regex)
        val notesRegex = regexNotesFilter?.let(::Regex)
        for (i in releases.indices) {
            val release = releases[i]
            if (!fallbackToOlderReleases && i > releaseSkipped) break
            if (!includePrereleases && release["prerelease"] == true) {
                releaseSkipped++
                continue
            }
            if (release["draft"] == true) {
                releaseSkipped++
                continue
            }
            var nameToFilter = release["name"] as? String
            if (nameToFilter.isNullOrBlank()) nameToFilter = release["tag_name"]?.toString() ?: ""
            if (titleRegex != null && !titleRegex.containsMatchIn(nameToFilter.trim())) continue
            if (notesRegex != null && !notesRegex.containsMatchIn(((release["body"] as? String) ?: "").trim())) {
                continue
            }
            if (isReleaseTooYoung(releaseDate(release, useAssetDate), minAgeDays)) {
                releaseSkipped++
                continue
            }
            val allAssets = findReleaseAssetUrls(release, includeZips, includeTarballs, sourceConfig)
            val allAssetUrls = allAssets.map { it["final_url"] as NamedUrl }.toMutableList()
            val apkAssets = allAssets.filter {
                isApkOrContainerFile((it["final_url"] as NamedUrl).name, includeZips, includeTarballs)
            }
            val filteredApkUrls = ApkFilter.filterApks(
                apkAssets.map { it["final_url"] as NamedUrl },
                additionalSettings["apkFilterRegEx"] as? String,
                additionalSettings["invertAPKFilter"] as? Boolean,
            )
            val filteredApks = apkAssets.filter { asset ->
                filteredApkUrls.any { it.name == (asset["final_url"] as NamedUrl).name }
            }
            if (filteredApks.isEmpty() && additionalSettings[SettingKeys.TRACK_ONLY] != true) continue

            release["apkUrls"] = filteredApkUrls
            release["filteredAssets"] = filteredApks
            release["version"] =
                if (additionalSettings["releaseTitleAsVersion"] == true) nameToFilter else tagOf(release)
            val versionLabel = release["version"]?.toString() ?: "source"
            (release["tarball_url"] as? String)?.let {
                allAssetUrls.add(NamedUrl("$versionLabel.tar.gz", undoGHProxyMod(it, sourceConfig)))
            }
            (release["zipball_url"] as? String)?.let {
                allAssetUrls.add(NamedUrl("$versionLabel.zip", undoGHProxyMod(it, sourceConfig)))
            }
            release["allAssetUrls"] = allAssetUrls
            return release
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchReleaseDetails(
        requestUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        onHttpErrorCode: ((HttpResponse) -> Unit)?,
    ): ApkDetails {
        val sourceConfig = getSourceConfigValues(additionalSettings)
        checkForRepositoryRename(standardUrl, additionalSettings, sourceConfig)
        val includePrereleases = additionalSettings[SettingKeys.INCLUDE_PRERELEASES] == true
        val fallbackToOlderReleases = additionalSettings["fallbackToOlderReleases"] == true
        val regexFilter = (additionalSettings["filterReleaseTitlesByRegEx"] as? String)?.takeIf { it.isNotEmpty() }
        val regexNotesFilter = (additionalSettings["filterReleaseNotesByRegEx"] as? String)?.takeIf { it.isNotEmpty() }
        val verifyLatestTag = additionalSettings["verifyLatestTag"] == true
        val useAssetDate = additionalSettings["useLatestAssetDateAsReleaseDate"] == true
        val sortMethod = (additionalSettings["sortMethodChoice"] as? String) ?: "smartname-datefallback"
        val includeZips = additionalSettings["includeZips"] == true
        val includeTarballs = additionalSettings["includeTarballs"] == true
        val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)

        var latestRelease: Release? = null
        if (verifyLatestTag) {
            val uri = Url.parse(requestUrl)
            val res = sourceRequestWithAuthFallback(
                uri.withQuery(null).withPath("${uri.path}/latest").toString(), additionalSettings,
            )
            if (res.statusCode != 200) {
                onHttpErrorCode?.invoke(res)
                throw Http.errorFor(res)
            }
            latestRelease = JsonValues.parse(res.body) as? Release
        }
        val res = sourceRequestWithAuthFallback(requestUrl, additionalSettings)
        if (res.statusCode != 200) {
            onHttpErrorCode?.invoke(res)
            throw Http.errorFor(res)
        }
        val decoded = JsonValues.parse(res.body) as? List<Any?> ?: throw NoReleasesError()
        var releases = decoded.filterIsInstance<Map<String, Any?>>().map { it as Release }.toMutableList()
        if (latestRelease != null) {
            val latestTag = tagOf(latestRelease)
            if (releases.none { tagOf(it) == latestTag }) releases.add(0, latestRelease)
        }
        if (sortMethod == "none") releases.reverse() else sortReleases(releases, sortMethod, useAssetDate)
        positionLatestRelease(releases, latestRelease)
        releases = releases.asReversed().toMutableList()

        // Prefer a release that is old enough; otherwise take the newest so that the caller can
        // hold it back until it has aged.
        var target: Release? = null
        for (age in if (minAgeDays > 0) listOf(minAgeDays, 0) else listOf(0)) {
            target = selectTargetRelease(
                releases, fallbackToOlderReleases, includePrereleases, regexFilter, regexNotesFilter,
                includeZips, includeTarballs, useAssetDate, age, additionalSettings, sourceConfig,
            )
            if (target != null) break
        }
        if (target == null) throw NoReleasesError()
        val version = target["version"]?.toString()
        if (version.isNullOrEmpty()) throw NoVersionError()
        val changeLog = (target["body"] ?: "").toString()
        return ApkDetails(
            version,
            target["apkUrls"] as List<NamedUrl>,
            getAppNames(standardUrl),
            releaseDate = releaseDate(target, useAssetDate),
            changeLog = changeLog.ifEmpty { null },
            allAssetUrls = target["allAssetUrls"] as List<NamedUrl>,
        )
    }

    /** Falls back to the tag list when a track-only app has no releases. */
    fun fetchReleaseDetailsWithTagFallback(
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        reqUrlGenerator: (useTagUrl: Boolean) -> String,
        onHttpErrorCode: ((HttpResponse) -> Unit)?,
    ): ApkDetails = try {
        fetchReleaseDetails(reqUrlGenerator(false), standardUrl, additionalSettings, onHttpErrorCode)
    } catch (e: Throwable) {
        if (e is NoReleasesError && additionalSettings[SettingKeys.TRACK_ONLY] == true) {
            fetchReleaseDetails(reqUrlGenerator(true), standardUrl, additionalSettings, onHttpErrorCode)
        } else {
            rethrowOrWrap(e)
        }
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        fetchReleaseDetailsWithTagFallback(
            standardUrl,
            additionalSettings,
            { useTagUrl ->
                "${convertStandardUrlToAPIUrl(standardUrl, additionalSettings)}/" +
                    "${if (useTagUrl) "tags" else "releases"}?per_page=100"
            },
            ::githubErrorCheck,
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    /** Owner and repository (everything after the owner) from a standard URL. */
    fun getAppNames(standardUrl: String): AppNames {
        val afterScheme = standardUrl.substring(standardUrl.indexOf("://") + 3)
        val pathStart = afterScheme.indexOf('/')
        if (pathStart < 0) throw InvalidUrlError(name)
        val names = afterScheme.substring(pathStart + 1).split('/')
        if (names.isEmpty() || names[0].isEmpty()) throw InvalidUrlError(name)
        return AppNames(names[0], names.drop(1).joinToString("/"))
    }

    /** Shared by GitHub and Forgejo searches: both answer with a list of repositories. */
    fun searchCommon(
        requestUrl: String,
        rootProp: String,
        onHttpErrorCode: ((HttpResponse) -> Unit)? = null,
        querySettings: Map<String, Any?> = emptyMap(),
        additionalSettings: Map<String, Any?> = emptyMap(),
    ): Map<String, List<String>> {
        val res = sourceRequest(requestUrl, additionalSettings)
        if (res.statusCode != 200) {
            onHttpErrorCode?.invoke(res)
            throw Http.errorFor(res)
        }
        val minStarCount = querySettings["minStarCount"]?.toString()?.toIntOrNull() ?: 0
        val results = LinkedHashMap<String, List<String>>()
        for (entry in JsonValues.parse(res.body).dig(rootProp).asList() ?: emptyList()) {
            val repo = entry.asMap() ?: continue
            val stars = ((repo["stargazers_count"] ?: repo["stars_count"]) as? Number)?.toInt() ?: 0
            if (stars < minStarCount) continue
            results[repo["html_url"] as String] = listOf(
                repo["full_name"] as String,
                (if (repo["archived"] == true) "[ARCHIVED] " else "") +
                    ((repo["description"] as? String) ?: Tr.get("noDescription")),
            )
        }
        return results
    }

    fun undoGHProxyMod(reqUrl: String, sourceConfig: Map<String, String>): String {
        val prefix = sourceConfig["GHReqPrefix"]
        if (prefix.isNullOrEmpty()) return reqUrl
        return reqUrl.removePrefix("https://$prefix/")
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val sourceConfig = getSourceConfigValues(emptyMap())
        val results = searchCommon(
            "${apiHost()}/search/repositories?q=${Url.encodeQueryComponent(query)}&per_page=100",
            "items",
            onHttpErrorCode = ::githubErrorCheck,
            querySettings = querySettings,
        )
        if ((sourceConfig["GHReqPrefix"] ?: "").isEmpty()) return results
        return results.mapKeys { undoGHProxyMod(it.key, sourceConfig) }
    }

    fun rateLimitErrorCheck(res: HttpResponse) {
        if (res.headers["x-ratelimit-remaining"] != "0") return
        val nowSeconds = System.currentTimeMillis() / 1000
        val resetSeconds = res.headers["x-ratelimit-reset"]?.toLongOrNull() ?: (nowSeconds + FALLBACK_CACHE_SECONDS)
        throw RateLimitError(ceil((resetSeconds - nowSeconds) / 60.0).toInt().coerceIn(0, 9999))
    }

    /**
     * Raises the real GitHub error of a failed API call: a rate limit when the headers say so,
     * or the API's own message for an authorisation problem.
     */
    fun githubErrorCheck(res: HttpResponse) {
        rateLimitErrorCheck(res)
        if (res.statusCode == 401 || res.statusCode == 403) {
            val message = try {
                (JsonValues.parse(res.body).dig("message") as? String)?.trim()
            } catch (e: Exception) {
                null
            }
            if (!message.isNullOrEmpty() && !message.lowercase().contains("rate limit")) throw SourceError(message)
        }
    }

    companion object {
        private const val FALLBACK_CACHE_SECONDS = 3600

        /** A 401/403 whose body blames the configured token. */
        private fun isAuthRejection(res: HttpResponse): Boolean {
            if (res.statusCode != 401 && res.statusCode != 403) return false
            return try {
                val message = ((JsonValues.parse(res.body).dig("message") as? String) ?: "").lowercase()
                message.contains("access token") || message.contains("bad credentials")
            } catch (e: Exception) {
                false
            }
        }
    }
}
