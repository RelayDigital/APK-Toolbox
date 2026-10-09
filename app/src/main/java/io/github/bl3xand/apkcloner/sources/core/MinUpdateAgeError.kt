package io.github.bl3xand.apkcloner.sources.core

import java.time.Instant

/** The latest release is younger than the minimum update age and no older one is available. */
class MinUpdateAgeError(releaseDate: Instant, minAgeDays: Int) : SourceError(
    code = "MIN_UPDATE_AGE",
    data = mapOf("releaseDate" to releaseDate.toString(), "minAgeDays" to minAgeDays),
)
