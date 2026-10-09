package io.github.bl3xand.apkcloner.sources.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.model.appFromStoredJson
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** The list of tracked apps as a file: export, import, and the automatic export after changes. */
class SourcesBackup(private val context: Context, private val repo: SourcesRepository) {
    private val settings = repo.settings
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingAutoExport: ScheduledFuture<*>? = null

    fun generateExportJson(appIds: List<String>? = null, overrideExportSettings: Int? = null): JSONObject {
        val exportSettings = overrideExportSettings ?: settings.exportSettings
        val appList = JSONArray()
        for (entry in repo.all()) {
            if (appIds != null && entry.app.id !in appIds) continue
            if (settings.exportInstalledOnly && entry.app.installedVersion == null) continue
            // Per-app credentials are secrets too.
            val app = if (exportSettings < 2) {
                entry.app.copy(additionalSettings = entry.app.additionalSettings.filterKeys { !it.endsWith("-creds") })
            } else entry.app
            appList.put(app.toJson())
        }
        val settingsJson: Any = if (exportSettings > 0) {
            JSONObject().also { json ->
                for ((key, value) in settings.prefs.all) {
                    if (exportSettings < 2 && key.endsWith("-creds")) continue
                    json.put(key, if (value is Set<*>) JSONArray(value) else value)
                }
            }
        } else JSONObject.NULL
        return JSONObject().apply {
            put("schemaVersion", EXPORT_SCHEMA_VERSION)
            put("exportedAt", Instant.now().toString())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("apps", appList)
            put("settings", settingsJson)
        }
    }

    /** The ids of the apps in an export, without importing it. */
    fun appIdsInImportJson(text: String): List<String> {
        val apps = when (val decoded = JsonValues.parse(text)) {
            is Map<*, *> -> decoded["apps"] as? List<*>
            is List<*> -> decoded
            else -> null
        } ?: return emptyList()
        return apps.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
    }

    /** Imports apps (and settings, when present). Returns the apps and whether settings came along. */
    fun importJson(text: String): Pair<List<TrackedApp>, Boolean> {
        val imported: List<TrackedApp>
        var importedSettings: Map<String, Any?>? = null
        try {
            val decoded = JsonValues.parse(text)
            val appMaps: List<*>
            if (decoded is Map<*, *>) {
                val version = (decoded["schemaVersion"] as? Number)?.toInt() ?: 1
                if (version > EXPORT_SCHEMA_VERSION) {
                    throw SourceError("Export schema v$version is newer than this app supports (v$EXPORT_SCHEMA_VERSION).")
                }
                appMaps = decoded["apps"] as? List<*> ?: emptyList<Any?>()
                @Suppress("UNCHECKED_CAST")
                importedSettings = decoded["settings"] as? Map<String, Any?>
            } else {
                appMaps = decoded as List<*>
            }
            @Suppress("UNCHECKED_CAST")
            imported = appMaps.map { appFromStoredJson(it as Map<String, Any?>) }
        } catch (e: Exception) {
            throw SourceError("${Tr.get("failedToImport")}: ${errorText(e)}")
        }
        val adjusted = imported.map { it.copy(installedVersion = realInstalledVersionOf(it, repo.installedInfo(it.id))) }
        AppLog.info("Imported ${adjusted.size} app(s) into the list" + if (importedSettings != null) ", with settings" else "")
        repo.saveApps(adjusted, onlyIfExists = false)
        importedSettings?.let { values ->
            val editor = settings.prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putInt(key, value.toInt())
                    is Double -> editor.putFloat(key, value.toFloat())
                    is List<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
        }
        return adjusted to (importedSettings != null)
    }

    /** The export folder, when one is set and still reachable. */
    fun exportDirUri(): Uri? {
        val uri = settings.exportDir?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) uri else null
    }

    fun setExportDir(uri: Uri?) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (permission in resolver.persistedUriPermissions) {
            if (permission.uri != uri) runCatching { resolver.releasePersistableUriPermission(permission.uri, flags) }
        }
        if (uri != null) resolver.takePersistableUriPermission(uri, flags)
        settings.exportDir = uri?.toString()
    }

    /** Writes an export file into the export folder; returns a readable path, or null. */
    fun export(isAuto: Boolean = false): String? {
        if (isAuto && !settings.autoExportOnChanges) return null
        val tree = exportDirUri() ?: return null
        val resolver = context.contentResolver
        val customName = settings.autoExportFileName
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (isAuto) {
            // The previous automatic export is replaced, not piled up.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.endsWith("-auto.json") || (customName != null && name == "$customName.json")) {
                        runCatching {
                            DocumentsContract.deleteDocument(
                                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                            )
                        }
                    }
                }
            }
        }
        val displayName = if (isAuto && customName != null) {
            "$customName.json"
        } else {
            "$EXPORT_FILE_PREFIX-${Instant.now().toString().replace(':', '-')}${if (isAuto) "-auto" else ""}.json"
        }
        val document = DocumentsContract.createDocument(resolver, parent, "application/json", displayName)
            ?: throw SourceError(Tr.get("unexpectedError"))
        resolver.openOutputStream(document)?.use { it.write(generateExportJson().toString(4).toByteArray()) }
            ?: throw SourceError(Tr.get("unexpectedError"))
        AppLog.info("Exported the list of apps as $displayName" + if (isAuto) " (automatic)" else "")
        return DocumentsContract.getTreeDocumentId(tree).substringAfter(':').let { "/$it" }
    }

    /** Many saves in a row lead to one export. */
    fun scheduleAutoExport() {
        if (!settings.autoExportOnChanges) return
        synchronized(scheduler) {
            pendingAutoExport?.cancel(false)
            pendingAutoExport = scheduler.schedule(
                { runCatching { export(isAuto = true) }.onFailure { AppLog.warn("Auto-export failed: $it") } },
                2, TimeUnit.SECONDS,
            )
        }
    }

    companion object {
        const val EXPORT_SCHEMA_VERSION = 2
        const val EXPORT_FILE_PREFIX = "apk-toolbox-sources-export"
    }
}
