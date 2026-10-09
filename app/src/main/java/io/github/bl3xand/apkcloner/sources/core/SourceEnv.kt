package io.github.bl3xand.apkcloner.sources.core

object SourceEnv {
    @Volatile
    var settings: SourceSettings = object : SourceSettings {
        private val values = HashMap<String, String>()
        override fun getString(key: String): String? = values[key]?.takeIf { it.isNotEmpty() }
        override fun getBool(key: String): Boolean = values[key] == "true"
        override fun setString(key: String, value: String) {
            values[key] = value
        }

        override val enableCertificatePinning get() = getBool("enableCertificatePinning")
        override val globalApkFilterRegEx get() = getString("globalApkFilterRegEx")
        override val minimumUpdateAgeDays get() = 0
        override val hideDowngrades get() = true
    }

    @Volatile
    var platform: Platform = object : Platform {
        override val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        override val sdkInt = 36
        override val appVersionName = "1.0"
        override val screenDensityDpi = 420
        override val isTv = false
    }
}
