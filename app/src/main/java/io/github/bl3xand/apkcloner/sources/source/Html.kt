package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.compareAlphaNumeric
import io.github.bl3xand.apkcloner.sources.core.extractVersion
import io.github.bl3xand.apkcloner.sources.core.regExValidator
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.core.tolerantSort
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SubFormItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.net.Downloader
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.net.HttpResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** A link found on a page: where it points and the text it is shown with. */
data class PageLink(val url: String, val text: String)

fun collectAllStringsFromJson(value: Any?): List<String> = when (value) {
    is String -> listOf(value)
    is List<*> -> value.flatMap(::collectAllStringsFromJson)
    is Map<*, *> -> value.values.flatMap(::collectAllStringsFromJson)
    else -> emptyList()
}

private val linkInText = Regex("(?:(?:http|https|ftp)://)[^\\s\"'<>()\\[\\]{}]+")
private val trailingPunctuation = Regex("[.,;:!?]+$")

/** URLs written out in plain text; trailing punctuation belongs to the text around them. */
fun getLinksInLines(lines: String): List<PageLink> = linkInText.findAll(lines)
    .map { trailingPunctuation.replaceFirst(it.value, "") }
    .filter { it.isNotEmpty() }
    .map { PageLink(it, it.split('/').last()) }
    .toList()

/** Absolute and root-relative URLs in element attributes (`<script src="/js/app.js">`). */
fun getLinksInHtmlAttributes(html: Document, reqUrl: Url): List<PageLink> {
    val absolute = Regex("^(https?|ftp)://", RegexOption.IGNORE_CASE)
    val links = mutableListOf<PageLink>()
    for (element in html.allElements) {
        for (attribute in element.attributes()) {
            val trimmed = attribute.value.trim()
            if (trimmed.isEmpty()) continue
            if (!absolute.containsMatchIn(trimmed) && !trimmed.startsWith("/")) continue
            val resolved = Url.ensureAbsolute(trimmed, reqUrl)
            val scheme = Url.tryParse(resolved)?.scheme ?: continue
            if (scheme !in listOf("http", "https", "ftp")) continue
            links.add(PageLink(resolved, resolved.split('/').last()))
        }
    }
    return links
}

private fun decodedOrSame(url: String): String = try {
    Url.decodeFull(url)
} catch (e: Exception) {
    url
}

/**
 * Collects the links of a page and applies the link options shared by intermediate and final
 * steps: where to look, the filter, and the ordering. The caller takes the last link.
 */
fun grabLinksCommon(rawBody: String, reqUrl: Url, additionalSettings: Map<String, Any?>): List<PageLink> {
    val matchLinksOutsideATags = additionalSettings["matchLinksOutsideATags"] == true
    val html = Jsoup.parse(rawBody)
    var allLinks = html.select("a")
        .map { element ->
            val href = if (element.hasAttr("href")) element.attr("href") else ""
            PageLink(href, element.wholeText().ifEmpty { href.split('/').last() })
        }
        .filter { it.url.isNotEmpty() }
        .map { PageLink(Url.ensureAbsolute(it.url, reqUrl), it.text) }
    if (allLinks.isEmpty() || matchLinksOutsideATags) {
        // Every kind of link is a candidate; the first one seen for a URL wins.
        val merged = LinkedHashMap<String, PageLink>()
        allLinks.forEach { merged[it.url] = it }
        fun addAll(links: List<PageLink>) = links.forEach { merged.putIfAbsent(it.url, it) }
        if (allLinks.isEmpty()) {
            try {
                val jsonStrings = collectAllStringsFromJson(JsonValues.parse(rawBody))
                var jsonLinks = getLinksInLines(jsonStrings.joinToString("\n"))
                if (jsonLinks.isEmpty()) {
                    jsonLinks = getLinksInLines(jsonStrings.joinToString("\n") { Url.ensureAbsolute(it, reqUrl) })
                }
                addAll(jsonLinks)
            } catch (e: Exception) {
                addAll(getLinksInLines(rawBody))
            }
        }
        if (matchLinksOutsideATags) {
            addAll(getLinksInLines(rawBody))
            addAll(getLinksInHtmlAttributes(html, reqUrl))
        }
        allLinks = merged.values.toList()
    }
    val filterByLinkText = additionalSettings["filterByLinkText"] == true
    val customFilter = (additionalSettings["customLinkFilterRegex"] as? String)?.takeIf { it.isNotEmpty() }
    var links = if (customFilter != null) {
        val regex = Regex(customFilter)
        allLinks.filter { regex.containsMatchIn(if (filterByLinkText) it.text else decodedOrSame(it.url)) }
    } else {
        allLinks.filter {
            ApkFilter.isApkOrContainerFile(Url.parse((if (filterByLinkText) it.text else decodedOrSame(it.url)).trim()).path)
        }
    }.toMutableList()
    if (additionalSettings["skipSort"] != true) {
        val byLastSegment = additionalSettings["sortByLastLinkSegment"] == true
        tolerantSort(links) { a, b ->
            if (byLastSegment) {
                compareAlphaNumeric(
                    a.url.split('/').last { it.isNotEmpty() },
                    b.url.split('/').last { it.isNotEmpty() },
                )
            } else compareAlphaNumeric(a.url, b.url)
        }
    }
    if (additionalSettings["reverseSort"] == true) links = links.asReversed().toMutableList()
    return links
}

