package io.github.bl3xand.apkcloner.sources.core

class IdChangedError(newId: String) : SourceError(code = "ID_CHANGED", data = mapOf("newId" to newId))
