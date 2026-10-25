package io.github.bl3xand.apkcloner.sources.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions of the request path that stay the same however TDLib behaves: how long to wait
 * for an answer and whether one that got none is sent again. TDLib itself cannot be driven from
 * a unit test, so the policy is kept in this plain class and TelegramClient only carries it out.
 */
class TelegramRetryTest {

    private fun policy(firstRequestAfterStart: Boolean = false) =
        TelegramRetry(firstRequestAfterStart = firstRequestAfterStart)

    @Test
    fun firstCallGetsTheLongerBudget() {
        assertEquals(90L, policy(firstRequestAfterStart = true).timeoutSeconds)
        assertEquals(60L, policy().timeoutSeconds)
    }

    @Test
    fun anAnswerEndsTheFirstCallBudget() {
        val retry = policy(firstRequestAfterStart = true)
        retry.onAnswerArrived()
        assertFalse(retry.firstRequestAfterStart)
        assertEquals(60L, retry.timeoutSeconds)
    }

    @Test
    fun aNewClientArmsTheBudgetAgain() {
        val retry = policy(firstRequestAfterStart = true)
        retry.onAnswerArrived()
        retry.onClientStarted()
        assertTrue(retry.firstRequestAfterStart)
        assertEquals(90L, retry.timeoutSeconds)
    }

    @Test
    fun aTimeoutAloneDoesNotEndTheBudget() {
        // No answer is not an answer: only an arrived one ends the first-call budget.
        val retry = policy(firstRequestAfterStart = true)
        assertTrue(retry.firstRequestAfterStart)
    }

    @Test
    fun readsWithoutAnAnswerAreTriedAgainUntilTheAttemptsRunOut() {
        val retry = policy()
        assertTrue(retry.shouldTryAgain(attempt = 1, read = true))
        assertTrue(retry.shouldTryAgain(attempt = 2, read = true))
        assertFalse(retry.shouldTryAgain(attempt = 3, read = true))
        assertEquals(2_000L, retry.retryPauseMillis)
    }

    @Test
    fun anythingElseIsNotTriedAgain() {
        val retry = policy()
        assertFalse(retry.shouldTryAgain(attempt = 1, read = false))
    }
}