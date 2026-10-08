package io.github.bl3xand.apkcloner.sources.model

import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.source.HTML
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import org.json.JSONArray

// Stored and imported apps may come from any earlier version of the reference format. These
// transformations bring them to the current schema; all of them can be applied repeatedly.

private fun migrateAppToHtml(
    json: MutableMap<String, Any?>,
    additionalSettings: Map<String, Any?>,
    newUrl: String,
    overrides: Map<String, Any?>,
): MutableMap<String, Any?> {
    json["url"] = newUrl
    val replacement = defaultValuesOf(HTML().combinedAppSpecificSettingFormItems)
    for (key in replacement.keys.toList()) {
        if (additionalSettings.containsKey(key)) replacement[key] = additionalSettings[key]
    }
    replacement.putAll(overrides)
    return replacement
}

/** The oldest format kept settings as a positional array. */
private fun migrateAdditionalData(
    json: Map<String, Any?>,
    additionalSettings: MutableMap<String, Any?>,
    formItems: List<SettingItem>,
) {
    val raw = json["additionalData"] as? String ?: return
    val values = JsonValues.parse(raw) as? List<*> ?: return
    values.forEachIndexed { index, value ->
        val item = formItems.getOrNull(index) ?: return@forEachIndexed
        additionalSettings[item.key] = if (item is SwitchItem) value == "true" else value
    }
    additionalSettings[SettingKeys.TRACK_ONLY] = json[SettingKeys.TRACK_ONLY] == "true" || json[SettingKeys.TRACK_ONLY] == true
    additionalSettings["noVersionDetection"] = json["noVersionDetection"] == "true" || json["noVersionDetection"] == true
}

private fun migrateVersionDetectionFormat(settings: MutableMap<String, Any?>) {
    if (settings["noVersionDetection"] == true) {
        settings[SettingKeys.VERSION_DETECTION] = "noVersionDetection"
        if (settings[SettingKeys.RELEASE_DATE_AS_VERSION] == true) settings[SettingKeys.VERSION_DETECTION] = "releaseDateAsVersion"
        settings.remove("noVersionDetection")
        settings.remove("releaseDateAsVersion")
    }
    when (settings[SettingKeys.VERSION_DETECTION]) {
        "standardVersionDetection" -> settings[SettingKeys.VERSION_DETECTION] = true
        "noVersionDetection" -> settings[SettingKeys.VERSION_DETECTION] = false
        "releaseDateAsVersion" -> {
            settings[SettingKeys.VERSION_DETECTION] = false
            settings[SettingKeys.RELEASE_DATE_AS_VERSION] = true
        }
    }
}

private fun migrateHtml(
    json: MutableMap<String, Any?>,
    original: Map<String, Any?>,
    settingsIn: MutableMap<String, Any?>,
): MutableMap<String, Any?> {
    var settings = settingsIn
    original["sortByFileNamesNotLinks"]?.let { settings["sortByLastLinkSegment"] = it }
    if (original["intermediateLinkRegex"] != null && (settings["intermediateLinkRegex"] as? String).isNullOrEmpty()) {
        settings["intermediateLink"] = listOf(
            mapOf(
                "customLinkFilterRegex" to original["intermediateLinkRegex"],
                "filterByLinkText" to original["intermediateLinkByText"],
            ),
        )
    }
    (settings["intermediateLink"] as? List<*>)?.let { list ->
        settings["intermediateLink"] = list.filter {
            !((it as? Map<*, *>)?.get("customLinkFilterRegex") as? String).isNullOrEmpty()
        }
    }
    fun isLegacy(url: String, id: String, author: String, name: String) =
        json["url"] == url && json["id"] == id && json["author"] == author && json["name"] == name &&
            json["overrideSource"] == null && settings[SettingKeys.TRACK_ONLY] == false &&
            settings["versionExtractionRegEx"] == "" && json["lastUpdateCheck"] != null

    // Sources that once had their own implementation and are plain HTML configurations now.
    val steamApp = settings["app"] as? String
    if (steamApp == "steam" || steamApp == "steam-chat-app") {
        settings = migrateAppToHtml(
            json, settings, "${json["url"]}/mobile",
            mapOf(
                "customLinkFilterRegex" to "/$steamApp-(([0-9]+\\.?){1,})\\.apk",
                "versionExtractionRegEx" to "/$steamApp-(([0-9]+\\.?){1,})\\.apk",
                "matchGroupToUse" to "$1",
            ),
        )
    }
    if (isLegacy("https://signal.org", "org.thoughtcrime.securesms", "Signal", "Signal")) {
        settings = migrateAppToHtml(
            json, settings, "https://updates.signal.org/android/latest.json",
            mapOf("versionExtractionRegEx" to "\\d+.\\d+.\\d+"),
        )
    }
    if (isLegacy("https://whatsapp.com", "com.whatsapp", "Meta", "WhatsApp")) {
        settings = migrateAppToHtml(
            json, settings, "https://whatsapp.com/android", mapOf("refreshBeforeDownload" to true),
        )
    }
    if (isLegacy("https://videolan.org", "org.videolan.vlc", "VideoLAN", "VLC")) {
        fun level(regex: String, byText: Boolean) = mapOf(
            "customLinkFilterRegex" to regex, "filterByLinkText" to byText, "skipSort" to false,
            "reverseSort" to false, "sortByLastLinkSegment" to false,
        )
        settings = migrateAppToHtml(
            json, settings, "https://www.videolan.org/vlc/download-android.html",
            mapOf(
                "refreshBeforeDownload" to true,
                "intermediateLink" to listOf(level("APK", true), level("arm64-v8a\\.apk$", false)),
                "versionExtractionRegEx" to "/vlc-android/([^/]+)/",
                "matchGroupToUse" to "1",
            ),
        )
    }
    return settings
}

