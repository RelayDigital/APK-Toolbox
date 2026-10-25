package io.github.bl3xand.apkcloner.compat

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat

/*
 * What the app asks the system about packages, in a form every supported Android answers. The
 * newer ways are used where they exist; below them the older ones say the same thing.
 */

/** The flag that makes a [PackageInfo] carry the certificates of who signed the package. */
@Suppress("DEPRECATION")
val SIGNERS_FLAG: Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

/** The version code in full; before Android 9 it had no upper half. */
val PackageInfo.versionCodeLong: Long get() = PackageInfoCompat.getLongVersionCode(this)

/** Who signed the package as it is now. Needs [SIGNERS_FLAG]. */
@Suppress("DEPRECATION")
val PackageInfo.currentSigners: List<Signature>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners.orEmpty().toList()
    else signatures.orEmpty().toList()

/** Whether several parties signed the package at once. Needs [SIGNERS_FLAG]. */
@Suppress("DEPRECATION")
val PackageInfo.hasSeveralSigners: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.hasMultipleSigners() ?: false
    else signatures.orEmpty().size > 1

/**
 * Every certificate the package may be recognised by: its signers when there are several, else
 * the one signer with the keys it has had before. Needs [SIGNERS_FLAG].
 */
@Suppress("DEPRECATION")
val PackageInfo.knownSigners: List<Signature>
    get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return signatures.orEmpty().toList()
        val signing = signingInfo ?: return emptyList()
        return (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory).orEmpty().toList()
    }

/** An installed package, or null when there is none by that name. */
fun PackageManager.packageInfoOrNull(packageName: String, flags: Int = 0): PackageInfo? =
    try {
        getPackageInfo(packageName, flags)
    } catch (e: Exception) {
        null
    }

/** What an APK file says about itself, or null when the system cannot read it. */
fun PackageManager.archiveInfoOrNull(path: String, flags: Int = 0): PackageInfo? =
    try {
        getPackageArchiveInfo(path, flags)
    } catch (e: Exception) {
        null
    }

/** The package that installed [packageName], if the system knows. */
@Suppress("DEPRECATION")
fun PackageManager.installerOf(packageName: String): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getInstallSourceInfo(packageName).installingPackageName
    else getInstallerPackageName(packageName)
}.getOrNull()

inline fun <reified T> Intent.parcelable(name: String): T? = IntentCompat.getParcelableExtra(this, name, T::class.java)

inline fun <reified T> Intent.parcelableList(name: String): List<T> =
    IntentCompat.getParcelableArrayListExtra(this, name, T::class.java).orEmpty()
