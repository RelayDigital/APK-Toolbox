package io.github.bl3xand.apkcloner.sources.model

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedAppMergerTest {
    private fun urls(vararg names: String): List<NamedUrl> = names.map { NamedUrl(it, "https://x/$it") }

    /** The record the list holds, say, when the user has just edited it. */
    private fun stored(
        preferredApkIndex: Int = 0,
        additionalSettings: Map<String, Any?> = emptyMap(),
        installedVersion: String? = "1.0",
        pendingRepoRenameUrl: String? = null,
        preferredBranchKey: String? = null,
        pinned: Boolean = false,
        categories: List<String> = emptyList(),
        allowIdChange: Boolean = false,
    ) = TrackedApp(
        id = "app",
        url = "https://x/app",
        author = "a",
        name = "App",
        installedVersion = installedVersion,
        latestVersion = "1.0",
        apkUrls = urls("a.apk", "b.apk"),
        preferredApkIndex = preferredApkIndex,
        additionalSettings = additionalSettings,
        lastUpdateCheck = Instant.EPOCH,
        pinned = pinned,
        categories = categories,
        preferredBranchKey = preferredBranchKey,
        allowIdChange = allowIdChange,
        pendingRepoRenameUrl = pendingRepoRenameUrl,
    )

    /** The same app as a check fetched it - fresher release words, user fields as they were. */
    private fun fetched(
        latestVersion: String = "2.0",
        apkUrls: List<NamedUrl> = urls("a2.apk", "b2.apk"),
        additionalSettings: Map<String, Any?> = emptyMap(),
        installedVersion: String? = null,
        releaseDate: Instant? = Instant.ofEpochSecond(1000),
    ) = TrackedApp(
        id = "app",
        url = "https://x/app",
        author = "a",
        name = "App",
        installedVersion = installedVersion,
        latestVersion = latestVersion,
        apkUrls = apkUrls,
        otherAssetUrls = urls("notes.txt"),
        preferredApkIndex = 1,
        additionalSettings = additionalSettings,
        lastUpdateCheck = Instant.now(),
        releaseDate = releaseDate,
        changeLog = "what changed",
        releaseUrl = "https://x/release",
    )

    // ---- the release's own words come from the fetch ---------------------------------------

    @Test
    fun checkFieldsAreTheFetchs() {
        val fetched = fetched()
        val merged = preserveUserFields(stored(), fetched)
        assertEquals("2.0", merged.latestVersion)
        assertEquals(fetched.apkUrls, merged.apkUrls)
        assertEquals(fetched.otherAssetUrls, merged.otherAssetUrls)
        assertEquals(fetched.releaseDate, merged.releaseDate)
        assertEquals("what changed", merged.changeLog)
        assertEquals("https://x/release", merged.releaseUrl)
        assertEquals(fetched.lastUpdateCheck, merged.lastUpdateCheck)
    }

    @Test
    fun aFetchWithNoReleaseDateOfItsOwnStoresNoDate() {
        val merged = preserveUserFields(stored(), fetched(releaseDate = null))
        assertNull(merged.releaseDate)
    }

    // ---- the user's words are kept as the list has them ------------------------------------

    @Test
    fun userSettingsAreNotOverwrittenByTheFetchsStaleCopy() {
        val merged = preserveUserFields(
            stored(additionalSettings = mapOf(SettingKeys.CLONE_PACKAGE to "clone.app", "appName" to "My name")),
            fetched(additionalSettings = mapOf(SettingKeys.CLONE_PACKAGE to "old.clone", "appName" to "Old name")),
        )
        assertEquals("clone.app", merged.additionalSettings[SettingKeys.CLONE_PACKAGE])
        assertEquals("My name", merged.additionalSettings["appName"])
    }

    @Test
    fun aSettingOnlyTheFetchHasIsKept() {
        val merged = preserveUserFields(stored(additionalSettings = mapOf("appName" to "My name")), fetched(additionalSettings = mapOf("about" to "note")))
        assertEquals("My name", merged.additionalSettings["appName"])
        assertEquals("note", merged.additionalSettings["about"])
    }

    @Test
    fun aSettingOnlyTheStoredRecordHasIsKept() {
        val merged = preserveUserFields(stored(additionalSettings = mapOf("about" to "note")), fetched())
        assertEquals("note", merged.additionalSettings["about"])
    }

    @Test
    fun cloneSettingsSurviveAFetchThatNeverSawThem() {
        val clone = mapOf(
            SettingKeys.CLONE_PACKAGE to "clone.app",
            SettingKeys.CLONE_REMOVED_PERMISSIONS to "cam",
            SettingKeys.CLONE_PATCHES to "p1,p2",
            SettingKeys.CLONE_ACTIVE to true,
            SettingKeys.CLONE_SOURCE_SIGNER to "ab:cd",
        )
        val merged = preserveUserFields(stored(additionalSettings = clone), fetched())
        for ((key, value) in clone) assertEquals(value, merged.additionalSettings[key])
    }

    @Test
    fun pinnedCategoriesAndAllowIdChangeAreTheUsers() {
        val merged = preserveUserFields(
            stored(pinned = true, categories = listOf("tools"), additionalSettings = mapOf(SettingKeys.APP_ID to "other")),
            fetched().copy(pinned = false, categories = emptyList(), allowIdChange = true),
        )
        assertTrue(merged.pinned)
        assertEquals(listOf("tools"), merged.categories)
        assertEquals(false, merged.allowIdChange)
        assertEquals("other", merged.additionalSettings[SettingKeys.APP_ID])
    }

    @Test
    fun anInstalledVersionStaysSet() {
        val merged = preserveUserFields(stored(installedVersion = "1.0"), fetched(installedVersion = null))
        assertEquals("1.0", merged.installedVersion)
    }

    @Test
    fun aFetchsInstalledVersionIsKeptWhenTheListHasNone() {
        val merged = preserveUserFields(stored(installedVersion = null), fetched(installedVersion = "1.9"))
        assertEquals("1.9", merged.installedVersion)
    }

    // ---- preferredApkIndex -------------------------------------------------------------------

    @Test
    fun theChosenFileIsKeptWhereItFits() {
        val merged = preserveUserFields(stored(preferredApkIndex = 0), fetched())
        assertEquals(0, merged.preferredApkIndex)
    }

    @Test
    fun nothingChosenStaysNothingChosen() {
        val merged = preserveUserFields(stored(preferredApkIndex = -1), fetched())
        assertEquals(-1, merged.preferredApkIndex)
    }

    @Test
    fun anOutOfRangeChoiceFallsBackToTheFetchsJudgment() {
        // The fetch's own preferredApkIndex (1 here) is what a check left there.
        val merged = preserveUserFields(stored(preferredApkIndex = 5), fetched())
        assertEquals(1, merged.preferredApkIndex)
    }

    @Test
    fun anOutOfRangeChoiceMovesToTheRememberedBranch() {
        // Four files now; the chosen index 5 no longer fits, but the remembered branch does.
        val fetched = fetched(apkUrls = urls("w.apk", "x.apk", "y.apk", "z.apk"))
        val merged = preserveUserFields(stored(preferredApkIndex = 5, preferredBranchKey = "z.apk"), fetched)
        assertEquals(3, merged.preferredApkIndex)
    }

    @Test
    fun anEmptyFileListKeepsTheStoredChoice() {
        val merged = preserveUserFields(stored(preferredApkIndex = 2), fetched(apkUrls = emptyList()))
        assertEquals(2, merged.preferredApkIndex)
    }

    // ---- check-owned settings -----------------------------------------------------------------

    @Test
    fun aSourcesWayOfNamingItsFilesIsTheFetchs() {
        val merged = preserveUserFields(
            stored(additionalSettings = mapOf("telegramFilePattern" to "old | kind", "telegramBuildFile" to "old.apk")),
            fetched(additionalSettings = mapOf("telegramFilePattern" to "new | kind")),
        )
        assertEquals("new | kind", merged.additionalSettings["telegramFilePattern"])
        // A key the fetch no longer names stays as stored: the check keeps it only by carrying
        // its own value, not by absence.
        assertEquals("old.apk", merged.additionalSettings["telegramBuildFile"])
    }

    // ---- the pending rename ------------------------------------------------------------------

    @Test
    fun aPendingRenameIsTheFetchsWholeRecord() {
        val merged = preserveUserFields(
            stored(pendingRepoRenameUrl = "https://x/new", preferredApkIndex = 1, additionalSettings = mapOf("appName" to "Mine")),
            fetched().copy(pendingRepoRenameUrl = "https://x/new", preferredApkIndex = 0, additionalSettings = emptyMap()),
        )
        // The rename path saves the whole record on purpose; nothing of the user's is moved
        // onto it here, so the incoming record stands as it is.
        assertEquals(0, merged.preferredApkIndex)
        assertNull(merged.additionalSettings["appName"])
    }

    @Test
    fun aStoredPendingRenameIsNotSilentlyWipedByAnUnrelatedFetch() {
        val merged = preserveUserFields(stored(pendingRepoRenameUrl = "https://x/new"), fetched())
        assertEquals("https://x/new", merged.pendingRepoRenameUrl)
    }
}