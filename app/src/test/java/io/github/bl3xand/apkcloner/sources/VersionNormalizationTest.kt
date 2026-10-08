package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.installedMatchesRemote
import io.github.bl3xand.apkcloner.sources.core.isPreReleaseMajorMatch
import io.github.bl3xand.apkcloner.sources.core.normalizeVersionForComparison
import io.github.bl3xand.apkcloner.sources.core.versionsAreCosmeticallyEqual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from the reference project's test/version_normalization_test.dart.

class VersionNormalizationTest {

    // normalizeVersionForComparison
    @Test
    fun t001() { // strips a leading v before a digit
        assertEquals("1.2.3", normalizeVersionForComparison("v1.2.3"))
        assertEquals("1.2.3", normalizeVersionForComparison("V1.2.3"))
    }

    @Test
    fun t002() { // keeps a leading word that is not just v
        assertEquals("version.1.2.3", normalizeVersionForComparison("version1.2.3"))
    }

    @Test
    fun t003() { // drops packaging words
        assertEquals("0.9.108", normalizeVersionForComparison("0.9.108 strip"))
        assertEquals("1.6.15", normalizeVersionForComparison("1.6.15-debug"))
        assertEquals("1.0.5", normalizeVersionForComparison("1.0.5-release"))
        assertEquals("2.0.0", normalizeVersionForComparison("2.0.0-universal"))
        assertEquals("1.0.0", normalizeVersionForComparison("1.0.0-signed"))
    }

    @Test
    fun t004() { // keeps pre-release qualifiers
        assertEquals("1.2.3.beta", normalizeVersionForComparison("1.2.3-beta"))
        assertEquals("1.2.3.rc.1", normalizeVersionForComparison("1.2.3-rc1"))
        assertEquals("1.2.3.alpha.1", normalizeVersionForComparison("1.2.3-alpha.1"))
    }

    @Test
    fun t005() { // splits letter/digit runs so beta1 and beta.1 match
        assertEquals(normalizeVersionForComparison("1.2.3-beta.1"), normalizeVersionForComparison("1.2.3-beta1"))
    }

    @Test
    fun t006() { // does not merge different numeric components
        assertNotEquals("1.2.3", normalizeVersionForComparison("1.2.30"))
        assertNotEquals("1.2.30", normalizeVersionForComparison("1.2.3"))
    }

    @Test
    fun t007() { // excludes build metadata from the key
        assertEquals("1.0.5", normalizeVersionForComparison("1.0.5+26090410"))
        assertEquals("1.0.5", normalizeVersionForComparison("1.0.5+build.5"))
    }

    @Test
    fun t008() { // keeps non-ASCII words
        assertEquals("1.2.3.稳定版", normalizeVersionForComparison("1.2.3稳定版"))
        assertEquals("1.2.3.正式版", normalizeVersionForComparison("1.2.3正式版"))
    }

    @Test
    fun t009() { // normalizes separators and whitespace
        assertEquals("1.2.3", normalizeVersionForComparison("1_2_3"))
        assertEquals("1.2.3", normalizeVersionForComparison(" 1.2.3 "))
        assertEquals("1.2.3", normalizeVersionForComparison("1-2-3"))
    }

    @Test
    fun t010() { // blank or qualifier-only versions produce an empty key
        assertEquals("", normalizeVersionForComparison(""))
        assertEquals("", normalizeVersionForComparison(" "))
        assertEquals("", normalizeVersionForComparison("release"))
        assertEquals("v", normalizeVersionForComparison("v"))
    }


    // versionsAreCosmeticallyEqual (detection-oriented)
    @Test
    fun t011() { // equal for identical and separator-only differences
        assertTrue(versionsAreCosmeticallyEqual("1.2.3", "1.2.3"))
        assertTrue(versionsAreCosmeticallyEqual("1_2_3", "1.2.3"))
        assertTrue(versionsAreCosmeticallyEqual("1-2-3", "1.2.3"))
    }

    @Test
    fun t012() { // equal across a leading v
        assertTrue(versionsAreCosmeticallyEqual("v1.2.3", "1.2.3"))
        assertTrue(versionsAreCosmeticallyEqual("V1.2.3", "v1.2.3"))
    }

    @Test
    fun t013() { // equal when only packaging words differ
        assertTrue(versionsAreCosmeticallyEqual("1.6.15-debug", "1.6.15"))
        assertTrue(versionsAreCosmeticallyEqual("1.6.15-debug", "1.6.15-release"))
        assertTrue(versionsAreCosmeticallyEqual("0.9.108 strip", "0.9.108"))
    }

    @Test
    fun t014() { // equal for letter/digit punctuation differences in qualifiers
        assertTrue(versionsAreCosmeticallyEqual("1.2.3-beta1", "1.2.3-beta.1"))
    }

    @Test
    fun t015() { // not equal when a pre-release qualifier is present on one side
        assertFalse(versionsAreCosmeticallyEqual("1.2.3-beta", "1.2.3"))
        assertFalse(versionsAreCosmeticallyEqual("1.2.3-rc1", "1.2.3"))
        assertFalse(versionsAreCosmeticallyEqual("61.0-beta1", "61.0"))
    }

    @Test
    fun t016() { // not equal for non-ASCII differences
        assertFalse(versionsAreCosmeticallyEqual("1.2.3稳定版", "1.2.3"))
        assertFalse(versionsAreCosmeticallyEqual("1.2.3稳定版", "1.2.3正式版"))
    }

