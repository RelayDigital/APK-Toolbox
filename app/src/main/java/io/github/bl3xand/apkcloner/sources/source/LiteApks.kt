package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoReleasesError
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http
import java.util.Base64

class LiteAPKs : AppSource("LiteAPKs") {
    init {
        hosts = listOf("liteapks.com")
        fixedName = "LiteAPKs"
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/+[^/]+")

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String> = mapOf("Referer" to url.split('#').last())

    /** The CDN wants a token made from a time three hours ahead. */
    override fun assetUrlPrefetchModifier(
        assetUrl: String,
        standardUrl: String,
        additionalSettings: Map<String, Any?>,
    ): String {
        val encoder = Base64.getEncoder()
        val time = (System.currentTimeMillis() / 1000 + CACHE_SECONDS).toString()
        val token = encoder.encodeToString(encoder.encodeToString(time.toByteArray()).toByteArray())
            .replace("=", "%3D")
        val parts = assetUrl.split('#').toMutableList()
        parts[0] = "${parts[0]}?token=$token"
        return parts.joinToString("#")
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val standardUri = Url.parse(standardUrl)
        val slug = standardUri.pathSegments.last().split('.').first()
        val res1 = sourceRequest("${standardUri.origin}/wp-json/wp/v2/posts?slug=$slug", additionalSettings)
        Http.ensureSuccess(res1)
        val postId = JsonValues.parse(res1.body).asList()?.firstOrNull().dig("id") ?: throw NoReleasesError()
        val res2 = sourceRequest(
            "${standardUri.origin}/wp-json/v2/posts/${FDroid.numberText(postId)}", additionalSettings,
        )
        Http.ensureSuccess(res2)
        val data = JsonValues.parse(res2.body).dig("data")
        val firstVersion = data.dig("versions").asList()?.firstOrNull().asMap()
        val version = firstVersion?.get("version") as? String
        if (version.isNullOrEmpty()) throw NoVersionError()
        // The page the file belongs to travels after '#': it becomes the Referer of the download.
        val apkUrls = (firstVersion["version_downloads"].asList() ?: emptyList())
            .mapNotNull { it.asMap()?.get("version_download_link") as? String }
            .filter { it.isNotEmpty() }
            .map { link ->
                val fileName = Url.parse(link).pathSegments.lastOrNull() ?: link.split('/').last { it.isNotEmpty() }
                NamedUrl(Url.decodeComponent(fileName), "$link#$standardUrl")
            }
        ApkDetails(
            version,
            apkUrls,
            AppNames(
                (data.dig("publisher") as? String) ?: standardUri.host,
                (data.dig("title") as? String) ?: standardUrl.split('/').last(),
            ),
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    companion object {
        private const val CACHE_SECONDS = 3 * 60 * 60
    }
}
