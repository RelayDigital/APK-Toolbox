package io.github.bl3xand.apkcloner.sources.core

import org.json.JSONArray

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

    // ---- the release file an app is set to use ------------------------------------------------

    /** Reads the stored pick of a release file: a NamedUrl pair, as TrackedApp.namedUrlsToJson writes it. */
    fun storedPreferredApk(setting: String?): NamedUrl? {
        if (setting.isNullOrBlank()) return null
        return runCatching {
            val pair = JSONArray(setting).optJSONArray(0) ?: return null
            if (pair.length() != 2) return null
            NamedUrl(pair.getString(0), pair.getString(1))
        }.getOrNull()
    }

    /**
     * The file of [choices] that the remembered pick [preferred] stands for, or null when it
     * stands for none of them any more:
     *
     * 1. the very same URL (the same release, or a source that keeps its URLs stable);
     * 2. the very same name;
     * 3. the same name with the runs of digits taken out - `app-1.2.3-arm64-v8a.apk` matches
     *    `app-1.3.0-arm64-v8a.apk` - but only when exactly one choice is left after that;
     * 4. the place in the list, as the last resort and only when it is a real pick (0 or more;
     *    -1 says that no file was ever chosen).
     */
    fun resolvePreferredApk(
        preferred: NamedUrl?,
        choices: List<NamedUrl>,
        preferredApkIndex: Int = -1,
    ): NamedUrl? {
        if (choices.isEmpty()) return null
        if (preferred != null) {
            choices.firstOrNull { it.url == preferred.url }?.let { return it }
            choices.firstOrNull { it.name == preferred.name }?.let { return it }
            normalized(preferred.name)?.let { wanted ->
                val matching = choices.filter { normalized(it.name) == wanted }
                if (matching.size == 1) return matching.single()
            }
        }
        return choices.getOrNull(preferredApkIndex)
    }

    /** A name with everything that changes between releases taken out, or null when nothing is left. */
    private fun normalized(name: String): String? {
        val base = name.substringBeforeLast('.')
        val stripped = base.replace(Regex("[0-9]+"), "")
        val joined = Regex("[^a-z]+").replace(stripped.lowercase(), "")
        return joined.takeIf { it.isNotEmpty() }
    }
}
