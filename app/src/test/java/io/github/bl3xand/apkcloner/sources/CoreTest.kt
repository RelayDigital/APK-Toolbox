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
import io.github.bl3xand.apkcloner.sources.model.carryPreferredFile
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

    // ---- the release branch a followed file belongs to --------------------------------------

    /** The named branch of a file: its name up to the version, as a keyed source would give it. */
    private val byName: (NamedUrl) -> String? = { it.name.substringBefore('-') }

    private fun branchApp(
        apkUrls: List<NamedUrl>,
        preferredApkIndex: Int,
        preferredBranchKey: String? = null,
        releaseBranchGone: Boolean = false,
    ) = TrackedApp(
        id = "com.example.app", url = "https://example.com/app", author = "Author", name = "App",
        latestVersion = "2.0", apkUrls = apkUrls, preferredApkIndex = preferredApkIndex,
        preferredBranchKey = preferredBranchKey, releaseBranchGone = releaseBranchGone,
        additionalSettings = emptyMap(),
    )

    @Test
    fun branchGoneWhenTheRememberedNameIsNoLongerOffered() {
        val current = branchApp(
            listOf(NamedUrl("app-1.0.apk", "u1"), NamedUrl("extra-1.0.apk", "u2")),
            preferredApkIndex = 1, preferredBranchKey = "extra",
        )
        val fresh = branchApp(listOf(NamedUrl("app-2.0.apk", "u3")), preferredApkIndex = 0)
        val carried = carryPreferredFile(current, fresh, byName)
        // The fresh release is kept - the page has to offer it for choosing another branch -
        // while the old place and key stay (the place may sit past the end now), so that the
        // branch coming back is recognized.
        assertTrue(carried.releaseBranchGone)
        assertEquals(1, carried.preferredApkIndex)
        assertEquals("extra", carried.preferredBranchKey)
        assertEquals(fresh.apkUrls, carried.apkUrls)
    }

    @Test
    fun branchBackAfterBeingGone() {
        val current = branchApp(
            listOf(NamedUrl("app-2.0.apk", "u3")), preferredApkIndex = 1,
            preferredBranchKey = "extra", releaseBranchGone = true,
        )
        val fresh = branchApp(
            listOf(NamedUrl("app-2.1.apk", "u4"), NamedUrl("extra-2.1.apk", "u5")),
            preferredApkIndex = 1,
        )
        val carried = carryPreferredFile(current, fresh, byName)
        assertFalse(carried.releaseBranchGone)
        assertEquals(1, carried.preferredApkIndex)
        assertEquals("extra", carried.preferredBranchKey)
    }

    @Test
    fun aReorderedListIsHealedByName() {
        // The remembered place is past the end, but the branch it stood for is still there.
        val current = branchApp(
            listOf(NamedUrl("app-1.0.apk", "u1"), NamedUrl("extra-1.0.apk", "u2")),
            preferredApkIndex = 1, preferredBranchKey = "extra",
        )
        val fresh = branchApp(
            listOf(NamedUrl("extra-2.0.apk", "u5"), NamedUrl("app-2.0.apk", "u4")),
            preferredApkIndex = 1,
        )
        val carried = carryPreferredFile(current, fresh, byName)
        assertFalse(carried.releaseBranchGone)
        assertEquals(0, carried.preferredApkIndex)
    }

    @Test
    fun aKeylessSourceIsJudgedOnlyByTheShrinkingOfTheList() {
        val keyless: (NamedUrl) -> String? = { null }
        val current = branchApp(listOf(NamedUrl("a-1.0.apk", "u1"), NamedUrl("b-1.0.apk", "u2")), preferredApkIndex = 1)
        val shrunk = branchApp(listOf(NamedUrl("a-2.0.apk", "u3")), preferredApkIndex = 1)
        assertTrue(carryPreferredFile(current, shrunk, keyless).releaseBranchGone)
        val reordered = branchApp(listOf(NamedUrl("b-2.0.apk", "u4"), NamedUrl("a-2.0.apk", "u3")), preferredApkIndex = 1)
        val carried = carryPreferredFile(current, reordered, keyless)
        assertFalse(carried.releaseBranchGone)
        assertEquals(1, carried.preferredApkIndex)
    }

    @Test
    fun noFileChosenYetNeverMarksTheBranch() {
        val current = branchApp(listOf(NamedUrl("a-1.0.apk", "u1")), preferredApkIndex = -1)
        val fresh = branchApp(listOf(NamedUrl("a-2.0.apk", "u2")), preferredApkIndex = 0)
        val carried = carryPreferredFile(current, fresh, byName)
        assertFalse(carried.releaseBranchGone)
        assertEquals(-1, carried.preferredApkIndex)
        // The app was already marked gone and the user has not chosen yet: it stays so.
        val stillGone = carryPreferredFile(
            current.copy(releaseBranchGone = true, preferredBranchKey = "gone"),
            fresh, byName,
        )
        assertTrue(stillGone.releaseBranchGone)
        assertNull(stillGone.preferredBranchKey)
    }

    @Test
    fun theKeyIsWrittenDownFromTheKeptPlace() {
        // Old records have no key; the file at the remembered place is what has been installed,
        // so its name becomes the branch from then on.
        val current = branchApp(
            listOf(NamedUrl("app-1.0.apk", "u1"), NamedUrl("extra-1.0.apk", "u2")),
            preferredApkIndex = 1,
        )
        val fresh = branchApp(
            listOf(NamedUrl("app-2.0.apk", "u3"), NamedUrl("extra-2.0.apk", "u4")),
            preferredApkIndex = 1,
        )
        val carried = carryPreferredFile(current, fresh, byName)
        assertFalse(carried.releaseBranchGone)
        assertEquals("extra", carried.preferredBranchKey)
    }

    @Test
    fun aKindChosenInTheSettingsIsAdoptedAsTheBranch() {
        // The Telegram kind path: the settings name the kind to follow, and the fresh list is
        // that kind alone, so the place in it says nothing. A re-pointed kind heals the flag,
        // and a kept one keeps it healed however the list shrank or grew.
        val current = branchApp(
            listOf(NamedUrl("old-1.0.apk", "u1"), NamedUrl("other-1.0.apk", "u2")),
            preferredApkIndex = 0, preferredBranchKey = "old", releaseBranchGone = true,
        )
        val fresh = branchApp(listOf(NamedUrl("new-2.0.apk", "u2")), preferredApkIndex = 0)
        val repointed = carryPreferredFile(current, fresh, byName, settingsBranchKey = "new")
        assertFalse(repointed.releaseBranchGone)
        assertEquals("new", repointed.preferredBranchKey)
        assertEquals(0, repointed.preferredApkIndex)
        val kept = carryPreferredFile(current, fresh, byName, settingsBranchKey = "old")
        assertTrue(kept.releaseBranchGone)
        assertEquals("old", kept.preferredBranchKey)
    }

    @Test
    fun branchFieldsRoundTripThroughJson() {
        val original = branchApp(
            listOf(NamedUrl("a-1.0.apk", "u1"), NamedUrl("b-1.0.apk", "u2")),
            preferredApkIndex = 1, preferredBranchKey = "b", releaseBranchGone = true,
        )
        val restored = TrackedApp.fromJson(JsonValues.parseObject(original.toJson().toString()))
        assertEquals(original, restored)
        // Records from before the fields existed parse to null/false.
        val plain = TrackedApp.fromJson(
            JsonValues.parseObject(branchApp(emptyList(), preferredApkIndex = -1).toJson().toString()),
        )
        assertNull(plain.preferredBranchKey)
        assertFalse(plain.releaseBranchGone)
    }
}
