package io.github.bl3xand.apkcloner.sources.telegram

import io.github.bl3xand.apkcloner.sources.core.CHECK_TIMEOUT_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions of the request path that stay the same however TDLib behaves: how long to wait
 * for an answer. TDLib itself cannot be driven from a unit test, so the policy is kept in this
 * plain class and TelegramClient only carries it out.
 */
class TelegramRetryTest {

    private fun policy(firstRequestAfterStart: Boolean = false) =
        TelegramRetry(firstRequestAfterStart = firstRequestAfterStart)

    @Test
    fun signingInGetsTheLongerBudgets() {
        assertEquals(90L, policy(firstRequestAfterStart = true).timeoutSeconds)
        assertEquals(60L, policy().timeoutSeconds)
    }

    @Test
    fun aReadGetsTheCheckBudget() {
        assertEquals(CHECK_TIMEOUT_SECONDS, TelegramRetry.READ_SECONDS)
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
}