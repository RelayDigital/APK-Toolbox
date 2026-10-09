package io.github.bl3xand.apkcloner.sources.telegram

/** Where the sources find the app's Telegram client; unset where there is none, as in unit tests. */
object Telegram {
    @Volatile
    var gateway: TelegramGateway? = null
}