fun grabLinksCommonFromRes(res: HttpResponse, additionalSettings: Map<String, Any?>): List<PageLink> {
    Http.ensureSuccess(res)
    return grabLinksCommon(res.body, res.requestUrl, additionalSettings)
}

/** Any web page (or JSON document) that links to an APK; the fallback for unknown URLs. */
class HTML : AppSource("HTML") {
    init {
        fixedName = "HTML"
        suppressStandardVersionExtraction = true
    }

    override val combinedAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = super.combinedAppSpecificSettingFormItems.onEach { row ->
            row.forEach { item ->
                // Here the regex finds the version; elsewhere it only trims one.
                if (item.key == "versionExtractionRegEx") {
                    item.labelKey = "versionExtractionRegEx"
                    item.labelOverride = null
                }
                if (item.key == "matchGroupToUse") {
                    item.labelKey = "matchGroupToUse"
                    item.labelOverride = null
                }
            }
        }

    private val finalStepFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(
                TextItem(
                    "customLinkFilterRegex", "customLinkFilterRegex",
                    hint = "download/(.*/)?(android|apk|mobile)", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
            listOf(SwitchItem("versionExtractWholePage", "versionExtractWholePage")),
        )

    private val commonFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(SwitchItem("filterByLinkText", "filterByLinkText")),
            listOf(SwitchItem("matchLinksOutsideATags", "matchLinksOutsideATags")),
            listOf(SwitchItem("skipSort", "skipSort")),
            listOf(SwitchItem("reverseSort", "takeFirstLink")),
            listOf(SwitchItem("sortByLastLinkSegment", "sortByLastLinkSegment")),
        )

