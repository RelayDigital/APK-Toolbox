package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.ApkPeek
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.RemoteApk
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import io.github.bl3xand.apkcloner.sources.telegram.Telegram
import io.github.bl3xand.apkcloner.sources.telegram.TelegramFile
import io.github.bl3xand.apkcloner.sources.telegram.TelegramFileName
import io.github.bl3xand.apkcloner.sources.telegram.TelegramGateway
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * A public Telegram channel that posts apps as files. A channel may post one app or hundreds, so
 * an app tracked from here is one kind of file of the channel: the releases that agree in the
 * app's name and in the words that say which build it is (see [TelegramFileName]). The version is
 * read from the file's name. A file named some other way is not taken for a release of the app -
 * when an author starts naming files differently, nothing is offered until the app is told so.
 */
class TelegramChannel : AppSource("TelegramChannel") {
    override val name: String get() = Tr.get("telegramChannel")
    override val supportedNote: String get() = Tr.get("telegramChannelsNote")

    init {
        hosts = listOf("t.me", "telegram.me")
        // The name of a file says nothing reliable about the version the installed app reports.
        versionDetectionDisallowed = true
        // The name of the file is kept as it was posted, extension included.
        urlsAlwaysHaveExtension = true
        changeLogIfAnyIsMarkDown = false
        // Which file is meant goes by its kind; names seldom say an architecture, and filtering
        // by one would throw away every file that does not. A kind that does say a foreign one
        // is refused instead (see refuseForeignArchitecture).
        excludeCommonSettingKeys = listOf("autoApkFilterByArch")
    }

    /** The files offered by the last look at a channel, by their links: what a choice is described from. */
    private val lastSeen = ConcurrentHashMap<String, TelegramFile>()

