package io.github.bl3xand.apkcloner.sources.live

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Platform
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.net.Http
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Goes the way a user goes, for every source that can be searched: search by a name, add what was
 * found, and start the download to see that a file really comes. Nothing is asserted - the run
 * prints a line per app, to be read. Opt-in like the other network tests:
 * `./gradlew :app:testDebugUnitTest -Plive=true --tests '*LiveAuditTest*' -i`
 */
class LiveAuditTest {
    /** The first bytes of what the source would download for an app: "PK" is an APK or an archive of them. */
    private fun startOfDownload(source: AppSource, appUrl: String, rawUrl: String, settings: Map<String, Any?>): String {
        val merged = source.buildMergedSettings(settings)
        val url = source.assetUrlPrefetchModifier(
            source.generalReqPrefetchModifier(ApkFilter.splitMultiApkUrl(rawUrl).first(), merged), appUrl, merged,
        )
        Http.requestStream(
            "GET", url, source.getRequestHeaders(settings, url, forAPKDownload = true), source.requestOptions(settings),
        ).use { stream ->
            val head = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = stream.body.read(head, read, 4 - read)
                if (n < 0) break
                read += n
            }
            val kind = if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) "file ok" else "NOT AN APK"
            return "$kind (HTTP ${stream.statusCode}, ${stream.contentLength?.let { it / 1024 / 1024 } ?: "?"} MB)"
        }
    }

    @Test
    fun searchAddDownload() {
        assumeTrue(System.getProperty("live") == "true")
        val only = System.getProperty("live.only")
        val chinese = setOf("Tencent", "VivoAppStore", "CoolApk")
        val asked = System.getProperty("live.query")?.takeIf { it.isNotEmpty() }
        for (source in SourceRegistry.sources.filter { it.canSearch && !it.includeAdditionalOptsInMainSearch }) {
            if (!only.isNullOrEmpty() && !only.equals(source.sourceIdentifier, ignoreCase = true)) continue
            val queries = if (asked != null) listOf(asked) else if (source.sourceIdentifier in chinese) listOf("telegram", "vlc", "微信", "firefox") else listOf("telegram", "vlc", "firefox", "whatsapp")
            for (query in queries) {
                val found = try {
                    source.search(query)
                } catch (e: Throwable) {
                    println("AUDIT ${source.sourceIdentifier} | search '$query' FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    continue
                }
                println("AUDIT ${source.sourceIdentifier} | search '$query': ${found.size} results")
                for ((url, texts) in found.entries.take(if (asked != null) 6 else 2)) {
                    val line = StringBuilder("AUDIT ${source.sourceIdentifier} |   ${texts.firstOrNull()} <$url> ")
                    try {
                        // As the app does it with what a search found.
                        val picked = SourceRegistry.getSource(url, SourceRegistry.overrideFor(url, source.sourceIdentifier))
                        val values = defaultValuesOf(picked.combinedAppSpecificSettingFormItems)
                        values.putAll(picked.runOnAddAppInputChange(url))
                        val app = SourceRegistry.getApp(picked, url, values, inferAppIdIfOptional = true)
                        line.append("-> ${app.name} | id=${app.id} | v=${app.latestVersion} | files=${app.apkUrls.size} | notes=${if (app.changeLog.isNullOrBlank()) "-" else "yes"} | ")
                        line.append(
                            if (app.apkUrls.isEmpty()) "no file (track only)"
                            else startOfDownload(picked, app.url, app.apkUrls[app.preferredApkIndex.coerceIn(0, app.apkUrls.size - 1)].url, app.additionalSettings),
                        )
                    } catch (e: Throwable) {
                        line.append("FAILED: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
                    }
                    println(line)
                }
            }
        }
    }

    /** One known app of every source that has no search: add it and start its download. */
    @Test
    fun knownAppsDownload() {
        assumeTrue(System.getProperty("live") == "true")
        val known = listOf(
            "https://apt.izzysoft.de/fdroid/index/apk/com.machiav3lli.backup" to null,
            "https://anuke.itch.io/mindustry" to null,
            "https://www.farsroid.com/antutu-benchmark/" to null,
            "https://galaxystore.samsung.com/detail/com.sec.android.app.sbrowser" to null,
            "https://apk4free.net/capcut/" to null,
            "https://sourceforge.net/projects/opencamera" to null,
            "https://neutroncode.com/downloads/file/2-neutronmpapkarm64" to null,
            "https://telegram.org/android" to null,
            "https://f-droid.org/F-Droid.apk" to "DirectAPKLink",
            "https://github.com/ImranR98/Obtainium" to null,
            "https://gitlab.com/AuroraOSS/AuroraStore" to null,
            "https://codeberg.org/Freeyourgadget/Gadgetbridge" to null,
            "https://www.apkmirror.com/apk/google-inc/chrome/" to null,
            "https://rockmods.net/apps/exteragram-mod-apk" to null,
            "https://git.sr.ht/~emersion/goguma" to null,
        )
        for ((url, forced) in known) {
            val line = StringBuilder("AUDIT known | <$url> ")
            try {
                val source = SourceRegistry.getSource(url, forced)
                val values = defaultValuesOf(source.combinedAppSpecificSettingFormItems)
                values.putAll(source.runOnAddAppInputChange(url))
                val trackOnly = source.enforceTrackOnly
                if (trackOnly) values["trackOnly"] = true
                val app = SourceRegistry.getApp(source, url, values, trackOnlyOverride = trackOnly, sourceIsOverriden = forced != null, inferAppIdIfOptional = true)
                line.append("[${source.sourceIdentifier}] ${app.name} | id=${app.id} | v=${app.latestVersion} | files=${app.apkUrls.size} | notes=${if (app.changeLog.isNullOrBlank()) "-" else "yes"} | ")
                line.append(
                    if (trackOnly || app.apkUrls.isEmpty()) "track only"
                    else startOfDownload(source, app.url, app.apkUrls[app.preferredApkIndex.coerceIn(0, app.apkUrls.size - 1)].url, app.additionalSettings),
                )
            } catch (e: Throwable) {
                line.append("FAILED: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            }
            println(line)
        }
    }

    /**
     * "Only for my phone" on an old device: every store that answers for the device is asked for
     * apps the way that search does, as Android 8.0 on a 32-bit processor. Each either has a
     * version for it or says that it has none.
     */
    @Test
    fun onlyForAnOldPhone() {
        assumeTrue(System.getProperty("live") == "true")
        val real = SourceEnv.platform
        SourceEnv.platform = object : Platform by real {
            override val supportedAbis = listOf("armeabi-v7a", "armeabi")
            override val sdkInt = 26
        }
        try {
            for (source in SourceRegistry.sources.filter { it.canSearch && it.answersForDevice }) {
                val query = if (source.sourceIdentifier == "CoolApk") "微信" else "telegram"
                val found = runCatching { source.search(query) }.getOrElse {
                    println("AUDIT old ${source.sourceIdentifier} | search FAILED: ${it.message}")
                    emptyMap()
                }
                for ((url, texts) in found.entries.take(4)) {
                    val outcome = try {
                        val app = SourceRegistry.getApp(source, url, defaultValuesOf(source.combinedAppSpecificSettingFormItems))
                        "fits: v=${app.latestVersion} ${app.apkUrls.firstOrNull()?.name}"
                    } catch (e: Throwable) {
                        "${e.javaClass.simpleName}: ${e.message?.take(110)}"
                    }
                    println("AUDIT old ${source.sourceIdentifier} | ${texts.firstOrNull()} -> $outcome")
                }
            }
        } finally {
            SourceEnv.platform = real
        }
    }
}
