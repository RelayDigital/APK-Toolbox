package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.asMap
import io.github.bl3xand.apkcloner.sources.net.Http
import java.time.Instant

/** APKs of the last successful build of a Jenkins job; the build number is the version. */
class Jenkins : AppSource("Jenkins") {
    init {
        fixedName = "Jenkins"
        versionDetectionDisallowed = true
        neverAutoSelect = true
        showReleaseDateAsVersionToggle = true
        changeLogPageIsStandardUrl = true
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        Regex("https?://[^/]+/job/[^/]+", RegexOption.IGNORE_CASE).find(url)?.value ?: throw InvalidUrlError(name)

    override fun getLatestAPKDetails(standardUrlIn: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val standardUrl = sourceSpecificStandardizeURL(standardUrlIn)
        val res = sourceRequest("$standardUrl/lastSuccessfulBuild/api/json", additionalSettings)
        if (res.statusCode != 200) throw Http.errorFor(res)
        val json = JsonValues.parse(res.body).asMap() ?: emptyMap()
        val version = (json["number"] as? Number)?.toLong()?.toString()
        if (version.isNullOrEmpty()) throw NoVersionError()
        val apkUrls = (json["artifacts"].asList() ?: emptyList()).mapNotNull { it.asMap() }.mapNotNull { artifact ->
            val path = (artifact["relativePath"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            NamedUrl(
                (artifact["fileName"] ?: artifact["relativePath"]) as String,
                "$standardUrl/lastSuccessfulBuild/artifact/$path",
            )
        }.filter { isApkOrContainerFile(it.name) }
        ApkDetails(
            version,
            apkUrls,
            AppNames(Url.parse(standardUrl).host, standardUrl.split('/').last()),
            releaseDate = json["timestamp"]?.toString()?.toDoubleOrNull()?.let { Instant.ofEpochMilli(it.toLong()) },
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }
}
