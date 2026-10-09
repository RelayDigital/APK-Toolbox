package io.github.bl3xand.apkcloner.sources.telegram

import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr

/**
 * Turns what Telegram answers with into something a person can act on. Telegram names its errors
 * in capitals (PHONE_CODE_INVALID); the ones a user can run into get words of their own, the rest
 * are passed on as they are.
 */
object TelegramErrors {
    private val FLOOD_WAIT = Regex("""(?:FLOOD_WAIT_|FLOOD_PREMIUM_WAIT_|retry after )(\d+)""", RegexOption.IGNORE_CASE)
    private const val SECONDS_IN_MINUTE = 60

    /** Names that mean the account can no longer be used from here, in any request. */
    private val ACCOUNT_REFUSED = listOf(
        "AUTH_KEY_UNREGISTERED", "AUTH_KEY_INVALID", "SESSION_REVOKED", "SESSION_EXPIRED", "USER_DEACTIVATED", "Unauthorized",
    )

    private val MESSAGES = mapOf(
        "PHONE_NUMBER_INVALID" to "telegramErrPhone",
        "PHONE_NUMBER_BANNED" to "telegramErrBanned",
        "PHONE_NUMBER_UNOCCUPIED" to "telegramErrNoAccount",
        "PHONE_CODE_INVALID" to "telegramErrCode",
        "PHONE_CODE_EMPTY" to "telegramErrCode",
        "PHONE_CODE_EXPIRED" to "telegramErrCodeExpired",
        "EMAIL_INVALID" to "telegramErrEmail",
        "EMAIL_CODE_INVALID" to "telegramErrCode",
        "CODE_INVALID" to "telegramErrCode",
        "PASSWORD_HASH_INVALID" to "telegramErrPassword",
        "USERNAME_NOT_OCCUPIED" to "telegramErrNoChannel",
        "USERNAME_INVALID" to "telegramErrNoChannel",
        "CHANNEL_PRIVATE" to "telegramErrPrivate",
        "CHANNEL_INVALID" to "telegramErrNoChannel",
        "Chat not found" to "telegramErrNoChannel",
        "API_ID_INVALID" to "telegramErrKeys",
        "API_ID_PUBLISHED_FLOOD" to "telegramErrKeys",
        "UPDATE_APP_TO_LOGIN" to "telegramErrKeys",
        "FILE_REFERENCE_EXPIRED" to "telegramErrFileGone",
        "MESSAGE_ID_INVALID" to "telegramErrFileGone",
    )

    /** Whether [message] says the session is gone: signed out elsewhere, revoked, or the account closed. */
    fun isAccountRefused(code: Int, message: String): Boolean =
        code == 401 || ACCOUNT_REFUSED.any { message.contains(it, ignoreCase = true) }

    /** The error to throw for an answer of Telegram with this [code] and [message]. */
    fun toError(code: Int, message: String): SourceError {
        FLOOD_WAIT.find(message)?.let { wait ->
            return RateLimitError(((wait.groupValues[1].toIntOrNull() ?: 0) + SECONDS_IN_MINUTE - 1) / SECONDS_IN_MINUTE)
        }
        if (isAccountRefused(code, message)) return SourceError(Tr.get("telegramErrSession"))
        MESSAGES.entries.firstOrNull { message.contains(it.key, ignoreCase = true) }?.let { return SourceError(Tr.get(it.value)) }
        return SourceError("Telegram: $message")
    }
}
