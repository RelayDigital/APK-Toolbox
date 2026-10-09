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
