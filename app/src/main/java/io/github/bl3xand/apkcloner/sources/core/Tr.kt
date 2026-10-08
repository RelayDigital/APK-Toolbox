package io.github.bl3xand.apkcloner.sources.core

/**
 * Text lookup for the Android-free part of the sources code. The app installs a resolver backed
 * by string resources; without one (unit tests) the key itself is returned.
 */
object Tr {
    @Volatile
    var resolver: ((key: String, args: List<String>) -> String)? = null

    @Volatile
    var pluralResolver: ((key: String, count: Int, args: List<String>) -> String)? = null

    /** Two-letter language of the texts the resolver returns; only used for list punctuation. */
    @Volatile
    var languageCode: String = "en"

    fun get(key: String, vararg args: String): String =
        resolver?.invoke(key, args.toList()) ?: fallback(key, args.toList())

    fun plural(key: String, count: Int, vararg args: String): String =
        pluralResolver?.invoke(key, count, args.toList())
            ?: fallback(key, listOf(count.toString()) + args)

    private fun fallback(key: String, args: List<String>): String =
        if (args.isEmpty()) key else "$key(${args.joinToString(", ")})"
}
