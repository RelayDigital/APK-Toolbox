package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.isUpdateable
import io.github.bl3xand.apkcloner.sources.core.reconcileTrackedVersion
import io.github.bl3xand.apkcloner.sources.core.reconcileVersionDifferences
import io.github.bl3xand.apkcloner.sources.core.versionDetectionPossible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from the reference project's test/version_reconciliation_test.dart.

private fun trackedAfter(
    tracked: String?,
    real: String?,
    latest: String,
    standard: Boolean = true,
    naive: Boolean = false,
): String = reconcileTrackedVersion(
    trackedVersion = tracked,
    realInstalledVersion = real,
    latestVersion = latest,
    versionDetectionIsStandard = standard,
    naiveStandardVersionDetection = naive,
) ?: tracked!!

private fun updateShown(installed: String, latest: String): Boolean =
    isUpdateable(installed, latest, hideDowngrades = true)

private fun possible(
    trackOnly: Boolean = false,
    releaseDateAsVersion: Boolean = false,
    isHtmlWithNoVersionDetection: Boolean = false,
    versionDetectionDisallowed: Boolean = false,
    real: String? = "1.2.3",
    tracked: String? = "1.2.3",
    latest: String = "1.2.4",
    naive: Boolean = false,
): Boolean = versionDetectionPossible(
    trackOnly = trackOnly,
    releaseDateAsVersion = releaseDateAsVersion,
    isHtmlWithNoVersionDetection = isHtmlWithNoVersionDetection,
    versionDetectionDisallowed = versionDetectionDisallowed,
    realInstalledVersion = real,
    trackedVersion = tracked,
    latestVersion = latest,
    naiveStandardVersionDetection = naive,
)

private fun effectiveInstalled(tracked: String, real: String, latest: String): String =
    trackedAfter(tracked = tracked, real = real, latest = latest)

class VersionReconciliationTest {

    // reconcileVersionDifferences
    @Test
    fun t001() { // equal versions share a format and match
        val result = reconcileVersionDifferences("1.2.3", "1.2.3")
        assertNotNull(result)
        assertTrue(result!!.areEqual)
        assertEquals("1.2.3", result.version)
    }

    @Test
    fun t002() { // different versions sharing a format are reported as different
        val result = reconcileVersionDifferences("1.2.3", "1.2.4")
        assertNotNull(result)
        assertFalse(result!!.areEqual)
        assertEquals("1.2.3", result.version)
    }

    @Test
    fun t003() { // a leading v on the comparison side matches under a loose format
        val result = reconcileVersionDifferences("1.2.3", "v1.2.3")
        assertNotNull(result)
        assertTrue(result!!.areEqual)
        assertEquals("v1.2.3", result.version)
    }

    @Test
    fun t004() { // a leading v on the template side cannot be reconciled
        assertNull(reconcileVersionDifferences("v1.2.3", "1.2.3"))
    }

    @Test
    fun t005() { // unknown packaging suffixes are not standard formats
        assertNull(reconcileVersionDifferences("1.6.15-debug", "1.6.15"))
        assertNotNull(reconcileVersionDifferences("1.6.15", "1.6.15-debug"))
    }

    @Test
    fun t006() { // pre-release qualifiers reconcile only when they match
        assertNull(reconcileVersionDifferences("1.2.3-beta1", "1.2.3-beta.1"))
        assertNull(reconcileVersionDifferences("61.0", "61.0-beta1"))
        assertNull(reconcileVersionDifferences("61.0-beta1", "61.0"))
    }

    @Test
    fun t007() { // Debian-style versions reconcile
        val result = reconcileVersionDifferences("1.2.3-1", "1.2.3-1")
        assertNotNull(result)
        assertTrue(result!!.areEqual)
    }

    @Test
    fun t008() { // numeric-only versions with different values are different
        val result = reconcileVersionDifferences("2412", "622797097")
        assertNotNull(result)
        assertFalse(result!!.areEqual)
    }


    // versionDetectionPossible
    @Test
    fun t009() { // keeps detection on for cosmetic differences
        assertTrue(possible(real = "v2026.03", tracked = "v2026.03", latest = "v2026.07"))
        assertTrue(possible( real = "1.6.15-debug", tracked = "1.6.15-debug", latest = "1.6.15", ))
        assertTrue(possible(real = "1.0.5基本版", tracked = "1.0.5基本版", latest = "1.0.5正式版"))
    }

