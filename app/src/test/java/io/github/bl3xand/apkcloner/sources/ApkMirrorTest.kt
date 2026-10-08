package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.source.RuStore
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.source.apkMirrorChangeLogFromReleasePageHtml
import io.github.bl3xand.apkcloner.sources.source.apkMirrorCleanReleaseTitle
import io.github.bl3xand.apkcloner.sources.source.apkMirrorDownloadPageUrlFromReleasePage
import io.github.bl3xand.apkcloner.sources.source.apkMirrorPackageFromIconUrl
import io.github.bl3xand.apkcloner.sources.source.apkMirrorReleaseUrlFromFeedBodyForItemIndex
import io.github.bl3xand.apkcloner.sources.source.apkMirrorSizeBytesFromPageText
import io.github.bl3xand.apkcloner.sources.source.apkMirrorVersionFromTitle
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from the reference project's test/apkmirror_test.dart and test/rustore_apk_urls_test.dart.
class ApkMirrorTest {
    @Test
    fun versionFromTitle() {
        assertEquals("9.0.36.643", apkMirrorVersionFromTitle("Spotify 9.0.36.643 (arm64-v8a) (Android 7.0+) APK Download by Spotify AB"))
        assertEquals("25.01", apkMirrorVersionFromTitle("7-Zip 25.01 (arm64-v8a) (Android 4.4+) APK Download by Igor Pavlov"))
        assertEquals("42.5.15-21", apkMirrorVersionFromTitle("Google Play Store 42.5.15-21 [0] [PR] 630551585 (arm64-v8a) (Android 12+) by Google LLC"))
        assertEquals("6.32", apkMirrorVersionFromTitle("1.1.1.1: Faster & Safer Internet 6.32 (arm64-v8a) (Android 5.0+) by Cloudflare, Inc."))
        assertEquals("1.1.5", apkMirrorVersionFromTitle("APKMirror Installer (Official) 1.1.5 (arm64-v8a) (Android 8.0+) by APKMirror"))
        assertNull(apkMirrorVersionFromTitle("Some App (Android 8.0+) by Author"))
        assertEquals("App 1.2.3", apkMirrorCleanReleaseTitle("App 1.2.3 [0] (arm64-v8a) (Android 8.0+) by Author"))
    }

    @Test
    fun releaseUrlFromFeed() {
        val feed = """
<?xml version="1.0"?>
<rss><channel>
<item>
<title>App One 1.0 by A</title>
<link>https://www.apkmirror.com/apk/dev/app-one/app-one-1-0-release/</link>
<pubDate>Sun, 13 Sep 2026 01:02:27 +0000</pubDate>
</item>
<item>
<title>App One 1.1 by A</title>
<link>https://www.apkmirror.com/apk/dev/app-one/app-one-1-1-release/</link>
<pubDate>Mon, 14 Sep 2026 01:02:27 +0000</pubDate>
</item>
</channel></rss>
"""
        assertEquals("https://www.apkmirror.com/apk/dev/app-one/app-one-1-0-release/", apkMirrorReleaseUrlFromFeedBodyForItemIndex(feed, 0))
        assertEquals("https://www.apkmirror.com/apk/dev/app-one/app-one-1-1-release/", apkMirrorReleaseUrlFromFeedBodyForItemIndex(feed, 1))
        assertNull(apkMirrorReleaseUrlFromFeedBodyForItemIndex(feed, 5))
        assertNull(apkMirrorReleaseUrlFromFeedBodyForItemIndex(feed, -1))
        assertNull(apkMirrorReleaseUrlFromFeedBodyForItemIndex("<item></item>", 0))
    }

    @Test
    fun packageAndSize() {
        assertEquals("com.android.chrome", apkMirrorPackageFromIconUrl("https://downloadr2.apkmirror.com/wp-content/uploads/2024/01/11/65a71d34ecd19_com.android.chrome.png"))
        assertNull(apkMirrorPackageFromIconUrl("https://downloadr2.apkmirror.com/wp-content/uploads/2024/01/11/65a71d34ecd19.png"))
        assertNull(apkMirrorPackageFromIconUrl(null))
        assertNull(apkMirrorPackageFromIconUrl(""))
        assertEquals((270.70 * 1024 * 1024).roundToLong(), apkMirrorSizeBytesFromPageText("File size:\n270.70 MB"))
        assertEquals(12L * 1024, apkMirrorSizeBytesFromPageText("File size: 12 KB"))
        assertEquals((1.5 * 1073741824).toLong(), apkMirrorSizeBytesFromPageText("File size: 1.5 GB"))
        assertNull(apkMirrorSizeBytesFromPageText("No size here"))
    }

    @Test
    fun downloadPageAndChangeLog() {
        val html = """
<html><body>
<a href="/apk/dev/app/app-1-2-3-release/app-1-2-3-android-apk-download/">APK</a>
<a href="/apk/other/thing/other-1-0-release/other-1-0-android-apk-download/">Other</a>
<a href="https://example.com/">External</a>
</body></html>
"""
        assertEquals(
            "https://www.apkmirror.com/apk/dev/app/app-1-2-3-release/app-1-2-3-android-apk-download/",
            apkMirrorDownloadPageUrlFromReleasePage(html, "https://www.apkmirror.com/apk/dev/app/app-1-2-3-release/"),
        )
        assertNull(apkMirrorDownloadPageUrlFromReleasePage("<a href=\"/apk/other/x/\">x</a>", "https://www.apkmirror.com/apk/dev/app/app-1-2-3-release/"))

        val release = """
<html><body>
<h2><a href="#whatsnew">What's new in App 1.2.3</a></h2>
<div>
<p>Thanks for choosing App!</p>
<p>Fixed bugs.</p>
<ul><li>Faster startup</li><li>New icon</li></ul>
</div>
<p>Verified safe to install (read more)</p>
<p>Scroll to available downloads</p>
<h2>About App 1.2.3</h2>
<p>This text must not be included.</p>
</body></html>
"""
        val changeLog = apkMirrorChangeLogFromReleasePageHtml(release)!!
        assertTrue(changeLog.contains("Thanks for choosing App!"))
        assertTrue(changeLog.contains("Fixed bugs."))
        assertTrue(changeLog.contains("- Faster startup"))
        assertTrue(changeLog.contains("- New icon"))
        assertFalse(changeLog.contains("Verified safe"))
        assertFalse(changeLog.contains("This text must not be included."))
        assertNull(apkMirrorChangeLogFromReleasePageHtml("<html><body><h2>About App</h2><p>x</p></body></html>"))
    }

    @Test
    fun ruStoreApkUrls() {
        val single = "https://static-m.rustore.ru/version/4/accubattery.apk"
        assertEquals("accubattery.apk", RuStore.apkUrlsFromDownloadUrls(listOf(single)).single().name)
        assertEquals(single, RuStore.apkUrlsFromDownloadUrls(listOf(single)).single().url)
        assertEquals(
            "https://static-m.rustore.ru/version/4/app.apk",
            RuStore.apkUrlsFromDownloadUrls(listOf("https://static-m.rustore.ru/version/4/app.zip")).single().url,
        )
        val urls = listOf(
            "https://static-m.rustore.ru/2026/9/12/base.zip",
            "https://static-m.rustore.ru/2026/9/12/config.arm64_v8a.zip",
            "https://static-m.rustore.ru/2026/9/12/config.xxhdpi.zip",
        )
        val joined = RuStore.apkUrlsFromDownloadUrls(urls).single()
        assertEquals("base.zip", joined.name)
        assertEquals(urls, ApkFilter.splitMultiApkUrl(joined.url))
    }
}
