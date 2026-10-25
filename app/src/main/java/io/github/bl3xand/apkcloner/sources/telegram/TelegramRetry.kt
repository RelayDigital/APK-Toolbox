package io.github.bl3xand.apkcloner.sources.telegram

import io.github.bl3xand.apkcloner.sources.core.CHECK_TIMEOUT_SECONDS

/**
 * How long a request to TDLib waits for its answer. Only the signing-in steps wait longer
 * than a check's budget: they open the client's database and sync with Telegram inside the
 * first request that follows, so that one waits out the start; a new client arms the long
 * budget again for its first request. Everything a check reads waits exactly what every check
 * path gives a source - [CHECK_TIMEOUT_SECONDS] - so no read can outwait the check that asked
 * for it.
 *
 * Kept free of TDLib and Android types so plain JVM tests can check the decisions;
 * TelegramClient only carries them out.
 */
internal class TelegramRetry(
    private val firstCallSeconds: Long = FIRST_CALL_SECONDS,
    private val laterCallsSeconds: Long = LATER_CALL_SECONDS,
    firstRequestAfterStart: Boolean = true,
) {
    companion object {
        /** The first request after the client came up: its own start is part of the wait. */
        const val FIRST_CALL_SECONDS = 90L

        /** The rest of the signing-in steps: the client is up, only the answer is waited for. */
        const val LATER_CALL_SECONDS = 60L

        /** What a read of a channel waits: the one budget every check path gives a source. */
        const val READ_SECONDS = CHECK_TIMEOUT_SECONDS
    }

    /** Whether the next request is the first after the client came up. */
    @Volatile
    var firstRequestAfterStart: Boolean = firstRequestAfterStart
        private set

    /** The budget of a signing-in step that begins now. */
    val timeoutSeconds: Long get() = if (firstRequestAfterStart) firstCallSeconds else laterCallsSeconds

    /** An answer arrived, so the client is up: later sign-in steps get the shorter budget. */
    fun onAnswerArrived() {
        firstRequestAfterStart = false
    }

    /** A new client came up: its first request carries a start again. */
    fun onClientStarted() {
        firstRequestAfterStart = true
    }
}