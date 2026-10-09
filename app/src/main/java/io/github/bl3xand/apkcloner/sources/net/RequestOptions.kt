package io.github.bl3xand.apkcloner.sources.net

/** Per-request options; these travel in the settings map of the reference implementation. */
data class RequestOptions(
    val allowInsecure: Boolean = false,
    val enableCertificatePinning: Boolean = false,
    val allowInsecureRedirects: Boolean = false,
)
