package io.github.bl3xand.apkcloner.sources.live

import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fetches one known app per source from the real site. Network tests are opt-in:
 * `./gradlew :app:testDebugUnitTest -Plive=true --tests '*LiveSourcesTest*'`
 * and `-Plive.only=<Source>` narrows the run to one source.
 */
class LiveSourcesTest {
    private fun fetch(url: String, settings: Map<String, Any?> = emptyMap(), override: String? = null): TrackedApp {
        val source = SourceRegistry.getSource(url, override)
        val values = defaultValuesOf(source.combinedAppSpecificSettingFormItems)
        values.putAll(source.runOnAddAppInputChange(url))
        values.putAll(settings)
        val app = SourceRegistry.getApp(
            source, url, values, sourceIsOverriden = override != null, inferAppIdIfOptional = true,
        )
        println(
            "[${source.sourceIdentifier}] ${app.name} by ${app.author} | id=${app.id} | v=${app.latestVersion} | " +
                "date=${app.releaseDate} | apks=${app.apkUrls.map { it.name }} | other=${app.otherAssetUrls.size} | " +
                "changelog=${app.changeLog?.take(40)?.replace('\n', ' ')}",
        )
        return app
    }

    private fun live(source: String) {
        assumeTrue(System.getProperty("live") == "true")
        val only = System.getProperty("live.only")
        assumeTrue(only.isNullOrEmpty() || only.equals(source, ignoreCase = true))
    }

    @Test
    fun gitHub() {
        live("GitHub")
        val app = fetch("https://github.com/ImranR98/Obtainium")
        check(app.apkUrls.isNotEmpty() && app.latestVersion.isNotEmpty())
        val search = SourceRegistry.getSource("github.com/a/b").search("obtainium")
        println("  search: ${search.size} results, first=${search.entries.first()}")
        check(search.isNotEmpty())
    }

    @Test
    fun gitLab() {
        live("GitLab")
        check(fetch("https://gitlab.com/AuroraOSS/AuroraStore/-/releases").apkUrls.isNotEmpty())
        println("  search: ${SourceRegistry.getSource("gitlab.com/a/b").search("aurora store").entries.take(2)}")
    }

    @Test
    fun codeberg() {
        live("Codeberg")
        check(fetch("https://codeberg.org/Freeyourgadget/Gadgetbridge", mapOf("trackOnly" to true)).latestVersion.isNotEmpty())
        println("  search: ${SourceRegistry.getSource("codeberg.org/a/b").search("gadgetbridge").entries.take(2)}")
    }

    @Test
    fun fDroid() {
        live("FDroid")
        val app = fetch("https://f-droid.org/en/packages/org.fdroid.fdroid/")
        check(app.id == "org.fdroid.fdroid" && app.apkUrls.size == 1)
        println("  search: ${SourceRegistry.getSource("f-droid.org/packages/x").search("newpipe").entries.take(2)}")
    }

    @Test
    fun izzyOnDroid() {
        live("IzzyOnDroid")
        check(fetch("https://apt.izzysoft.de/fdroid/index/apk/com.aurora.store").id == "com.aurora.store")
    }

    @Test
    fun fDroidRepo() {
        live("FDroidRepo")
        val app = fetch(
            "https://mobileapp.bitwarden.com/fdroid/repo?appId=com.x8bit.bitwarden", override = "FDroidRepo",
        )
        check(app.id == "com.x8bit.bitwarden" && app.apkUrls.isNotEmpty())
        val search = SourceRegistry.getSource("https://mobileapp.bitwarden.com/fdroid/repo", "FDroidRepo")
            .search("", mapOf("url" to "https://mobileapp.bitwarden.com/fdroid/repo"))
        println("  search: $search")
    }

    @Test
    fun html() {
        live("HTML")
        check(fetch("https://f-droid.org/", override = "HTML").apkUrls.single().url.endsWith(".apk"))
        check(
            fetch(
                "https://updates.signal.org/android/latest.json",
                mapOf("versionExtractionRegEx" to "\\d+.\\d+.\\d+"),
            ).latestVersion.matches(Regex("\\d+.\\d+.\\d+")),
        )
    }

