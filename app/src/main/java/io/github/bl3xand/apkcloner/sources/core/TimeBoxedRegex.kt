package io.github.bl3xand.apkcloner.sources.core

/** A user regex that took too long against its input; the per-app check says so and moves on. */
class RegExTimeoutError(val pattern: String, val budgetMs: Long) :
    SourceError(code = "REGEX_TIMEOUT", data = mapOf("pattern" to pattern))

/**
 * Runs user-supplied regular expressions with a time budget: a catastrophic-backtracking
 * pattern against a long, source-controlled string must fail as a normal per-app error
 * instead of hanging the checker thread (which would keep every later check waiting on
 * the update lock).
 *
 * The regex engine has no match timeout of its own, so the input is wrapped in a
 * CharSequence that counts its reads and throws once the deadline has passed: the matcher
 * consults the input all through its backtracking, so the deadline is checked often.
 */
object TimeBoxedRegex {
    /** Budget for one short string: a file name, a link, a release title. */
    const val SHORT_INPUT_BUDGET_MS = 2_000L

    /** Budget for whole-page text: version extraction runs over the entire page. */
    const val PAGE_INPUT_BUDGET_MS = 5_000L

    /** A match with its groups read out; safe to use once the matching is over. */
    class Match(val value: String, val groupValues: List<String>)

    /** The deadline signal; turned into [RegExTimeoutError] at the API edge. */
    private class BudgetExceededError : RuntimeException()

    /** The clock is read only every so many char reads, so the wrapper stays cheap. */
    private const val CHECK_EVERY_READS = 64

    private class DeadlinedCharSequence(
        private val text: CharSequence,
        private val deadline: Long,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = checked(text.length)
        override fun get(index: Int): Char = checked(text[index])
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlinedCharSequence(checked(text.subSequence(startIndex, endIndex)), deadline)

        // The matcher builds its groups as subSequence().toString(); without this the group
        // values would be the wrapper's default toString, not the matched text.
        override fun toString(): String = text.toString()

        private fun <T> checked(value: T): T {
            if (++reads % CHECK_EVERY_READS == 0 && System.currentTimeMillis() > deadline) {
                throw BudgetExceededError()
            }
            return value
        }
    }

    private fun deadline(budgetMs: Long): Long = System.currentTimeMillis() + budgetMs

    /** Whether [regex] matches somewhere in [input] within [budgetMs]. */
    fun containsMatchIn(regex: Regex, input: String, budgetMs: Long): Boolean = try {
        regex.containsMatchIn(DeadlinedCharSequence(input, deadline(budgetMs)))
    } catch (e: BudgetExceededError) {
        throw RegExTimeoutError(regex.pattern, budgetMs)
    }

    /** The last match of [regex] in [input] within [budgetMs], with its groups read out. */
    fun lastMatch(regex: Regex, input: String, budgetMs: Long): Match? = try {
        val wrapped = DeadlinedCharSequence(input, deadline(budgetMs))
        regex.findAll(wrapped).lastOrNull()?.let { Match(it.value, it.groupValues) }
    } catch (e: BudgetExceededError) {
        throw RegExTimeoutError(regex.pattern, budgetMs)
    }
}