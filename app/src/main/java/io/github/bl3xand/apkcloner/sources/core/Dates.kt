package io.github.bl3xand.apkcloner.sources.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    /** The oldest date; stands in for "unknown" when sorting. */
    val epochZero: Instant = Instant.ofEpochSecond(-62_167_219_200L)

    private val isoLike = Regex(
        "^([+-]?\\d{4,6})-?(\\d\\d)-?(\\d\\d)(?:[ T](\\d\\d)(?::?(\\d\\d)(?::?(\\d\\d)(?:[.,](\\d+))?)?)?" +
            "( ?[zZ]| ?([-+])(\\d\\d)(?::?(\\d\\d))?)?)?$",
    )

    /**
     * Parses the ISO-8601 variants found in APIs ("2024-01-02T03:04:05Z", with an offset, with a
     * space instead of T, date only). A value without a zone is taken as local time.
     */
    fun tryParse(text: String?): Instant? {
        if (text == null) return null
        val match = isoLike.find(text.trim()) ?: return null
        return try {
            val g = match.groupValues
            val fraction = g[7].padEnd(9, '0').take(9)
            val local = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
                if (g[7].isEmpty()) 0 else fraction.toInt(),
            )
            when {
                g[8].isEmpty() -> local.atZone(ZoneId.systemDefault()).toInstant()
                g[9].isEmpty() -> local.toInstant(ZoneOffset.UTC)
                else -> {
                    val seconds = g[10].toInt() * 3600 + g[11].ifEmpty { "0" }.toInt() * 60
                    local.toInstant(ZoneOffset.ofTotalSeconds(if (g[9] == "-") -seconds else seconds))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** RFC 1123 date as used by HTTP and RSS ("Tue, 03 Jun 2025 10:00:00 GMT" or "+0000"). */
    fun tryParseRfc1123(text: String?): Instant? {
        if (text == null) return null
        val trimmed = text.trim()
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z")) {
            try {
                return OffsetDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toInstant()
            } catch (_: Exception) {
            }
            try {
                return java.time.ZonedDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toInstant()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** A date in an English pattern such as "MMM d, yyyy", at local midnight. */
    fun tryParseLocalDate(text: String?, pattern: String): Instant? {
        if (text == null) return null
        return try {
            LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                .atStartOfDay(ZoneId.systemDefault()).toInstant()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Merge sort that accepts a comparator which is not a proper ordering. Some release orderings
 * compare by name or by date depending on the pair, which the platform sort rejects outright.
 */
fun <T> tolerantSort(list: MutableList<T>, comparator: (T, T) -> Int) {
    if (list.size < 2) return
    val middle = list.size / 2
    val left = list.subList(0, middle).toMutableList()
    val right = list.subList(middle, list.size).toMutableList()
    tolerantSort(left, comparator)
    tolerantSort(right, comparator)
    var i = 0
    var j = 0
    var k = 0
    while (i < left.size && j < right.size) {
        list[k++] = if (comparator(left[i], right[j]) <= 0) left[i++] else right[j++]
    }
    while (i < left.size) list[k++] = left[i++]
    while (j < right.size) list[k++] = right[j++]
}
