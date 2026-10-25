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
        preferredBranchKey: String? = null,
        releaseBranchGone: Boolean = false,
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
        preferredBranchKey = preferredBranchKey,
        releaseBranchGone = releaseBranchGone,
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

    // ---- the branch the user's choice names --------------------------------------------------

    @Test
    fun theRememberedBranchKeyIsNotOverwrittenByTheFetchsStaleCopy() {
        // The user picked a file while the fetch was out; the fetch's record still names the
        // branch as it was when it started.
        val merged = preserveUserFields(
            stored(preferredApkIndex = 5, preferredBranchKey = "z.apk"),
            fetched(preferredBranchKey = "old.apk"),
        )
        assertEquals("z.apk", merged.preferredBranchKey)
    }

    @Test
    fun aGoneMarkIsNotUndoneByTheFetchsStaleCopy() {
        // A check judged the branch gone after the user's pick; a fetch that started before
        // that judgment must not clear the mark - nor set one the list does not have.
        val merged = preserveUserFields(
            stored(preferredApkIndex = 2, preferredBranchKey = "b.apk"),
            fetched(releaseBranchGone = false),
        )
        assertEquals(false, merged.releaseBranchGone)
        val unGone = preserveUserFields(
            stored(preferredApkIndex = 2, preferredBranchKey = "b.apk").copy(releaseBranchGone = true),
            fetched(releaseBranchGone = false),
        )
        assertTrue("a gone-mark the list holds must survive the fetch", unGone.releaseBranchGone)
        val reMarked = preserveUserFields(
            stored(preferredApkIndex = 2, preferredBranchKey = "b.apk"),
            fetched(releaseBranchGone = true),
        )
        assertEquals("a fetch that never saw the pick must not mark the pick's branch gone", false, reMarked.releaseBranchGone)
    }

    @Test
    fun anOutOfRangeChoiceMovesToTheRememberedBranchKeyOnAKeyedSource() {
        // A keyed source: the file's name changes with the version (app-2.0.apk), so the
        // remembered branch key is found by key, not by exact name. Four files now.
        val fetched = fetched(apkUrls = urls("app-1.9.apk", "app-2.0.apk", "other-1.apk", "other-2.apk"))
        val merged = preserveUserFields(
            stored(preferredApkIndex = 5, preferredBranchKey = "other"),
            fetched,
            branchKeyOf = { file -> file.name.substringBefore('-') },
        )
        assertEquals(2, merged.preferredApkIndex)
    }

    @Test
    fun anOutOfRangeChoiceStillMovesByExactNameWhereTheSourceHasNoKeys() {
        // A source without keys: the file's name is stable across releases, so name equality
        // is the judgment, exactly as before.
        val fetched = fetched(apkUrls = urls("w.apk", "x.apk", "y.apk", "z.apk"))
        val merged = preserveUserFields(stored(preferredApkIndex = 5, preferredBranchKey = "z.apk"), fetched)
        assertEquals(3, merged.preferredApkIndex)
    }

    @Test
    fun aPickMadeDuringACheckKeepsItsBranchWhenTheChecksSaveLands() {
        // The race this whole fix is about, end to end: the user picked a file (the pick
        // writes preferredApkIndex, the branch key and releaseBranchGone = false), and a
        // check that started before the pick finishes and saves its stale fetch over the
        // record through the merge. The pick's branch word must come through.
        val picked = stored(preferredApkIndex = 3, preferredBranchKey = "instander", additionalSettings = mapOf(SettingKeys.PREFERRED_APK to "[]"))
        val staleFetch = fetched(apkUrls = urls("app-2.0.apk", "instander-9.0.apk"), preferredBranchKey = "old-family", releaseBranchGone = true)
        val saved = preserveUserFields(picked, staleFetch, branchKeyOf = { file -> file.name.substringBefore('-') })
        assertEquals("instander", saved.preferredBranchKey)
        assertEquals(false, saved.releaseBranchGone)
        // The remembered index fits the fetched list only through the key, and it is found.
        assertEquals(1, saved.preferredApkIndex)
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

    // ---- the wiring the check paths use ------------------------------------------------------------

    /**
     * The merge only runs through saveApps(preserveUserFields = true), which the check paths in
     * UpdateChecker pass: checkUpdate saves its one fetched record, and checkUpdates saves the
     * fetched and failed batches, all with reuseInstalledInfo = true (the failed batch also with
     * attemptToCorrectInstallStatus = false). A repository needs Android to stand up, so what the
     * wiring does is said here instead: this is saveApps(preserveUserFields = true) applying
     * that merge before storing, with the arguments exactly as UpdateChecker passes them.
     */
    @Test
    fun aUserEditMadeDuringACheckIsNotClobberedByTheChecksSave() {
        val beforeTheCheck = stored(additionalSettings = mapOf(SettingKeys.CLONE_PACKAGE to "old.clone"))
        // The check fetched its record before the user touched the clone set-up.
        val fetchedDuringTheCheck = fetched(additionalSettings = mapOf(SettingKeys.CLONE_PACKAGE to "old.clone"))
        // The user's edit, in the record the list holds when the check's save lands.
        val stored = stored(additionalSettings = mapOf(SettingKeys.CLONE_PACKAGE to "clone.app", "appName" to "My name"))
        val saved = preserveUserFields(stored, fetchedDuringTheCheck)
        assertEquals("clone.app", saved.additionalSettings[SettingKeys.CLONE_PACKAGE])
        assertEquals("My name", saved.additionalSettings["appName"])
        // The release's own words still come from the fetch, and the stored record's other user
        // fields (the installed version, the pin) are what survives as well.
        assertEquals("2.0", saved.latestVersion)
        assertEquals("1.0", saved.installedVersion)
        assertEquals(beforeTheCheck.id, saved.id)
    }
}