package io.github.bl3xand.apkcloner.sources.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.FileProvider
import io.github.bl3xand.apkcloner.clone.ApkCloner
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.InstallReceiver
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.install.StockInstaller
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.core.DowngradeError
import io.github.bl3xand.apkcloner.sources.core.IdChangedError
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.SigningCertMismatchError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.data.certHashesOf
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.CancellationToken
import io.github.bl3xand.apkcloner.sources.net.Downloader
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import io.github.bl3xand.apkcloner.sources.work.SourcesNotifications
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

private class InstallBaseline(val wasInstalled: Boolean, val versionCode: Long?, val updateTime: Long?)

/** Downloads tracked apps and installs them. */
class SourcesInstaller private constructor(private val context: Context) {
    private val repo = SourcesRepository.get(context)
    private val settings = repo.settings
    private val packageManager = context.packageManager
    private val cancellations = ConcurrentHashMap<String, CancellationToken>()

    /** Whether the app's window is in front; installs that ask a question need it. */
    @Volatile
    var isForeground = false

    /** The installer the whole app uses for this kind of install. */
    private fun installer(background: Boolean): Installer = Installer.choose(context, background)

    fun cancelDownload(appId: String) {
        cancellations[appId]?.cancel()
        repo.setDownload(appId, null)
    }

    // ---- download ----------------------------------------------------------------------------

    private fun archiveInfo(file: File): PackageInfo? = try {
        packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
    } catch (e: Exception) {
        null
    }