    @Test
    fun directApkLink() {
        live("DirectAPKLink")
        val app = fetch(
            "https://f-droid.org/F-Droid.apk", mapOf("defaultPseudoVersioningMethod" to "ETag"), "DirectAPKLink",
        )
        check(app.latestVersion.length == 12)
        check(fetch("https://f-droid.org/F-Droid.apk", override = "DirectAPKLink").latestVersion.length == 8)
    }

    private fun searchOf(url: String, query: String): String =
        SourceRegistry.getSource(url).search(query).entries.take(2).toString().take(260)

    @Test
    fun sourceHut() {
        live("SourceHut")
        check(fetch("https://git.sr.ht/~emersion/goguma", mapOf("trackOnly" to true)).latestVersion.isNotEmpty())
    }

    @Test
    fun apkPure() {
        live("APKPure")
        check(fetch("https://apkpure.com/telegram/org.telegram.messenger").id == "org.telegram.messenger")
    }

    @Test
    fun aptoide() {
        live("Aptoide")
        check(fetch("https://aptoide-games.en.aptoide.com/app").apkUrls.isNotEmpty())
        val found = SourceRegistry.getSource("https://x.en.aptoide.com/app").search("telegram")
        println("  search: ${found.entries.take(2)}")
        check(found.isNotEmpty() && fetch(found.keys.first()).apkUrls.isNotEmpty())
    }

    @Test
    fun uptodown() {
        live("Uptodown")
        val app = fetch("https://telegram.en.uptodown.com/android")
        check(app.apkUrls.isNotEmpty())
        val source = SourceRegistry.getSource(app.url)
        val real = source.assetUrlPrefetchModifier(app.apkUrls[0].url, app.url, app.additionalSettings)
        println("  download url: ${real.take(90)}")
        println("  search: ${searchOf(app.url, "telegram")}")
    }

    @Test
    fun itchIo() {
        live("ItchIO")
        val app = fetch("https://anuke.itch.io/mindustry")
        check(app.apkUrls.isNotEmpty())
        val real = SourceRegistry.getSource(app.url).assetUrlPrefetchModifier(app.apkUrls[0].url, app.url, app.additionalSettings)
        println("  download url: ${real.take(70)}")
    }

    @Test
    fun huaweiAppGallery() {
        live("HuaweiAppGallery")
        val app = fetch("https://appgallery.huawei.com/app/C101184875")
        check(app.apkUrls.isNotEmpty())
        println("  search: ${searchOf(app.url, "telegram")}")
    }

    @Test
    fun tencent() {
        live("Tencent")
        check(fetch("https://sj.qq.com/appdetail/com.tencent.mm").id == "com.tencent.mm")
        val found = SourceRegistry.getSource("https://sj.qq.com/appdetail/x").search("微信")
        println("  search: ${found.entries.take(2)}")
        check(found.keys.first() == "https://sj.qq.com/appdetail/com.tencent.mm")
    }

    @Test
    fun vivo() {
        live("VivoAppStore")
        val results = SourceRegistry.getSource("https://h5.appstore.vivo.com.cn/#/details?appId=1").search("微信")
        println("  search: ${results.entries.take(2)}")
        check(fetch(results.keys.first()).apkUrls.isNotEmpty())
    }

    @Test
    fun ruStore() {
        live("RuStore")
        val app = fetch("https://www.rustore.ru/catalog/app/ru.yandex.yandexmaps")
        check(app.id == "ru.yandex.yandexmaps" && app.apkUrls.isNotEmpty())
        println("  urls in entry: ${app.apkUrls[0].url.split('\n').size}; search: ${searchOf(app.url, "яндекс")}")
    }

    @Test
    fun farsroid() {
        live("Farsroid")
        check(fetch("https://www.farsroid.com/antutu-benchmark/").apkUrls.isNotEmpty())
    }

