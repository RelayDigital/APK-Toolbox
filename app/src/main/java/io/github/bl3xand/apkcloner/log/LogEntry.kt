package io.github.bl3xand.apkcloner.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the log. */
class LogEntry(val level: LogLevel, val message: String, val timestamp: Long) {
    override fun toString(): String =
        "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timestamp))}: " +
            "${level.name.lowercase()}: $message"
}
