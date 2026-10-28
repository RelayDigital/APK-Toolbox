package io.github.bl3xand.apkcloner.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A log a user attaches to a public issue must not carry their secrets: URL query values
 * whose name says they are one (token, key, password...) are replaced before anything is
 * stored or printed. That is the contract tested here, through the same path a log() call
 * takes.
 */
class LogRedactionTest {

    @Test
    fun secretQueryValuesAreRedacted() {
        val line = "Downloading https://example.com/app.apk?token=abc123&version=2&api_key=ZZZ from source"
        val redacted = AppLog.redactForTest(line)
        assertFalse("the token value must be gone", redacted.contains("abc123"))
        assertFalse("the api_key value must be gone", redacted.contains("ZZZ"))
        assertTrue("the token is named but emptied", redacted.contains("token=<redacted>"))
        assertTrue("an ordinary parameter stands", redacted.contains("version=2"))
    }

    @Test
    fun everyOccurrenceIsRedactedWhereverItSits() {
        val redacted = AppLog.redactForTest("https://a.io/x?sig=1 and again https://b.io/y?sig=2")
        assertFalse(redacted.contains("sig=1"))
        assertFalse(redacted.contains("sig=2"))
        assertEquals("https://a.io/x?sig=<redacted> and again https://b.io/y?sig=<redacted>", redacted)
    }

    @Test
    fun plainLinesPassThroughUntouched() {
        val line = "Renamed com.example.app to com.example.app.clone, 12345 bytes"
        assertEquals(line, AppLog.redactForTest(line))
    }
}