    @Test
    fun t017() { // build metadata must match when both sides carry it
        assertFalse(versionsAreCosmeticallyEqual("1.0.5+1", "1.0.5+2"))
        assertTrue(versionsAreCosmeticallyEqual("1.0.5+1", "1.0.5+1"))
        assertTrue(versionsAreCosmeticallyEqual("1.0.5+1", "1.0.5"))
        assertTrue(versionsAreCosmeticallyEqual("1.0.5", "1.0.5+1"))
    }

    @Test
    fun t018() { // not equal for genuinely different or empty versions
        assertFalse(versionsAreCosmeticallyEqual("1.2.3", "1.2.4"))
        assertFalse(versionsAreCosmeticallyEqual("", "1.2.3"))
        assertFalse(versionsAreCosmeticallyEqual("release", "1.2.3"))
        assertFalse(versionsAreCosmeticallyEqual("", ""))
    }


    // installedMatchesRemote (update-decision-oriented)
    @Test
    fun t019() { // installed may carry extra packaging words
        assertTrue(installedMatchesRemote(installed = "1.6.15-debug", remote = "1.6.15"))
        assertTrue(installedMatchesRemote(installed = "0.9.108 strip", remote = "0.9.108"))
        assertTrue(installedMatchesRemote( installed = "1.6.15-debug-universal", remote = "1.6.15-debug", ))
    }

    @Test
    fun t020() { // remote packaging words must also be on the installed side
        assertFalse(installedMatchesRemote(installed = "1.6.15", remote = "1.6.15-debug"))
        assertFalse(installedMatchesRemote( installed = "1.6.15-debug", remote = "1.6.15-release", ))
        assertFalse(installedMatchesRemote( installed = "1.6.15-release", remote = "1.6.15-debug", ))
        assertFalse(installedMatchesRemote( installed = "1.6.15-debug", remote = "1.6.15-debug-universal", ))
    }

    @Test
    fun t021() { // leading v is cosmetic in both directions
        assertTrue(installedMatchesRemote(installed = "v1.2.3", remote = "1.2.3"))
        assertTrue(installedMatchesRemote(installed = "1.2.3", remote = "v1.2.3"))
    }

    @Test
    fun t022() { // installed-only build metadata is packaging noise
        assertTrue(installedMatchesRemote( installed = "1.0.5-release+26090410", remote = "1.0.5", ))
        assertTrue(installedMatchesRemote(installed = "1.0.5+1", remote = "1.0.5"))
        assertTrue(installedMatchesRemote(installed = "1.0.5+build.5", remote = "1.0.5"))
    }

    @Test
    fun t023() { // remote build metadata is a distinct build
        assertFalse(installedMatchesRemote(installed = "1.0.5", remote = "1.0.5+1"))
        assertFalse(installedMatchesRemote(installed = "1.0.5+1", remote = "1.0.5+2"))
        assertTrue(installedMatchesRemote(installed = "1.0.5+1", remote = "1.0.5+1"))
    }

    @Test
    fun t024() { // pre-release qualifiers stay significant
        assertFalse(installedMatchesRemote(installed = "61.0-beta1", remote = "61.0"))
        assertTrue(installedMatchesRemote( installed = "1.2.3-beta1", remote = "1.2.3-beta.1", ))
        assertFalse(installedMatchesRemote(installed = "1.2.3-beta1", remote = "1.2.3-beta2"))
    }

    @Test
    fun t025() { // non-ASCII words stay significant
        assertFalse(installedMatchesRemote(installed = "1.2.3稳定版", remote = "1.2.3"))
        assertFalse(installedMatchesRemote(installed = "1.2.3", remote = "1.2.3稳定版"))
        assertTrue(installedMatchesRemote(installed = "1.2.3稳定版", remote = "1.2.3稳定版"))
    }

    @Test
    fun t026() { // different versions do not match
        assertFalse(installedMatchesRemote(installed = "1.6.14", remote = "1.6.15"))
        assertFalse(installedMatchesRemote(installed = "1.2.3", remote = "1.2.30"))
        assertFalse(installedMatchesRemote(installed = "release", remote = "release"))
    }


    // isPreReleaseMajorMatch
    @Test
    fun t027() { // matches a major-only rolling tag to the installed major
        assertTrue(isPreReleaseMajorMatch("v151_beta", "151.0.7922.47"))
        assertTrue(isPreReleaseMajorMatch("151_beta", "151.0.7922.47"))
        assertTrue(isPreReleaseMajorMatch("v151-beta", "151.0.7922.47"))
        assertTrue(isPreReleaseMajorMatch("v151_beta1", "151.0.7922.47"))
        assertTrue(isPreReleaseMajorMatch("v151_alpha", "151.0.7922.47"))
        assertTrue(isPreReleaseMajorMatch("v151_beta", "151"))
    }

    @Test
    fun t028() { // does not match a different major
        assertFalse(isPreReleaseMajorMatch("v151_beta", "152.0.0"))
        assertFalse(isPreReleaseMajorMatch("v2_beta", "1.9.0"))
    }

    @Test
    fun t029() { // does not match full versions or major-only versions
        assertFalse(isPreReleaseMajorMatch("v1.2.3", "1.2.3"))
        assertFalse(isPreReleaseMajorMatch("v151", "151.0.0"))
        assertFalse(isPreReleaseMajorMatch("v151_beta_extra", "151.0.0"))
    }

}
