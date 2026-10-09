package io.github.bl3xand.apkcloner.sources.core

/** Device facts the sources need; kept behind an interface so the code runs on a plain JVM. */
interface Platform {
    val supportedAbis: List<String>
    val sdkInt: Int
    val appVersionName: String
    val screenDensityDpi: Int
    val isTv: Boolean
}