    @Test
    fun t010() { // keeps detection on when the real version matches latest
        assertTrue(possible( real = "1.6.15-debug", tracked = "1.6.15-debug", latest = "1.6.15", ))
        assertTrue(possible( real = "v151_beta", tracked = "v151_beta", latest = "151.0.7922.47", ))
    }

    @Test
    fun t011() { // turns detection off for a major-only rolling tag
        assertFalse(possible( real = "151.0.7922.47", tracked = "151.0.7922.47", latest = "v151_beta", ))
        assertFalse(possible( real = "151.0.7922.47", tracked = "v151_beta", latest = "v151_beta", ))
    }

    @Test
    fun t012() { // a naive source keeps detection on for a rolling tag
        assertTrue(possible( real = "151.0.7922.47", tracked = "151.0.7922.47", latest = "v151_beta", naive = true, ))
    }

    @Test
    fun t013() { // turns detection off for unrelated non-standard versions
        assertFalse(possible(real = "foo", tracked = "bar", latest = "baz"))
    }

    @Test
    fun t014() { // a naive source accepts unrelated non-standard versions
        assertTrue(possible(real = "foo", tracked = "bar", latest = "baz", naive = true))
    }

    @Test
    fun t015() { // track-only, release-date, HTML-without-regex and disallowed sources opt out
        assertFalse(possible(trackOnly = true))
        assertFalse(possible(releaseDateAsVersion = true))
        assertFalse(possible(isHtmlWithNoVersionDetection = true))
        assertFalse(possible(versionDetectionDisallowed = true))
    }

    @Test
    fun t016() { // missing real or tracked versions opt out
        assertFalse(possible(real = null))
        assertFalse(possible(tracked = null))
    }

    @Test
    fun t017() { // standard versions are always possible
        assertTrue(possible(real = "1.2.3", tracked = "1.2.3", latest = "1.2.4"))
        assertTrue(possible(real = "1.2.3-1", tracked = "1.2.3-1", latest = "1.2.3-2"))
    }


    // reconcileTrackedVersion
    @Test
    fun t018() { // collapses packaging suffixes on the installed side
        assertEquals("1.6.15", trackedAfter( tracked = "1.6.15-debug", real = "1.6.15-debug", latest = "1.6.15", ))
        assertEquals("0.9.108", trackedAfter( tracked = "0.9.108 strip", real = "0.9.108 strip", latest = "0.9.108", ))
        assertEquals("1.0.5", trackedAfter( tracked = "1.0.5-release+26090410", real = "1.0.5-release+26090410", latest = "1.0.5", ))
    }

    @Test
    fun t019() { // collapses a leading v in both directions
        assertEquals("2026.03", trackedAfter(tracked = "v2026.03", real = "v2026.03", latest = "2026.03"))
        assertEquals("v2026.03", trackedAfter(tracked = "2026.03", real = "2026.03", latest = "v2026.03"))
    }

