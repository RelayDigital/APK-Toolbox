package io.github.bl3xand.apkcloner.sources.core

import java.math.BigInteger

// Version extraction, classification into "standard" shapes, comparison, and the reconciliation
// of the tracked version with the one reported by the system. Everything here is pure.

private const val DEFAULT_MATCH_GROUP = "0"

/** Returns an error text when [value] is not a valid regular expression. */
fun regExValidator(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    return try {
        Regex(value)
        null
    } catch (e: Exception) {
        Tr.get("invalidRegEx")
    }
}

/**
 * Applies [versionExtractionRegEx] to [stringToCheck] and returns the chosen group of the last
 * match. Null when no regex is set; [NoVersionError] when it does not match or yields nothing.
 */
fun extractVersion(
    versionExtractionRegEx: String?,
    matchGroupString: String?,
    stringToCheck: String,
): String? {
    if (versionExtractionRegEx.isNullOrEmpty()) return null
    val match = Regex(versionExtractionRegEx).findAll(stringToCheck).lastOrNull()
        ?: throw NoVersionError()
    val trimmedGroup = matchGroupString?.trim()
    val version = replaceMatchGroupsInString(
        match,
        if (trimmedGroup.isNullOrEmpty()) DEFAULT_MATCH_GROUP else trimmedGroup,
    )
    if (version.isNullOrEmpty()) throw NoVersionError()
    return version
}

/** Replaces `$N` references with match groups; `\$N` keeps the reference literal. */
private fun replaceMatchGroupsInString(match: MatchResult, groupString: String): String? {
    var matchGroupString = groupString
    if (Regex("^\\d+$").containsMatchIn(matchGroupString)) matchGroupString = "$$matchGroupString"
    val numbers = Regex("\\$\\d+").findAll(matchGroupString).toList()
    if (numbers.isEmpty()) return null
    var output = matchGroupString
    for (numberMatch in numbers) {
        val number = numberMatch.value
        val index = number.substring(1).toInt()
        val group = if (index <= match.groupValues.size - 1) match.groups[index]?.value ?: "" else ""
        val escaped = output.contains("\\$number")
        output = if (!escaped) output.replace(number, group) else output.replace("\\$number", number)
    }
    return output
}

// A numeric basic (1, 1.2, 1.2.3, 1.2.3.4) optionally followed by a pre-release qualifier
// and/or a trailing numeric build.
private val standardVersionPatterns: List<String> = run {
    val basics = listOf(
        "[0-9]+",
        "[0-9]+\\.[0-9]+",
        "[0-9]+\\.[0-9]+\\.[0-9]+",
        "[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+",
    )
    val preSuffixes = listOf("-", "\\+")
    val suffixes = listOf("alpha", "beta", "rc", "pre", "dev", "snapshot", "nightly", "ose", "[0-9]+")
    val finals = listOf("\\+[0-9]+", "[0-9]+")
    val results = LinkedHashSet<String>()
    for (basic in basics) {
        results.add(basic)
        for (preSuffix in preSuffixes) {
            for (suffix in suffixes) {
                results.add("$basic$suffix")
                results.add("$basic$preSuffix$suffix")
                for (finalSuffix in finals) {
                    results.add("$basic$suffix$finalSuffix")
                    results.add("$basic$preSuffix$suffix$finalSuffix")
                }
            }
        }
    }
    results.toList()
}

private val strictStandardVersionRegExes = standardVersionPatterns.map { it to Regex("^$it$") }
private val looseStandardVersionRegExes = standardVersionPatterns.map { it to Regex(it) }

private const val MAX_FORMAT_CACHE_SIZE = 4096
private val strictFormatCache = HashMap<String, Set<String>>()
private val looseFormatCache = HashMap<String, Set<String>>()

/** The standard patterns [version] matches: the whole string (strict) or any part (loose). */
fun findStandardFormatsForVersion(version: String, strict: Boolean): Set<String> {
    val cache = if (strict) strictFormatCache else looseFormatCache
    synchronized(cache) {
        cache[version]?.let { return it }
    }
    val patterns = if (strict) strictStandardVersionRegExes else looseStandardVersionRegExes
    val results = LinkedHashSet<String>()
    for ((pattern, regex) in patterns) {
        if (regex.containsMatchIn(version)) results.add(pattern)
    }
    synchronized(cache) {
        if (cache.size >= MAX_FORMAT_CACHE_SIZE) cache.clear()
        cache[version] = results
    }
    return results
}

/** Whether the first match of [pattern] is the same text in both strings. */
fun doStringsMatchUnderRegEx(pattern: String, value1: String, value2: String): Boolean {
    val regex = Regex(pattern)
    val match1 = regex.find(value1)
    val match2 = regex.find(value2)
    return if (match1 != null && match2 != null) match1.value == match2.value else false
}

/**
 * Numeric comparison of two versions that share a loose standard format: negative when
 * [version1] is older, positive when newer, 0 when equal, null when not comparable.
 */
