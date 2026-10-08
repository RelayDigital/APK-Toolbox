package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.AppNames
import io.github.bl3xand.apkcloner.sources.net.Http
import org.jsoup.Jsoup

/** The official Telegram APK; the version comes from its release channel. */
class TelegramApp : AppSource("TelegramApp") {
    override val name: String get() = Tr.get("telegramApp")

    init {
        hosts = listOf("telegram.org")
    }

    // There is one download page, whatever URL was typed.
    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String = "https://${hosts[0]}"

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        val res = sourceRequest(CHANNEL_URL, additionalSettings)
        if (res.statusCode != 200) throw Http.errorFor(res)
        val version = Jsoup.parse(res.body).select(".tgme_widget_message_text.js-message_text").lastOrNull()
            ?.html()?.split('\n')?.first()?.trim()?.split(' ')?.first()
        if (version.isNullOrEmpty()) throw NoVersionError()
        ApkDetails(version, listOf(NamedUrl("telegram-$version.apk", DOWNLOAD_URL)), AppNames("Telegram", "Telegram"))
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    companion object {
        private const val CHANNEL_URL = "https://t.me/s/TAndroidAPK"
        private const val DOWNLOAD_URL = "https://telegram.org/dl/android/apk"
    }
}
