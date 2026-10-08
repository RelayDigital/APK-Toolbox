package io.github.bl3xand.apkcloner.install

import android.content.Context
import android.provider.Settings

object PlayProtect {

    private const val CONSENT = "package_verifier_user_consent"

    /**
     * Whether Play Protect scans apps on install, or null when the device does not say. There is
     * no public API for this short of bundling Play services; the consent flag it stores in the
     * global settings is what the system's own verifier checks.
     */
    fun isEnabled(context: Context): Boolean? = runCatching {
        when (Settings.Global.getInt(context.contentResolver, CONSENT)) {
            1 -> true
            -1 -> false
            else -> null
        }
    }.getOrNull()
}
