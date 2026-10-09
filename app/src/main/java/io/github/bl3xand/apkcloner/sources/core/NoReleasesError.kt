package io.github.bl3xand.apkcloner.sources.core

class NoReleasesError(note: String? = null) :
    SourceError(code = "NO_RELEASES", data = mapOf("note" to (note ?: "")))
