package io.github.bl3xand.apkcloner.data

import io.github.bl3xand.apkcloner.compat.installerOf
import io.github.bl3xand.apkcloner.compat.currentSigners
import io.github.bl3xand.apkcloner.compat.SIGNERS_FLAG
import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.clone.CloneMetadata
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.data.certHashesOf
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class AppRepository(private val context: Context, private val cloner: ApkCloner) {

    private val packageManager: PackageManager = context.packageManager

    fun installed(): InstalledApps {
        val packages = packageManager.getInstalledPackages(SIGNERS_FLAG)
            .filter { it.packageName != context.packageName && it.applicationInfo != null }
        // Asked first: it also sees to it that the tracked apps have been read.
        val sourcesRepository = runCatching { SourcesRepository.get(context) }.getOrNull()
        val asClones = sourcesRepository?.let { runCatching { it.installedAsClones() }.getOrDefault(emptyMap()) } ?: emptyMap()
        val trackedByPackage = (sourcesRepository?.let { runCatching { it.all() }.getOrDefault(emptyList()) } ?: emptyList())
            .filter { it.installedInfo != null }.associateBy { it.app.devicePackage }
        val sources = packages.associate { info ->
            val app = info.applicationInfo!!
            info.packageName to toSource(info, app, originOf(info, app, trackedByPackage[info.packageName], sourcesRepository))
        }

        // Clones are recognised by the key they are signed with rather than by a local database,
        // so the list survives this app's data being cleared.
        val tracked = asClones
        val frozen = AppSettings(context).frozenClones
        val categories = AppSettings(context).cloneCategories
        val clones = packages.mapNotNull { info ->
            val key = info.currentSigners.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) } ?: return@mapNotNull null
            val source = sources.getValue(info.packageName)
            val metadata = info.applicationInfo?.sourceDir?.let { cloneMetadata(info, it) } ?: return@mapNotNull null
            CloneInfo(source, metadata.originalPackage, sources[metadata.originalPackage], key, metadata.badged, metadata.removedPermissions, metadata.patches, metadata.patchesNotApplied, tracked[info.packageName], info.packageName in frozen, categories[info.packageName].orEmpty(),
            )
        }
        return InstalledApps(
            apps = sources.values.sortedBy { it.label.lowercase() },
            clones = clones.sortedBy { it.app.label.lowercase() },
        )
    }

    /**
     * Installed clones of [originalPackage], by their own package. Reads nothing about the other
     * apps, so it is cheap enough to ask for one app.
     */
    fun clonesOf(originalPackage: String): Map<String, CloneMetadata> =
        packageManager.getInstalledPackages(SIGNERS_FLAG).mapNotNull { info ->
            if (info.currentSigners.none { cloner.keys.matching(it.toByteArray()) != null }) return@mapNotNull null
            val metadata = info.applicationInfo?.sourceDir?.let { cloneMetadata(info, it) } ?: return@mapNotNull null
            if (metadata.originalPackage == originalPackage) info.packageName to metadata else null
        }.toMap()

    /**
     * Whether [packageName] is on the device as an app a clone cannot be installed over: one
     * signed with a key that is not ours.
     */
    fun isTakenByOther(packageName: String): Boolean {
        val info = runCatching { packageManager.getPackageInfo(packageName, SIGNERS_FLAG) }.getOrNull() ?: return false
        return info.currentSigners.none { cloner.keys.matching(it.toByteArray()) != null }
    }

    /** Copies the picked document to [target] (it may not be a real file) and parses it. */
    fun fromUri(uri: Uri, target: File): ApkSource? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: return null
        val info = packageManager.getPackageArchiveInfo(target.path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        // Not filled in for archives; without them the label and icon cannot be resolved.
        app.sourceDir = target.path
        app.publicSourceDir = target.path
        return toSource(info, app)
    }

    /**
     * An app this app installed from a source is from that source; anything else, from whatever
     * installed it - a tracked app too: tracking an app from Google Play does not make it less
     * of one.
     *
     * The installer of record is the system's memory of the LAST install only: an adb install
     * leaves it empty on many systems, a file manager that sideloads an APK sets itself, so
     * after an uninstall and a reinstall done another way the label drifts. What the app is
     * signed with does not drift: a build a source once offered carries a certificate the
     * source recorded for it, and a clone made here carries this app's key. Those are asked
     * first; the record decides only what nothing else can vouch for.
     */
    private fun originOf(info: PackageInfo, app: ApplicationInfo, tracked: AppEntry?, sourcesRepository: SourcesRepository?): String {
        val installer = packageManager.installerOf(app.packageName)
        val ours = info.currentSigners.any { cloner.keys.matching(it.toByteArray()) != null }
        if (tracked != null) {
            // A clone of the tracked app built here goes on being kept by its source, whatever
            // way it was last put on the device.
            if (ours) return tracked.sourceName()
            val offered = sourcesRepository?.storedApkCertHashes(tracked.app.id).orEmpty()
            if (offered.isNotEmpty()) {
                // A match vouches for the installed build whoever put it on the device; a miss
                // says this build is not the source's, and the source must not be named for it.
                return if (certHashesOf(info).any { it in offered }) tracked.sourceName() else fromInstallerRecord(app, installer)
            }
            if (installer == null || installer == context.packageName) return tracked.sourceName()
            return fromInstallerRecord(app, installer)
        }
        // A clone: ours by the key it is signed with, whatever the last install went through.
        if (ours) return context.getString(R.string.app_name)
        return fromInstallerRecord(app, installer)
    }

    private fun fromInstallerRecord(app: ApplicationInfo, installer: String?): String = when {
        installer == Installer.PLAY_STORE_PACKAGE -> "Google Play"
        installer == context.packageName -> context.getString(R.string.app_name)
        installer != null -> runCatching {
            packageManager.getApplicationInfo(installer, 0).loadLabel(packageManager).toString()
        }.getOrDefault(installer)
        app.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> context.getString(R.string.origin_system)
        else -> context.getString(R.string.origin_file)
    }

    private fun toSource(info: PackageInfo, app: ApplicationInfo, origin: String? = null) = ApkSource(
        packageName = app.packageName,
        label = app.loadLabel(packageManager).toString(),
        versionName = info.versionName,
        versionCode = info.versionCodeLong,
        apkPaths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty(),
        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        appInfo = app,
        origin = origin,
    )

    /**
     * What a clone says about itself, read from the base APK named by [info]. The APK of an
     * installed app changes only when the app is updated, so the read is cached by what the
     * system knows about the app - (package, versionCode, lastUpdateTime) - and a rescan that
     * sees the same version reads nothing. One cache per repository; the repository of the main
     * list lives as long as the view model does, so the cache rides along with it.
     */
    private val metadataCache = ConcurrentHashMap<String, Optional<CloneMetadata>>()

    private fun cloneMetadata(info: PackageInfo, baseApk: String): CloneMetadata? {
        val key = "${info.packageName}@${info.versionCodeLong}@${info.lastUpdateTime}"
        return metadataCache.computeIfAbsent(key) { Optional.ofNullable(ApkCloner.readMetadata(baseApk)) }.orElse(null)
    }
}
