package io.github.bl3xand.apkcloner.sources.data

/** [progress] is 0..100, or -1 while installing. */
data class DownloadState(val progress: Double, val receivedBytes: Long? = null, val totalBytes: Long? = null)
