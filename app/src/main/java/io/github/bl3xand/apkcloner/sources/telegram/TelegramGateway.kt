package io.github.bl3xand.apkcloner.sources.telegram

import io.github.bl3xand.apkcloner.sources.core.RangeReader
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import java.io.File

/**
 * What a source needs of Telegram. Files of a channel cannot be had anonymously, so everything
 * here goes through the account the user signed in with.
 */
interface TelegramGateway {
    /** Whether an account was signed in to, as far as is known without asking Telegram. */
    val hasAccount: Boolean

    /** Whether there is an account to act for; finds out by asking, so it may take a moment. */
    val isSignedIn: Boolean

    /**
     * The files of the public channel [username], newest first and [limit] at most. With a
     * [query] only the messages Telegram's own search finds for it are looked at, however far
     * back they are.
     */
    fun channel(username: String, limit: Int, query: String = ""): TelegramChannelInfo

    /** The file of one message, or null if the message has none. */
    fun file(channel: String, messageId: Long): TelegramFile?

    /** The size of the file of a message and a way to read parts of it, or null if it has none. */
    fun reader(channel: String, messageId: Long): Pair<Long, RangeReader>?

    /** Downloads the file of a message to [destination]; throws a cancellation when [isCancelled]. */
    fun download(
        channel: String,
        messageId: Long,
        destination: File,
        onProgress: ProgressListener?,
        isCancelled: () -> Boolean,
    ): File
}
