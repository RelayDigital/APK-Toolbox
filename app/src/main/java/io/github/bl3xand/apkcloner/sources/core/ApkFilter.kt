package io.github.bl3xand.apkcloner.sources.core

/** A named download: [name] is what filters and pickers show, [url] where it comes from. */
data class NamedUrl(val name: String, val url: String)

object ApkFilter {
    val apkContainerExtensions = listOf(".apk", ".xapk", ".apkm", ".apks")
    val archiveExtensions = listOf(".zip")
    val tarballExtensions = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tar.xz")

    fun isApkOrContainerFile(
        name: String,
        includeArchives: Boolean = false,
        includeTarballs: Boolean = false,
    ): Boolean {
        val lower = name.lowercase()
        fun endsWithAny(extensions: List<String>) = extensions.any { lower.endsWith(it) }
        return endsWithAny(apkContainerExtensions) ||
            (includeArchives && endsWithAny(archiveExtensions)) ||
            (includeTarballs && endsWithAny(tarballExtensions))
    }

    // The URLs of a split APK set live in one value, base first. A line break cannot occur in a
    // URL, so it is a safe separator.
    const val MULTI_APK_URL_SEPARATOR = "\n"

    fun splitMultiApkUrl(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        return if (value.contains(MULTI_APK_URL_SEPARATOR)) {
            value.split(MULTI_APK_URL_SEPARATOR).filter { it.isNotEmpty() }
        } else listOf(value)
    }

    fun joinMultiApkUrl(urls: Iterable<String>): String = urls.joinToString(MULTI_APK_URL_SEPARATOR)

    /** Names each URL after its last APK-looking path segment, else its last segment. */
    fun apkUrlsFromUrls(urls: List<String>): List<NamedUrl> = urls.map { url ->
        val segments = url.split('/').filter { it.trim().isNotEmpty() }
        val apkSegments = segments.filter { isApkOrContainerFile(it) }
        NamedUrl(if (apkSegments.isNotEmpty()) apkSegments.last() else segments.last(), url)
    }

    fun filterApks(apkUrls: List<NamedUrl>, apkFilterRegEx: String?, invert: Boolean?): List<NamedUrl> {
        if (apkFilterRegEx.isNullOrEmpty()) return apkUrls
        val regex = Regex(apkFilterRegEx)
        return apkUrls.filter {
            val hasMatch = regex.containsMatchIn(it.name)
            if (invert == true) !hasMatch else hasMatch
        }
    }

    /** Names commonly used in file names for an ABI besides the canonical one. */
    val abiNameAliases = mapOf(
        "arm64-v8a" to listOf("aarch64", "arm64"),
        "armeabi-v7a" to listOf("armv7", "armeabi"),
        "x86_64" to listOf("x64"),
    )

    /** Narrows the list to the first device ABI that matches some, but not all, entries. */
    fun filterApksByArch(apkUrls: List<NamedUrl>, abis: List<String>): List<NamedUrl> {
        if (apkUrls.size <= 1) return apkUrls
        for (abi in abis) {
            val variants = listOf(abi) + (abiNameAliases[abi] ?: emptyList())
            val abiRegex = Regex(".*(?:${variants.joinToString("|")}).*", RegexOption.IGNORE_CASE)
            val matching = apkUrls.filter { abiRegex.containsMatchIn(it.name) }
            if (matching.isNotEmpty() && matching.size < apkUrls.size) return matching
        }
        return apkUrls
    }
}
