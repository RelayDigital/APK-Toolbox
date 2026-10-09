package io.github.bl3xand.apkcloner.sources.telegram

import java.time.Instant

/** A file attached to a message of a channel. [messageId] is the number in the message's link. */
data class TelegramFile(
    val channel: String,
    val messageId: Long,
    val name: String,
    val size: Long,
    val date: Instant,
    val caption: String,
) {
    /** The link of the message; it is also what the file is downloaded by. */
    val url: String get() = "https://t.me/$channel/$messageId"
}
