package io.github.bl3xand.apkcloner.sources.core

private val NAMES = linkedMapOf(
    16 to "4.1", 17 to "4.2", 18 to "4.3", 19 to "4.4", 21 to "5.0", 22 to "5.1", 23 to "6.0", 24 to "7.0", 25 to "7.1",
    26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
)

/** The Android version people know an API level by: 27 is "8.1". */
fun androidVersionName(sdk: Int): String = NAMES[sdk] ?: "API $sdk"

/**
 * The API level of an Android version as stores write it: "8.0", "8", "4.4.2", "12L". Null when
 * it cannot be told; a version between two known ones counts as the lower.
 */
fun androidSdkOf(version: String): Int? {
    val text = version.trim()
    NAMES.entries.firstOrNull { it.value.equals(text, ignoreCase = true) }?.let { return it.key }
    val parts = text.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.getOrNull(0) ?: return null
    val minor = parts.getOrNull(1) ?: 0
    return NAMES.entries.lastOrNull { (_, name) ->
        val known = name.removeSuffix("L").split('.').map { it.toInt() }
        known[0] < major || (known[0] == major && known.getOrElse(1) { 0 } <= minor)
    }?.key
}
