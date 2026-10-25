package io.github.bl3xand.apkcloner.sources.telegram

/**
 * How long a request to TDLib waits for its answer, and whether one that got none is sent
 * again. The client's own start - opening its database, syncing with Telegram - happens inside
 * the first request that follows it, so that one waits longer than the rest; a new client arms
 * the long budget again for its first request.
 *
 * Kept free of TDLib and Android types so plain JVM tests can check the decisions;
 * TelegramClient only carries them out.
 */
internal class TelegramRetry(
    private val firstCallSeconds: Long = FIRST_CALL_SECONDS,
    private val laterCallsSeconds: Long = LATER_CALL_SECONDS,
    val retryPauseMillis: Long = RETRY_PAUSE_MILLIS,
    firstRequestAfterStart: Boolean = true,
) {
    companion object {
        /** The first request after the client came up: its own start is part of the wait. */
        const val FIRST_CALL_SECONDS = 90L

        /** The rest: the client is up, only the answer is waited for. */
        const val LATER_CALL_SECONDS = 60L

        /** How long to wait before a read that got no answer is sent again. */
        const val RETRY_PAUSE_MILLIS = 2_000L

        /** How many times in all a read that keeps getting no answer is sent. */
        const val READ_ATTEMPTS = 3
    }

    /** Whether the next request is the first after the client came up. */
    @Volatile
    var firstRequestAfterStart: Boolean = firstRequestAfterStart
        private set

    /** The budget of a request that begins now, in seconds. */
    val timeoutSeconds: Long get() = if (firstRequestAfterStart) firstCallSeconds else laterCallsSeconds

    /** An answer arrived, so the client is up: later requests get the shorter budget. */
    fun onAnswerArrived() {
        firstRequestAfterStart = false
    }

    /** A new client came up: its first request carries a start again. */
    fun onClientStarted() {
        firstRequestAfterStart = true
    }

    /**
     * Whether a read that got no answer on attempt [attempt] (counting from 1) is sent again.
     * Only reads are, and only while attempts are left: the steps of signing in are driven by
     * hand and must not fire twice, and a file part has the download's own retries around it.
     */
    fun shouldTryAgain(attempt: Int, read: Boolean): Boolean = read && attempt < READ_ATTEMPTS
}