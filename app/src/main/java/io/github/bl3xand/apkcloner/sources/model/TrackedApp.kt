package io.github.bl3xand.apkcloner.sources.model

import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.Tr
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** An app tracked from a source. The JSON form matches the reference app's export format. */
data class TrackedApp(
    val id: String,
    val url: String,
    val author: String,
    val name: String,
    val installedVersion: String? = null,
    val latestVersion: String,
    val apkUrls: List<NamedUrl> = emptyList(),
    val otherAssetUrls: List<NamedUrl> = emptyList(),
    val preferredApkIndex: Int,
    val additionalSettings: Map<String, Any?>,
    val lastUpdateCheck: Instant? = null,
    val pinned: Boolean = false,
    val categories: List<String> = emptyList(),
    val releaseDate: Instant? = null,
    val changeLog: String? = null,
    val releaseUrl: String? = null,
    val overrideSource: String? = null,
    val allowIdChange: Boolean = false,
    val pendingRepoRenameUrl: String? = null,
    val preferredBranchKey: String? = null,
    val releaseBranchGone: Boolean = false,
) {
    val hasPendingRepoRename: Boolean get() = !pendingRepoRenameUrl.isNullOrEmpty()

    val settings: TypedSettings get() = TypedSettings(additionalSettings)

    val overrideName: String? get() = settings.getStringOrNull("appName")?.takeIf { it.isNotBlank() }
    val finalName: String get() = overrideName ?: name

    val overrideAuthor: String? get() = settings.getStringOrNull("appAuthor")?.takeIf { it.isNotBlank() }
    val finalAuthor: String get() = overrideAuthor ?: author

    /** The id is a placeholder (hash or legacy number) until the real package name is known. */
    val hasTempId: Boolean get() = tempIdNumeric.matches(id) || tempIdHex.matches(id)

    /** Track-only, or installed with version detection off: the version is not a real one. */
    val isVersionPseudo: Boolean
        get() = settings.getBool(SettingKeys.TRACK_ONLY) ||
            (installedVersion != null && !settings.getBool(SettingKeys.VERSION_DETECTION))

    /** Kept at the version that is installed: new releases are neither offered nor installed. */
    val updatesOff: Boolean get() = settings.getBool(SettingKeys.NO_UPDATES)

    /**
     * The package of the clone this app is set up to be installed as - rebuilt under another
     * name, without the permissions the user took away - whether or not that has happened yet.
     */
    val cloneTarget: String? get() = settings.getStringOrNull(SettingKeys.CLONE_PACKAGE)?.takeIf { it.isNotBlank() }

    /**
     * The package this app is installed under as a clone, once it is: null while the clone is
     * only set up. Until the clone is on the device nothing about the app changes - what is
     * installed as the app itself stays what is shown, checked and updated.
     */
    val clonePackage: String? get() = cloneTarget?.takeIf { settings.getBool(SettingKeys.CLONE_ACTIVE) }

    /** The package to look for on the device: the clone's when the app is installed as one. */
    val devicePackage: String get() = clonePackage ?: id

    /**
     * Permissions the clone is built without. A name stays here whatever a single release asks
     * for, so a permission that is dropped by one release and brought back by a later one is
     * taken away again.
     */
    val cloneRemovedPermissions: Set<String> get() = listSetting(SettingKeys.CLONE_REMOVED_PERMISSIONS)

    /** What the last APK seen of this app asks for; empty until one has been downloaded. */
    val cloneRequestedPermissions: Set<String> get() = listSetting(SettingKeys.CLONE_REQUESTED_PERMISSIONS)

    /** Dex patches (PatchIds) the clone of this app is built with. */
    val clonePatches: Set<String> get() = listSetting(SettingKeys.CLONE_PATCHES)

    /** Every permission the user has been shown for this app. */
    val cloneKnownPermissions: Set<String> get() = listSetting(SettingKeys.CLONE_KNOWN_PERMISSIONS)

    /** Permissions a release brought that the user has not been shown yet. Nothing before the first look. */
    val cloneNewPermissions: Set<String>
        get() = cloneKnownPermissions.takeIf { it.isNotEmpty() }?.let { cloneRequestedPermissions - it }.orEmpty()

    /** The signer of the APK the installed clone was built from: the clone itself carries our key. */
    val cloneSourceSigner: Set<String> get() = listSetting(SettingKeys.CLONE_SOURCE_SIGNER)

    private fun listSetting(key: String): Set<String> =
        settings.getStringOrNull(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun withSetting(key: String, value: Any?): TrackedApp =
        copy(additionalSettings = LinkedHashMap(additionalSettings).also { it[key] = value })

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("author", author)
        put("name", name)
        put("installedVersion", installedVersion ?: JSONObject.NULL)
        put("latestVersion", latestVersion)
        put("apkUrls", namedUrlsToJson(apkUrls))
        put("otherAssetUrls", namedUrlsToJson(otherAssetUrls))
        put("preferredApkIndex", preferredApkIndex)
        put("additionalSettings", JsonValues.stringify(additionalSettings))
        put("lastUpdateCheck", lastUpdateCheck?.let(::toMicros) ?: JSONObject.NULL)
        put("pinned", pinned)
        put("categories", JSONArray(categories))
        put("releaseDate", releaseDate?.let(::toMicros) ?: JSONObject.NULL)
        put("changeLog", changeLog ?: JSONObject.NULL)
        put("releaseUrl", releaseUrl ?: JSONObject.NULL)
        put("overrideSource", overrideSource ?: JSONObject.NULL)
        put("allowIdChange", allowIdChange)
        put("pendingRepoRenameUrl", pendingRepoRenameUrl ?: JSONObject.NULL)
        put("preferredBranchKey", preferredBranchKey ?: JSONObject.NULL)
        put("releaseBranchGone", releaseBranchGone)
    }

    companion object {
        private val tempIdNumeric = Regex("^[0-9]+$")
        private val tempIdHex = Regex("^[0-9a-f]{12}$")

        /** Parses the current schema; stored data goes through the migrations first. */
        fun fromJson(json: Map<String, Any?>): TrackedApp {
            val categories = when {
                json["categories"] != null -> (json["categories"] as List<*>).map { it.toString() }
                json["category"] != null -> listOf(json["category"] as String)
                else -> emptyList()
            }
            return TrackedApp(
                id = json["id"] as String,
                url = json["url"] as String,
                author = json["author"] as String,
                name = json["name"] as String,
                installedVersion = json["installedVersion"] as String?,
                latestVersion = (json["latestVersion"] as String?) ?: Tr.get("unknown"),
                apkUrls = namedUrlsFromJson(
                    (json["apkUrls"] as String?) ?: "[[\"placeholder\", \"placeholder\"]]",
                ),
                otherAssetUrls = namedUrlsFromJson((json["otherAssetUrls"] as String?) ?: "[]"),
                preferredApkIndex = (json["preferredApkIndex"] as Number?)?.toInt() ?: -1,
                additionalSettings = JsonValues.parseObject(json["additionalSettings"] as String),
                lastUpdateCheck = (json["lastUpdateCheck"] as Number?)?.let { fromMicros(it.toLong()) },
                pinned = (json["pinned"] as Boolean?) ?: false,
                categories = categories,
                releaseDate = (json["releaseDate"] as Number?)?.let { fromMicros(it.toLong()) },
                changeLog = json["changeLog"] as String?,
                releaseUrl = json["releaseUrl"] as String?,
                overrideSource = json["overrideSource"] as String?,
                allowIdChange = (json["allowIdChange"] as Boolean?) ?: false,
                pendingRepoRenameUrl = json["pendingRepoRenameUrl"] as String?,
                preferredBranchKey = json["preferredBranchKey"] as String?,
                releaseBranchGone = (json["releaseBranchGone"] as Boolean?) ?: false,
            )
        }

        fun toMicros(instant: Instant): Long = instant.epochSecond * 1_000_000 + instant.nano / 1000

        fun fromMicros(micros: Long): Instant =
            Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L)

        fun namedUrlsToJson(urls: List<NamedUrl>): String =
            JSONArray(urls.map { JSONArray(listOf(it.name, it.url)) }).toString()

        fun namedUrlsFromJson(text: String): List<NamedUrl> {
            val array = JSONArray(text)
            return (0 until array.length()).map {
                val pair = array.getJSONArray(it)
                NamedUrl(pair.getString(0), pair.getString(1))
            }
        }
    }
}

