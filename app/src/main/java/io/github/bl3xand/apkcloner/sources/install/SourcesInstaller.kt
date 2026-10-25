package io.github.bl3xand.apkcloner.sources.install

import io.github.bl3xand.apkcloner.compat.currentSigners
import io.github.bl3xand.apkcloner.compat.archiveInfoOrNull
import io.github.bl3xand.apkcloner.compat.SIGNERS_FLAG
import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.FileProvider
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.install.InstallCoordinator
import io.github.bl3xand.apkcloner.install.InstallOutcome
import io.github.bl3xand.apkcloner.install.InstallReceiver
import io.github.bl3xand.apkcloner.install.Installer
import io.github.bl3xand.apkcloner.install.methods.StockInstaller
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.core.DowngradeError
import io.github.bl3xand.apkcloner.sources.core.IdChangedError
import io.github.bl3xand.apkcloner.sources.core.MultiAppMultiError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.RemoteApk
import io.github.bl3xand.apkcloner.sources.core.SigningCertMismatchError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.TimeBoxedRegex
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.data.certHashesOf
import io.github.bl3xand.apkcloner.sources.data.realInstalledVersionOf
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
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

    /**
     * The apps whose differently-signed installed build is being removed through the system
     * prompt, for the wait to show as the removal it is.
     */
    private val _uninstalling = MutableStateFlow<Set<String>>(emptySet())
    val uninstalling: kotlinx.coroutines.flow.StateFlow<Set<String>> = _uninstalling

    /** The installer the whole app uses for this kind of install. */
    private fun installer(background: Boolean): Installer = Installer.choose(context, background)

    /**
     * The APK whose install is about to begin, the signer the source last offered, and the one
     * the installed app carries: the pair of hashes a clash is judged from, as
     * [checkClashWithInstalled] decides it.
     */
    data class Clash(
        /** The signer of what is on the device, by the app's own package. Empty when it is not installed. */
        val installedSigner: Set<String>,
        /** The signer the source last offered for this app. Empty when it was never learned. */
        val offeredSigner: Set<String>,
    )

    /**
     * Whether installing [picked] over what is on the device is blocked by a signing clash that
     * the user could still do something about: the signer of the installed build is known, the
     * source's say-so about the file is known, and the two disagree. The user can remove the
     * installed app and install the offered build fresh; that is the decision the caller asks
     * about. Everything else - an unknown signer, nothing installed, a clone that carries this
     * app's key - is not this clash and stays with the checks that already handle it.
     */
    fun checkClashWithInstalled(app: TrackedApp, picked: NamedUrl, clash: Clash): Boolean {
        if (app.clonePackage != null || app.cloneTarget != null) return false
        val installed = repo.installedInfo(app.id) ?: return false
        if (!settings.verifySigningCertHashes) return false
        // What the source offered last is the file it offers now only by name: a source whose
        // names carry versions renames every file. The signer of the very file this install is
        // about is the one to judge, and it is known when it was downloaded already.
        val downloads = apkCertHashes(listOfNotNull(pickDownloadedApk(app, picked)))
        return signingClash(clash.installedSigner, clash.offeredSigner, downloads)
    }

    /** The file an earlier download of [picked] left in the cache, when it is still there. */
    private fun pickDownloadedApk(app: TrackedApp, picked: NamedUrl): File? {
        val suffix = "-${picked.url.hashCode()}"
        return repo.apkDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("${app.id}-") && it.name.substringBeforeLast('.') == "${app.id}$suffix"
        }
    }

    fun cancelDownload(appId: String) {
        cancellations[appId]?.cancel()
        repo.setDownload(appId, null)
    }

    // ---- download ----------------------------------------------------------------------------

    /** What an APK file says about itself, whoever was able to read it. */
    private class ApkFacts(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val permissions: Set<String>,
        /** Only when the system read the file: what its name and icon are loaded with. */
        val appInfo: ApplicationInfo?,
    )

    /**
     * Reads an APK file. The system is asked first; what it will not read by itself - the base of
     * a set of splits, which it refuses because it could not be installed alone - is read here.
     */
    private fun facts(file: File): ApkFacts? {
        val info = packageManager.archiveInfoOrNull(file.path, PackageManager.GET_PERMISSIONS)
        if (info != null) {
            return ApkFacts(
                info.packageName, info.versionName, info.versionCodeLong, info.requestedPermissions.orEmpty().toSet(), info.applicationInfo,
            )
        }
        val peek = RemoteApk.peek(file) ?: return null
        return ApkFacts(peek.packageName, peek.versionName, peek.versionCode ?: 0, peek.permissions, null)
    }

    private fun apkCertHashes(files: List<File>): Set<String> = files.flatMap { file ->
        // Read here when the system does not say: it refuses some files, and older versions
        // do not tell the signer of a file that is not installed.
        certHashesOf(packageManager.archiveInfoOrNull(file.path, SIGNERS_FLAG)).ifEmpty { RemoteApk.peek(file)?.certHashes.orEmpty() }
    }.toSet()

    /**
     * Extracts a zip, refusing entries that would land outside [destination]. The archive is read
     * by its directory, not as a stream: an APK stored in it uncompressed is a zip itself, and a
     * reader that goes through the bytes in order takes the files of that APK for files of the
     * archive.
     */
    private fun unzip(file: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalPath
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                val out = File(destination, entry.name)
                if (!out.canonicalPath.startsWith("$root/")) throw SourceError(Tr.get("invalidArchive"))
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
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

    /** A zip that is not an APK itself and has APKs inside: APKS, XAPK, APKM, whatever it is called. */
    private fun isArchiveOfApks(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") == null &&
                zip.entries().asSequence().any { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
        }
    }.getOrDefault(false)

    private fun isTarballName(name: String): Boolean = ApkFilter.tarballExtensions.any { name.endsWith(it) }

    /**
     * Gives an app its real package name once the APK is at hand. A changed name on an app that
     * already had a real one is refused: it would be a different app. So is one on an app whose
     * name was only worked out but is borne out by what is installed - a release can hold builds
     * under several packages, and picking another must not turn the app into a second one next
     * to the first. The other way round is let through: an app that is not on the device under
     * the name it has, while the downloaded one is, takes that name.
     */
    private fun handleApkIdChange(appIn: TrackedApp, actual: String, file: File, downloadUrl: String): Pair<File, TrackedApp> {
        var app = appIn
        var result = file
        if (app.id != actual) {
            val tracked = repo.entry(app.id) != null
            val installedHere = repo.installedInfo(app.id) != null
            val settles = !installedHere && repo.installedInfo(actual) != null
            if (tracked && !app.hasTempId && !settles && (!app.allowIdChange || installedHere)) {
                throw IdChangedError(actual).also { it.url = app.url }
            }
            val idChangeWasAllowed = app.allowIdChange
            val wasTemp = app.hasTempId
            val originalId = app.id
            app = app.copy(id = actual, allowIdChange = false)
            val renamed = File(file.parentFile, "${app.id}-${downloadUrl.hashCode()}.${file.extension}")
            if (file.renameTo(renamed)) result = renamed
            if (tracked) {
                repo.removeApps(listOf(originalId))
                repo.saveApps(listOf(app), onlyIfExists = !wasTemp && !idChangeWasAllowed && !settles)
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
            // The file remembered from the last pick decides which one this is, not the place in
            // the list; a source that reorders or renames its files cannot redirect a download.
            val chosen = ApkFilter.resolvePreferredApk(
                ApkFilter.storedPreferredApk(app.settings.getStringOrNull(SettingKeys.PREFERRED_APK)),
                app.apkUrls, app.preferredApkIndex,
            ) ?: app.apkUrls[app.preferredApkIndex.coerceIn(0, app.apkUrls.size - 1)]
            app = app.copy(preferredApkIndex = app.apkUrls.indexOf(chosen).takeIf { it >= 0 } ?: app.preferredApkIndex)
            val source = repo.sourceOf(app)
            val merged = source.buildMergedSettings(app.additionalSettings, settings)
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
            // By what it holds, not by what it is called: a set of splits may come under the name of an APK.
            val isApk = file.path.lowercase().endsWith(".apk") && !isArchiveOfApks(file)
            val isTarball = isTarballName(originalAssetName)
            var dir: File? = null
            var info: ApkFacts? = null
            if (isApk && !multipleApks) {
                info = facts(file)
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
                        // User pattern over source-provided entry names: budgeted for the same
                        // reason as the other filters (a hang would block this install thread).
                        val unwanted = !TimeBoxedRegex.containsMatchIn(
                            regex, apk.path.substring(dir.path.length + 1), TimeBoxedRegex.SHORT_INPUT_BUDGET_MS,
                        )
                        if (unwanted) apk.delete()
                        unwanted
                    }
                }
                if (apks.isEmpty()) throw NoApkError()
                info = apks.firstNotNullOfOrNull(::facts)
            }
            if (info == null) {
                // What came instead of an APK says most about why: a page, a part of a file, an archive.
                val head = runCatching { file.inputStream().use { input -> ByteArray(4).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }.joinToString("") { "%02x".format(it) } }.getOrNull()
                AppLog.warn("${app.id}: ${file.name} (${file.length()} bytes, starts with $head) is not an APK that can be read" +
                    (dir?.let { d -> "; unpacked: ${d.walkTopDown().filter(File::isFile).joinToString { "${it.name} ${it.length()}" }}" } ?: ""))
                file.delete()
                dir?.deleteRecursively()
                throw SourceError(Tr.get("couldNotGetIdFromApk")).also { it.url = app.url }
            }
            val (renamed, resolved) = handleApkIdChange(app, info.packageName, file, downloadUrl)
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
        // The branch that was followed is gone: nothing is guessed in its place by itself.
        if (app.releaseBranchGone) return false
        // Several files to choose from need a choice - unless the one to take is remembered.
        if (app.apkUrls.size > 1) {
            if (resolveRememberedApk(app) == null) return false
        }
        if (installer(background = true) !is StockInstaller) return true
        if (app.id == context.packageName) return false
        val targetSdk = repo.installedInfo(app.devicePackage)?.applicationInfo?.targetSdkVersion ?: return false
        return StockInstaller.isUpdateWithoutPrompt(context, app.devicePackage, targetSdk)
    }

    /** The file of the current list the app's remembered pick stands for, when it still does. */
    private fun resolveRememberedApk(app: TrackedApp): NamedUrl? = ApkFilter.resolvePreferredApk(
        ApkFilter.storedPreferredApk(app.settings.getStringOrNull(SettingKeys.PREFERRED_APK)),
        app.apkUrls, app.preferredApkIndex,
    )

    fun canInstallSilentlyInBackground(app: TrackedApp): Boolean =
        settings.enableBackgroundUpdates && !app.settings.getBool(SettingKeys.EXEMPT_FROM_BACKGROUND_UPDATES) && canInstallSilently(app)

    private fun baseline(appId: String): InstallBaseline {
        val info = repo.installedInfo(appId)
        return InstallBaseline(info != null, info?.versionCodeLong, info?.lastUpdateTime)
    }

    private fun changedSince(appId: String, baseline: InstallBaseline): Boolean {
        val info = repo.installedInfo(appId) ?: return false
        if (!baseline.wasInstalled) return true
        return if (baseline.updateTime != null) info.lastUpdateTime != baseline.updateTime
        else info.versionCodeLong != baseline.versionCode
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
    private suspend fun verifySignatures(entry: AppEntry, apkHashes: Set<String>, prompts: InstallPrompts?, asClone: Boolean) {
        val userHashes = CertHashes.parseAllowed(entry.app.settings.getStringOrNull("allowedSigningCertHashes"))
        // A clone carries this app's key; what its updates are held against is the signer of the
        // APK it was built from.
        // APK it was built from - and nothing at all for a clone that is yet to be made, which
        // goes next to whatever is installed, not over it.
        val installedHashes = if (!asClone) entry.certificateHashes.toSet()
        else entry.app.cloneSourceSigner.takeIf { entry.app.clonePackage != null && entry.installedInfo != null }.orEmpty()
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
     * Installs [apks] (base first) for the tracked app [id], which land on the device as [appId]:
     * the app's own package, or its clone's. Returns true when the app is installed afterwards.
     */
    private suspend fun installFiles(
        id: String,
        appId: String,
        apks: List<File>,
        background: Boolean,
        pretendPlay: Boolean,
        /** For files that are not this install's to delete: the splits of the installed app itself. */
        keepFiles: Boolean = false,
        /** Chosen once by the caller for the whole pass; Installer.choose is not re-run here. */
        chosen: Installer? = null,
    ): Boolean {
        val entry = repo.entry(id) ?: throw SourceError(Tr.get("appNotFound"))
        val newInfo = facts(apks.first())
        if (newInfo == null) {
            apks.forEach { it.delete() }
            throw SourceError(Tr.get("badDownload")).also { it.url = entry.app.url }
        }
        val installed = repo.installedInfo(appId)
        AppLog.info(
            "Installing \"${newInfo.packageName}\" version \"${newInfo.versionName}\" (${newInfo.versionCode})" +
                (installed?.let { " over \"${it.versionName}\" (${it.versionCodeLong})" } ?: ""),
        )
        if (installed != null && newInfo.versionCode < installed.versionCodeLong &&
            repo.installedInfo("com.berdik.letmedowngrade") == null && settings.showAppDowngradeError
        ) {
            apks.first().delete()
            throw DowngradeError(installed.versionCodeLong, newInfo.versionCode)
        }
        val before = baseline(appId)
        // The method was chosen when the pass began; it does not change under an install.
        val method = chosen ?: installer(background)
        AppLog.debug("Installing $appId with ${method.javaClass.simpleName}, ${apks.size} file(s), background=$background")
        // One install of a package at a time, across every update driver; the hand-off to
        // the system goes through the coordinator's commit, so a background pass and an
        // obtain asked for on screen cannot interleave their prompts.
        // The outcome is reported here and by the callers: a claimed session makes the
        // receiver stay silent about it, and the claim is consumed by its own result.
        val outcome = InstallCoordinator.installing(appId, background = background) {
            commit {
                val result = method.install(context, apks, background, entry.name, if (pretendPlay) Installer.PLAY_STORE_PACKAGE else null)
                if (result is InstallOutcome.Pending) InstallReceiver.claimSelfReport(result.sessionId)
                result
            }
        }
        val succeeded = when (outcome) {
            is InstallOutcome.Failed -> {
                AppLog.error("Install of $appId failed: ${outcome.reason}")
                throw SourceError(outcome.reason)
            }
            InstallOutcome.Success -> true
            is InstallOutcome.Pending -> {
                if (background) {
                    // This session's answer, with a poll for a success that is never
                    // broadcast as one (some system installs just land). A failure is
                    // thrown as the installer's own words, so it is reported once, by
                    // whoever ran this pass - not again by the receiver.
                    val answer = withTimeoutOrNull(BACKGROUND_CONFIRM_TIMEOUT_MS) {
                        val answered = async { InstallReceiver.awaitSession(outcome.sessionId) }
                        val landed = async { waitForPackageInstall(appId, before, INSTALL_CONFIRM_POLLS, 1000) }
                        @Suppress("UNCHECKED_CAST")
                        kotlinx.coroutines.selects.select<Any?> {
                            answered.onAwait { result -> result }
                            landed.onAwait { null }
                        } as InstallReceiver.SessionResult?
                    }
                    when {
                        answer?.succeeded == true -> true
                        answer != null && !answer.succeeded -> {
                            AppLog.error("Install of $appId failed: ${answer.reasonText}")
                            throw SourceError(answer.reasonText)
                        }
                        // No answer within the timeout: what landed on the device decides.
                        else -> waitForPackageInstall(appId, before, attempts = BACKGROUND_CONFIRM_ATTEMPTS)
                    }
                } else {
                    // Whichever comes first: this session's answer or the package changing.
                    withTimeoutOrNull(INSTALL_CONFIRM_TIMEOUT_MS) {
                        coroutineScope {
                            val answered = async { InstallReceiver.awaitSession(outcome.sessionId) }
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
        AppLog.info(if (succeeded) "Installed $appId ${newInfo.versionName}" else "Install of $appId was not confirmed")
        if (succeeded) {
            repo.entry(id)?.let { repo.saveApps(listOf(it.app.copy(installedVersion = it.app.latestVersion))) }
            if (!keepFiles) apks.forEach { it.delete() }
        }
        return succeeded
    }

    private suspend fun installDownloaded(
        downloaded: Downloaded,
        prompts: InstallPrompts?,
        background: Boolean,
        /** Chosen once for the whole pass, so the method cannot change under an install. */
        chosen: Installer,
    ): Boolean {
        val id = downloaded.appId
        val entry = repo.entry(id) ?: return false
        val apks = if (downloaded.dir != null) {
            moveObbFiles(downloaded.dir, entry.app.clonePackage ?: id)
            orderApks(
                downloaded.dir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".apk") }.toMutableList(),
                id, downloaded.splitSet,
            )
        } else mutableListOf(downloaded.file)
        if (apks.isEmpty()) throw NoApkError()
        // As a clone when that is how the app is installed already, or when this install was
        // asked to make the clone that is set up.
        val target = entry.app.clonePackage ?: entry.app.cloneTarget?.takeIf { cloneRequests.remove(id) }
        val apkHashes = apkCertHashes(apks)
        try {
            verifySignatures(entry, apkHashes, prompts, asClone = target != null)
        } catch (e: SigningCertMismatchError) {
            // The first time a differently-signed file meets the app it replaces, nothing knew
            // its signer beforehand - the download itself was the discovery. The user can still
            // have the last word over it: remove the installed app and install this build
            // fresh, while the file is already in hand. Only the soft refusal (the installed
            // app is in the way); a user-pinned certificate stays final.
            if (!background && !e.hardBlock && target == null && prompts != null &&
                removeForInstall(entry, prompts)
            ) {
                // The signer on record is this file's now, so the page stops telling about a
                // conflict the removal just resolved.
                repo.storeApkCertHashes(id, apkHashes)
                verifySignatures(repo.entry(id) ?: entry, apkHashes, prompts, asClone = false)
            } else {
                throw e
            }
        }
        repo.setDownload(id, DownloadState(-1.0))
        try {
            if (!background && entry.installedInfo == null) shareWithVerifier(apks.first())
            val pretendPlay = settings.shizukuPretendToBeGooglePlay ||
                entry.app.settings.getBool(SettingKeys.PRETEND_GOOGLE_PLAY)
            // Installed as a clone, the downloaded APK never reaches the device itself.
            val files = if (target != null) cloneOf(entry, apks, target) else apks
            val installed = installFiles(id, target ?: id, files, background, pretendPlay, chosen = chosen)
            if (target != null) {
                cloneDir(id).deleteRecursively()
                if (installed) {
                    apks.forEach { it.delete() }
                    // From here on the clone is what the app is installed as. The signer is what
                    // the next release is held against, as the system would for an app of its own.
                    repo.entry(id)?.let {
                        repo.saveApps(
                            listOf(
                                it.app.withSetting(SettingKeys.CLONE_ACTIVE, true)
                                    .withSetting(SettingKeys.CLONE_SOURCE_SIGNER, apkHashes.joinToString(","))
                                    .copy(installedVersion = it.app.latestVersion),
                            ),
                        )
                    }
                }
            }
            if (background) {
                val app = repo.entry(id)?.app ?: entry.app
                if (chosen is StockInstaller && !installed) {
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
        val requested = facts(base)?.permissions.orEmpty()
        repo.entry(downloaded.appId)?.let {
            repo.saveApps(listOf(it.app.withSetting(SettingKeys.CLONE_REQUESTED_PERMISSIONS, requested.sorted().joinToString(","))))
        }
        return downloaded.appId to requested
    }

    private val cloneRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * The next install of [id] makes the clone it is set up to be installed as. Asked for by the
     * user only: nothing that runs by itself turns an app into a clone.
     */
    fun installAsClone(id: String) {
        cloneRequests.add(id)
    }

    private fun manifestFile(id: String) = File(context.filesDir, "sources/clone_manifests/$id.xml")

    /**
     * Keeps the manifest the original has, for the version being made into a clone. It is all of
     * the original that the clone does not have itself, and what lets the clone be built again
     * with other permissions without the original being fetched once more.
     */
    private fun keepOriginalManifest(app: TrackedApp, base: File, versionCode: Long) {
        runCatching {
            val bytes = ZipFile(base).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() } }
            manifestFile(app.id).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            repo.entry(app.id)?.let {
                repo.saveApps(listOf(it.app.withSetting(SettingKeys.CLONE_MANIFEST_VERSION, versionCode.toString())))
            }
        }.onFailure { AppLog.warn("${app.id}: the original's manifest could not be kept: ${it.message}") }
    }

    /**
     * Applies the permissions set for the clone [id] is installed as to the clone that is on the
     * device, without downloading anything: the clone is built again from itself and the manifest
     * kept from its original, and installed over itself. False when that manifest is not there or
     * is of another version - then the release has to be fetched, as for an update - or when the
     * clone's dex patches are not what the app is set to be built with: the dex the clone carries
     * is already patched and cannot be patched again (the spoof's helper would recurse into
     * itself), so a change of patches has to be built from the release.
     */
    suspend fun rebuildInstalledClone(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = repo.entry(id) ?: return@withContext false
        val app = entry.app
        val target = app.clonePackage ?: return@withContext false
        val installed = repo.installedInfo(target) ?: return@withContext false
        val manifest = manifestFile(id).takeIf {
            it.isFile && app.settings.getStringOrNull(SettingKeys.CLONE_MANIFEST_VERSION) == installed.versionCodeLong.toString()
        } ?: return@withContext false
        val info = installed.applicationInfo ?: return@withContext false
        // The dex patches of the build that is on the device. A rebuild from itself reuses its
        // dex as it is, so the settings and the APK may not disagree about what is on.
        if (ApkCloner.readMetadata(info.sourceDir)?.patches != app.clonePatches) return@withContext false
        val cloner = ApkCloner(context)
        val key = installed.currentSigners.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) }
            ?: return@withContext false
        repo.setDownload(id, DownloadState(-1.0))
        try {
            val removed = app.cloneRemovedPermissions
            AppLog.info("Building the installed clone $target again, without: ${removed.sorted().joinToString().ifEmpty { "-" }}, with patches: ${app.clonePatches.sorted().joinToString().ifEmpty { "-" }}")
            val name = app.settings.getStringOrNull(SettingKeys.CLONE_NAME)?.takeIf { it.isNotBlank() }
            val base = cloner.recloneBase(
                File(info.sourceDir), manifest.readBytes(),
                CloneRequest(emptyList(), target, name, key, null, removed, app.clonePatches),
                cloneDir(id),
            )
            val pretendPlay = settings.shizukuPretendToBeGooglePlay || app.settings.getBool(SettingKeys.PRETEND_GOOGLE_PLAY)
            val files = listOf(base) + info.splitSourceDirs.orEmpty().map(::File)
            installFiles(id, target, files, background = false, pretendPlay = pretendPlay, keepFiles = true)
        } finally {
            cloneDir(id).deleteRecursively()
            repo.setDownload(id, null)
        }
    }

    /**
     * Makes the clone [id] is set up to be installed as out of the app that is on the device:
     * nothing is downloaded, the clone is of the very version that is installed. The original is
     * left where it is - removing it is for whoever asked, once the clone is known to be there.
     * False when the clone did not get installed.
     */
    suspend fun cloneInstalledOriginal(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = repo.entry(id) ?: return@withContext false
        val target = entry.app.cloneTarget ?: return@withContext false
        val original = repo.installedInfo(id) ?: return@withContext false
        val info = original.applicationInfo ?: return@withContext false
        repo.setDownload(id, DownloadState(-1.0))
        try {
            AppLog.info("$id: the clone $target is made from the installed app, nothing is downloaded")
            val apks = listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File)
            val pretendPlay = settings.shizukuPretendToBeGooglePlay || entry.app.settings.getBool(SettingKeys.PRETEND_GOOGLE_PLAY)
            val installed = installFiles(id, target, cloneOf(entry, apks, target), background = false, pretendPlay = pretendPlay)
            if (installed) {
                repo.entry(id)?.let {
                    repo.saveApps(
                        listOf(
                            it.app.withSetting(SettingKeys.CLONE_ACTIVE, true)
                                // What the source offers next has to be signed as the original was.
                                .withSetting(SettingKeys.CLONE_SOURCE_SIGNER, certHashesOf(original).joinToString(","))
                                .copy(installedVersion = realInstalledVersionOf(it.app, original)),
                        ),
                    )
                }
            }
            installed
        } finally {
            cloneDir(id).deleteRecursively()
            repo.setDownload(id, null)
        }
    }

    private fun cloneDir(id: String) = File(repo.apkDir, "$id-clone")

    /**
     * Rebuilds the downloaded [apks] as the clone [entry] is installed as: under the clone's
     * package, without the permissions that were taken away, signed with the key the installed
     * clone has so that it goes over it and the data stays.
     */
    private fun cloneOf(entry: AppEntry, apks: List<File>, target: String): List<File> {
        var app = entry.app
        val cloner = ApkCloner(context)
        // Only a clone made here can be updated: anything else under that package is signed by someone else.
        val key = repo.installedInfo(target)?.let { installed ->
            installed.currentSigners.firstNotNullOfOrNull { cloner.keys.matching(it.toByteArray()) }
                ?: throw SourceError(Tr.get("cloneInstallTaken", target))
        }
        val info = facts(apks.first())
        // Nothing is asked here: what the clone goes without is set on the app's page, before or
        // after. What this release asks for is kept, so that the page can show what is new.
        val requested = info?.permissions.orEmpty()
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
        val icon = info?.appInfo?.takeIf { app.settings.getBool(SettingKeys.CLONE_BADGE) }?.apply {
            sourceDir = apks.first().path
            publicSourceDir = apks.first().path
        }
        AppLog.info("Building ${app.id} ${app.latestVersion} as the clone $target, without: ${removed.sorted().joinToString().ifEmpty { "-" }}, with patches: ${app.clonePatches.sorted().joinToString().ifEmpty { "-" }}")
        val name = app.settings.getStringOrNull(SettingKeys.CLONE_NAME)?.takeIf { it.isNotBlank() }
        keepOriginalManifest(app, apks.first(), info?.versionCode ?: 0)
        return cloner.clone(CloneRequest(apks, target, name, key, icon, removed, app.clonePatches), cloneDir(app.id)) { _, _, _ -> }
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
        // The file remembered from the last pick: it is what the next update goes through with,
        // without asking again. While the followed branch is gone there is no preselection
        // either - the "suggested" mark would point at a place that no longer means anything -
        // and the pick has to be made on purpose.
        val branchGone = app.releaseBranchGone && !pickAnyAsset
        val remembered = ApkFilter.resolvePreferredApk(
            ApkFilter.storedPreferredApk(app.settings.getStringOrNull(SettingKeys.PREFERRED_APK)),
            choices, app.preferredApkIndex,
        )
        var chosen: NamedUrl? = if (branchGone) null else remembered
        if (pickAnyAsset) {
            // The APK filter only pre-selects here; every asset stays available.
            ApkFilter.filterApks(
                choices, app.settings.getStringOrNull("apkFilterRegEx"), app.settings.getBool("invertAPKFilter"),
            ).takeIf { app.settings.getStringOrNull("apkFilterRegEx") != null }?.firstOrNull()?.let { chosen = it }
        }
        if ((choices.size > 1 || evenIfSingleChoice || branchGone) && prompts != null && (remembered == null || branchGone)) {
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
     * Removes the app installed under [entry]'s own package, once the user has agreed to lose
     * it, through the system prompt, keeping the record honest about no longer being installed.
     * False when the user backed out of the prompt or the removal did not finish: the install
     * then does not go ahead.
     */
    private suspend fun removeForInstall(entry: AppEntry, prompts: InstallPrompts): Boolean {
        if (!prompts.removeThenInstall(entry.name)) return false
        AppLog.info("${entry.app.id}: removing the differently-signed installed build before installing ${entry.app.latestVersion}")
        _uninstalling.update { it + entry.app.id }
        try {
            if (!Installer.uninstall(context, entry.app.id)) return false
        } finally {
            _uninstalling.update { it - entry.app.id }
        }
        // Gone from the device: the record stops claiming an install it cannot name, so
        // the page and the list say "not installed" until this very build lands on it.
        repo.entry(entry.app.id)?.let {
            repo.saveApps(listOf(it.app.copy(installedVersion = null)), attemptToCorrectInstallStatus = false)
        }
        return true
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
            // A pick made on purpose is remembered in every form the app judges files by: the
            // place in the list, the branch it belongs to, and the file itself. It also clears
            // a branch said to be gone - the user has just chosen one that is there.
            if (index >= 0 && (index != entry.app.preferredApkIndex ||
                    entry.app.releaseBranchGone || entry.app.preferredBranchKey == null)
            ) {
                repo.saveApps(
                    listOf(
                        entry.app.copy(preferredApkIndex = index, releaseBranchGone = false).let {
                            if (index < it.apkUrls.size) {
                                it.copy(preferredBranchKey = repo.sourceOf(it).releaseBranchKeyOf(it.apkUrls[index]))
                            } else {
                                it
                            }
                        }.withSetting(SettingKeys.PREFERRED_APK, TrackedApp.namedUrlsToJson(listOf(picked))),
                    ),
                )
            }
            // A file signed differently from the installed build cannot go over it, so the
            // question is asked before anything is downloaded, and the app in the way is
            // removed when the user chooses the file. What stays installed afterwards is
            // judged live - another app may have been removed meanwhile.
            if (prompts != null) {
                val entryNow = repo.entry(id)!!
                if (checkClashWithInstalled(
                        entryNow.app, picked,
                        Clash(installedSigner = entryNow.certificateHashes.toSet(), offeredSigner = repo.storedApkCertHashes(id)),
                    ) &&
                    !removeForInstall(entryNow, prompts)
                ) {
                    continue
                }
            }
            if (!background || canInstallSilentlyInBackground(repo.entry(id)!!.app)) toInstall.add(id)
        }
        // Track-only apps have nothing to install: they are simply marked as up to date.
        repo.saveApps(trackOnly.mapNotNull { repo.entry(it)?.app }.map { it.copy(installedVersion = it.latestVersion) })

        // This app goes last: installing it ends the process.
        toInstall.sortBy { it == context.packageName }
        val installed = Collections.synchronizedList(ArrayList<String>())
        // Resolved once for the whole pass: what canInstallSilently predicted must be what
        // installs, or Shizuku dying between the two would strand a session mid-batch.
        val chosen = installer(background)

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
            try {
                // The long foreground wait happens before the install proper, so it blocks
                // no other install (lock discipline: long waits outside every lock).
                if (!background && !isForeground && chosen is StockInstaller) {
                    // The system prompt can only be shown over our own window.
                    SourcesNotifications.completeInstallation(context)
                    val cameToFront = withTimeoutOrNull(5 * 60_000L) { while (!isForeground) delay(500); true } ?: false
                    SourcesNotifications.cancel(context, SourcesNotifications.ID_COMPLETE_INSTALL)
                    // Committing with the app in the background would ask for a
                    // confirmation the system refuses to even show (a receiver cannot
                    // start an activity from the background), stranding the session.
                    // The update stays pending: the app's row offers it again.
                    if (!cameToFront) {
                        AppLog.warn("Install of $resolvedId waited for the app's window in vain; leaving it for a calmer moment")
                        repo.setDownload(resolvedId, null)
                        return
                    }
                }
                if (installDownloaded(downloaded, prompts, background, chosen)) installed.add(resolvedId)
            } catch (e: Exception) {
                synchronized(errors) { errors.add(resolvedId, e, appName = repo.entry(resolvedId)?.name) }
                AppLog.error("Install failed for $resolvedId", e)
            } finally {
                repo.setDownload(resolvedId, null)
            }
        }

        // A background batch installs as one unit: an obtain asked for on screen while it
        // runs waits for it instead of interleaving its notifications and prompts.
        val installBatch: suspend () -> Unit = {
            try {
                if (forceSerialDownloads || !settings.parallelDownloads) {
                    for (id in toInstall) handle(id)
                } else {
                    coroutineScope { toInstall.map { async { handle(it) } }.awaitAll() }
                }
            } finally {
                toInstall.forEach { repo.setDownload(it, null) }
                cloneRequests.removeAll(appIds.toSet())
            }
        }
        if (background) InstallCoordinator.backgroundBatch(installBatch) else installBatch()
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

        /**
         * Whether installing a file over what is on the device is blocked by a signing clash the
         * user could still do something about. Pure judgment over what is known: the signer of
         * the installed build, the signer the source last offered, and - when the very file was
         * already downloaded - the signer it carries. The caller supplies the facts and rules
         * out everything that is not this judgment (nothing installed, a clone, the check
         * switched off); with those out of the way, a known disagreement of the signers is a
         * clash, and the file's own signer wins over the source's remembered say-so whenever it
         * can be read - a source whose file names carry versions renames every file, so the
         * remembered signer is of another file of the same branch.
         */
        fun signingClash(installedSigner: Set<String>, offeredSigner: Set<String>, downloadedSigner: Set<String>): Boolean {
            if (installedSigner.isEmpty() || offeredSigner.isEmpty()) return false
            if (downloadedSigner.isNotEmpty()) return !installedSigner.containsAll(downloadedSigner)
            return !installedSigner.containsAll(offeredSigner)
        }

        /** How long a background session waits for the system's answer to its commit. */
        private const val BACKGROUND_CONFIRM_TIMEOUT_MS = 3 * 60_000L

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
