package io.github.bl3xand.apkcloner.sources.core

import java.util.concurrent.atomic.AtomicReference

/** A user regex that took too long against its input; the per-app check says so and moves on. */
class RegExTimeoutError(val pattern: String, val budgetMs: Long) :
    SourceError(code = "REGEX_TIMEOUT", data = mapOf("pattern" to pattern))

/**
 * Runs user-supplied regular expressions with a time budget: a catastrophic-backtracking
 * pattern against a long, source-controlled string must fail as a normal per-app error
 * instead of hanging the thread it runs on (the UI thread included - the live field
 * validation runs these too).
 *
 * Two guards, because no regex engine here has a match timeout of its own:
 *
 * 1. The input is wrapped in a CharSequence that counts its reads and throws once the
 *    deadline has passed. On engines that consult the input through the wrapper all through
 *    their backtracking (the desktop JVM's java.util.regex), the deadline fires mid-match,
 *    cheaply and precisely.
 * 2. The match itself runs on a dedicated thread that the caller waits for with
 *    [budgetMs]: when the thread does not come back in time, it is abandoned - [Thread.stop]
 *    is gone and a native matcher (ART's java.util.regex is ICU-backed) can consume the
 *    input into its own buffer and never re-read the wrapper, so the counting deadline
 *    never fires and only the abandoning wait ends the spin. An abandoned thread is left to
 *    finish its backtracking on its own and die; it holds its input alive but nothing the
 *    app waits on. One leaked thread per too-slow pattern, rare by construction (the field
 *    validation rejects such patterns before they are ever stored), documented here.
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
            if (++reads % CHECK_EVERY_READS == 0 && clock.get()() > deadline) {
                throw BudgetExceededError()
            }
            return value
        }
    }

    /** The clock the deadlines read; a seam the tests move. */
    internal val clock = AtomicReference<() -> Long>(System::currentTimeMillis)

    /**
     * Runs [block] on a dedicated thread and waits for it, with the two guards of the class
     * doc: the in-match deadline the wrapper raises, and the abandoning wait for engines
     * that never re-read the wrapper (ART's ICU matcher). The thread is a daemon, so a
     * leaked one never keeps the process alive on its own.
     */
    internal fun <T> withWatchdog(budgetMs: Long, block: () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val thread = Thread({
            result.compareAndSet(null, runCatching(block))
        }, "regex-probe").apply { isDaemon = true }
        thread.start()
        // The wrapper's deadline is budgetMs from now; the wait gives the thread the same
        // budget again, so an engine that honors the wrapper is interrupted by it inside the
        // wait, and one that does not is abandoned here.
        val finished = try {
            thread.join(budgetMs)
            result.get() != null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            // The thread is abandoned, not stopped: it will end on its own and its result is
            // ignored. That leak is the price of ending a native backtracking spin; see the
            // class doc.
            throw BudgetExceededError()
        }
        return result.get()!!.getOrThrow()
    }

    private fun deadline(budgetMs: Long): Long = clock.get()() + budgetMs

    /** Whether [regex] matches somewhere in [input] within [budgetMs]. */
    fun containsMatchIn(regex: Regex, input: String, budgetMs: Long): Boolean =
        try {
            withWatchdog(budgetMs) {
                regex.containsMatchIn(DeadlinedCharSequence(input, deadline(budgetMs)))
            }
        } catch (e: BudgetExceededError) {
            throw RegExTimeoutError(regex.pattern, budgetMs)
        }

    /** The last match of [regex] in [input] within [budgetMs], with its groups read out. */
    fun lastMatch(regex: Regex, input: String, budgetMs: Long): Match? = try {
        withWatchdog(budgetMs) {
            val wrapped = DeadlinedCharSequence(input, deadline(budgetMs))
            regex.findAll(wrapped).lastOrNull()?.let { Match(it.value, it.groupValues) }
        }
    } catch (e: BudgetExceededError) {
        throw RegExTimeoutError(regex.pattern, budgetMs)
    }
}