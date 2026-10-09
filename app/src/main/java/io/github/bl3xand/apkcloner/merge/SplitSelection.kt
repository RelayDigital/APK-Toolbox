package io.github.bl3xand.apkcloner.merge

import android.content.Context
import android.os.Build
import java.util.Locale

/** Works out, from split file names alone, which splits a given device actually needs. */
object SplitSelection {

    private val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64")
    private val DENSITIES = setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi")
    private val LANGUAGE = Regex("[a-z]{2,3}([_-]r?[A-Za-z0-9]+)?")
    private val CONFIG_PREFIX = Regex("^(split_config\\.|config\\.|split\\.)")

    /** `split_config.arm64_v8a.apk` -> `arm64_v8a`; null for anything that is not a config split. */
    private fun qualifier(name: String): String? {
        val file = name.substringAfterLast('/').removeSuffix(".apk")
        if (!CONFIG_PREFIX.containsMatchIn(file)) return null
        return file.replace(CONFIG_PREFIX, "").replace('-', '_')
    }

    fun kindOf(name: String, baseName: String): SplitKind {
        if (name == baseName) return SplitKind.BASE
        val qualifier = qualifier(name) ?: return SplitKind.FEATURE
        return when {
            qualifier in ABIS -> SplitKind.ABI
            qualifier in DENSITIES -> SplitKind.DENSITY
            LANGUAGE.matches(qualifier) -> SplitKind.LANGUAGE
            else -> SplitKind.FEATURE
        }
    }

    /**
     * The base, every feature module, and the ABI, density and language splits matching this
     * device. If the bundle has ABI or density splits but none match, all of that kind are kept
     * rather than risking an app with no native code or no images.
     */
    fun forDevice(context: Context, names: List<String>, baseName: String): Set<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().replace('-', '_')
        val language = Locale.getDefault().language
        val density = densityBucket(context.resources.displayMetrics.densityDpi)

        val byKind = names.groupBy { kindOf(it, baseName) }
        fun pick(kind: SplitKind, keepAllIfNoMatch: Boolean, matches: (String) -> Boolean): List<String> {
            val all = byKind[kind].orEmpty()
            val matching = all.filter { matches(qualifier(it).orEmpty()) }
            return if (matching.isEmpty() && keepAllIfNoMatch) all else matching
        }
        return buildSet {
            addAll(byKind[SplitKind.BASE].orEmpty())
            addAll(byKind[SplitKind.FEATURE].orEmpty())
            addAll(pick(SplitKind.ABI, keepAllIfNoMatch = true) { it == abi })
            addAll(pick(SplitKind.DENSITY, keepAllIfNoMatch = true) { it == density })
            addAll(pick(SplitKind.LANGUAGE, keepAllIfNoMatch = false) { it == language || it.startsWith(language + "_") })
        }
    }

    private fun densityBucket(dpi: Int) = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"
        dpi <= 480 -> "xxhdpi"
        else -> "xxxhdpi"
    }
}
