package io.github.bl3xand.apkcloner.sources.core

class InstallError(errorCode: Int, statusName: String? = null) : SourceError(
    code = "INSTALL_FAILED",
    data = mapOf("errorCode" to errorCode, "message" to statusName),
)
