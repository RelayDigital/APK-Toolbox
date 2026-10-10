package io.github.bl3xand.apkcloner.sources.core

/*
 * A source can have an app and still nothing for the device that asks: stores answer for an
 * Android version and a processor, and releases hold a file for each kind of processor. "No APK
 * found" says none of that, so these say it.
 */

private fun processor(): String = SourceEnv.platform.supportedAbis.firstOrNull().orEmpty()

/** [sourceName] answers for this device and has no file of the app for it. */
fun notForDevice(sourceName: String): SourceError =
    SourceError(Tr.get("notForDevice", sourceName, androidVersionName(SourceEnv.platform.sdkInt), processor()))

/** The release has files, and none of them is for the processor of this device. */
fun notForProcessor(): SourceError = SourceError(Tr.get("notForProcessor", processor()))
