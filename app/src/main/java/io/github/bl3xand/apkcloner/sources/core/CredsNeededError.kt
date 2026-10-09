package io.github.bl3xand.apkcloner.sources.core

class CredsNeededError(sourceName: String) :
    SourceError(code = "CREDS_NEEDED", data = mapOf("sourceName" to sourceName))
