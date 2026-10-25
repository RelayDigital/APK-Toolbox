package io.github.bl3xand.apkcloner.sources.core

/*
 * A source can have an app and still nothing for the device that asks: stores answer for an
 * Android version and a processor, and releases hold a file for each kind of processor. "No APK
 * found" says none of that, so these say it.
 */

/**
 * There is a release, and nothing in it for this device. A source that keeps older releases
 * takes this as a reason to look further back for the newest one the device can run.
 */
class NotForDeviceError(message: String) : SourceError(message)

private fun processor(): String = SourceEnv.platform.supportedAbis.firstOrNull().orEmpty()

/** [sourceName] answers for this device and has no file of the app for it. */
fun notForDevice(sourceName: String): NotForDeviceError =
    NotForDeviceError(Tr.get("notForDevice", sourceName, androidVersionName(SourceEnv.platform.sdkInt), processor()))

/**
 * The same for a store that only ever has the current release: an older one that still runs on
 * this device may be found in a source that keeps them.
 */
fun notForDeviceHere(sourceName: String): NotForDeviceError =
    NotForDeviceError(notForDevice(sourceName).message + " " + Tr.get("notForDeviceElsewhere"))

/** The release has files, and none of them is for the processor of this device. */
fun notForProcessor(): NotForDeviceError = NotForDeviceError(Tr.get("notForProcessor", processor()))