    @Test
    fun t020() { // does not collapse a rolling major-only pre-release tag
        assertNull(reconcileTrackedVersion( trackedVersion = "151.0.7922.47", realInstalledVersion = "151.0.7922.47", latestVersion = "v151_beta", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertNull(reconcileTrackedVersion( trackedVersion = "v151_beta", realInstalledVersion = "151.0.7922.47", latestVersion = "v151_beta", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
    }

    @Test
    fun t021() { // does not hide conflicting packaging variants
        assertNull(reconcileTrackedVersion( trackedVersion = "1.6.15-debug", realInstalledVersion = "1.6.15-debug", latestVersion = "1.6.15-release", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertNull(reconcileTrackedVersion( trackedVersion = "1.6.15", realInstalledVersion = "1.6.15", latestVersion = "1.6.15-debug", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertNull(reconcileTrackedVersion( trackedVersion = "1.6.15-release", realInstalledVersion = "1.6.15-release", latestVersion = "1.6.15-debug", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
    }

    @Test
    fun t022() { // keeps pre-release qualifiers significant
        assertNull(reconcileTrackedVersion( trackedVersion = "61.0-beta1", realInstalledVersion = "61.0-beta1", latestVersion = "61.0", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
    }

    @Test
    fun t023() { // adopts latest when the device version is the same release
        assertEquals("61.0", trackedAfter(tracked = "61.0-beta1", real = "61.0", latest = "61.0"))
        assertEquals("1.6.15", trackedAfter(tracked = "1.6.14", real = "1.6.15", latest = "1.6.15"))
        assertEquals("1.6.15", trackedAfter(tracked = "1.6.14", real = "1.6.15-debug", latest = "1.6.15"))
        assertEquals("151.0.7922.47", trackedAfter( tracked = "v151_beta", real = "151.0.7922.47", latest = "151.0.7922.47", ))
    }

    @Test
    fun t024() { // tracks a device downgrade
        assertEquals("1.6.14", trackedAfter(tracked = "1.6.15", real = "1.6.14", latest = "1.6.15"))
    }

    @Test
    fun t025() { // keeps build metadata significant when the remote carries it
        assertNull(reconcileTrackedVersion( trackedVersion = "1.0.5+1", realInstalledVersion = "1.0.5+1", latestVersion = "1.0.5+2", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertNull(reconcileTrackedVersion( trackedVersion = "1.0.5", realInstalledVersion = "1.0.5", latestVersion = "1.0.5+1", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertEquals("1.0.5", trackedAfter(tracked = "1.0.5+1", real = "1.0.5+1", latest = "1.0.5"))
    }

    @Test
    fun t026() { // does not swallow non-ASCII qualifiers
        assertNull(reconcileTrackedVersion( trackedVersion = "1.2.3稳定版", realInstalledVersion = "1.2.3稳定版", latestVersion = "1.2.3", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
        assertNull(reconcileTrackedVersion( trackedVersion = "1.2.3稳定版", realInstalledVersion = "1.2.3稳定版", latestVersion = "1.2.3正式版", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
    }

    @Test
    fun t027() { // normalizes letter/digit punctuation in qualifiers
        assertEquals("1.2.3-beta.1", trackedAfter( tracked = "1.2.3-beta1", real = "1.2.3-beta1", latest = "1.2.3-beta.1", ))
    }

    @Test
    fun t028() { // naive sources adopt the real version when possible
        assertEquals("2.0", trackedAfter(tracked = "v2", real = "2.0", latest = "2.0", naive = true))
    }

    @Test
    fun t029() { // does not reconcile when standard detection is off
        assertNull(reconcileTrackedVersion( trackedVersion = "1.6.14", realInstalledVersion = "1.6.15", latestVersion = "1.7.0", versionDetectionIsStandard = false, naiveStandardVersionDetection = false, ))
    }

    @Test
    fun t030() { // a null tracked version is left to the caller
        assertNull(reconcileTrackedVersion( trackedVersion = null, realInstalledVersion = "1.2.3", latestVersion = "1.2.4", versionDetectionIsStandard = true, naiveStandardVersionDetection = false, ))
    }


    // update badge outcomes after correction
    @Test
    fun t031() { // no badge for cosmetic differences
        assertFalse(updateShown( effectiveInstalled( tracked = "1.6.15-debug", real = "1.6.15-debug", latest = "1.6.15", ), "1.6.15", ))
        assertFalse(updateShown( effectiveInstalled( tracked = "v2026.03", real = "v2026.03", latest = "2026.03", ), "2026.03", ))
    }

    @Test
    fun t032() { // badge for a rolling major-only tag
        assertTrue(updateShown( effectiveInstalled( tracked = "151.0.7922.47", real = "151.0.7922.47", latest = "v151_beta", ), "v151_beta", ))
    }

    @Test
    fun t033() { // badge for real updates
        assertTrue(updateShown( effectiveInstalled( tracked = "1.6.14", real = "1.6.14", latest = "1.6.15", ), "1.6.15", ))
        assertTrue(updateShown( effectiveInstalled( tracked = "1.6.15-debug", real = "1.6.15-debug", latest = "1.7.0", ), "1.7.0", ))
        assertTrue(updateShown( effectiveInstalled( tracked = "1.0.5+1", real = "1.0.5+1", latest = "1.0.5+2", ), "1.0.5+2", ))
    }

    @Test
    fun t034() { // badge for conflicting packaging variants
        assertTrue(updateShown( effectiveInstalled( tracked = "1.6.15-debug", real = "1.6.15-debug", latest = "1.6.15-release", ), "1.6.15-release", ))
        assertTrue(updateShown( effectiveInstalled( tracked = "1.6.15", real = "1.6.15", latest = "1.6.15-debug", ), "1.6.15-debug", ))
    }

    @Test
    fun t035() { // badge for a genuine pre-release-to-final difference
        assertTrue(updateShown( effectiveInstalled( tracked = "61.0-beta1", real = "61.0-beta1", latest = "61.0", ), "61.0", ))
    }

    @Test
    fun t036() { // no badge when the device already has the latest release
        assertFalse(updateShown( effectiveInstalled( tracked = "61.0-beta1", real = "61.0", latest = "61.0", ), "61.0", ))
    }

}