    private val intermediateFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(
                TextItem(
                    "customLinkFilterRegex", "intermediateLinkRegex", hint = "([0-9]+.)*[0-9]+/$",
                    required = true, validators = listOf(::regExValidator),
                ),
            ),
            listOf(SwitchItem("autoLinkFilterByArch", "autoLinkFilterByArch", value = false)),
        )

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(
            listOf(SubFormItem("intermediateLink", "intermediateLink", intermediateFormItems + commonFormItems)),
            finalStepFormItems[0],
        ) + commonFormItems + finalStepFormItems.drop(1) + listOf(
            listOf(requestHeaderFormItem()),
            listOf(
                DropdownItem(
                    "defaultPseudoVersioningMethod", "defaultPseudoVersioningMethod",
                    listOf("partialAPKHash" to "partialAPKHash", "APKLinkHash" to "APKLinkHash", "ETag" to "ETag"),
                    value = "partialAPKHash",
                ),
            ),
            listOf(
                TextItem(
                    "zippedApkFilterRegEx", "zippedApkFilterRegEx", required = false,
                    validators = listOf(::regExValidator),
                ),
            ),
        )

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? {
        if (additionalSettings.isEmpty()) return null
        val headers = LinkedHashMap<String, String>()
        for (entry in (additionalSettings["requestHeader"] as? List<*>) ?: emptyList<Any?>()) {
            val line = ((entry as? Map<*, *>)?.get("requestHeader") as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val parts = line.split(':')
            headers[parts[0].trim()] = parts.drop(1).joinToString(":").trim()
        }
        return headers
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String = url

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            var currentUrl = standardUrl
            val intermediateLinks = ((additionalSettings["intermediateLink"] as? List<*>) ?: emptyList<Any?>())
                .mapNotNull {
                    @Suppress("UNCHECKED_CAST")
                    it as? Map<String, Any?>
                }
                .filter { !(it["customLinkFilterRegex"] as? String).isNullOrEmpty() }
            for (level in intermediateLinks.take(MAX_INTERMEDIATE_LINK_DEPTH)) {
                var links = grabLinksCommonFromRes(sourceRequest(currentUrl, additionalSettings), level)
                if (level["autoLinkFilterByArch"] == true) links = filterLinksByArch(links)
                if (links.isEmpty()) throw NoReleasesError(note = currentUrl)
                currentUrl = links.last().url
            }
            val uri = Url.parse(currentUrl)
            var links: List<PageLink>
            var wholePage = currentUrl
            if (additionalSettings["directAPKLink"] != true) {
                val res = sourceRequest(currentUrl, additionalSettings)
                // One line, with the breaks written out, so a regex can span them.
                wholePage = res.body.split("\r\n").joinToString("\n").split("\n").joinToString("\\n")
                links = grabLinksCommonFromRes(res, additionalSettings)
                val filter = (additionalSettings["apkFilterRegEx"] as? String)?.takeIf { it.isNotEmpty() }
                if (filter != null) {
                    val regex = Regex(filter)
                    val invert = additionalSettings["invertAPKFilter"] == true
                    links = links.filter { regex.containsMatchIn(it.url) != invert }
                }
                if (links.isEmpty()) throw NoReleasesError(note = currentUrl)
            } else {
                links = listOf(PageLink(currentUrl, currentUrl))
            }
            val rel = links.last().url
            var version = extractVersion(
                additionalSettings["versionExtractionRegEx"] as? String,
                additionalSettings["matchGroupToUse"] as? String,
                if (additionalSettings["versionExtractWholePage"] == true) wholePage else decodedOrSame(rel),
            )
            // No version in sight: fall back to a pseudo-version of the file itself.
            val options = requestOptions(additionalSettings)
            val apkHeaders = getRequestHeaders(additionalSettings, rel, forAPKDownload = true)
            val method = additionalSettings["defaultPseudoVersioningMethod"]
            if (version == null && method == "ETag") {
                version = Downloader.checkETagHeader(rel, options, apkHeaders)
                if (version.isNullOrEmpty()) throw NoVersionError()
            }
            if (version == null) {
                version = if (method == "APKLinkHash") {
                    rel.hashCode().toString()
                } else {
                    Downloader.checkPartialDownloadHashDynamic(rel, options, apkHeaders)
                }
            }
            val relUri = Url.parse(rel)
            val fileName = relUri.pathSegments.lastOrNull() ?: relUri.origin
            return ApkDetails(
                version,
                listOf(NamedUrl("${rel.hashCode()}-$fileName", rel)),
                AppNames(uri.host, Tr.get("app")),
            )
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    companion object {
        private const val MAX_INTERMEDIATE_LINK_DEPTH = 10

        const val DEFAULT_USER_AGENT_HEADER = "User-Agent: Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        fun requestHeaderFormItem(): SubFormItem = SubFormItem(
            "requestHeader", "requestHeader",
            listOf(
                listOf(
                    TextItem(
                        "requestHeader", "requestHeader", required = false,
                        validators = listOf { value ->
                            val parts = (value ?: "empty:valid").split(':').map { it.trim() }.filter { it.isNotEmpty() }
                            if (parts.size < 2) Tr.get("invalidInput") else null
                        },
                    ),
                ),
            ),
            value = listOf(mapOf("requestHeader" to DEFAULT_USER_AGENT_HEADER)),
        )

        /** Narrows links to the device architecture by their URL. */
        fun filterLinksByArch(links: List<PageLink>): List<PageLink> {
            val kept = ApkFilter.filterApksByArch(
                links.map { NamedUrl(it.url, it.text) }, SourceEnv.platform.supportedAbis,
            ).map { it.name }.toSet()
            return links.filter { it.url in kept }
        }
    }
}
