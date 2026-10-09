package io.github.bl3xand.apkcloner.sources.core

class DowngradeError(currentVersionCode: Long, newVersionCode: Long) : SourceError(
    code = "DOWNGRADE",
    data = mapOf("currentVersionCode" to currentVersionCode, "newVersionCode" to newVersionCode),
)
