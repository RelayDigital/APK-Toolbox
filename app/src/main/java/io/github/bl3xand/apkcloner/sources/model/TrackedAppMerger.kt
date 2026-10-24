package io.github.bl3xand.apkcloner.sources.model

/**
 * Saves the record of a check's fetch ([incoming]) over what the list holds for the app
 * ([stored]) without losing what the user set since the fetch started. Only what a check
 * owns is taken from [incoming] when both records have it; everything else keeps what the
 * list holds:
 *
 * - The release's own words - the version on offer, its files, the dates and notes of the
 *   release, the bookkeeping of the check itself - are [incoming]'s: they describe the source
 *   as it was seen moments ago, fresher than anything the list could hold.
 * - The user's words - the per-app settings (the clone set-up among them), the choice of a
 *   release file, the installed version once one is set, categories, pinning - are
 *   [stored]'s: [incoming] was built from a copy of the app as it was when the check started,
 *   and the user may have changed them since.
 *
 * A check's fetch can itself carry a settings change of its own - a source that names what it
 * offers in a new way, or the bits of release data a source fills in as it finds them - so the
 * settings a check owns are taken from [incoming] too (see [isCheckOwnedSetting]).
 *
 * A pending repository rename is the user's word through and through. A rename the list holds
 * stops the checks until the user answers it, so a fetch built before it landed does not drop
 * it; and a record that carries the rename itself is the rename path's own - it was built for
 * the moment the rename is answered, and nothing here would know how to move the user's
 * fields onto it, so it stands as it is (a check never builds one).
 */
fun preserveUserFields(stored: TrackedApp, incoming: TrackedApp): TrackedApp {
    // A record that carries the rename itself is the rename path's own: it was built for the
    // moment the rename is answered, and nothing here would know how to move the user's
    // fields onto it. It stands as it is.
    if (incoming.pendingRepoRenameUrl != null) return incoming
    val settings = LinkedHashMap<String, Any?>()
    for (key in stored.additionalSettings.keys + incoming.additionalSettings.keys) {
        settings[key] = if (isCheckOwnedSetting(key)) {
            incoming.additionalSettings[key] ?: stored.additionalSettings[key]
        } else {
            stored.additionalSettings[key] ?: incoming.additionalSettings[key]
        }
    }
    return incoming.copy(
        installedVersion = stored.installedVersion ?: incoming.installedVersion,
        additionalSettings = settings,
        preferredApkIndex = mergePreferredFileChoice(stored, incoming),
        pinned = stored.pinned,
        categories = stored.categories,
        allowIdChange = stored.allowIdChange,
        // A rename the list holds stops the checks until the user answers it; a fetch built
        // before it landed does not drop it.
        pendingRepoRenameUrl = stored.pendingRepoRenameUrl,
    )
}

/**
 * What falls to a check to keep current: a source's own way of naming what it offers. A user's
 * or clone's word in the settings never comes from a fetch.
 */
private fun isCheckOwnedSetting(key: String): Boolean =
    key == "telegramFilePattern" || key.startsWith("telegramBuild")

/**
 * The file the user chose to follow, moved onto [incoming]'s file list the way a check would
 * judge it: a remembered choice (index >= 0) stays where it fits the list, or moves to the file
 * whose branch key is the remembered one when the list was reordered under it. A choice that
 * fits nothing of the incoming list is [incoming]'s to say - the check will have judged how it
 * breaks, and the incoming record (not the stale stored one) carries that judgment, "the
 * branch is gone" included. "Nothing chosen" (-1) stays nothing chosen.
 */
private fun mergePreferredFileChoice(stored: TrackedApp, incoming: TrackedApp): Int {
    if (stored.preferredApkIndex < 0) return -1
    if (incoming.apkUrls.isEmpty()) return stored.preferredApkIndex
    if (stored.preferredApkIndex < incoming.apkUrls.size) return stored.preferredApkIndex
    if (stored.preferredBranchKey != null) {
        incoming.apkUrls.indexOfFirst { it.name == stored.preferredBranchKey }.let { if (it >= 0) return it }
    }
    return incoming.preferredApkIndex.coerceIn(0, incoming.apkUrls.size - 1)
}