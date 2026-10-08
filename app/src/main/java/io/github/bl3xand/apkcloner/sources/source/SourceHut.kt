package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

class SourceHut : AppSource("SourceHut") {
    init {
        fixedName = "SourceHut"
        hosts = listOf("git.sr.ht")
        changeLogPageIsStandardUrl = true
        showReleaseDateAsVersionToggle = true
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(fallbackToOlderReleasesFormItem())

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/[^/]+/[^/]+")

    override fun getLatestAPKDetails(standardUrlIn: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val standardUrl = standardUrlIn.removeSuffix("/refs")
            val standardUri = Url.parse(standardUrl)
            val appName = standardUri.pathSegments.last()
            val fallbackToOlderReleases = additionalSettings["fallbackToOlderReleases"] == true
            val res = sourceRequest("$standardUrl/refs/rss.xml", additionalSettings)
            if (res.statusCode != 200) throw Http.errorFor(res)
            var details = mutableListOf<ApkDetails>()
            val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)
            var index = 0
            var releaseSkipped = 0
            for (entry in Jsoup.parse(res.body).select("item").take(6)) {
                index++
                val releasePage = entry.selectFirst("guid")?.html()?.trim() ?: ""
                if (!releasePage.startsWith("$standardUrl/refs")) continue
                if (!fallbackToOlderReleases && index > releaseSkipped + 1) break
                val version = entry.selectFirst("title")?.text()?.trim()
                if (version.isNullOrEmpty()) throw NoVersionError()
                val releaseDate = Dates.tryParseRfc1123(entry.selectFirst("pubDate")?.html())
                if (isReleaseTooYoung(releaseDate, minAgeDays)) releaseSkipped++
                val res2 = sourceRequest(releasePage, additionalSettings)
                val apkUrls = if (res2.statusCode == 200) {
                    ApkFilter.apkUrlsFromUrls(
                        Jsoup.parse(res2.body).select("a").map { it.attr("href") }
                            .filter { isApkOrContainerFile(it) }
                            .map { Url.ensureAbsolute(it, standardUri) },
                    )
                } else emptyList()
                details.add(
                    ApkDetails(
                        version, apkUrls,
                        AppNames(entry.selectFirst("author")?.html()?.trim() ?: appName, appName),
                        releaseDate = releaseDate,
                    ),
                )
            }
            if (details.isEmpty()) throw NoReleasesError()
            if (minAgeDays > 0) {
                val eligible = details.filter { !isReleaseTooYoung(it.releaseDate, minAgeDays) }
                if (eligible.isNotEmpty()) details = eligible.toMutableList()
            }
            if (fallbackToOlderReleases) {
                if (additionalSettings[SettingKeys.TRACK_ONLY] != true) {
                    details = details.filter { it.apkUrls.isNotEmpty() }.toMutableList()
                }
                if (details.isEmpty()) throw NoReleasesError()
            }
            return details.first()
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }
}
