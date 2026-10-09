package io.github.bl3xand.apkcloner.sources.model

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import java.time.Instant

/** What a source knows about the newest release of an app. */
data class ApkDetails(
    val version: String,
    val apkUrls: List<NamedUrl>,
    val names: AppNames,
    val releaseDate: Instant? = null,
    val changeLog: String? = null,
    val releaseUrl: String? = null,
    val allAssetUrls: List<NamedUrl> = emptyList(),
)
