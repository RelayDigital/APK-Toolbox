package io.github.bl3xand.apkcloner.sources.source

/** A source of many app URLs at once (for bulk import). */
interface MassAppUrlSource {
    val name: String
    val requiredArgs: List<String>
    fun getUrlsWithDescriptions(args: List<String>): Map<String, List<String>>
}