/** Keeps the current release fields so a too-young release stays hidden until it has aged. */
fun applyMinAgeSuppression(currentApp: TrackedApp, fetchedApp: TrackedApp): TrackedApp = fetchedApp.copy(
    latestVersion = currentApp.latestVersion,
    releaseDate = currentApp.releaseDate,
    changeLog = currentApp.changeLog,
    releaseUrl = currentApp.releaseUrl,
    apkUrls = currentApp.apkUrls,
    otherAssetUrls = currentApp.otherAssetUrls,
)

/**
 * Carries the choice of a release file from [currentApp] onto [fetchedApp], judging whether the
 * branch that choice stood for is still there:
 *
 * - The file is chosen, not merely defaulted to, only when the index is a real pick (0 or more);
 *   -1 is the app's "nothing chosen" and is carried over as is, branch or no branch.
 * - When the app's settings name the branch to follow ([settingsBranchKey], the kind of a
 *   Telegram file), that is a choice made on purpose: the fresh list is that branch alone, so
 *   the place in it and the shrinking of it say nothing, and the named branch is simply adopted.
 * - Gone otherwise: with a remembered branch (by its key, when the source names branches), a
 *   branch no file of the new release carries - but only once something else agrees that a file
 *   went missing (the remembered place is past the end of the list, or the list shrank), because
 *   a source whose file names carry the version renames every file every release; without a
 *   remembered branch, a list too short to hold the remembered place in it. The fresh release
 *   data is kept as it is - the page has to offer the new files for choosing another branch -
 *   while the old index and key stay, so that a branch coming back is recognized; nothing
 *   installs on its own from then on.
 * - Not gone: the index moves to the file whose key is the remembered one, when there is one (a
 *   source reordering or renaming its files); the key is written down anew from the file at the
 *   kept place when the remembered one is not carried any more (a version-bearing name), so
 *   that the judgment stays by name and not by place alone.
 *
 * [branchKeyOf] names the branch a file belongs to, as the source sees it; null is a source
 * whose file names carry the version - there only the shrinking of the list can be judged.
 */
