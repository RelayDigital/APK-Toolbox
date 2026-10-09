package io.github.bl3xand.apkcloner.sources.telegram

/** Where signing in to Telegram stands. */
sealed interface TelegramAuth {
    /** This build was made without the app's Telegram API id and hash. */
    data object NotConfigured : TelegramAuth

    /** The client is not running, or has not said yet where it stands. */
    data object Starting : TelegramAuth
    data object WaitPhone : TelegramAuth
    data object WaitCode : TelegramAuth
    data class WaitPassword(val hint: String) : TelegramAuth
    data object Ready : TelegramAuth

    /** A step this app has no screen for (e-mail confirmation, registration and the like). */
    data class Unsupported(val step: String) : TelegramAuth
}
