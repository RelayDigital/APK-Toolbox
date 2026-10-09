package io.github.bl3xand.apkcloner.sources.core

/** Reads a stretch of a file that is somewhere else, without fetching the rest of it. */
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}
