package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.CertHashes
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.NoVersionError
import io.github.bl3xand.apkcloner.sources.core.UnsupportedUrlError
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.compareAlphaNumeric
import io.github.bl3xand.apkcloner.sources.core.compareVersionsNumerically
import io.github.bl3xand.apkcloner.sources.core.effectiveMinUpdateAgeDays
import io.github.bl3xand.apkcloner.sources.core.extractVersion
import io.github.bl3xand.apkcloner.sources.core.formatBytes
import io.github.bl3xand.apkcloner.sources.core.isReleaseTooYoung
import io.github.bl3xand.apkcloner.sources.core.preStandardizeUrl
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.applyMinAgeSuppression
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun date(y: Int, m: Int, d: Int, h: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC)

    private fun app(
        latestVersion: String,
        releaseDate: Instant? = null,
        changeLog: String? = null,
        apkUrls: List<NamedUrl> = emptyList(),
    ) = TrackedApp(
        id = "com.example.app", url = "https://example.com/app", author = "Author", name = "App",
        latestVersion = latestVersion, apkUrls = apkUrls, preferredApkIndex = 0,
        additionalSettings = emptyMap(), releaseDate = releaseDate, changeLog = changeLog,
    )

    // Ported from test/min_update_age_test.dart.
    @Test
    fun minUpdateAge() {
        val now = date(2026, 9, 12, 12)
        assertTrue(isReleaseTooYoung(date(2026, 9, 7), 14, now))
        assertFalse(isReleaseTooYoung(date(2026, 8, 28), 14, now))
        assertFalse(isReleaseTooYoung(null, 14, now))
        assertFalse(isReleaseTooYoung(date(2026, 9, 11), 0, now))
        assertEquals(7, effectiveMinUpdateAgeDays(mapOf("minimumUpdateAgeDays" to "7")))

        val current = app("0.12.6", date(2026, 8, 28), "old", listOf(NamedUrl("old.apk", "https://example.com/0.12.6/old.apk")))
        val fetched = app("0.12.8", date(2026, 9, 7), "new", listOf(NamedUrl("new.apk", "https://example.com/0.12.8/new.apk")))
        val result = applyMinAgeSuppression(current, fetched)
        assertEquals("0.12.6", result.latestVersion)
        assertEquals(date(2026, 8, 28), result.releaseDate)
        assertEquals("old", result.changeLog)
        assertEquals(NamedUrl("old.apk", "https://example.com/0.12.6/old.apk"), result.apkUrls.single())
        assertEquals(fetched.name, result.name)
    }

    // Ported from test/multi_apk_url_test.dart.
    @Test
    fun multiApkUrl() {
        assertEquals(listOf("https://example.com/app.apk"), ApkFilter.splitMultiApkUrl("https://example.com/app.apk"))
        val three = listOf(
            "https://example.com/base.zip",
            "https://example.com/config.arm64_v8a.zip",
            "https://example.com/config.xxhdpi.zip",
        )
        assertEquals(three, ApkFilter.splitMultiApkUrl(three.joinToString("\n")))
        assertTrue(ApkFilter.splitMultiApkUrl("").isEmpty())
        assertEquals(listOf("a", "b"), ApkFilter.splitMultiApkUrl("a\n\nb"))
        val urls = listOf("https://example.com/base.apk", "https://example.com/config.arm64_v8a.apk")
        assertEquals(urls, ApkFilter.splitMultiApkUrl(ApkFilter.joinMultiApkUrl(urls)))
        assertTrue(ApkFilter.joinMultiApkUrl(urls).contains(ApkFilter.MULTI_APK_URL_SEPARATOR))
    }

    @Test
    fun apkFiltering() {
        val all = listOf(
            NamedUrl("app-arm64-v8a.apk", "u1"), NamedUrl("app-armeabi-v7a.apk", "u2"), NamedUrl("app-x86_64.apk", "u3"),
        )
        assertEquals(listOf(all[0]), ApkFilter.filterApksByArch(all, listOf("arm64-v8a", "armeabi-v7a")))
        val aliased = listOf(NamedUrl("app-aarch64.apk", "u1"), NamedUrl("app-x64.apk", "u2"))
        assertEquals(listOf(aliased[0]), ApkFilter.filterApksByArch(aliased, listOf("arm64-v8a")))
        val universal = listOf(NamedUrl("a.apk", "u1"), NamedUrl("b.apk", "u2"))
        assertEquals(universal, ApkFilter.filterApksByArch(universal, listOf("arm64-v8a")))
        assertEquals(listOf(all[2]), ApkFilter.filterApks(all, "x86", false))
        assertEquals(listOf(all[0], all[1]), ApkFilter.filterApks(all, "x86", true))
        assertEquals(
            listOf(NamedUrl("app.apk", "https://e.com/dl/app.apk/download"), NamedUrl("file", "https://e.com/file")),
            ApkFilter.apkUrlsFromUrls(listOf("https://e.com/dl/app.apk/download", "https://e.com/file")),
        )
        assertTrue(ApkFilter.isApkOrContainerFile("A.XAPK"))
        assertFalse(ApkFilter.isApkOrContainerFile("a.zip"))
        assertTrue(ApkFilter.isApkOrContainerFile("a.tar.gz", includeTarballs = true))
    }

    @Test
    fun versionExtraction() {
        assertNull(extractVersion(null, null, "v1.2.3"))
        assertEquals("1.2.3", extractVersion("[0-9.]+", null, "app-1.0-to-1.2.3"))
        assertEquals("1.2", extractVersion("v(\\d+)\\.(\\d+)", "\$1.\$2", "tag v1.2"))
        assertEquals("2", extractVersion("v(\\d+)\\.(\\d+)", "2", "tag v1.2"))
        assertEquals("\$1", extractVersion("v(\\d+)", "\\\$1", "v7"))
        assertThrows(NoVersionError::class.java) { extractVersion("x+", null, "abc") }
        assertEquals(-1, compareVersionsNumerically("1.2.3", "1.2.10"))
        assertEquals(1, compareVersionsNumerically("v2.0", "1.9"))
        assertEquals(0, compareVersionsNumerically("1.0", "v1.0-beta"))
        assertNull(compareVersionsNumerically("abc", "1.0"))
    }

    @Test
    fun urls() {
        assertEquals("https://github.com/a/b", preStandardizeUrl("github.com//a/b"))
        assertEquals("https://github.com/a/b/", preStandardizeUrl("https://github.com/a/b/"))
        assertEquals("http://x.org/a?u=http://y//z", preStandardizeUrl("http://x.org//a?u=http://y//z"))
        assertThrows(UnsupportedUrlError::class.java) { preStandardizeUrl("nodot") }

        val url = Url.parse("HTTPS://User@Example.COM:8443/a/b%20c/?q=1&r=x+y#frag")
        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals(8443, url.port)
        assertEquals(listOf("a", "b c", ""), url.pathSegments)
        assertEquals(mapOf("q" to "1", "r" to "x y"), url.queryParameters)
        assertEquals("https://example.com:8443", url.origin)
        assertEquals(emptyList<String>(), Url.parse("https://e.com/").pathSegments)
        assertEquals("https://e.com/x/c.apk", Url.parse("https://e.com/a/b").resolve("../x/c.apk").toString())
        assertEquals("https://e.com/r", Url.parse("https://e.com/a/b?q").resolve("/r").toString())
        assertEquals("https://o.org/z", Url.parse("https://e.com/a/b").resolve("//o.org/z").toString())
        assertEquals("https://e.com/a/c?d", Url.parse("https://e.com/a/b").resolve("c?d").toString())
        assertEquals("github.com", Url.rootHost("api.github.com"))
        assertEquals("a%20b%2Fc", Url.encodeComponent("a b/c"))
        assertEquals("a+b%2Fc", Url.encodeQueryComponent("a b/c"))
    }

    @Test
    fun misc() {
        assertTrue(compareAlphaNumeric("v1.9", "v1.10") < 0)
        assertTrue(compareAlphaNumeric("2a", "ab") > 0)
        assertEquals(0, compareAlphaNumeric("x", "x"))
        assertEquals("1.0 MB", formatBytes(1048525))
        assertEquals("512 B", formatBytes(512))
        val hash = "AB".repeat(32)
        assertEquals(setOf(hash.chunked(2).joinToString(":")), CertHashes.parseAllowed(hash.lowercase()))
        assertTrue(CertHashes.isValidList(""))
        assertFalse(CertHashes.isValidList("abc"))
    }

    @Test
    fun appJsonRoundTrip() {
        val original = TrackedApp(
            id = "org.example", url = "https://github.com/e/x", author = "e", name = "x",
            installedVersion = null, latestVersion = "1.0",
            apkUrls = listOf(NamedUrl("a.apk", "https://e.com/a.apk")), preferredApkIndex = 0,
            additionalSettings = mapOf("trackOnly" to false, "apkFilterRegEx" to "", "requestHeader" to listOf(mapOf("requestHeader" to "A: b"))),
            lastUpdateCheck = Instant.ofEpochSecond(1_700_000_000, 123_456_000),
            categories = listOf("Tools"), releaseDate = Instant.ofEpochSecond(1_600_000_000),
        )
        val restored = TrackedApp.fromJson(JsonValues.parseObject(original.toJson().toString()))
        assertEquals(original, restored)
        assertFalse(original.hasTempId)
        assertTrue(original.copy(id = "0123456789ab").hasTempId)
    }
}