    private fun apkCertHashes(files: List<File>): Set<String> = files.flatMap { file ->
        val info = try {
            packageManager.getPackageArchiveInfo(
                file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } catch (e: Exception) {
            null
        }
        certHashesOf(info)
    }.toSet()

    /** Extracts a zip, refusing entries that would land outside [destination]. */
    private fun unzip(file: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalPath
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destination, entry.name)
                if (!out.canonicalPath.startsWith("$root/")) throw SourceError(Tr.get("invalidArchive"))
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /** Extracts a tarball; the compression is recognised by its first bytes. */
    private fun extractTarball(file: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalPath
        val head = ByteArray(6)
        file.inputStream().use { it.read(head) }
        val raw: InputStream = file.inputStream().buffered()
        val stream = when {
            head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> GZIPInputStream(raw)
            head[0] == 0x42.toByte() && head[1] == 0x5a.toByte() && head[2] == 0x68.toByte() -> BZip2CompressorInputStream(raw)
            head[0] == 0xfd.toByte() && head[1] == 0x37.toByte() && head[2] == 0x7a.toByte() -> XZCompressorInputStream(raw)
            else -> raw
        }
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                val out = File(destination, entry.name)
                if (!out.canonicalPath.startsWith("$root/")) throw SourceError(Tr.get("invalidArchive"))
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    private fun isTarballName(name: String): Boolean = ApkFilter.tarballExtensions.any { name.endsWith(it) }

    /**
     * Gives an app its real package name once the APK is at hand. A changed name on an app that
     * already had a real one is refused: it would be a different app.
     */
    private fun handleApkIdChange(appIn: TrackedApp, info: PackageInfo, file: File, downloadUrl: String): Pair<File, TrackedApp> {
        var app = appIn
        var result = file
        val actual = info.packageName
        if (app.id != actual) {
            val tracked = repo.entry(app.id) != null
            if (tracked && !app.hasTempId && !app.allowIdChange) throw IdChangedError(actual).also { it.url = app.url }
            val idChangeWasAllowed = app.allowIdChange
            val wasTemp = app.hasTempId
            val originalId = app.id
            app = app.copy(id = actual, allowIdChange = false)
            val renamed = File(file.parentFile, "${app.id}-${downloadUrl.hashCode()}.${file.extension}")
            if (file.renameTo(renamed)) result = renamed
            if (tracked) {
                repo.removeApps(listOf(originalId))
                repo.saveApps(listOf(app), onlyIfExists = !wasTemp && !idChangeWasAllowed)
            }
        }
        return result to app
    }

    /** Downloads the preferred file of [appIn] and prepares it for installing. */
    fun downloadApp(appIn: TrackedApp, background: Boolean, useExisting: Boolean = true): Downloaded {
        var app = appIn
        val token = CancellationToken().also { cancellations[app.id] = it }
        val notificationKey = app.id
        try {
            repo.setDownload(app.id, DownloadState(0.0))
            if (app.apkUrls.isEmpty()) throw NoApkError()
            AppLog.info("Downloading ${app.id} ${app.latestVersion} from ${app.url}")
            app = app.copy(preferredApkIndex = app.preferredApkIndex.coerceIn(0, app.apkUrls.size - 1))
            val source = repo.sourceOf(app)
            val merged = source.buildMergedSettings(app.additionalSettings, settings)
            val chosen = app.apkUrls[app.preferredApkIndex]
            val downloadUrls = ApkFilter.splitMultiApkUrl(chosen.url).map {
                source.assetUrlPrefetchModifier(source.generalReqPrefetchModifier(it, merged), app.url, merged)
            }
            val downloadUrl = downloadUrls.first()
            val multipleApks = downloadUrls.size > 1
            var fileName = "${app.id}-${downloadUrl.hashCode()}"
            if (source.urlsAlwaysHaveExtension) fileName = "$fileName.${chosen.name.split('.').last()}"
            val options = source.requestOptions(app.additionalSettings)

            var completed = 0
            var lastPercent: Int? = null
            val appName = app.finalName
            val appId = app.id
            fun report(percent: Double?, received: Long?, total: Long?) {
                val overall = percent?.let { (completed * 100 + it) / downloadUrls.size }
                val shown = overall?.let { ceil(it).toInt() }
                if (overall != null) repo.setDownload(appId, DownloadState(overall, received, total))
                if (shown != null && shown != lastPercent) {
                    SourcesNotifications.download(
                        context, notificationKey, appName, shown, received, total, appId = if (background) null else appId,
                    )
                }
                lastPercent = shown
            }

            var file = source.downloadAsset(downloadUrl, File(repo.apkDir, fileName), ::report) { token.isCancelled }
                ?: Downloader.downloadFileWithRetry(
                    downloadUrl, fileName, source.urlsAlwaysHaveExtension, ::report, repo.apkDir, options, useExisting,
                    source.getRequestHeaders(app.additionalSettings, downloadUrl, forAPKDownload = true),
                    cancellationToken = token,
                )
            completed = 1
            repo.setDownload(appId, DownloadState(90.0))

            val originalAssetName = chosen.name.lowercase()
            val isApk = file.path.lowercase().endsWith(".apk")
            val isTarball = isTarballName(originalAssetName)
            var dir: File? = null
            var info: PackageInfo? = null
            if (isApk && !multipleApks) {
                info = archiveInfo(file)
            } else {
                dir = File("${file.path}-dir").also { it.deleteRecursively() }
                when {
                    isTarball -> extractTarball(file, dir)
                    isApk -> {
                        // A base APK with splits from further URLs: gather them in one folder.
                        dir.mkdirs()
                        val moved = File(dir, file.name)
                        if (file.renameTo(moved)) file = moved
                    }
                    else -> unzip(file, dir)
                }
                for (i in 1 until downloadUrls.size) {
                    completed = i
                    val splitFile = Downloader.downloadFileWithRetry(
                        downloadUrls[i], "$fileName-$i", false, ::report, repo.apkDir, options, useExisting,
                        source.getRequestHeaders(app.additionalSettings, downloadUrls[i], forAPKDownload = true),
                        cancellationToken = token,
                    )
                    if (splitFile.path.lowercase().endsWith(".apk")) {
                        splitFile.renameTo(File(dir, splitFile.name))
                    } else {
                        unzip(splitFile, dir)
                        splitFile.delete()
                    }
                }
                var apks = dir.walkTopDown().filter { it.isFile && ApkFilter.isApkOrContainerFile(it.path) }.toMutableList()
                apks = orderApks(apks, app.id, multipleApks)
                val filterKey = if (isTarball) "tarballedApkFilterRegEx" else "zippedApkFilterRegEx"
                app.settings.getStringOrNull(filterKey)?.let { pattern ->
                    val regex = Regex(pattern)
                    apks.removeAll { apk ->
                        val unwanted = !regex.containsMatchIn(apk.path.substring(dir.path.length + 1))
                        if (unwanted) apk.delete()
                        unwanted
                    }
                }
                if (apks.isEmpty()) throw NoApkError()
                info = apks.firstNotNullOfOrNull(::archiveInfo)
            }
            if (info == null) {
                file.delete()
                dir?.deleteRecursively()
                throw SourceError(Tr.get("couldNotGetIdFromApk")).also { it.url = app.url }
            }
            val (renamed, resolved) = handleApkIdChange(app, info, file, downloadUrl)
            // Older downloads of this app are of no use any more.
            repo.apkDir.listFiles()?.forEach {
                if (it.isFile && it.name.startsWith("${resolved.id}-") && it.path != renamed.path) it.delete()
            }
            return Downloaded(resolved.id, renamed, dir, splitSet = multipleApks)
        } finally {
            cancellations.remove(appIn.id)
            SourcesNotifications.cancel(context, SourcesNotifications.idForKey(notificationKey))
            repo.setDownload(appIn.id, null)
        }
    }

    /** The APK named after the app first; in a split set, base.apk first. */
    private fun orderApks(apks: MutableList<File>, appId: String, splitSet: Boolean): MutableList<File> {
        apks.lastOrNull { it.name.startsWith(appId) }?.let {
            apks.removeAll { apk -> apk.name.startsWith(appId) }
            apks.add(0, it)
        }
        if (splitSet) {
            val base = apks.indexOfFirst { it.name.lowercase() == "base.apk" }
            if (base > 0) apks.add(0, apks.removeAt(base))
        }
        return apks
    }

    // ---- install -----------------------------------------------------------------------------

    /** Whether [app] can be installed without asking, by what the device and installer allow. */
    fun canInstallSilently(app: TrackedApp): Boolean {
        // Several files to choose from need a choice.
        if (app.apkUrls.size > 1) return false
        if (installer(background = true) !is StockInstaller) return true
        if (app.id == context.packageName) return false
        val targetSdk = repo.installedInfo(app.devicePackage)?.applicationInfo?.targetSdkVersion ?: return false
        return StockInstaller.isUpdateWithoutPrompt(context, app.devicePackage, targetSdk)
    }

    fun canInstallSilentlyInBackground(app: TrackedApp): Boolean =
        settings.enableBackgroundUpdates && !app.settings.getBool(SettingKeys.EXEMPT_FROM_BACKGROUND_UPDATES) && canInstallSilently(app)

    private fun baseline(appId: String): InstallBaseline {
        val info = repo.installedInfo(appId)
        return InstallBaseline(info != null, info?.longVersionCode, info?.lastUpdateTime)
    }

    private fun changedSince(appId: String, baseline: InstallBaseline): Boolean {
        val info = repo.installedInfo(appId) ?: return false
        if (!baseline.wasInstalled) return true
        return if (baseline.updateTime != null) info.lastUpdateTime != baseline.updateTime
        else info.longVersionCode != baseline.versionCode
    }

    private suspend fun waitForPackageInstall(appId: String, baseline: InstallBaseline, attempts: Int, intervalMs: Long = 500): Boolean {
        repeat(attempts) { attempt ->
            if (changedSince(appId, baseline)) return true
            if (attempt < attempts - 1) delay(intervalMs)
        }
        return false
    }

    /**
     * Checks the certificate of what was downloaded. A list of hashes given by the user is
     * binding; a difference to the installed app can be overridden, but only by the user.
     */
    private suspend fun verifySignatures(entry: AppEntry, apkHashes: Set<String>, prompts: InstallPrompts?) {
        val userHashes = CertHashes.parseAllowed(entry.app.settings.getStringOrNull("allowedSigningCertHashes"))
        // A clone carries this app's key; what its updates are held against is the signer of the
        // APK it was built from.
        val installedHashes = if (entry.app.clonePackage == null) entry.certificateHashes.toSet()
        else entry.app.cloneSourceSigner.takeIf { entry.installedInfo != null }.orEmpty()
        if (apkHashes.isEmpty()) {
            if (userHashes.isNotEmpty()) {
                // Unreadable, and a certificate is required: do not install unverified.
                prompts?.signingMismatch(entry.name, userHashes, apkHashes, hardBlock = true)
                throw SigningCertMismatchError(true, userHashes, apkHashes)
            }
            return
        }
        // Keep the latest APK's signer on record so the app's page can judge a conflict live.
        repo.storeApkCertHashes(entry.app.id, apkHashes)
        if (userHashes.isNotEmpty() && !userHashes.containsAll(apkHashes)) {
            prompts?.signingMismatch(entry.name, userHashes, apkHashes, hardBlock = true)
            throw SigningCertMismatchError(true, userHashes, apkHashes)
        }
        if (!settings.verifySigningCertHashes || installedHashes.isEmpty() || installedHashes.containsAll(apkHashes)) return
        // What is installed under this package was signed by someone else - another build of the
        // app. The system will not put this one over it, so there is nothing to ask: the app's
        // page says so until the installed one is removed.
        AppLog.warn("${entry.app.id}: the installed app is signed differently from ${entry.app.latestVersion} of this source")
        throw SigningCertMismatchError(false, installedHashes, apkHashes)
    }

    fun hasSignerConflict(entry: AppEntry): Boolean = repo.hasSignerConflict(entry)

    /** The signer of what a download produced; empty when it cannot be read. */
    fun signerHashesOf(downloaded: Downloaded): Set<String> = apkCertHashes(
        downloaded.dir?.walkTopDown()?.filter { it.isFile && it.name.lowercase().endsWith(".apk") }?.toList()
            ?: listOf(downloaded.file),
    )

    /** Whether an APK signed with [apkHashes] would clash with the build of [appId] on the device. */
    fun clashesWithInstalled(appId: String, apkHashes: Set<String>): Boolean =
        repo.signersClash(repo.installedInfo(appId), apkHashes)

    private fun moveObbFiles(dir: File, appId: String) {
        for (obb in dir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".obb") }) {
            try {
                val target = File(Environment.getExternalStorageDirectory(), "Android/obb/$appId").apply { mkdirs() }
                obb.copyTo(File(target, obb.name), overwrite = true)
            } catch (e: Exception) {
                AppLog.info("Failed to place OBB file for $appId: ${e.message}")
            }
        }
    }

