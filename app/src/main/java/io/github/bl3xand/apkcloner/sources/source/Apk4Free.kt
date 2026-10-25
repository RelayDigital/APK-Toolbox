package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

import java.util.concurrent.Executors

class Apk4Free : AppSource("Apk4Free") {
    init {
        fixedName = "Apk4Free"
        hosts = listOf("apk4free.net")
        canSearch = true
    }

    /**
     * The site keeps the pages of apps whose files it has taken down, and its search finds them
     * like any other. Whether a page still leads to a file shows only on the page, so the first
     * results are looked at, side by side, and those without a download are left out.
     */
    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val found = searchWordPress("https://${hosts[0]}", query, querySettings).entries.take(CHECKED_RESULTS)
        val pool = Executors.newFixedThreadPool(CHECK_THREADS)
        try {
            val checks = found.map { hit ->
                pool.submit<Boolean> {
                    runCatching { DOWNLOAD_LINK.containsMatchIn(sourceRequest(hit.key, querySettings).body) }.getOrDefault(false)
                }
            }
            return found.filterIndexed { index, _ -> checks[index].get() }.associate { it.key to it.value }
        } finally {
            pool.shutdownNow()
        }
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/[^/]+/?")

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails {
        try {
            val res = sourceRequest(standardUrl, additionalSettings)
            Http.ensureSuccess(res)
            val html = Jsoup.parse(res.body)
            val rawTitle = html.selectFirst("h1.main-box-title")?.text()?.trim()
            // The title carries the version and marketing words around the app name.
            val title = if (rawTitle.isNullOrEmpty()) {
                standardUrl.split('/').last()
            } else {
                rawTitle
                    .replace(Regex("\\[.*?\\]|\\{.*?\\}"), "")
                    .replace(Regex("\\b(APK|MOD|XAPK|HACK)\\b", RegexOption.IGNORE_CASE), "")
                    .replace(
                        Regex(
                            "\\((?:[^)]*?(?:Unlocked|Mod|Premium|Money|Menu|Full|Patched|Subscribed|AdFree|" +
                                "BG Play|Paid|Unlimited|God Mode)[^)]*?)\\)",
                            RegexOption.IGNORE_CASE,
                        ),
                        "",
                    )
                    .replace(Regex("\\s+v?\\d+(\\.\\d+)+.*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+[\\+\\-]\\s+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            val versionRegex = Regex("v?(\\d+(\\.\\d+)+)")
            var version = html.selectFirst("div.version")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: versionRegex.find(rawTitle ?: "")?.groupValues?.get(1)

            val downloadPage = html.select("a.downloadAPK").map { it.attr("href") }.firstOrNull { it.contains("/download/") }
                ?: html.select("a").map { it.attr("href") }.firstOrNull { it.contains("/download/") }
            if (downloadPage.isNullOrEmpty()) throw NoReleasesError()

            val resDownload = sourceRequest(downloadPage, additionalSettings)
            Http.ensureSuccess(resDownload)
            val page = Jsoup.parse(resDownload.body)
            var apkUrls = page.select("a.downloadAPK").filter { it.hasAttr("href") }
                .map { NamedUrl(it.text().trim(), it.attr("href").trim()) }
            if (apkUrls.isEmpty()) {
                apkUrls = page.select("a").filter { it.hasAttr("href") }.mapNotNull { link ->
                    val href = link.attr("href").trim()
                    if (!isApkOrContainerFile(href)) return@mapNotNull null
                    NamedUrl(link.text().trim().ifEmpty { href.split('/').last() }, href)
                }
            }
            if (apkUrls.isEmpty()) throw NoApkError()
            if (version == null) {
                version = apkUrls.firstNotNullOfOrNull {
                    (versionRegex.find(it.name) ?: versionRegex.find(it.url))?.groupValues?.get(1)
                }
            }
            if (version == null) throw NoVersionError()
            return ApkDetails(version.trim(), apkUrls, AppNames(shortName, title))
        } catch (e: Throwable) {
            rethrowOrWrap(e)
        }
    }

    companion object {
        private val DOWNLOAD_LINK = Regex("href=\"[^\"]+/download/\"")
        private const val CHECKED_RESULTS = 16
        private const val CHECK_THREADS = 8
    }
}
