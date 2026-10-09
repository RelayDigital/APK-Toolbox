package io.github.bl3xand.apkcloner.sources.telegram

/** A channel as its name and the files of its latest messages, newest first. */
class TelegramChannelInfo(val title: String, val files: List<TelegramFile>)
