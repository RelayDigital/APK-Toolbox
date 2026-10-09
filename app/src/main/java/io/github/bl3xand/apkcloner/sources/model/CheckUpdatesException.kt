package io.github.bl3xand.apkcloner.sources.model

import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.SourceError

class CheckUpdatesException(val updates: List<TrackedApp>, val errors: MultiAppMultiError) :
    SourceError(code = "CHECK_UPDATES_FAILED", unexpected = true) {
    override fun toString(): String {
        val base = if (!url.isNullOrEmpty()) "$message ($url)" else message
        return "$base\n$errors"
    }
}
