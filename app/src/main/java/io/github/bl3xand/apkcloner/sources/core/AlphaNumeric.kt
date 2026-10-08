package io.github.bl3xand.apkcloner.sources.core

/** Compares strings run by run: digit runs numerically, a number sorts after text. */
fun compareAlphaNumeric(a: String, b: String): Int {
    val aParts = splitAlphaNumeric(a)
    val bParts = splitAlphaNumeric(b)
    var i = 0
    while (i < aParts.size && i < bParts.size) {
        val aPart = aParts[i]
        val bPart = bParts[i]
        val aIsNumber = isDigit(aPart)
        val bIsNumber = isDigit(bPart)
        if (aIsNumber && bIsNumber) {
            val aNumber = aPart.toLongOrNull()
            val bNumber = bPart.toLongOrNull()
            val cmp = if (aNumber == null || bNumber == null) aPart.compareTo(bPart) else aNumber.compareTo(bNumber)
            if (cmp != 0) return cmp
        } else if (!aIsNumber && !bIsNumber) {
            val cmp = aPart.compareTo(bPart)
            if (cmp != 0) return cmp
        } else {
            return if (aIsNumber) 1 else -1
        }
        i++
    }
    return aParts.size.compareTo(bParts.size)
}

private fun splitAlphaNumeric(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var numeric = s[0] in '0'..'9'
    current.append(s[0])
    for (i in 1 until s.length) {
        val currentIsNumeric = s[i] in '0'..'9'
        if (currentIsNumeric == numeric) {
            current.append(s[i])
        } else {
            parts.add(current.toString())
            current.setLength(0)
            current.append(s[i])
            numeric = currentIsNumeric
        }
    }
    parts.add(current.toString())
    return parts
}

private fun isDigit(s: String): Boolean = s.isNotEmpty() && s[0] in '0'..'9'

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    var value = if (unit == 0) "%.0f".format(java.util.Locale.ROOT, size) else "%.1f".format(java.util.Locale.ROOT, size)
    // Rounding can reach the next unit ("1024.0 KB").
    if (unit > 0 && unit < units.size - 1 && value.toDouble() >= 1024) {
        size /= 1024
        unit++
        value = "%.1f".format(java.util.Locale.ROOT, size)
    }
    return "$value ${units[unit]}"
}

fun formatDownloadSize(receivedBytes: Long?, totalBytes: Long?): String? {
    if (receivedBytes == null) return null
    if (totalBytes != null && totalBytes > 0) return "${formatBytes(receivedBytes)} / ${formatBytes(totalBytes)}"
    return formatBytes(receivedBytes)
}
