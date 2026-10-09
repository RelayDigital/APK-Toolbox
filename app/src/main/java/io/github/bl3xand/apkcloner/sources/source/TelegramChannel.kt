package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.CredsNeededError
import io.github.bl3xand.apkcloner.sources.core.InvalidUrlError
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoApkError
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A public Telegram channel that posts an app as a file. The version is read from the file's
 * name, and a file counts as a release only while it is named the way the tracked one is: when
 * the author starts naming files differently, nothing is offered until the app is told the new
 * pattern. A channel that posts several kinds of files asks which one to follow.
 */
class TelegramChannel : AppSource("TelegramChannel") {
    override val name: String get() = Tr.get("telegramChannel")

    init {
        hosts = listOf("t.me", "telegram.me")
        // The name of a file says nothing reliable about the version the installed app reports.
        versionDetectionDisallowed = true
        // The name of the file is kept as it was posted, extension included.
        urlsAlwaysHaveExtension = true
        changeLogIfAnyIsMarkDown = false
    }

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = listOf(listOf(TextItem(SETTING_PATTERN, "telegramFilePattern", required = false, hint = "App_*_arm64.apk")))

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String {
        val channel = CHANNEL_URL.find(url)?.groupValues?.get(1)
        if (channel == null || channel.lowercase() in NOT_CHANNELS) throw InvalidUrlError(name)
        return "https://${hosts[0]}/$channel"
    }

    override fun getSourceNote(): String? = if (gatewayOrNull() == null) Tr.get("telegramSignInFirst") else null

    private fun gatewayOrNull(): TelegramGateway? = Telegram.gateway?.takeIf { it.isSignedIn }

    private fun gateway(): TelegramGateway = gatewayOrNull() ?: throw CredsNeededError(name)

    private fun channelOf(standardUrl: String): String = standardUrl.substringAfterLast('/')

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val channel = channelOf(standardUrl)
        val info = gateway().channel(channel, SCAN_LIMIT)
        // The newest file of every kind the channel posts; the files come newest first.
        val newestByFamily = LinkedHashMap<String, TelegramFile>()
        for (file in info.files.filter { isApkOrContainerFile(it.name) }) {
            newestByFamily.putIfAbsent(TelegramFileName.family(file.name), file)
        }
        val wanted = (additionalSettings[SETTING_PATTERN] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        // With a pattern only files named that way count; without one every kind is on offer.
        val offered = if (wanted != null) listOfNotNull(newestByFamily[wanted]) else newestByFamily.values.toList()
        val newest = offered.maxByOrNull { it.date } ?: throw NoApkError()
        ApkDetails(
            version = TelegramFileName.version(newest.name) ?: DATE_VERSION.format(newest.date.atOffset(ZoneOffset.UTC)),
            // The newest goes last: that is the one picked when nothing was chosen.
            apkUrls = offered.sortedBy { it.date }.map { NamedUrl(it.name, it.url) },
            names = AppNames(channel, info.title),
            releaseDate = newest.date,
            changeLog = newest.caption.takeIf { it.isNotBlank() },
            releaseUrl = newest.url,
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    /** Several kinds of files on offer and none chosen yet: the user says which one to follow. */
    override fun trackingChoice(app: TrackedApp): TrackingChoice? {
        if (!(app.additionalSettings[SETTING_PATTERN] as? String).isNullOrBlank() || app.apkUrls.size < 2) return null
        return TrackingChoice(
            SETTING_PATTERN, Tr.get("telegramChooseFile"),
            app.apkUrls.reversed().map { TrackingChoice.Option(TelegramFileName.family(it.name), TelegramFileName.pattern(it.name), it.name) },
        )
    }

    private fun messageOf(assetUrl: String): Pair<String, Long>? =
        MESSAGE_URL.find(assetUrl)?.let { it.groupValues[1] to it.groupValues[2].toLong() }

    override fun downloadAsset(assetUrl: String, destination: File, onProgress: ProgressListener?, isCancelled: () -> Boolean): File? {
        val (channel, messageId) = messageOf(assetUrl) ?: return null
        return gateway().download(channel, messageId, destination, onProgress, isCancelled)
    }

    override fun assetSize(assetUrl: String, additionalSettings: Map<String, Any?>): Long? {
        val (channel, messageId) = messageOf(assetUrl) ?: return null
        return gatewayOrNull()?.file(channel, messageId)?.size
    }

    companion object {
        /** The per-app setting that holds the kind of file to follow, as [TelegramFileName.family] gives it. */
        const val SETTING_PATTERN = "telegramFilePattern"

        /** How many of the latest messages with a file are looked at. */
        private const val SCAN_LIMIT = 100

        private val CHANNEL_URL = Regex("""^https?://(?:t|telegram)\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})(?:/\d+)?/?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        private val MESSAGE_URL = Regex("""^https?://(?:t|telegram)\.me/([A-Za-z][A-Za-z0-9_]{3,31})/(\d+)$""", RegexOption.IGNORE_CASE)

        /** First parts of a t.me path that are not channel names. */
        private val NOT_CHANNELS = setOf("joinchat", "addstickers", "addemoji", "share", "proxy", "socks", "login", "iv", "addlist", "boost")

        /** What stands in for a version when the name of a file has none. */
        private val DATE_VERSION = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmm")
    }
}