    /** How many files of each kind that look found, by the link of the kind's newest file. */
    private val filesOfKind = ConcurrentHashMap<String, Int>()

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(listOf(TextItem(SETTING_KIND, "telegramFilePattern", required = false, hint = "instander | clone")))

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        val channel = CHANNEL_URL.find(url)?.groupValues?.get(1)
        if (channel == null || channel.lowercase() in NOT_CHANNELS) throw InvalidUrlError(name)
        return "https://${hosts[0]}/$channel"
    }

    override fun getSourceNote(): String? = if (gatewayOrNull() == null) Tr.get("telegramSignInFirst") else null

    private fun gatewayOrNull(): TelegramGateway? = Telegram.gateway?.takeIf { it.isSignedIn }

    /** Said in the words of the settings where the signing in is done, not as "credentials". */
    private fun gateway(): TelegramGateway = gatewayOrNull() ?: throw SourceError(Tr.get("telegramSignInFirst"))

    override val signInNote: String? get() = if (Telegram.gateway?.hasAccount == true) null else Tr.get("srcNeedsSignIn")

    private fun channelOf(standardUrl: String): String = standardUrl.substringAfterLast('/')

    private fun kindOf(settings: Map<String, Any?>): String? =
        (settings[SETTING_KIND] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun isRelease(file: TelegramFile): Boolean = isApkOrContainerFile(file.name)

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val channel = channelOf(standardUrl)
        val gateway = gateway()
        val kind = kindOf(additionalSettings)
        if (kind != null) refuseForeignArchitecture(kind)
        val (title, offered) = if (kind != null) releasesOf(gateway, channel, kind) else everyKind(gateway, channel)
        val newest = offered.maxByOrNull { it.date } ?: throw NoApkError()
        offered.forEach { lastSeen[it.url] = it }
        ApkDetails(
            version = TelegramFileName.version(newest.name) ?: DATE_VERSION.format(newest.date.atOffset(ZoneOffset.UTC)),
            // The newest goes last: that is the one picked when nothing was chosen.
            apkUrls = offered.sortedBy { it.date }.map { NamedUrl(it.name, it.url) },
            names = AppNames(channel, if (kind != null) TelegramFileName.parse(newest.name).name else title),
            releaseDate = newest.date,
            changeLog = newest.caption.takeIf { it.isNotBlank() },
            releaseUrl = newest.url,
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    /**
     * A kind that names an architecture this device does not run cannot be installed here; that is
     * said at once rather than found out by a failed install.
     */
    private fun refuseForeignArchitecture(kind: String) {
        val named = kind.substringAfter(KIND_BAR, "").split(' ').mapNotNull { ARCHITECTURES[it] }
        val supported = SourceEnv.platform.supportedAbis
        if (named.isNotEmpty() && named.none { it == ANY_ARCHITECTURE || it in supported }) {
            throw SourceError(Tr.get("telegramErrArch", named.joinToString(), supported.joinToString()))
        }
    }

    /**
     * The newest release of one [kind]. The channel is asked for the app by name, which finds its
     * files however far back they are; a channel whose names the search does not take apart the
     * way they are read here is looked through from the top instead.
     */
    private fun releasesOf(gateway: TelegramGateway, channel: String, kind: String): Pair<String, List<TelegramFile>> {
        fun newestOf(files: List<TelegramFile>) =
            files.filter { isRelease(it) && TelegramFileName.family(it.name) == kind }.maxByOrNull { it.date }

        val found = gateway.channel(channel, SEARCH_LIMIT, TelegramFileName.appOf(kind))
        var newest = newestOf(found.files)
        // The search may miss a release that was only just posted, so the top is looked at as well.
        val top = gateway.channel(channel, if (newest == null) SCAN_LIMIT else TOP_LIMIT)
        newestOf(top.files)?.let { fromTop -> if (newest == null || fromTop.date > newest!!.date) newest = fromTop }
        AppLog.debug("Telegram: $channel, '$kind': ${found.files.size} file(s) by name, newest ${newest?.name ?: "none"}")
        return found.title to listOfNotNull(newest)
    }

    /** The newest file of every kind the channel has posted lately, for the user to choose from. */
    private fun everyKind(gateway: TelegramGateway, channel: String): Pair<String, List<TelegramFile>> {
        val info = gateway.channel(channel, CHOICE_LIMIT)
        val newestByKind = LinkedHashMap<String, TelegramFile>()
        // The files come newest first, so the first of a kind is its newest.
        val counts = HashMap<String, Int>()
        for (file in info.files.filter(::isRelease)) {
            val kind = TelegramFileName.family(file.name)
            newestByKind.putIfAbsent(kind, file)
            counts.merge(kind, 1, Int::plus)
        }
        newestByKind.forEach { (kind, newest) -> filesOfKind[newest.url] = counts.getValue(kind) }
        return info.title to newestByKind.values.toList()
    }

    /**
     * A channel with one kind of file needs no choosing; the kind is written down all the same.
     * Which build the newest file is gets written down too, so that an app already on the
     * device can be told to be that release or not: its version on the device and the version in
     * the file's name are not the same thing.
     */
    override fun postProcessApp(app: TrackedApp): TrackedApp {
        var result = app
        if (kindOf(app.additionalSettings) == null && app.apkUrls.size == 1) {
            result = result.withSetting(SETTING_KIND, TelegramFileName.family(app.apkUrls.single().name))
        }
        val newest = result.apkUrls.singleOrNull()?.url ?: return result
        if (kindOf(result.additionalSettings) == null || result.additionalSettings[SETTING_BUILD_FILE] == newest) return result
        val build = runCatching { peekAsset(newest, result.additionalSettings) }.getOrNull() ?: return result
        return result.withSetting(SETTING_BUILD_FILE, newest)
            .withSetting(SETTING_BUILD_NAME, build.versionName.orEmpty())
            .withSetting(SETTING_BUILD_CODE, build.versionCode?.toString().orEmpty())
    }

    override fun isLatestBuildInstalled(app: TrackedApp, versionName: String?, versionCode: Long): Boolean? {
        val code = (app.additionalSettings[SETTING_BUILD_CODE] as? String)?.toLongOrNull() ?: return null
        return code == versionCode && (app.additionalSettings[SETTING_BUILD_NAME] as? String).orEmpty() == versionName.orEmpty()
    }

    /**
     * Several kinds of files on offer and none chosen yet: the user says which one to follow. A
     * link to a message with a file has said so already.
     */
    override fun trackingChoice(app: TrackedApp, typedUrl: String): TrackingChoice? {
        if (kindOf(app.additionalSettings) != null) return null
        val message = CHANNEL_URL.find(typedUrl.trim())?.groupValues?.get(2)?.toLongOrNull()
        val linked = message?.let { runCatching { gatewayOrNull()?.file(channelOf(app.url), it) }.getOrNull() }
            ?.takeIf(::isRelease)?.let { TelegramFileName.family(it.name) }
        if (linked == null && app.apkUrls.size < 2) return null
        return TrackingChoice(
            SETTING_KIND, Tr.get("telegramChooseFile"),
            app.apkUrls.reversed().map { file ->
                TrackingChoice.Option(
                    TelegramFileName.family(file.name), TelegramFileName.title(file.name),
                    // When it was last posted, and how many releases of it were found: both tell a
                    // kind that is kept up from a file that was posted once.
                    listOfNotNull(
                        lastSeen[file.url]?.let { Tr.get("telegramLastPosted", POSTED.format(it.date.atZone(ZoneId.systemDefault()))) },
                        filesOfKind[file.url]?.let { Tr.get("telegramFilesFound", it.toString()) },
                    ).joinToString("\n").ifEmpty { file.name.substringAfterLast('.').uppercase() },
                )
            },
            chosen = linked,
        )
    }

    private fun messageOf(assetUrl: String): Pair<String, Long>? =
        MESSAGE_URL.find(assetUrl)?.let { it.groupValues[1] to it.groupValues[2].toLong() }

    override fun downloadAsset(assetUrl: String, destination: File, onProgress: ProgressListener?, isCancelled: () -> Boolean): File? {
        val (channel, messageId) = messageOf(assetUrl) ?: return null
        return gateway().download(channel, messageId, destination, onProgress, isCancelled)
    }

    /** An APK is read in parts for what it says about itself; a bundle of several is not. */
    override fun peekAsset(assetUrl: String, additionalSettings: Map<String, Any?>): ApkPeek? {
        val (channel, messageId) = messageOf(assetUrl) ?: return null
        if (lastSeen[assetUrl]?.name?.endsWith(".apk", ignoreCase = true) == false) return null
        val (size, reader) = gateway().reader(channel, messageId) ?: return null
        return RemoteApk.peek(size, reader)?.also {
            AppLog.debug("Telegram: read ${it.packageName} out of $channel/$messageId without downloading it")
        }
    }

    override fun ownsAsset(assetUrl: String): Boolean = messageOf(assetUrl) != null

    override fun assetSize(assetUrl: String, additionalSettings: Map<String, Any?>): Long? {
        val (channel, messageId) = messageOf(assetUrl) ?: return null
        return gatewayOrNull()?.file(channel, messageId)?.size
    }

    companion object {
        /** The per-app setting that holds the kind of file to follow, as [TelegramFileName.family] gives it. */
        const val SETTING_KIND = "telegramFilePattern"

        /** The newest file of the app, and the version name and code of the build in it. */
        private const val SETTING_BUILD_FILE = "telegramBuildFile"
        private const val SETTING_BUILD_NAME = "telegramBuildName"
        private const val SETTING_BUILD_CODE = "telegramBuildCode"

        /** How many of the latest files are looked at for the kinds a channel has. */
        private const val CHOICE_LIMIT = 1000

        /** How many of the latest files are looked through for one kind when the search by name finds none. */
        private const val SCAN_LIMIT = 300

        /** How many files Telegram's search is asked for, and how many of the latest are checked beside it. */
        private const val SEARCH_LIMIT = 100
        private const val TOP_LIMIT = 100

        private val CHANNEL_URL = Regex("""^https?://(?:t|telegram)\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})(?:/(\d+))?/?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        private val MESSAGE_URL = Regex("""^https?://(?:t|telegram)\.me/([A-Za-z][A-Za-z0-9_]{3,31})/(\d+)$""", RegexOption.IGNORE_CASE)

        /** First parts of a t.me path that are not channel names. */
        private val NOT_CHANNELS = setOf("joinchat", "addstickers", "addemoji", "share", "proxy", "socks", "login", "iv", "addlist", "boost")

        private const val KIND_BAR = " | "
        private const val ANY_ARCHITECTURE = "any"

        /** The words a file name says an architecture with, and the ABI each of them means. */
        private val ARCHITECTURES = mapOf(
            "arm64-v8a" to "arm64-v8a", "arm64" to "arm64-v8a", "armv8" to "arm64-v8a",
            "armeabi-v7a" to "armeabi-v7a", "armv7" to "armeabi-v7a",
            "x86-64" to "x86_64", "x86" to "x86", "universal" to ANY_ARCHITECTURE,
        )

        /** What stands in for a version when the name of a file has none. */
        private val DATE_VERSION = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmm")
        private val POSTED = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