fun compareVersionsNumerically(version1: String, version2: String): Int? {
    val commonFormats = findStandardFormatsForVersion(version1, strict = false)
        .intersect(findStandardFormatsForVersion(version2, strict = false))
    if (commonFormats.isEmpty()) return null
    val digitRun = Regex("[0-9]+")
    var mostSpecific = commonFormats.first()
    var mostSpecificRuns = digitRun.findAll(mostSpecific).count()
    for (format in commonFormats) {
        val runs = digitRun.findAll(format).count()
        if (runs > mostSpecificRuns || (runs == mostSpecificRuns && format.length > mostSpecific.length)) {
            mostSpecific = format
            mostSpecificRuns = runs
        }
    }
    val mostSpecificRegex = Regex(mostSpecific)
    fun numericRuns(version: String): List<BigInteger> =
        digitRun.findAll(mostSpecificRegex.find(version)!!.value).map { BigInteger(it.value) }.toList()

    val runs1 = numericRuns(version1)
    val runs2 = numericRuns(version2)
    for (i in runs1.indices) {
        if (runs1[i] != runs2[i]) return if (runs1[i] > runs2[i]) 1 else -1
    }
    return 0
}

/** [version] is the string to keep: the comparison one when both match, else the template. */
data class VersionComparison(val areEqual: Boolean, val version: String)

/** Reconciles two versions sharing a standard format; null when they share none. */
fun reconcileVersionDifferences(templateVersion: String, comparisonVersion: String): VersionComparison? {
    val templateFormats = findStandardFormatsForVersion(templateVersion, strict = true)
    var comparisonFormats = findStandardFormatsForVersion(comparisonVersion, strict = true)
    if (comparisonFormats.isEmpty()) {
        comparisonFormats = findStandardFormatsForVersion(comparisonVersion, strict = false)
    }
    val commonFormats = templateFormats.intersect(comparisonFormats)
    if (commonFormats.isEmpty()) return null
    for (pattern in commonFormats) {
        if (doStringsMatchUnderRegEx(pattern, comparisonVersion, templateVersion)) {
            return VersionComparison(true, comparisonVersion)
        }
    }
    return VersionComparison(false, templateVersion)
}

/**
 * Whether standard version detection can work for an app. A rolling major-only pre-release tag
 * cannot be reconciled with a full installed version, so detection is off for it (except on
 * naive sources) and the update stays visible.
 */
fun versionDetectionPossible(
    trackOnly: Boolean,
    releaseDateAsVersion: Boolean,
    isHtmlWithNoVersionDetection: Boolean,
    versionDetectionDisallowed: Boolean,
    realInstalledVersion: String?,
    trackedVersion: String?,
    latestVersion: String,
    naiveStandardVersionDetection: Boolean,
): Boolean {
    if (trackOnly || releaseDateAsVersion || isHtmlWithNoVersionDetection || versionDetectionDisallowed) {
        return false
    }
    if (realInstalledVersion == null || trackedVersion == null) return false
    val rollingTag = isPreReleaseMajorMatch(latestVersion, realInstalledVersion) ||
        isPreReleaseMajorMatch(latestVersion, trackedVersion)
    if (rollingTag && !naiveStandardVersionDetection) return false
    return reconcileVersionDifferences(realInstalledVersion, trackedVersion) != null ||
        versionsAreCosmeticallyEqual(realInstalledVersion, trackedVersion) ||
        versionsAreCosmeticallyEqual(realInstalledVersion, latestVersion) ||
        naiveStandardVersionDetection
}

/**
 * Corrects the tracked version using only version strings: collapses cosmetic differences to
 * [latestVersion], lets the device's real version override a stale value, reconciles the two,
 * and collapses again. Returns the corrected version, or null when nothing changes.
 */
fun reconcileTrackedVersion(
    trackedVersion: String?,
    realInstalledVersion: String?,
    latestVersion: String,
    versionDetectionIsStandard: Boolean,
    naiveStandardVersionDetection: Boolean,
): String? {
    if (trackedVersion == null) return null
    var current: String = trackedVersion
    var changed = false

    fun collapseIfSameRelease(version: String) {
        if (current == latestVersion) return
        if (installedMatchesRemote(installed = version, remote = latestVersion)) {
            current = latestVersion
            changed = true
        }
    }

    collapseIfSameRelease(current)
    if (realInstalledVersion != null) collapseIfSameRelease(realInstalledVersion)
    if (realInstalledVersion != null && current != realInstalledVersion && versionDetectionIsStandard) {
        val corrected = reconcileVersionDifferences(realInstalledVersion, current)
        if (corrected != null && !corrected.areEqual) {
            current = corrected.version
            changed = true
        } else if (naiveStandardVersionDetection &&
            !versionsAreCosmeticallyEqual(realInstalledVersion, current)
        ) {
            current = realInstalledVersion
            changed = true
        }
    }
    collapseIfSameRelease(current)
    return if (changed) current else null
}

/**
 * Whether an update should be shown: installed differs from latest and, when downgrades are
 * hidden, is not numerically newer.
 */
fun isUpdateable(installedVersion: String?, latestVersion: String, hideDowngrades: Boolean): Boolean {
    if (installedVersion == null || installedVersion == latestVersion) return false
    if (!hideDowngrades) return true
    val comparison = compareVersionsNumerically(installedVersion, latestVersion)
    return comparison == null || comparison <= 0
}
