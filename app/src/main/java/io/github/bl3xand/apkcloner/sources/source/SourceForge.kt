package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.extractVersion
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.sourceRegex
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

/** The newest files of a SourceForge project, from its RSS feed. */
class SourceForge : AppSource("SourceForge") {
    init {
        fixedName = "SourceForge"
        suppressStandardVersionExtraction = true
        hosts = listOf("sourceforge.net")
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        var result = url
        val hostsRegex = sourceRegex(hosts)
        // The short "/p/<project>" form.
        Regex("^https?://(www\\.)?$hostsRegex/p/.+", RegexOption.IGNORE_CASE).find(result)?.let { match ->
            val parsed = Url.parse(match.value)
            result = "https://${parsed.host}/projects/${parsed.pathSegments.getOrElse(1) { "" }}"
        }
        val project = Regex("^https?://(www\\.)?$hostsRegex/projects/[^/]+", RegexOption.IGNORE_CASE).find(result)
        if (project != null && project.value == result) result = "$result/files"
        return Regex("^https?://(www\\.)?$hostsRegex/projects/[^/]+/files(/.+)?", RegexOption.IGNORE_CASE)
            .find(result)?.value ?: throw InvalidUrlError(name)
    }

    override fun getLatestAPKDetails(standardUrlIn: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            var standardUrl = standardUrlIn
            var standardUri = Url.parse(standardUrl)
            if (standardUri.pathSegments.size == 2) {
                standardUrl = "$standardUrl/files"
                standardUri = Url.parse(standardUrl)
            }
            val res = sourceRequest(
                "${standardUri.origin}/${standardUri.pathSegments.take(2).joinToString("/")}/rss?path=/",
                additionalSettings,
            )
            if (res.statusCode != 200) throw Http.errorFor(res)
            val downloadLinks = Jsoup.parse(res.body).select("guid").map { it.html() }
                .filter { it.startsWith(standardUrl) }

            // The version is the folder path: without the file name and, when deeper, one folder.
            fun versionOf(url: String): String? {
                val segments = url.substring(standardUrl.length).split('/').filter { it.isNotEmpty() }.toMutableList()
                if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                if (segments.size > 1) segments.removeAt(segments.size - 1)
                val version = segments.takeIf { it.isNotEmpty() }?.joinToString("/") ?: return null
                return try {
                    extractVersion(
                        additionalSettings["versionExtractionRegEx"] as? String,
                        additionalSettings["matchGroupToUse"] as? String,
                        version,
                    ) ?: version
                } catch (e: NoVersionError) {
                    null
                }
            }

            val releases = downloadLinks
                .filter {
                    val lower = it.lowercase()
                    lower.endsWith("/download") && isApkOrContainerFile(lower.removeSuffix("/download"))
                }
                .mapNotNull { link -> versionOf(link)?.let { link to it } }
            if (releases.isEmpty()) throw NoReleasesError()
            val version = releases.first().second
            if (version.isEmpty()) throw NoVersionError()
            val segments = standardUrl.split('/')
            return ApkDetails(
                version,
                ApkFilter.apkUrlsFromUrls(releases.filter { it.second == version }.map { it.first }),
                AppNames(name, segments[segments.indexOf("files") - 1]),
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }
}