fun carryPreferredFile(
    currentApp: TrackedApp,
    fetchedApp: TrackedApp,
    branchKeyOf: (NamedUrl) -> String?,
    settingsBranchKey: String? = null,
): TrackedApp {
    if (fetchedApp.apkUrls.isEmpty()) {
        // Nothing to judge a branch by (a track-only app whose files were all filtered away):
        // whatever was known stays as it was.
        return fetchedApp.copy(
            preferredApkIndex = currentApp.preferredApkIndex,
            preferredBranchKey = currentApp.preferredBranchKey,
            releaseBranchGone = currentApp.releaseBranchGone,
        )
    }
    if (settingsBranchKey != null) {
        // The settings say which branch to follow, so the list is that branch alone: the place
        // in it means nothing. A named branch that is not there at all is the source's own
        // error to tell; should it slip through anyway, it is said here all the same.
        val index = fetchedApp.apkUrls.indexOfFirst { branchKeyOf(it) == settingsBranchKey }
        return fetchedApp.copy(
            preferredApkIndex = index.takeIf { it >= 0 }
                ?: fetchedApp.preferredApkIndex.coerceIn(0, fetchedApp.apkUrls.size - 1),
            preferredBranchKey = settingsBranchKey,
            releaseBranchGone = index < 0,
        )
    }
    val key = currentApp.preferredBranchKey
    val keyFound = key != null && fetchedApp.apkUrls.any { branchKeyOf(it) == key }
    val gone = currentApp.preferredApkIndex >= 0 && (
        if (key != null) {
            !keyFound && (currentApp.preferredApkIndex >= fetchedApp.apkUrls.size ||
                fetchedApp.apkUrls.size < currentApp.apkUrls.size)
        } else {
            currentApp.preferredApkIndex >= fetchedApp.apkUrls.size
        }
        )
    if (gone) {
        // The old index and key stay - the ones a branch coming back has to be recognized by.
        return fetchedApp.copy(
            preferredApkIndex = currentApp.preferredApkIndex,
            preferredBranchKey = key,
            releaseBranchGone = true,
        )
    }
    if (currentApp.preferredApkIndex < 0) {
        // Nothing chosen: only a pick clears a branch already said to be gone.
        return fetchedApp.copy(
            preferredApkIndex = -1,
            preferredBranchKey = null,
            releaseBranchGone = currentApp.releaseBranchGone,
        )
    }
    val index = if (keyFound) {
        fetchedApp.apkUrls.indexOfFirst { branchKeyOf(it) == key }
    } else {
        currentApp.preferredApkIndex.takeIf { it < fetchedApp.apkUrls.size }
            ?: currentApp.preferredApkIndex.coerceAtMost(fetchedApp.apkUrls.size - 1)
    }
    return fetchedApp.copy(
        preferredApkIndex = index,
        preferredBranchKey = if (keyFound) key else branchKeyOf(fetchedApp.apkUrls[index]),
        releaseBranchGone = false,
    )
}