    @Test
    fun samsungGalaxyStore() {
        live("SamsungGalaxyStore")
        check(fetch("https://galaxystore.samsung.com/detail/com.sec.android.app.sbrowser").id == "com.sec.android.app.sbrowser")
    }

    @Test
    fun apk4Free() {
        live("Apk4Free")
        check(fetch("https://apk4free.net/capcut/").apkUrls.isNotEmpty())
    }

    @Test
    fun coolApk() {
        live("CoolApk")
        check(fetch("https://www.coolapk.com/apk/com.coolapk.market").id == "com.coolapk.market")
    }

    @Test
    fun sourceForge() {
        live("SourceForge")
        check(fetch("https://sourceforge.net/projects/opencamera").apkUrls.isNotEmpty())
    }

    @Test
    fun jenkins() {
        live("Jenkins")
        check(fetch("https://ci.md-5.net/job/BungeeCord/", mapOf("trackOnly" to true), "Jenkins").latestVersion.isNotEmpty())
    }

    @Test
    fun apkMirror() {
        live("APKMirror")
        val app = fetch("https://www.apkmirror.com/apk/google-inc/chrome/")
        check(app.id == "com.android.chrome") { "id ${app.id}" }
        check(app.releaseUrl != null)
        println("  release: ${app.releaseUrl} size: ${SourceRegistry.getSource(app.url).resolveDownloadSize(app.url, app.additionalSettings, app.releaseUrl)}")
    }

    @Test
    fun rockMods() {
        live("RockMods")
        check(fetch("https://rockmods.net/apps/exteragram-mod-apk").latestVersion.isNotEmpty())
    }

    @Test
    fun telegramApp() {
        live("TelegramApp")
        check(fetch("https://telegram.org/android").latestVersion.first().isDigit())
    }

    @Test
    fun neutronCode() {
        live("NeutronCode")
        check(fetch("https://neutroncode.com/downloads/file/2-neutronmpapkarm64").apkUrls.isNotEmpty())
    }

    @Test
    fun downloader() {
        live("Downloader")
        val dir = java.nio.file.Files.createTempDirectory("dl").toFile()
        val url = "https://f-droid.org/F-Droid.apk"
        val options = io.github.bl3xand.apkcloner.sources.net.RequestOptions()
        var ticks = 0
        val file = io.github.bl3xand.apkcloner.sources.net.Downloader.downloadFileWithRetry(
            url, "test", false, { _, _, _ -> ticks++ }, dir, options,
        )
        val size = file.length()
        println("  downloaded ${file.name} $size bytes, $ticks progress calls")
        check(file.name == "test.apk" && size > 1_000_000 && ticks > 0)
        val sum = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        // A second call finds the finished file and does not download again.
        val again = io.github.bl3xand.apkcloner.sources.net.Downloader.downloadFile(url, "test", false, null, dir, options)
        check(again.length() == size)
        // Resume: half of the file is left as a partial download.
        val part = java.io.File(dir, "test.apk.part")
        part.writeBytes(file.readBytes().copyOf((size / 2).toInt()))
        file.delete()
        val resumed = io.github.bl3xand.apkcloner.sources.net.Downloader.downloadFile(url, "test", false, null, dir, options)
        check(resumed.length() == size && !part.exists())
        check(java.security.MessageDigest.getInstance("SHA-256").digest(resumed.readBytes()).toList() == sum) { "resumed file differs" }
        println("  resumed download matches")
        // Cancellation leaves no finished file behind.
        resumed.delete()
        val token = io.github.bl3xand.apkcloner.sources.net.CancellationToken()
        val cancelled = runCatching {
            io.github.bl3xand.apkcloner.sources.net.Downloader.downloadFile(
                url, "test", false, { _, _, _ -> token.cancel() }, dir, options, cancellationToken = token,
            )
        }.exceptionOrNull()
        check(cancelled is io.github.bl3xand.apkcloner.sources.core.CancellationSignal && !java.io.File(dir, "test.apk").exists())
        println("  cancellation works")
        dir.deleteRecursively()
    }
}
