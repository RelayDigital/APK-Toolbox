package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.SearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    @Test
    fun theVeryNameComesFirst() {
        assertEquals(0, SearchMatch.rank("т банк", "Т-Банк"))
        assertEquals(0, SearchMatch.rank("Obtainium", "obtainium"))
    }

    @Test
    fun closerNamesRankHigher() {
        val query = "т банк"
        val exact = SearchMatch.rank(query, "Т-Банк")
        val starts = SearchMatch.rank(query, "Т-Банк Инвестиции")
        val phrase = SearchMatch.rank(query, "Мой Т-Банк бизнес")
        val words = SearchMatch.rank(query, "Банк Т Плюс")
        val some = SearchMatch.rank(query, "Альфа-Банк")
        val none = SearchMatch.rank(query, "Калькулятор")
        assertTrue(exact < starts && starts < phrase && phrase < words && words < some && some < none)
    }

    @Test
    fun wordsMayBeInTheDescription() {
        assertEquals(4, SearchMatch.rank("fossify clock", "Clock", "org.fossify.clock"))
    }
}
