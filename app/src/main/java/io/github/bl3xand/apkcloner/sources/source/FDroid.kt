package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.notForDevice
import io.github.bl3xand.apkcloner.sources.core.androidSdkOf
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.sourceRegex
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import org.jsoup.Jsoup

class FDroid : AppSource("FDroid") {
    override val name: String get() = Tr.get("fdroid")
    override val shortName: String get() = "F-Droid"

    init {
        hosts = listOf("f-droid.org")
        naiveStandardVersionDetection = true
        canSearch = true
        answersForDevice = true
        inferAppIdFromUrlPath = true
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(
                TextItem(
                    "filterVersionsByRegEx", "filterVersionsByRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(SwitchItem("trySelectingSuggestedVersionCode", "trySelectingSuggestedVersionCode", value = true)),
            listOf(SwitchItem("autoSelectHighestVersionCode", "autoSelectHighestVersionCode")),
        )

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        var result = url
        // A localised page ("/en/packages/<id>") is the same package.
        val localised = Regex(
            "^https?://(www\\.)?${sourceRegex(hosts)}/+[^/]+/+packages/+[^/]+", RegexOption.IGNORE_CASE,
        ).find(result)
        if (localised != null) {
            result = "https://${Url.parse(localised.value).host}/packages/" +
                Url.parse(result).pathSegments.last { it.trim().isNotEmpty() }
        }
        return Regex("^https?://(www\\.)?${sourceRegex(hosts)}/+packages/+[^/]+", RegexOption.IGNORE_CASE)
            .find(result)?.value ?: throw InvalidUrlError(name)
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val appId = tryInferringAppId(standardUrl) ?: throw NoReleasesError()
            val host = Url.parse(standardUrl).host
            var details = getAPKUrlsFromFDroidPackagesAPIResponse(
                sourceRequest("https://$host/api/v1/packages/$appId", additionalSettings),
                "https://$host/repo/$appId",
                standardUrl,
                name,
                additionalSettings,
            )
            if (!hostChanged) {
                details = fittingThisDevice(details, host, appId, additionalSettings)
                try {
                    val lines = sourceRequest("$FDROID_DATA_BASE_URL/$appId.yml", additionalSettings).body.split('\n')
                    // The API knows the package only; what the app is called is in its metadata.
                    (lines.firstOrNull { it.startsWith("Name: ") } ?: lines.firstOrNull { it.startsWith("AutoName: ") })
                        ?.substringAfter(": ")?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }
                        ?.let { details = details.copy(names = details.names.copy(name = it)) }
                    lines.firstOrNull { it.startsWith("AuthorName: ") }?.let { line ->
                        details = details.copy(
                            names = details.names.copy(author = line.split(": ").drop(1).joinToString(": ")),
                        )
                    }
                    val changelogUrl = lines.firstOrNull { it.startsWith("Changelog: ") }
                        ?.split(' ')?.drop(1)?.joinToString(" ")
                    if (changelogUrl != null) {
                        details = details.copy(changeLog = changelogUrl)
                        val isGitHub = matches { GitHub(hostChanged = true).sourceSpecificStandardizeURL(changelogUrl) }
                        val isGitLab = matches { GitLab(hostChanged = true).sourceSpecificStandardizeURL(changelogUrl) }
                        // A file in a repository: fetch its text instead of showing the link.
                        if ((isGitHub || isGitLab) && changelogUrl.contains("/blob/")) {
                            details = details.copy(
                                changeLog = sourceRequest(
                                    changelogUrl.replaceFirst("/blob/", "/raw/"), additionalSettings,
                                ).body,
                            )
                        }
                    }
                } catch (_: Exception) {
                    // The metadata is a bonus; the release itself is already known.
                }
                val changeLog = details.changeLog
                if (changeLog != null && changeLog.length > MAX_CHANGE_LOG_CODE_UNITS) {
                    var end = MAX_CHANGE_LOG_CODE_UNITS
                    if (Character.isHighSurrogate(changeLog[end - 1])) end--
                    details = details.copy(changeLog = "${changeLog.substring(0, end)}...")
                }
            }
            return details
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    /** A version as the package page lists it. */
    private class Listed(val name: String, val code: String, val minSdk: Int?, val processors: List<String>) {
        fun fits(): Boolean = (minSdk == null || minSdk <= SourceEnv.platform.sdkInt) &&
            (processors.isEmpty() || processors.any { it in SourceEnv.platform.supportedAbis })
    }

    /**
     * The API names versions and nothing else. The package's page says what each needs - the
     * lowest Android, the processors it is built for - so when the chosen version is not for
     * this device, the newest one that is takes its place. A page that cannot be read changes
     * nothing: the choice stands as it was.
     */
    private fun fittingThisDevice(details: ApkDetails, host: String, appId: String, settings: Map<String, Any?>): ApkDetails {
        val listed = try {
            Jsoup.parse(sourceRequest("https://$host/en/packages/$appId/", settings).body).select("li.package-version").mapNotNull { item ->
                val header = item.selectFirst(".package-version-header") ?: return@mapNotNull null
                val name = header.selectFirst("b")?.text()?.removePrefix("Version ")?.trim() ?: return@mapNotNull null
                val code = VERSION_CODE.find(header.ownText())?.groupValues?.get(1) ?: return@mapNotNull null
                val needed = REQUIREMENT.find(item.selectFirst(".package-version-requirement")?.text().orEmpty())?.groupValues?.get(1)
                Listed(name, code, needed?.let(::androidSdkOf), item.select(".package-nativecode").map { it.text().trim() })
            }
        } catch (e: Exception) {
            return details
        }
        val chosen = listed.firstOrNull { it.name == details.version } ?: return details
        if (chosen.fits()) return details
        // Newest first, as the page has them.
        val fitting = listed.firstOrNull { it.fits() } ?: throw notForDevice(name)
        return details.copy(
            version = fitting.name,
            apkUrls = ApkFilter.apkUrlsFromUrls(listOf("https://$host/repo/${appId}_${fitting.code}.apk")),
        )
    }

    private inline fun matches(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: InvalidUrlError) {
        false
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val res = sourceRequest("https://search.${hosts[0]}/?q=${Url.encodeQueryComponent(query)}", emptyMap())
        if (res.statusCode != 200) throw Http.errorFor(res)
        val results = LinkedHashMap<String, List<String>>()
        for (element in Jsoup.parse(res.body).select(".package-header")) {
            // The canonical form, so results match stored app URLs.
            val url = try {
                standardizeUrl(element.attr("href").ifEmpty { continue })
            } catch (e: Exception) {
                continue
            }
            results[url] = listOf(
                element.selectFirst(".package-name")?.text()?.trim() ?: "",
                element.selectFirst(".package-summary")?.text()?.trim() ?: Tr.get("noDescription"),
            )
        }
        return results
    }

    /** Picks the release from an F-Droid "packages" API answer (also used by IzzyOnDroid). */
    fun getAPKUrlsFromFDroidPackagesAPIResponse(
        res: HttpResponse,
        apkUrlPrefix: String,
        standardUrl: String,
        sourceName: String,
        additionalSettings: Map<String, Any?> = emptyMap(),
    ): ApkDetails {
        val autoSelectHighestVersionCode = additionalSettings["autoSelectHighestVersionCode"] == true
        val trySelectingSuggestedVersionCode = additionalSettings["trySelectingSuggestedVersionCode"] == true
        val filterVersionsByRegEx = (additionalSettings["filterVersionsByRegEx"] as? String)?.takeIf { it.isNotEmpty() }
        val apkFilterRegEx = (additionalSettings["apkFilterRegEx"] as? String)?.takeIf { it.isNotEmpty() }
        if (res.statusCode != 200) throw Http.errorFor(res)
        val response = JsonValues.parse(res.body).asMap()
        var releases = (response?.get("packages").asList() ?: emptyList()).mapNotNull { it.asMap() }
        if (apkFilterRegEx != null) {
            releases = releases.filter { release ->
                val apk = "${apkUrlPrefix}_${numberText(release["versionCode"])}.apk"
                ApkFilter.filterApks(listOf(NamedUrl(apk, apk)), apkFilterRegEx, false).isNotEmpty()
            }
        }
        if (releases.isEmpty()) throw NoReleasesError()
        val suggested = response?.get("suggestedVersionCode")
        var version: String? = null
        var choices: List<Map<String, Any?>> = emptyList()
        // The suggested version code is only used at this stage when no version filter is set.
        if (trySelectingSuggestedVersionCode && suggested != null && filterVersionsByRegEx == null) {
            val suggestedReleases = releases.filter { sameNumber(it["versionCode"], suggested) }
            if (suggestedReleases.isNotEmpty()) {
                choices = suggestedReleases
                version = suggestedReleases.first()["versionName"] as? String
            }
        }
        if (filterVersionsByRegEx != null) {
            val versionFilter = Regex(filterVersionsByRegEx)
            choices = emptyList()
            version = releases.firstOrNull { versionFilter.containsMatchIn((it["versionName"] as? String) ?: "") }
                ?.get("versionName") as? String
            if (version.isNullOrEmpty()) throw NoVersionError()
        }
        // Default to the newest version.
        if (version == null) version = releases[0]["versionName"] as? String
        if (version.isNullOrEmpty()) throw NoVersionError()
        if (choices.isEmpty()) choices = releases.filter { it["versionName"] == version }
        if (choices.size > 1) {
            if (autoSelectHighestVersionCode) {
                choices = listOf(choices.first())
            } else if (trySelectingSuggestedVersionCode && suggested != null) {
                val suggestedReleases = choices.filter { sameNumber(it["versionCode"], suggested) }
                if (suggestedReleases.isNotEmpty()) choices = suggestedReleases
            }
        }
        if (choices.isEmpty()) throw NoReleasesError()
        if (additionalSettings[SettingKeys.VERSION_CODE_AS_OS_VERSION] == true) {
            version = choices.first()["versionCode"]?.let(::numberText) ?: version
        }
        val apkUrls = choices.map { "${apkUrlPrefix}_${numberText(it["versionCode"])}.apk" }.distinct()
        return ApkDetails(
            version!!,
            ApkFilter.apkUrlsFromUrls(apkUrls),
            AppNames(sourceName, Url.parse(standardUrl).pathSegments.last()),
        )
    }

    companion object {
        private val VERSION_CODE = Regex("\\((\\d+)\\)")
        private val REQUIREMENT = Regex("Android ([0-9][0-9.]*L?)", RegexOption.IGNORE_CASE)
        private const val MAX_CHANGE_LOG_CODE_UNITS = 2048
        private const val FDROID_DATA_BASE_URL = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata"

        /** Version codes arrive as JSON numbers; print them without a fraction. */
        fun numberText(value: Any?): String = when (value) {
            is Number -> value.toLong().toString()
            else -> value.toString()
        }

        private fun sameNumber(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toLong() == b.toLong() else a == b
    }
}
