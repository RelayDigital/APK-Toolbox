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
