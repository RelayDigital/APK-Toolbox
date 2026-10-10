package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.Dates
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import java.time.Instant
import kotlin.math.roundToLong
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Track-only: the site's maintainers do not allow direct downloads by third-party apps. */
class APKMirror : AppSource("APKMirror") {
    init {
        fixedName = "APKMirror"
        hosts = listOf("apkmirror.com")
        enforceTrackOnly = true
        naiveStandardVersionDetection = true
        showReleaseDateAsVersionToggle = true
        inferAppIdEvenWhenTrackOnly = true
        changeLogIfAnyIsMarkDown = false
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            fallbackToOlderReleasesFormItem(),
            listOf(
                TextItem(
                    "filterReleaseTitlesByRegEx", "filterReleaseTitlesByRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
        )

    // The site only serves HTML pages to a User-Agent carrying this allow-listed token; our own
    // name follows it so the traffic can still be attributed.
    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> =
        mapOf("User-Agent" to "$ALLOWLISTED_USER_AGENT_TOKEN ApkToolbox/${SourceEnv.platform.appVersionName}")

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/apk/[^/]+/[^/]+")

    override fun changeLogPageFromStandardUrl(standardUrl: String): String = "$standardUrl/#whatsnew"

    override fun tryInferringAppId(standardUrl: String, additionalSettings: Map<String, Any?>): String? = try {
        val res = sourceRequest(standardUrl, additionalSettings)
        val fromPage = if (res.statusCode != 200) {
            null
        } else {
            val doc = Jsoup.parse(res.body)
            apkMirrorPackageFromIconUrl(
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }
                    ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            )
        }
        // The page is often kept behind a check for browsers; the feed of releases is not, and
        // its pictures are named after the package as well.
        fromPage ?: sourceRequest("$standardUrl/feed/", additionalSettings).takeIf { it.statusCode == 200 }?.let { feed ->
            ICON_URL.findAll(feed.body).firstNotNullOfOrNull { apkMirrorPackageFromIconUrl(it.value) }
        }
    } catch (e: Exception) {
        null
    }

    override fun resolveDownloadSize(
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
        releaseUrl: String?,
    ): Long? {
        if (releaseUrl.isNullOrEmpty()) return null
        return try {
            val releaseRes = sourceRequest(releaseUrl, additionalSettings)
            if (releaseRes.statusCode != 200) return null
            apkMirrorSizeBytesFromPageText(Jsoup.parse(releaseRes.body).body().wholeText())?.let { return it }
            val downloadPage = apkMirrorDownloadPageUrlFromReleasePage(releaseRes.body, releaseUrl) ?: return null
            val downloadRes = sourceRequest(downloadPage, additionalSettings)
            if (downloadRes.statusCode != 200) return null
            apkMirrorSizeBytesFromPageText(Jsoup.parse(downloadRes.body).body().wholeText())
        } catch (e: Exception) {
            null
        }
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val fallbackToOlderReleases = additionalSettings["fallbackToOlderReleases"] == true
            val regexFilter = (additionalSettings["filterReleaseTitlesByRegEx"] as? String)?.takeIf { it.isNotEmpty() }
            val res = sourceRequest("$standardUrl/feed/", additionalSettings)
            if (res.statusCode != 200) throw Http.errorFor(res)
            val items = Jsoup.parse(res.body).select("item")
            val minAgeDays = effectiveMinUpdateAgeDays(additionalSettings)

            fun releaseDateFor(item: Element): Instant? {
                val raw = item.selectFirst("pubDate")?.html() ?: return null
                return Dates.tryParseRfc1123("${raw.trim().split(' ').take(5).joinToString(" ")} GMT")
            }

            var targetIndex: Int? = null
            var tooYoungIndex: Int? = null
            var releaseSkipped = 0
            for (i in items.indices) {
                if (!fallbackToOlderReleases && i > releaseSkipped) break
                val title = items[i].selectFirst("title")?.html()
                if (regexFilter != null && title != null && !Regex(regexFilter).containsMatchIn(title.trim())) continue
                if (isReleaseTooYoung(releaseDateFor(items[i]), minAgeDays)) {
                    if (tooYoungIndex == null) tooYoungIndex = i
                    releaseSkipped++
                    continue
                }
                targetIndex = i
                break
            }
            // Nothing old enough: take the newest so that it can be held back until it has aged.
            val index = targetIndex ?: tooYoungIndex
                ?: throw NoReleasesError(note = if (regexFilter != null) Tr.get("noMatchingReleaseFound") else null)
            val target = items[index]
            val titleString = target.selectFirst("title")?.html()
            val releaseUrl = apkMirrorReleaseUrlFromFeedBodyForItemIndex(res.body, index)
            var version = titleString?.let { apkMirrorVersionFromTitle(it) ?: apkMirrorCleanReleaseTitle(it) }
            if (version.isNullOrEmpty()) version = titleString
            if (version.isNullOrEmpty()) throw NoVersionError()
            var changeLog: String? = null
            if (!releaseUrl.isNullOrEmpty()) {
                try {
                    val releaseRes = sourceRequest(releaseUrl, additionalSettings)
                    if (releaseRes.statusCode == 200) changeLog = apkMirrorChangeLogFromReleasePageHtml(releaseRes.body)
                } catch (_: Exception) {
                    // The change log is optional.
                }
            }
            return ApkDetails(
                version, emptyList(), getAppNames(standardUrl),
                releaseDate = releaseDateFor(target), changeLog = changeLog, releaseUrl = releaseUrl,
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    private fun getAppNames(standardUrl: String): AppNames {
        val afterScheme = standardUrl.substring(standardUrl.indexOf("://") + 3)
        val pathStart = afterScheme.indexOf('/')
        if (pathStart < 0 || pathStart + 1 >= afterScheme.length) throw InvalidUrlError(name)
        val names = afterScheme.substring(pathStart + 1).split('/')
        if (names.size < 3) throw InvalidUrlError(name)
        return AppNames(names[1], names[2])
    }

    companion object {
        private val ICON_URL = Regex("https://[^\"'<>\\s]+\\.(png|webp|jpg)")
        private const val ALLOWLISTED_USER_AGENT_TOKEN = "APKUpdater-v3.5.9"
    }
}

/** The release page URL of a feed `<item>`; read from the raw XML, where `<link>` has text. */
fun apkMirrorReleaseUrlFromFeedItemXml(itemXml: String): String? {
    val url = Regex("<link>\\s*([^<]+?)\\s*</link>", RegexOption.IGNORE_CASE).find(itemXml)?.groupValues?.get(1)
        ?: return null
    return if (url.startsWith("http://") || url.startsWith("https://")) url else null
}

fun apkMirrorReleaseUrlFromFeedBodyForItemIndex(body: String, index: Int): String? {
    if (index < 0) return null
    val segments = body.split(Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE))
    if (index + 1 >= segments.size) return null
    val afterOpen = segments[index + 1]
    val closeIndex = afterOpen.lowercase().indexOf("</item>")
    return apkMirrorReleaseUrlFromFeedItemXml(if (closeIndex >= 0) afterOpen.substring(0, closeIndex) else afterOpen)
}

/** Drops the decorations of a release title (" by Author", "(arm64-v8a)", "[0]"). */
fun apkMirrorCleanReleaseTitle(title: String): String {
    var cleaned = title
    val byIndex = cleaned.lowercase().lastIndexOf(" by ")
    if (byIndex > 0) cleaned = cleaned.substring(0, byIndex)
    cleaned = cleaned.replace(Regex("\\([^)]*\\)"), " ")
    cleaned = cleaned.replace(Regex("\\[[^\\]]*\\]"), " ")
    return cleaned.replace(Regex("\\s+"), " ").trim()
}

/** The bare version token ("42.5.15-21") of a release title. */
fun apkMirrorVersionFromTitle(title: String): String? =
    Regex("(?:^|\\s)v?(\\d[\\d.\\-+_]*)(?=\\s|$)").find(apkMirrorCleanReleaseTitle(title))?.groupValues?.get(1)

/** The package name inside a listing's icon file name ("65a71d34ecd19_com.android.chrome.png"). */
fun apkMirrorPackageFromIconUrl(iconUrl: String?): String? {
    if (iconUrl.isNullOrBlank()) return null
    val fileName = Url.tryParse(iconUrl.trim())?.pathSegments?.lastOrNull() ?: return null
    val dotIndex = fileName.lastIndexOf('.')
    // A picture cut to a size says so at the end of its name: "..._com.example.app-384x384.png".
    val stem = (if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName).replace(Regex("-\\d+x\\d+$"), "")
    val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    return stem.split('_').reversed().firstOrNull {
        packagePattern.matches(it) && !it.lowercase().contains("apkmirror")
    }
}

/** The "File size: 270.70 MB" line of a page, in bytes. */
fun apkMirrorSizeBytesFromPageText(text: String): Long? {
    val match = Regex("File size:\\s*([0-9][0-9.]*)\\s*(B|KB|MB|GB)\\b", RegexOption.IGNORE_CASE).find(text)
        ?: return null
    val size = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "GB" -> 1L shl 30
        "MB" -> 1L shl 20
        "KB" -> 1L shl 10
        else -> 1L
    }
    return (size * multiplier).roundToLong()
}

/** The first "-apk-download/" page of the same release linked from a release page. */
fun apkMirrorDownloadPageUrlFromReleasePage(html: String, releasePageUrl: String): String? {
    val releaseUri = Url.tryParse(releasePageUrl) ?: return null
    if (!releaseUri.hasScheme) return null
    val releaseBase = "${releaseUri.origin}${releaseUri.path}"
    val validPrefix = if (releaseBase.endsWith("/")) releaseBase else "$releaseBase/"
    for (link in Jsoup.parse(html).select("a[href]")) {
        val href = link.attr("href").trim().ifEmpty { null } ?: continue
        val absolute = releaseUri.resolve(href).withoutFragment()
        val path = if (absolute.path.endsWith("/")) absolute.path else "${absolute.path}/"
        if (!path.endsWith("-apk-download/")) continue
        val url = absolute.withPath(path).toString()
        if (url.startsWith(validPrefix)) return url
    }
    return null
}

private fun normalizedText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

private fun blockText(element: Element): String {
    val parts = mutableListOf<String>()
    for (child in element.children()) {
        if (child.tagName() == "ul" || child.tagName() == "ol") {
            for (item in child.select("li")) {
                normalizedText(item.wholeText()).takeIf { it.isNotEmpty() }?.let { parts.add("- $it") }
            }
        } else {
            normalizedText(child.wholeText()).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
    }
    return if (parts.isNotEmpty()) parts.joinToString("\n") else normalizedText(element.wholeText())
}

/** The "What's new in ..." block of a release page. */
fun apkMirrorChangeLogFromReleasePageHtml(html: String): String? {
    val heading = Jsoup.parse(html).select("h1,h2,h3,h4,h5,h6")
        .firstOrNull { normalizedText(it.wholeText()).lowercase().startsWith("what's new in ") } ?: return null
    val parts = mutableListOf<String>()
    var sibling = heading.nextElementSibling()
    while (sibling != null) {
        val text = blockText(sibling)
        val lower = text.lowercase()
        if (lower.startsWith("about ") || lower.startsWith("download ") || lower.contains(" screenshots") ||
            lower.contains(" trailer")
        ) {
            break
        }
        val noise = lower == "advertisement" || lower.startsWith("verified safe to install") ||
            lower == "scroll to available downloads" || lower == "a more recent upload may be available below!"
        if (text.isNotEmpty() && !noise) parts.add(text)
        sibling = sibling.nextElementSibling()
    }
    return parts.joinToString("\n\n").trim().ifEmpty { null }
}