/** Apps saved while Huawei AppGallery forced date pseudo-versions. */
private fun migrateHuawei(json: MutableMap<String, Any?>, settings: MutableMap<String, Any?>) {
    fun isPseudo(value: Any?) = value is String && Regex("^\\d{10,}$").matches(value)
    if (settings[SettingKeys.RELEASE_DATE_AS_VERSION] != true && !isPseudo(json["installedVersion"]) &&
        !isPseudo(json["latestVersion"])
    ) {
        return
    }
    settings[SettingKeys.VERSION_DETECTION] = true
    settings.remove("releaseDateAsVersion")
    if (isPseudo(json["installedVersion"])) json["installedVersion"] = null
}

private fun migrateFdroidOverrides(json: MutableMap<String, Any?>) {
    val url = json["url"] as String
    if (url.startsWith("https://cloudflare.f-droid.org")) {
        json["overrideSource"] = "FDroid"
    } else if (!json.containsKey("overrideSource") &&
        Regex("^https?://.+/fdroid/([^/]+(/|\\?)|[^/]+$)").containsMatchIn(url)
    ) {
        json["overrideSource"] = "FDroidRepo"
    }
}

fun appJsonCompatibilityModifiers(json: MutableMap<String, Any?>): MutableMap<String, Any?> {
    val source = SourceRegistry.getSource(json["url"] as String, json["overrideSource"] as? String)
    val formItems = source.flatCombinedFormItems
    var settings = defaultValuesOf(listOf(formItems))
    var original: Map<String, Any?> = emptyMap()
    (json["additionalSettings"] as? String)?.let {
        original = JsonValues.parseObject(it)
        settings.putAll(original)
    }
    migrateAdditionalData(json, settings, formItems)
    migrateVersionDetectionFormat(settings)
    when (original["supportFixedAPKURL"]) {
        true -> settings["defaultPseudoVersioningMethod"] = "partialAPKHash"
        false -> settings["defaultPseudoVersioningMethod"] = "APKLinkHash"
    }
    for (item in formItems) {
        if (settings[item.key] != null) settings[item.key] = item.ensureType(settings[item.key])
    }
    json["preferredApkIndex"] = ((json["preferredApkIndex"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
    (json["apkUrls"] as? String)?.let { raw ->
        // Once a plain list of URLs, now a list of [name, url] pairs.
        val parsed = JsonValues.parse(raw) as? List<*> ?: emptyList<Any?>()
        if (parsed.all { it is String }) {
            json["apkUrls"] = JSONArray(
                ApkFilter.apkUrlsFromUrls(parsed.filterIsInstance<String>()).map { JSONArray(listOf(it.name, it.url)) },
            ).toString()
        }
    }
    if (settings["autoApkFilterByArch"] == null) settings["autoApkFilterByArch"] = false
    if (settings["dontSortReleasesList"] == true) settings["sortMethodChoice"] = "none"
    if (source.sourceIdentifier == "HTML") settings = migrateHtml(json, original, settings)
    if (source.sourceIdentifier == "HuaweiAppGallery") migrateHuawei(json, settings)
    json["additionalSettings"] = JsonValues.stringify(settings)
    migrateFdroidOverrides(json)
    return json
}

/** Parses an app as stored; if a migration fails the data is read as it is rather than lost. */
fun appFromStoredJson(json: Map<String, Any?>): TrackedApp {
    val migrated = try {
        appJsonCompatibilityModifiers(LinkedHashMap(json))
    } catch (e: Exception) {
        json
    }
    return TrackedApp.fromJson(migrated)
}