    /** Before a first install the APK can be handed to an installed verifier app. */
    private fun shareWithVerifier(apk: File) {
        if (!settings.beforeNewInstallsShareToAppVerifier) return
        if (verifierPackages.none { repo.installedInfo(it) != null }) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.sources", apk)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    Tr.get("appVerifierInstructionToast"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.info("Could not share with a verifier app: ${e.message}")
        }
    }

    /**
     * Installs [apks] (base first) for the tracked app [id]. Returns true when the app is
     * installed afterwards.
     */
    private suspend fun installFiles(id: String, apks: List<File>, background: Boolean, pretendPlay: Boolean): Boolean {
        val entry = repo.entry(id) ?: throw SourceError(Tr.get("appNotFound"))
        // Where it lands on the device: under the clone's package when it is installed as one.
        val appId = entry.app.devicePackage
        val newInfo = archiveInfo(apks.first())
        if (newInfo == null) {
            apks.forEach { it.delete() }
            throw SourceError(Tr.get("badDownload")).also { it.url = entry.app.url }
        }
        val installed = repo.installedInfo(appId)
        AppLog.info(
            "Installing \"${newInfo.packageName}\" version \"${newInfo.versionName}\" (${newInfo.longVersionCode})" +
                (installed?.let { " over \"${it.versionName}\" (${it.longVersionCode})" } ?: ""),
        )
        if (installed != null && newInfo.longVersionCode < installed.longVersionCode &&
            repo.installedInfo("com.berdik.letmedowngrade") == null && settings.showAppDowngradeError
        ) {
            apks.first().delete()
            throw DowngradeError(installed.longVersionCode, newInfo.longVersionCode)
        }
        val before = baseline(appId)
        AppLog.debug("Installing $appId with ${installer(background).javaClass.simpleName}, ${apks.size} file(s), background=$background")
        // The outcome is reported by whoever asked for the install.
        InstallReceiver.reportedByCaller = true
        val outcome = try {
            installer(background).install(
                context, apks, background, entry.name, if (pretendPlay) Installer.PLAY_STORE_PACKAGE else null,
            )
        } catch (e: Exception) {
            InstallReceiver.reportedByCaller = false
            throw e
        }
        val succeeded = when (outcome) {
            is InstallOutcome.Failed -> {
                InstallReceiver.reportedByCaller = false
                AppLog.error("Install of $appId failed: ${outcome.reason}")
                throw SourceError(outcome.reason)
            }
            InstallOutcome.Success -> true
            InstallOutcome.Pending -> {
                if (background) {
                    // The result of a background session is not delivered here; look for it.
                    waitForPackageInstall(appId, before, attempts = BACKGROUND_CONFIRM_ATTEMPTS)
                } else {
                    // Whichever comes first: the installer's answer or the package changing.
                    withTimeoutOrNull(INSTALL_CONFIRM_TIMEOUT_MS) {
                        coroutineScope {
                            val answered = async { InstallReceiver.sessionFinished.first() }
                            val landed = async { waitForPackageInstall(appId, before, INSTALL_CONFIRM_POLLS, 1000) }
                            kotlinx.coroutines.selects.select<Unit> {
                                answered.onAwait { }
                                landed.onAwait { }
                            }
                            answered.cancel()
                            landed.cancel()
                        }
                    }
                    waitForPackageInstall(appId, before, attempts = 4)
                }
            }
        }
        InstallReceiver.reportedByCaller = false
        AppLog.info(if (succeeded) "Installed $appId ${newInfo.versionName}" else "Install of $appId was not confirmed")
        if (succeeded) {
            repo.entry(id)?.let { repo.saveApps(listOf(it.app.copy(installedVersion = it.app.latestVersion))) }
            apks.forEach { it.delete() }
        }
        return succeeded
    }

    private suspend fun installDownloaded(
        downloaded: Downloaded,
        prompts: InstallPrompts?,
        background: Boolean,
    ): Boolean {
        val id = downloaded.appId
        val entry = repo.entry(id) ?: return false
        val apks = if (downloaded.dir != null) {
            moveObbFiles(downloaded.dir, entry.app.devicePackage)
            orderApks(
                downloaded.dir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".apk") }.toMutableList(),
                id, downloaded.splitSet,
            )
        } else mutableListOf(downloaded.file)
        if (apks.isEmpty()) throw NoApkError()
        val apkHashes = apkCertHashes(apks)
        try {
            verifySignatures(entry, apkHashes, prompts)
        } catch (e: SigningCertMismatchError) {
            // A refused file is of no use; do not keep it for a retry.
            downloaded.file.delete()
            downloaded.dir?.deleteRecursively()
            throw e
        }
        repo.setDownload(id, DownloadState(-1.0))
        try {
            if (!background && entry.installedInfo == null) shareWithVerifier(apks.first())
            val pretendPlay = settings.shizukuPretendToBeGooglePlay ||
                entry.app.settings.getBool(SettingKeys.PRETEND_GOOGLE_PLAY)
            // Installed as a clone, the downloaded APK never reaches the device itself.
            val cloned = entry.app.clonePackage != null
            val files = if (cloned) cloneOf(entry, apks) else apks
            val installed = installFiles(id, files, background, pretendPlay)
            if (cloned) {
                cloneDir(id).deleteRecursively()
                if (installed) {
                    apks.forEach { it.delete() }
                    // What the next release is held against, as the system would for an app of its own.
                    repo.entry(id)?.let {
                        repo.saveApps(listOf(it.app.withSetting(SettingKeys.CLONE_SOURCE_SIGNER, apkHashes.joinToString(","))))
                    }
                }
            }
            if (background) {
                val app = repo.entry(id)?.app ?: entry.app
                if (installer(true) is StockInstaller && !installed) {
                    SourcesNotifications.possiblyUpdated(context, app)
                } else {
                    SourcesNotifications.silentUpdate(context, app, installed)
                }
            }
            if (installed) {
                SourcesNotifications.cancel(context, SourcesNotifications.ID_UPDATES)
                downloaded.dir?.deleteRecursively()
                if (downloaded.dir != null) downloaded.file.delete()
            }
            return installed
        } finally {
            repo.setDownload(id, null)
        }
    }

    /**
     * Downloads the latest release of [id] without installing it, to learn which permissions it
     * asks for. The file stays, so installing afterwards does not download it again. Returns the
     * id the app has once its APK is known, and the permissions.
     */
    fun downloadForPermissions(id: String): Pair<String, Set<String>> {
        val downloaded = downloadApp(repo.entry(id)?.app ?: throw SourceError(Tr.get("appNotFound")), background = false)
        val base = downloaded.dir?.let { dir ->
            orderApks(dir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".apk") }.toMutableList(), downloaded.appId, downloaded.splitSet)
                .firstOrNull()
        } ?: downloaded.file
        val requested = packageManager.getPackageArchiveInfo(
            base.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )?.requestedPermissions.orEmpty().toSet()
        repo.entry(downloaded.appId)?.let {
            repo.saveApps(listOf(it.app.withSetting(SettingKeys.CLONE_REQUESTED_PERMISSIONS, requested.sorted().joinToString(","))))
        }
        return downloaded.appId to requested
    }

    private fun cloneDir(id: String) = File(repo.apkDir, "$id-clone")

    /**
     * Rebuilds the downloaded [apks] as the clone [entry] is installed as: under the clone's
     * package, without the permissions that were taken away, signed with the key the installed
     * clone has so that it goes over it and the data stays.
     */
    private fun cloneOf(entry: AppEntry, apks: List<File>): List<File> {
        var app = entry.app
        val target = app.clonePackage ?: return apks
        val cloner = ApkCloner(context)
        // Only a clone made here can be updated: anything else under that package is signed by someone else.
        val key = repo.installedInfo(target)?.let { installed ->
            installed.signingInfo?.apkContentsSigners?.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) }
                ?: throw SourceError(Tr.get("cloneInstallTaken", target))
        }
        val info = packageManager.getPackageArchiveInfo(
            apks.first().path, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        // Nothing is asked here: what the clone goes without is set on the app's page, before or
        // after. What this release asks for is kept, so that the page can show what is new.
        val requested = info?.requestedPermissions.orEmpty().toSet()
        val removed = app.cloneRemovedPermissions
        if (requested != app.cloneRequestedPermissions) {
            app = (repo.entry(app.id)?.app ?: app)
                .withSetting(SettingKeys.CLONE_REQUESTED_PERMISSIONS, requested.sorted().joinToString(","))
            repo.saveApps(listOf(app))
            app.cloneNewPermissions.takeIf { it.isNotEmpty() }?.let {
                AppLog.warn("${app.id} ${app.latestVersion} asks for permissions it did not have before: ${it.sorted().joinToString()}")
            }
        }
        // The icon is drawn from the APK itself: nothing is installed to take it from.
        val icon = info?.applicationInfo?.takeIf { app.settings.getBool(SettingKeys.CLONE_BADGE) }?.apply {
            sourceDir = apks.first().path
            publicSourceDir = apks.first().path
        }
        AppLog.info("Building ${app.id} ${app.latestVersion} as the clone $target, without: ${removed.sorted().joinToString().ifEmpty { "-" }}")
        val name = app.settings.getStringOrNull(SettingKeys.CLONE_NAME)?.takeIf { it.isNotBlank() }
        return cloner.clone(CloneRequest(apks, target, name, key, icon, removed), cloneDir(app.id)) { _, _, _ -> }
    }

    /**
     * The file to download for [app]: the preferred one, or the user's pick when there are
     * several. A file hosted elsewhere than the source needs a confirmation.
     */
    suspend fun confirmAppFileUrl(
        app: TrackedApp,
        prompts: InstallPrompts?,
        pickAnyAsset: Boolean,
        evenIfSingleChoice: Boolean = false,
    ): NamedUrl? {
        val choices = if (pickAnyAsset) app.apkUrls + app.otherAssetUrls else app.apkUrls
        if (choices.isEmpty()) throw NoApkError()
        var chosen: NamedUrl? = choices[app.preferredApkIndex.takeIf { it in choices.indices } ?: 0]
        if (pickAnyAsset) {
            // The APK filter only pre-selects here; every asset stays available.
            ApkFilter.filterApks(
                choices, app.settings.getStringOrNull("apkFilterRegEx"), app.settings.getBool("invertAPKFilter"),
            ).takeIf { app.settings.getStringOrNull("apkFilterRegEx") != null }?.firstOrNull()?.let { chosen = it }
        }
        if ((choices.size > 1 || evenIfSingleChoice) && prompts != null) {
            chosen = prompts.pickFile(app, choices, chosen, pickAnyAsset)
        }
        fun rootHostOf(url: String): String? =
            if (url == "placeholder") null else Url.rootHost(Url.parse(ApkFilter.splitMultiApkUrl(url).first()).host)
        val picked = chosen ?: return null
        val pickedHost = rootHostOf(picked.url)
        if (pickedHost != null && pickedHost != rootHostOf(app.url) && prompts != null) {
            val trusted = repo.sourceOf(app).trustedApkHosts
            if (pickedHost !in trusted && !settings.hideAPKOriginWarning &&
                !prompts.confirmOrigin(app.url, picked.url)
            ) {
                return null
            }
        }
        return picked
    }

    /**
     * Downloads and installs the newest version of the given apps. With [prompts] the user can
     * be asked; without, only what installs silently is attempted. Returns the installed ids;
     * failures of single apps are thrown together at the end.
     */
    suspend fun downloadAndInstallLatestApps(
        appIds: List<String>,
        prompts: InstallPrompts?,
        forceSerialDownloads: Boolean = false,
        useExisting: Boolean = true,
    ): List<String> = withContext(Dispatchers.IO) {
        val background = prompts == null
        val errors = MultiAppMultiError()
        val toInstall = ArrayList<String>()
        val trackOnly = ArrayList<String>()
        for (id in appIds) {
            var entry = repo.entry(id) ?: throw SourceError(Tr.get("appNotFound"))
            if (entry.needsRefreshBeforeDownload) {
                try {
                    repo.updates.checkUpdate(id)
                } catch (e: Exception) {
                    errors.add(id, e, appName = entry.name)
                    continue
                }
                entry = repo.entry(id) ?: continue
            }
            if (entry.app.settings.getBool(SettingKeys.TRACK_ONLY)) {
                trackOnly.add(id)
                continue
            }
            val picked = try {
                confirmAppFileUrl(entry.app, prompts, pickAnyAsset = false)
            } catch (e: Exception) {
                errors.add(id, e, appName = entry.name)
                null
            } ?: continue
            val index = entry.app.apkUrls.indexOfFirst { it.url == picked.url }
            if (index >= 0 && index != entry.app.preferredApkIndex) {
                repo.saveApps(listOf(entry.app.copy(preferredApkIndex = index)))
            }
            if (!background || canInstallSilentlyInBackground(repo.entry(id)!!.app)) toInstall.add(id)
        }
        // Track-only apps have nothing to install: they are simply marked as up to date.
        repo.saveApps(trackOnly.mapNotNull { repo.entry(it)?.app }.map { it.copy(installedVersion = it.latestVersion) })

        // This app goes last: installing it ends the process.
        toInstall.sortBy { it == context.packageName }
        val installed = Collections.synchronizedList(ArrayList<String>())
        val installLock = Mutex()

        suspend fun handle(id: String) {
            val downloaded = try {
                downloadApp(repo.entry(id)!!.app, background, useExisting)
            } catch (e: Exception) {
                // A cancelled download is not a failure.
                if (e !is CancellationSignal) {
                    synchronized(errors) { errors.add(id, e, appName = repo.entry(id)?.name) }
                    AppLog.error("Download failed for $id", e)
                }
                return
            }
            val resolvedId = downloaded.appId
            repo.setDownload(resolvedId, DownloadState(100.0))
            // Installs happen one at a time, in the order the downloads finish.
            installLock.lock()
            try {
                if (!background && !isForeground && installer(false) is StockInstaller) {
                    // The system prompt can only be shown over our own window.
                    SourcesNotifications.completeInstallation(context)
                    withTimeoutOrNull(5 * 60_000L) { while (!isForeground) delay(500) }
                    SourcesNotifications.cancel(context, SourcesNotifications.ID_COMPLETE_INSTALL)
                }
                if (installDownloaded(downloaded, prompts, background)) installed.add(resolvedId)
            } catch (e: Exception) {
                synchronized(errors) { errors.add(resolvedId, e, appName = repo.entry(resolvedId)?.name) }
                AppLog.error("Install failed for $resolvedId", e)
            } finally {
                installLock.unlock()
                repo.setDownload(resolvedId, null)
            }
        }

        try {
            if (forceSerialDownloads || !settings.parallelDownloads) {
                for (id in toInstall) handle(id)
            } else {
                coroutineScope { toInstall.map { async { handle(it) } }.awaitAll() }
            }
        } finally {
            toInstall.forEach { repo.setDownload(it, null) }
        }
        if (errors.idsByErrorString.isNotEmpty()) throw errors
        installed.toList()
    }

    /** Saves a release file of each app into the Downloads folder. Returns the saved names. */
    suspend fun downloadAppAssets(appIds: List<String>, prompts: InstallPrompts): List<String> = withContext(Dispatchers.IO) {
        val errors = MultiAppMultiError()
        val saved = ArrayList<String>()
        for (id in appIds) {
            var entry = repo.entry(id) ?: throw SourceError(Tr.get("appNotFound"))
            if (entry.needsRefreshBeforeDownload) {
                repo.updates.checkUpdate(id)
                entry = repo.entry(id) ?: continue
            }
            val app = entry.app
            if (app.apkUrls.isEmpty() && app.otherAssetUrls.isEmpty()) continue
            val picked = confirmAppFileUrl(app, prompts, pickAnyAsset = true, evenIfSingleChoice = true) ?: continue
            val source = repo.sourceOf(app)
            val merged = source.buildMergedSettings(app.additionalSettings, settings)
            val urls = ApkFilter.splitMultiApkUrl(picked.url)
            for ((index, rawUrl) in urls.withIndex()) {
                val fileName = if (index == 0) picked.name
                else Url.tryParse(rawUrl)?.pathSegments?.lastOrNull() ?: "${picked.name}-split$index"
                val key = "${app.id}|$rawUrl"
                try {
                    val url = source.assetUrlPrefetchModifier(source.generalReqPrefetchModifier(rawUrl, merged), app.url, merged)
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val progress: ProgressListener = { percent, received, total ->
                        SourcesNotifications.download(context, key, fileName, percent?.let { ceil(it).toInt() } ?: 0, received, total)
                    }
                    source.downloadAsset(url, File(downloads, fileName), progress) { false } ?: Downloader.downloadFileWithRetry(
                        url, fileName, true,
                        progress,
                        downloads,
                        source.requestOptions(app.additionalSettings),
                        useExisting = false,
                        headers = source.getRequestHeaders(
                            app.additionalSettings, url, forAPKDownload = ApkFilter.isApkOrContainerFile(fileName),
                        ),
                    )
                    SourcesNotifications.downloaded(context, fileName, rawUrl)
                    saved.add(fileName)
                } catch (e: Exception) {
                    if (e !is CancellationSignal) errors.add(fileName, e)
                } finally {
                    SourcesNotifications.cancel(context, SourcesNotifications.idForKey(key))
                }
            }
        }
        if (errors.idsByErrorString.isNotEmpty()) throw errors
        saved
    }

    /** Shows the system uninstall prompt and waits for it; true if the app was actually removed. */
    suspend fun uninstallApp(appId: String): Boolean = Installer.uninstall(context, appId)

    companion object {
        private const val BACKGROUND_CONFIRM_ATTEMPTS = 20
        private const val INSTALL_CONFIRM_POLLS = 300
        private const val INSTALL_CONFIRM_TIMEOUT_MS = 10 * 60_000L

        /** Apps that verify an APK shared with them before it is installed. */
        private val verifierPackages = listOf(
            "dev.soupslurpr.appverifier", "com.roundsalmon4.appverifier", "org.privacyguides.verifiedapps",
            "org.privacyguides.verifiedapps.play",
        )

        @Volatile
        private var instance: SourcesInstaller? = null

        fun get(context: Context): SourcesInstaller = instance ?: synchronized(this) {
            instance ?: SourcesInstaller(context.applicationContext).also { instance = it }
        }
    }
}
