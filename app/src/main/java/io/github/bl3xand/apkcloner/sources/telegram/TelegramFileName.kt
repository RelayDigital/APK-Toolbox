package io.github.bl3xand.apkcloner.sources.telegram

/**
 * Reads the names of files posted in channels. Channels name them every which way -
 * `Instander ver.17.2 Clone.apk`, `SD Maid SE_2.2.0-rc0.apk`, `Subnautica+1.22-@EasyAPK.apk`,
 * `Nova Launcher-88700 (8.8.7)-079r302842 (1).apk` - so the reading goes by what holds across
 * them: the name of the app comes first, the version follows it, and what comes after the
 * version is either more numbers of the release or a word that says which build of the app it is.
 *
 * Two files are releases of the same thing when they agree in the app and in those words, and
 * only then; that is what keeps an app's updates together and other apps out of them.
 */
object TelegramFileName {
    /** Parts of a name that hold digits without being a version. */
    private val ARCHITECTURE = Regex("""arm64[-_]v8a|armeabi[-_]v7a|x86[-_]64|x86|arm64|armv7|armv8|universal""", RegexOption.IGNORE_CASE)

    /**
     * Where the version starts: a number said to be one ("v1", "ver.2", "build3"), a dotted or an
     * underscored one, or a bare number of three digits and more.
     */
    private val VERSION = Regex(
        """(?<![A-Za-z0-9])(?:version|ver|build|v)\.?\s*\d+(?:[._]\d+)*[A-Za-z0-9]*""" +
            """|(?<![A-Za-z0-9.])\d+(?:\.\d+)+[A-Za-z0-9]*""" +
            """|(?<![A-Za-z0-9.])\d+(?:_\d+)+[A-Za-z0-9]*""" +
            """|(?<![A-Za-z0-9.])\d{3,}(?![A-Za-z0-9.])""",
        RegexOption.IGNORE_CASE,
    )
    private val VERSION_PREFIX = Regex("""^(?:version|ver|build|v)\.?\s*""", RegexOption.IGNORE_CASE)

    /** "(1)" at the end: the mark of a file that was saved a second time. */
    private val COPY_MARK = Regex("""\s*\(\d+\)\s*$""")

    /** The channel's own tag, as in "-@EasyAPK". */
    private val CHANNEL_TAG = Regex("""[-_ +]*@[\p{L}\p{N}_]+""")

    /** What the part after the version is made of. */
    private val TOKEN = Regex("""[^\s\-_,;+()\[\]]+""")
    private val DIGIT = Regex("""\d""")
    private val LETTER = Regex("""\p{L}""")
    private val KEY_SEPARATORS = Regex("""[\s\-_.]+""")
    private val SPACES = Regex("""\s+""")

    /** Words after a version that say nothing about which build it is. */
    private val NOISE = setOf("release", "build", "final", "stable", "apk", "ver", "version")
    private const val EDGE = " -_.,;+()[]"
    private const val FAMILY_BAR = " | "
    private const val MIN_WORD_LETTERS = 2

    fun parse(fileName: String): ParsedFileName {
        var base = fileName.substringBeforeLast('.', fileName)
        base = COPY_MARK.replace(base, "")
        base = CHANNEL_TAG.replace(base, "")
        // A name without a single space has its words joined by plus signs.
        if (' ' !in base) base = base.replace('+', ' ')
        val masked = ARCHITECTURE.replace(base) { "x".repeat(it.value.length) }
        // A name that begins with a number is called that ("1.1.1.1"); the version comes later.
        val match = VERSION.findAll(masked).firstOrNull { it.range.first > 0 }
        val rawName = if (match != null) base.substring(0, match.range.first) else base
        val tail = if (match != null) base.substring(match.range.last + 1) else ""
        var version = match?.let { VERSION_PREFIX.replace(base.substring(it.range.first, it.range.last + 1), "").replace('_', '.') }
        val name = rawName.replace('_', ' ').trim { it in EDGE }.ifEmpty { base.trim { it in EDGE } }.let { SPACES.replace(it, " ") }

        val architectures = ARCHITECTURE.findAll(tail).map { it.value.lowercase().replace('_', '-') }.toList()
        val tokens = TOKEN.findAll(ARCHITECTURE.replace(tail, " ")).map { it.value.trim('.') }.filter { it.isNotEmpty() }.toList()
        // A word says which build this is; anything with a digit in it belongs to the release.
        val words = tokens.filter { !DIGIT.containsMatchIn(it) && LETTER.findAll(it).count() >= MIN_WORD_LETTERS && it.lowercase() !in NOISE }
        val numbers = tokens.filter { DIGIT.containsMatchIn(it) }
        if (version != null && numbers.isNotEmpty()) version += "-" + numbers.joinToString("-")
        val variants = words + architectures

        var family = KEY_SEPARATORS.replace(name.lowercase(), " ").trim()
        if (variants.isNotEmpty()) family += FAMILY_BAR + variants.map { it.lowercase() }.toSortedSet().joinToString(" ")
        val title = if (variants.isEmpty()) name else "$name (${variants.joinToString(", ")})"
        return ParsedFileName(family, title, name, version)
    }

    /** What every release of the same thing has in common; see [ParsedFileName.family]. */
    fun family(fileName: String): String = parse(fileName).family

    /** The version [fileName] carries, or null when it carries none. */
    fun version(fileName: String): String? = parse(fileName).version

    /** What the kind of file is called, for showing to the user. */
    fun title(fileName: String): String = parse(fileName).title

    /** The app's part of a [family]: what to look a channel through for when only this app is wanted. */
    fun appOf(family: String): String = family.substringBefore(FAMILY_BAR)
}
