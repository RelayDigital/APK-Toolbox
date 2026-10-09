package io.github.bl3xand.apkcloner.sources.telegram

/** Where signing in to Telegram stands. */
sealed interface TelegramAuth {
    /** This build was made without the app's Telegram API id and hash. */
    data object NotConfigured : TelegramAuth

    /** The client is not running, or has not said yet where it stands. */
    data object Starting : TelegramAuth

    /** Nothing was entered yet: a phone number, or a sign-in confirmed from another device. */
    data object WaitPhone : TelegramAuth

    /** A sign-in to be confirmed in a Telegram app that is signed in already, by [link] or its QR code. */
    data class WaitOtherDevice(val link: String) : TelegramAuth

    /** The code Telegram sent; [viaTelegram] when it went to the account's other devices, not by SMS. */
    data class WaitCode(val viaTelegram: Boolean) : TelegramAuth

    /** An account that signs in with an e-mail address has to name it, then enter the code sent there. */
    data object WaitEmail : TelegramAuth
    data object WaitEmailCode : TelegramAuth

    /** The password of two-step verification. */
    data class WaitPassword(val hint: String) : TelegramAuth
    data object Ready : TelegramAuth

    /** A step this app has no screen for (registering a new account and the like). */
    data class Unsupported(val step: String) : TelegramAuth
}
