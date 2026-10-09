package io.github.bl3xand.apkcloner.sources.core

class InvalidUrlError(sourceName: String) :
    SourceError(code = "INVALID_URL", data = mapOf("sourceName" to sourceName))
