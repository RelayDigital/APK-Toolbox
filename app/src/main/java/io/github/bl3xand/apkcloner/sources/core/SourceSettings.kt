package io.github.bl3xand.apkcloner.sources.core

/** Global (not per-app) values the sources read. Backed by preferences in the app. */
interface SourceSettings {
    fun getString(key: String): String?
    fun getBool(key: String): Boolean
    fun setString(key: String, value: String)

    val enableCertificatePinning: Boolean
    val globalApkFilterRegEx: String?
    val minimumUpdateAgeDays: Int
    val hideDowngrades: Boolean
}
