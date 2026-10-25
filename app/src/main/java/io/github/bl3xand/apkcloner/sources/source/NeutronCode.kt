package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

class NeutronCode : AppSource("NeutronCode") {
    init {
        fixedName = "NeutronCode"
        hosts = listOf("neutroncode.com")
        showReleaseDateAsVersionToggle = true
        changeLogPageIsStandardUrl = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/downloads/file/[^/]+")

    /** "12 March 2024" in any order of its three parts, as yyyy-MM-dd. */
    private fun formatDateForParsing(dateString: String): String? {
        val parts = dateString.split(' ')
        if (parts.size != 3) return null
        val monthIndex = parts.indexOfFirst { it.toIntOrNull() == null }
        if (monthIndex < 0) return null
        val month = months[parts[monthIndex].lowercase()] ?: return null
        val numbers = parts.filterIndexed { index, _ -> index != monthIndex }.map { it.toIntOrNull() ?: return null }
        val (a, b) = numbers
        val year = if (a > 31) a else if (b > 31) b else if (a.toString().length == 4) a else b
        val day = if (a == year) b else a
        return "$year-$month-${day.toString().padStart(2, '0')}"
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val res = sourceRequest(standardUrl, additionalSettings)
        if (res.statusCode != 200) throw Http.errorFor(res)
        val html = Jsoup.parse(res.body)
        val fileName = html.selectFirst(".pd-filename .pd-float")?.html()?.trim() ?: throw NoReleasesError()
        val version = html.selectFirst(".pd-version-txt")?.nextElementSibling()?.html()
        if (version.isNullOrEmpty()) throw NoVersionError()
        val date = html.selectFirst(".pd-date-txt")?.nextElementSibling()?.html()?.let(::formatDateForParsing)
        ApkDetails(
            version,
            ApkFilter.apkUrlsFromUrls(listOf("https://${hosts[0]}/download/$fileName")),
            AppNames(shortName, html.selectFirst(".pd-title")?.html() ?: standardUrl.split('/').last()),
            releaseDate = Dates.tryParse(date),
            changeLog = html.select(".pd-fdesc p").lastOrNull()?.html(),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    companion object {
        private val months = listOf(
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december",
        ).mapIndexed { index, month -> month to (index + 1).toString().padStart(2, '0') }.toMap()
    }
}
