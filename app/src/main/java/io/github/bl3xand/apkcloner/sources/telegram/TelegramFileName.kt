package io.github.bl3xand.apkcloner.sources.telegram

/**
 * Reads a file name the way a release is named: which part is the version, and what is left of
 * the name without it. Two files are the same kind of release when what is left is the same,
 * so `App_1.2_arm64.apk` and `App_1.3_arm64.apk` belong together and `App_1.3_x86.apk` does not.
 */
object TelegramFileName {
    /** A number, dotted or not, standing on its own; a leading "v" belongs to it. */
    private val VERSION = Regex("""(?<![A-Za-z0-9.])[vV]?\d+(?:\.\d+)*(?![A-Za-z0-9])""")

    /** Parts of a name that hold digits without being a version. */
    private val NOT_A_VERSION = Regex("""arm64[-_]v8a|armeabi[-_]v7a|x86[-_]64|x86|arm64|armv\d+a?|android[-_ ]?\d+|sdk[-_ ]?\d+""", RegexOption.IGNORE_CASE)

    private const val PLACEHOLDER = "*"

    /** The version parts of [name] in order; what is not a version is blanked out first. */
    private fun versionParts(name: String): List<MatchResult> {
        val masked = NOT_A_VERSION.replace(baseName(name)) { "x".repeat(it.value.length) }
        return VERSION.findAll(masked).toList()
    }

    private fun baseName(name: String): String = name.substringBeforeLast('.', name)

    private fun extension(name: String): String = if ('.' in name) "." + name.substringAfterLast('.') else ""

    /** The version [name] carries, or null when it carries none. Several parts are kept, in order. */
    fun version(name: String): String? =
        versionParts(name).map { it.value.trimStart('v', 'V') }.takeIf { it.isNotEmpty() }?.joinToString("-")

    /** [name] with each version part replaced by a star, as it is shown to the user. */
    fun pattern(name: String): String {
        val base = StringBuilder(baseName(name))
        for (part in versionParts(name).asReversed()) base.replace(part.range.first, part.range.last + 1, PLACEHOLDER)
        return base.toString() + extension(name)
    }

    /** What two files of the same kind have in common: the pattern, whatever the letter case. */
    fun family(name: String): String = pattern(name).lowercase()
}
