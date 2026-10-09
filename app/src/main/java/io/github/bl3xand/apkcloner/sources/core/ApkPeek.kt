package io.github.bl3xand.apkcloner.sources.core

/** What an APK says about itself, read without downloading it whole: its package and who signed it. */
class ApkPeek(val packageName: String, val certHashes: Set<String>)
