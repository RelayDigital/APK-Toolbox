package io.github.bl3xand.apkcloner.sources.core

/** How well a search result answers what was typed. */
object SearchMatch {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Letters and digits only, in lower case, single spaces between words: "T-Bank" is "t bank". */
    private fun plain(text: String): String = text.lowercase().replace(SEPARATORS, " ").trim()

    /**
     * The smaller, the closer [title] is to [query]: the very name, a name that starts with it,
     * one that has it as a phrase, one that has all of its words, a description that has them,
     * a name that has some of them, and whatever else a source saw fit to return.
     */
    fun rank(query: String, title: String, description: String = ""): Int {
        val wanted = plain(query)
        val name = plain(title)
        val words = wanted.split(' ').filter { it.isNotEmpty() }
        val nameWords = name.split(' ')
        fun inName(word: String) = nameWords.any { it.startsWith(word) }
        return when {
            words.isEmpty() -> 6
            name == wanted -> 0
            name.startsWith("$wanted ") || name.startsWith(wanted) && words.size == 1 -> 1
            " $name ".contains(" $wanted ") -> 2
            words.all(::inName) -> 3
            words.all { inName(it) || plain(description).contains(it) } -> 4
            words.any(::inName) -> 5
            else -> 6
        }
    }
}
