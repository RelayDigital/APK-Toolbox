package io.github.bl3xand.apkcloner.sources.core

import java.time.Duration
import java.time.Instant

/** Per-app override when set, otherwise the global minimum update age (days). */
fun effectiveMinUpdateAgeDays(
    additionalSettings: Map<String, Any?>,
    settings: SourceSettings = SourceEnv.settings,
): Int {
    val raw = additionalSettings["minimumUpdateAgeDays"]
    if (raw is String && raw.isNotEmpty()) raw.toIntOrNull()?.let { return it }
    return settings.minimumUpdateAgeDays
}

fun isReleaseTooYoung(releaseDate: Instant?, minAgeDays: Int, now: Instant = Instant.now()): Boolean {
    if (releaseDate == null || minAgeDays <= 0) return false
    return Duration.between(releaseDate, now) < Duration.ofDays(minAgeDays.toLong())
}
