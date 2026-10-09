package io.github.bl3xand.apkcloner.merge

import java.io.File

/** One APK of a split set. [file] is null while it still sits inside [SplitSource.bundle]. */
class SplitEntry(val name: String, val size: Long, val file: File?)
