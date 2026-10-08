package io.github.bl3xand.apkcloner.sources.core

// Recognises two version strings as the same release despite cosmetic differences. Android
// reports the version name baked into the APK, which often carries packaging words the release
// tag does not ("0.9.108 strip", "1.6.15-debug", "1.0.5-release+26090410", a leading "v").
// The comparison is conservative: only packaging words are ignored, pre-release qualifiers keep
// their meaning, and a qualifier or build metadata on the remote side that the installed build
// lacks means a different build.

/** Build and packaging words that do not take part in version ordering. */
private val ignorableVersionTokens = setOf(
    "strip", "debug", "release", "stable", "final", "standard", "build", "signed", "unsigned",
    "universal", "nogms",
)

/** Pre-release qualifiers, which do change ordering. */
private val preReleaseQualifiers = setOf(
    "alpha", "beta", "rc", "pre", "dev", "snapshot", "nightly", "ose",
)

private val leadingV = Regex("^v(?=\\d)")
private val tokenSeparator = Regex("[^\\p{L}\\p{N}]+")
private val segments = Regex("\\p{L}+|\\p{N}+")
private val firstNumber = Regex("\\d+")
private val majorOnlyPreRelease =
    Regex("^(\\d+)(?:[._\\-\\s]?(" + preReleaseQualifiers.joinToString("|") + ")\\w*)$")

private class VersionParts(
    /** Ordering-significant tokens with letter and digit runs split apart. */
    val core: List<String>,
    /** Packaging words found in the version part. */
    val packaging: Set<String>,
    /** Ordering-significant tokens after the first '+'. */
    val metadata: List<String>,
)

private fun segment(token: String): List<String> = segments.findAll(token).map { it.value }.toList()

private fun tokens(value: String): List<String> = value.split(tokenSeparator).filter { it.isNotEmpty() }

private fun analyze(version: String): VersionParts {
    var value = version.trim().lowercase().replace('_', '-')
    value = leadingV.replaceFirst(value, "")
    val plus = value.indexOf('+')
    val versionPart = if (plus >= 0) value.substring(0, plus) else value
    val metadataPart = if (plus >= 0) value.substring(plus + 1) else ""
    val core = mutableListOf<String>()
    val packaging = mutableSetOf<String>()
    for (token in tokens(versionPart)) {
        if (token in ignorableVersionTokens) packaging.add(token) else core.addAll(segment(token))
    }
    val metadata = mutableListOf<String>()
    for (token in tokens(metadataPart)) {
        if (token in ignorableVersionTokens) continue
        metadata.addAll(segment(token))
    }
    return VersionParts(core, packaging, metadata)
}

/** Canonical key: numeric core and significant words, without packaging words or metadata. */
fun normalizeVersionForComparison(version: String): String = analyze(version).core.joinToString(".")

/**
 * Whether [a] and [b] describe the same release once packaging words are ignored. Symmetric and
 * tolerant of conflicting packaging words: it keeps version detection enabled, it does not
 * decide whether an update exists (see [installedMatchesRemote]).
 */
fun versionsAreCosmeticallyEqual(a: String, b: String): Boolean {
    val partsA = analyze(a)
    val partsB = analyze(b)
    if (partsA.core.isEmpty() || partsB.core.isEmpty()) return false
    if (partsA.core != partsB.core) return false
    if (partsA.metadata.isNotEmpty() && partsB.metadata.isNotEmpty() &&
        partsA.metadata != partsB.metadata
    ) {
        return false
    }
    return true
}

/**
 * Whether the installed build is the published release, allowing the installed side to carry
 * extra packaging words or build metadata.
 */
fun installedMatchesRemote(installed: String, remote: String): Boolean {
    val partsInstalled = analyze(installed)
    val partsRemote = analyze(remote)
    if (partsInstalled.core.isEmpty() || partsRemote.core.isEmpty()) return false
    if (partsInstalled.core != partsRemote.core) return false
    if (!partsInstalled.packaging.containsAll(partsRemote.packaging)) return false
    if (partsRemote.metadata.isNotEmpty() && partsInstalled.metadata != partsRemote.metadata) {
        return false
    }
    return true
}

/**
 * Whether [tag] is a major-only pre-release tag ("v151_beta") for the major version of
 * [installed] ("151.0.7922.47"). Such rolling tags cannot be reconciled with a full version.
 */
fun isPreReleaseMajorMatch(tag: String, installed: String): Boolean {
    var normalizedTag = tag.trim().lowercase().replace('_', '-')
    normalizedTag = leadingV.replaceFirst(normalizedTag, "")
    val match = majorOnlyPreRelease.find(normalizedTag) ?: return false
    val installedCore = analyze(installed).core
    if (installedCore.isEmpty()) return false
    val installedMajor = firstNumber.find(installedCore.joinToString("."))
    return installedMajor != null && installedMajor.value == match.groupValues[1]
}
