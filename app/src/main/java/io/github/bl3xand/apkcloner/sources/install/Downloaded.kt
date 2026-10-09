package io.github.bl3xand.apkcloner.sources.install

import java.io.File

/** What a download produced: a single APK, or a folder of APKs unpacked from a bundle. */
class Downloaded(val appId: String, val file: File, val dir: File? = null, val splitSet: Boolean = false)
