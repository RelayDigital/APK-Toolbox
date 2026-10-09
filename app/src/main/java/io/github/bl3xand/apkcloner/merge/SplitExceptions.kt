package io.github.bl3xand.apkcloner.merge

/**
 * The resource tables are too big to merge within the memory Android gives an app.
 * [megabytes] is their combined size.
 */
class TablesTooLargeException(val megabytes: Long) : Exception()

/** Some splits do not belong to the same build as the base; merging them needs an explicit go-ahead. */
class SplitMismatchException(val splits: List<String>) : Exception()
