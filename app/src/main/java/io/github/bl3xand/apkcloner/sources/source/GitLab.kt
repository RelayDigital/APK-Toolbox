package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.sourceRegex
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

class GitLab(hostChanged: Boolean = false) : AppSource("GitLab") {
    // Name parsing is the same as GitHub's.
    private val gh = GitHub(hostChanged = true)

    init {
        fixedName = "GitLab"
        hosts = listOf("gitlab.com")
        canSearch = true
        showReleaseDateAsVersionToggle = true
        this.hostChanged = hostChanged
    }

    override val sourceConfigSettingFormItems: List<SettingItem>
        get() = listOf(
            TextItem(
                "gitlab-creds", "gitlabPATLabel", password = true, required = false,
                helpUrl = "https://docs.gitlab.com/user/profile/personal_access_tokens/#create-a-personal-access-token",
            ),
        )

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(fallbackToOlderReleasesFormItem())

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        // Everything from the "/-/" marker on is a page inside the project.
        val segments = url.split('/')
        val cutOff = segments.indexOf("-")
        val projectUrl = (if (cutOff <= 0) segments else segments.subList(0, cutOff)).joinToString("/")
        val regex = Regex("^https?://(www\\.)?${sourceRegex(hosts)}/[^/]+(/[^/]+){1,20}", RegexOption.IGNORE_CASE)
        return regex.find(projectUrl)?.value ?: throw InvalidUrlError(name)
    }

    fun getPATIfAny(additionalSettings: Map<String, Any?>): String? =
        getSourceConfigValues(additionalSettings)["gitlab-creds"]?.takeIf { it.isNotEmpty() }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest(
            "https://${hosts[0]}/api/v4/projects?search=${Url.encodeQueryComponent(query)}", emptyMap(),
        )
        Http.ensureSuccess(res)
        val results = LinkedHashMap<String, List<String>>()
        for (element in JsonValues.parse(res.body).asList() ?: emptyList()) {
            results["https://${hosts[0]}/${element.dig("path_with_namespace")}"] = listOf(
                (element.dig("name_with_namespace") ?: element.dig("path_with_namespace") ?: "").toString(),
                (element.dig("description") as? String) ?: Tr.get("noDescription"),
            )
        }
        return results
    }

    override fun changeLogPageFromStandardUrl(standardUrl: String): String = "$standardUrl/-/releases"

    // Accepted by, for example, Cloudflare protection.
    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> = mapOf("Referer" to "https://${hosts[0]}")

    override fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String {
        val pat = getPATIfAny(if (hostChanged) additionalSettings else emptyMap()) ?: return assetUrl
        return "$assetUrl${if (Url.parse(assetUrl).query.isNullOrEmpty()) "?" else "&"}private_token=$pat"
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val names = gh.getAppNames(standardUrl)
            val project = "${Url.encodeComponent(names.author)}%2F${Url.encodeComponent(names.name)}"
            val pat = getPATIfAny(if (hostChanged) additionalSettings else emptyMap())
            val optionalAuth = if (pat != null) "private_token=$pat" else ""
            val trackOnly = additionalSettings[SettingKeys.TRACK_ONLY] == true

            val res0 = sourceRequest("https://${hosts[0]}/api/v4/projects/$project?$optionalAuth", additionalSettings)
            Http.ensureSuccess(res0)
            val projectId = (JsonValues.parse(res0.body).dig("id") as? Number)?.toLong() ?: throw NoReleasesError()

            val releasesPath = if (trackOnly) "repository/tags" else "releases"
            val query = listOfNotNull(optionalAuth.takeIf { it.isNotEmpty() }, "per_page=100").joinToString("&")
            val res = sourceRequest(
                "https://${hosts[0]}/api/v4/projects/$project/$releasesPath?$query", additionalSettings,
            )
            Http.ensureSuccess(res)
            val json = JsonValues.parse(res.body).asList() ?: throw NoReleasesError()

            var details = json.mapNotNull { e ->
                val fromAssets = (e.dig("assets", "links").asList() ?: emptyList())
                    .mapNotNull { it.asMap() }
                    .filter {
                        isApkAsset(
                            (it["name"] as? String) ?: "",
                            ((it["direct_asset_url"] ?: it["url"] ?: "") as String),
                            it["link_type"] as? String,
                        )
                    }
                    .map {
                        val url = (it["direct_asset_url"] ?: it["url"] ?: "") as String
                        val segments = if (url.isNotEmpty()) Url.parse(url).pathSegments else emptyList()
                        NamedUrl((it["name"] as? String) ?: segments.lastOrNull() ?: "unknown", url)
                    }
                    .filter { it.name.isNotEmpty() }
                // Files uploaded into the release notes are plain markdown links.
                val fromDescription = ((e.dig("description") ?: "") as String)
                    .split("](").joinToString("\n")
                    .split(".apk)").joinToString(".apk\n")
                    .split(".xapk)").joinToString(".xapk\n")
                    .split(".apkm)").joinToString(".apkm\n")
                    .split(".apks)").joinToString(".apks\n")
                    .split('\n')
                    .filter { it.startsWith("/uploads/") && isApkOrContainerFile(it) }
                    .map { "https://${hosts[0]}/-/project/$projectId$it" }
                    .map { NamedUrl(Url.parse(it).pathSegments.last(), it) }
                val apkUrls = LinkedHashMap<String, String>()
                for (entry in fromAssets + fromDescription) apkUrls[entry.name] = entry.url
                val version = (e.dig("tag_name") ?: e.dig("name"))?.toString() ?: return@mapNotNull null
                val dateString = e.dig("released_at") ?: e.dig("created_at") ?: e.dig("commit", "created_at")
                ApkDetails(
                    version,
                    apkUrls.map { NamedUrl(it.key, it.value) },
                    AppNames(names.author, names.name.split('/').last()),
                    releaseDate = Dates.tryParse(dateString?.toString()),
                )
            }
            if (details.isEmpty()) throw NoReleasesError()
            // Prefer releases old enough for the minimum update age when there are any.
            val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)
            if (minAgeDays > 0) {
                val eligible = details.filter { !isReleaseTooYoung(it.releaseDate, minAgeDays) }
                if (eligible.isNotEmpty()) details = eligible
            }
            var result = details.first()
            val fallbackToOlderReleases = additionalSettings["fallbackToOlderReleases"] == true
            if (result.apkUrls.isEmpty() && fallbackToOlderReleases && !trackOnly) {
                details = details.filter { it.apkUrls.isNotEmpty() }
                if (details.isEmpty()) throw NoReleasesError()
                result = details.first()
            }
            if (result.apkUrls.isEmpty() && !trackOnly) throw NoApkError()

            // The "file" page of a job artifact is HTML; "raw" is the file itself.
            val jobArtifact = Regex("^${Regex.escape(standardUrl)}/-/jobs/[0-9]+/artifacts/file/[^/]+")
            return result.copy(
                apkUrls = result.apkUrls.map {
                    if (jobArtifact.containsMatchIn(it.url)) it.copy(url = it.url.replaceFirst("/file/", "/raw/")) else it
                },
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    companion object {
        private val apkToken = Regex("(^|[^a-z])apk([^a-z]|$)", RegexOption.IGNORE_CASE)

        /**
         * Whether a release link is an installable package. Package links do not always carry a
         * file extension, so a package whose name or URL mentions "apk" as a word counts too.
         */
        fun isApkAsset(name: String, url: String, linkType: String?): Boolean {
            if (isApkOrContainerFile(name) || isApkOrContainerFile(url)) return true
            if (linkType != "package") return false
            return apkToken.containsMatchIn(name) || apkToken.containsMatchIn(url)
        }
    }
}
