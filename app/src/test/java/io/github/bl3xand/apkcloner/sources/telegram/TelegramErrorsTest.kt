package io.github.bl3xand.apkcloner.sources.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an answer of Telegram is read as, on names really seen in its answers. */
class TelegramErrorsTest {

    @Test
    fun aWrongClientIdMeansTheSessionIsGone() {
        // TDLib's answer to a request whose client was closed while it was in flight.
        assertTrue(TelegramErrors.isAccountRefused(400, "Wrong client id"))
    }

    @Test
    fun aRefusedSessionIsStillRecognized() {
        assertTrue(TelegramErrors.isAccountRefused(401, "AUTH_KEY_UNREGISTERED"))
        assertTrue(TelegramErrors.isAccountRefused(401, "SESSION_REVOKED"))
    }

    @Test
    fun otherAnswersSayNothingAboutTheSession() {
        assertFalse(TelegramErrors.isAccountRefused(400, "PHONE_NUMBER_INVALID"))
        assertFalse(TelegramErrors.isAccountRefused(400, "CHANNEL_PRIVATE"))
    }
}