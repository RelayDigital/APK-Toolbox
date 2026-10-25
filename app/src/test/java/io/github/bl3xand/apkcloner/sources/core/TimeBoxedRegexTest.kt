package io.github.bl3xand.apkcloner.sources.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time budget on user-supplied regexes: a catastrophic pattern must fail fast as an error. */
class TimeBoxedRegexTest {
    private fun evilPattern() = Regex("(a+){10}b")
    private fun evilInput(length: Int = 40) = "a".repeat(length) + "c"

    @Test
    fun evilPatternThrowsQuickly() {
        val budget = 2_000L
        val started = System.currentTimeMillis()
        val error = assertThrows(RegExTimeoutError::class.java) {
            TimeBoxedRegex.containsMatchIn(evilPattern(), evilInput(), budget)
        }
        // The throw must come from the budget, and long before the pattern would finish on its
        // own (which is minutes for this input on a phone).
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < budget * 2)
        assertEquals(evilPattern().pattern, error.pattern)
        assertEquals(budget, error.budgetMs)
        assertEquals("REGEX_TIMEOUT", error.code)
    }

    @Test
    fun evilPatternTimesOutOnFindToo() {
        assertThrows(RegExTimeoutError::class.java) {
            TimeBoxedRegex.lastMatch(evilPattern(), evilInput(), 2_000L)
        }
    }

    @Test
    fun normalPatternsAreUnaffected() {
        assertTrue(TimeBoxedRegex.containsMatchIn(Regex("app-[0-9.]+\\.apk"), "My app-1.2.3.apk file", 2_000L))
        assertFalse(TimeBoxedRegex.containsMatchIn(Regex("app-[0-9.]+\\.apk"), "readme.txt", 2_000L))
    }

    @Test
    fun lastMatchKeepsGroupsAndPicksTheLast() {
        val match = TimeBoxedRegex.lastMatch(Regex("v(\\d+)"), "first v1 then v22 end", 2_000L)!!
        assertEquals("v22", match.value)
        assertEquals(listOf("v22", "22"), match.groupValues)
    }

    @Test
    fun lastMatchReturnsNullWhenNothingMatches() {
        assertEquals(null, TimeBoxedRegex.lastMatch(Regex("zzz"), "abc", 2_000L))
    }

    @Test
    fun longButLinearInputPasses() {
        // A linear pattern over a long input must not trip the budget: the budget exists for
        // backtracking blowups, not for honest work.
        val input = ("lorem ipsum dolor sit amet ".repeat(2_000))
        assertTrue(TimeBoxedRegex.containsMatchIn(Regex("ipsum.*amet"), input, 2_000L))
    }

    @Test
    fun filterApksTimesOutInsteadOfHanging() {
        val urls = listOf(NamedUrl("app-" + "a".repeat(40) + ".apk", "https://example.com/app.apk"))
        assertThrows(RegExTimeoutError::class.java) {
            ApkFilter.filterApks(urls, "(a+){10}b", invert = false)
        }
    }

    @Test
    fun extractVersionTimesOutInsteadOfHanging() {
        assertThrows(RegExTimeoutError::class.java) {
            extractVersion("(a+){10}b", "0", "a".repeat(40) + "c")
        }
    }

    @Test
    fun regExValidatorRejectsEvilPatterns() {
        // The validator is the entry door: an evil pattern must be told apart from a good one,
        // by running it, not by compiling it.
        assertEquals(Tr.get("invalidRegExTooSlow"), regExValidator("(a+){10}b"))
    }

    @Test
    fun regExValidatorAcceptsGoodPatterns() {
        assertEquals(null, regExValidator("app-[0-9.]+\\.apk"))
        assertEquals(null, regExValidator(""))
        assertEquals(null, regExValidator(null))
    }

    @Test
    fun regExValidatorKeepsRejectingBadSyntax() {
        assertEquals(Tr.get("invalidRegEx"), regExValidator("(unclosed"))
    }
}