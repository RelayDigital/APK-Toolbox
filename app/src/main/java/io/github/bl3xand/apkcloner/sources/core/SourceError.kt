package io.github.bl3xand.apkcloner.sources.core

import java.net.SocketException
import java.time.Instant

/**
 * Base error of the sources code. [code] selects a localized message, [data] carries its
 * arguments; a few codes keep the raw message they were created with.
 */
open class SourceError(
    private val rawMessage: String = "",
    val code: String = "UNKNOWN",
    val unexpected: Boolean = false,
    val data: Map<String, Any?> = emptyMap(),
    /** The app or source URL the error relates to; appended to [toString] only. */
    var url: String? = null,
) : Exception(rawMessage) {

    override val message: String
        get() = when (code) {
            "UNKNOWN", "UNEXPECTED", "CHECK_UPDATES_FAILED", "HTTP_ERROR" -> rawMessage
            else -> localizeErrorCode(code, data)
        }

    fun withUrlContext(contextUrl: String?): SourceError {
        if (url.isNullOrEmpty() && !contextUrl.isNullOrEmpty()) url = contextUrl
        return this
    }

    override fun toString(): String = if (!url.isNullOrEmpty()) "$message ($url)" else message
}

/** A non-2xx response during a file download; the status decides whether a retry makes sense. */
class HttpStatusError(val statusCode: Int, message: String) : SourceError(message, code = "HTTP_ERROR")

class RateLimitError(val remainingMinutes: Int) :
    SourceError(code = "RATE_LIMIT", data = mapOf("remainingMinutes" to remainingMinutes))

class InvalidUrlError(sourceName: String) :
    SourceError(code = "INVALID_URL", data = mapOf("sourceName" to sourceName))

class CredsNeededError(sourceName: String) :
    SourceError(code = "CREDS_NEEDED", data = mapOf("sourceName" to sourceName))

class NoReleasesError(note: String? = null) :
    SourceError(code = "NO_RELEASES", data = mapOf("note" to (note ?: "")))

class NoApkError : SourceError(code = "NO_APK")

/** The latest release is younger than the minimum update age and no older one is available. */
class MinUpdateAgeError(releaseDate: Instant, minAgeDays: Int) : SourceError(
    code = "MIN_UPDATE_AGE",
    data = mapOf("releaseDate" to releaseDate.toString(), "minAgeDays" to minAgeDays),
)

/** RuStore lists some apps only as cards pulled from elsewhere and hosts no APK for them. */
class RuStoreAggregatedAppError : SourceError(code = "RUSTORE_AGGREGATED_APP")

class NoVersionError : SourceError(code = "NO_VERSION")

class UnsupportedUrlError : SourceError(code = "UNSUPPORTED_URL")

class DowngradeError(currentVersionCode: Long, newVersionCode: Long) : SourceError(
    code = "DOWNGRADE",
    data = mapOf("currentVersionCode" to currentVersionCode, "newVersionCode" to newVersionCode),
)

class InstallError(errorCode: Int, statusName: String? = null) : SourceError(
    code = "INSTALL_FAILED",
    data = mapOf("errorCode" to errorCode, "message" to statusName),
)

/** The downloaded APK is not signed with an expected certificate. */
class SigningCertMismatchError(
    val hardBlock: Boolean,
    val expected: Set<String>,
    val actual: Set<String>,
) : SourceError(
    code = "SIGNING_CERT_MISMATCH",
    data = mapOf("hardBlock" to hardBlock, "expected" to expected.toList(), "actual" to actual.toList()),
)

class IdChangedError(newId: String) : SourceError(code = "ID_CHANGED", data = mapOf("newId" to newId))

class RepositoryRenamedError(val oldUrl: String, val newUrl: String) :
    SourceError(code = "REPO_RENAMED", data = mapOf("oldUrl" to oldUrl, "newUrl" to newUrl))

class NotImplementedSourceError : SourceError(code = "NOT_IMPLEMENTED")

/** Collects one error per app and groups the apps that failed in the same way. */
class MultiAppMultiError : SourceError(code = "MULTI_ERROR", unexpected = true) {
    val rawErrors = LinkedHashMap<String, Any>()
    val idsByErrorString = LinkedHashMap<String, MutableList<String>>()
    val appIdNames = LinkedHashMap<String, String>()

    fun add(appId: String, error: Any, appName: String? = null) {
        // The OS-level text of a socket failure is long; its short message is enough.
        val stored: Any = if (error is SocketException) error.message ?: error.toString() else error
        rawErrors[appId] = stored
        val text = errorText(stored)
        idsByErrorString.values.forEach { it.remove(appId) }
        idsByErrorString.entries.removeIf { it.value.isEmpty() }
        val ids = idsByErrorString.remove(text) ?: mutableListOf()
        idsByErrorString[text] = ids
        ids.add(appId)
        if (appName != null) appIdNames[appId] = appName
    }

    private fun displayName(appId: String, includeIdsWithNames: Boolean): String {
        val name = appIdNames[appId] ?: return appId
        return if (includeIdsWithNames) "$name ($appId)" else name
    }

    fun errorString(appId: String, includeIdsWithNames: Boolean = false): String =
        "${displayName(appId, includeIdsWithNames)}: ${rawErrors[appId]?.let(::errorText)}"

    fun errorsAppsString(
        errString: String,
        appIds: List<String>,
        includeIdsWithNames: Boolean = false,
    ): String = "$errString [${listToFriendlyString(appIds.map { displayName(it, includeIdsWithNames) })}]"

    override val message: String
        get() = idsByErrorString.entries.joinToString("\n\n") { errorsAppsString(it.key, it.value) }

    override fun toString(): String = message
}

/** A download the user cancelled; never reported as a failure. */
class CancellationSignal : Exception()

fun errorText(error: Any?): String = when (error) {
    null -> ""
    is SourceError -> error.toString()
    is Throwable -> error.message ?: error.toString()
    else -> error.toString()
}

/** Rethrows [error] as is when it is ours, otherwise wraps it as an unexpected error. */
fun rethrowOrWrap(error: Throwable, sourceName: String? = null): Nothing {
    if (error is SourceError || error is CancellationSignal) throw error
    val text = error.message ?: error.toString()
    throw SourceError(
        if (sourceName != null) "$sourceName: $text" else text,
        code = "UNEXPECTED",
        unexpected = true,
    ).also { it.initCause(error) }
}

fun localizeErrorCode(code: String, data: Map<String, Any?>): String = when (code) {
    "NO_RELEASES" -> {
        val note = data["note"] as? String
        if (!note.isNullOrEmpty()) "${Tr.get("noReleaseFound")}\n\n$note" else Tr.get("noReleaseFound")
    }
    "RATE_LIMIT" -> Tr.plural("tooManyRequestsTryAgainInMinutes", (data["remainingMinutes"] as? Int) ?: 0)
    "INVALID_URL" -> Tr.get("invalidURLForSource", data["sourceName"]?.toString() ?: "")
    "CREDS_NEEDED" -> Tr.get("requiresCredentialsInSettings", data["sourceName"]?.toString() ?: "")
    "NO_APK" -> Tr.get("noAPKFound")
    "MIN_UPDATE_AGE" -> Tr.get("releaseTooYoungForMinAge", "${data["minAgeDays"] ?: ""}")
    "RUSTORE_AGGREGATED_APP" -> Tr.get("rustoreAggregatedAppNoApk")
    "NO_VERSION" -> Tr.get("noVersionFound")
    "UNSUPPORTED_URL" -> Tr.get("urlMatchesNoSource")
    "DOWNGRADE" -> "${Tr.get("cantInstallOlderVersion")} (versionCode " +
        "${data["currentVersionCode"] ?: "?"} → ${data["newVersionCode"] ?: "?"})"
    "INSTALL_FAILED" -> data["message"]?.toString() ?: Tr.get("installFailed")
    "SIGNING_CERT_MISMATCH" ->
        if (data["hardBlock"] == true) Tr.get("signingCertMismatchHardBlock")
        else Tr.get("signingCertMismatchMessage")
    "ID_CHANGED" -> "${Tr.get("appIdMismatch")} - ${data["newId"] ?: ""}"
    "REPO_RENAMED" -> Tr.get("repoRenamed")
    "NOT_IMPLEMENTED" -> Tr.get("functionNotImplemented")
    else -> data["message"]?.toString() ?: Tr.get("unexpectedError")
}

/** "A", "A and B", "A, B, and C" (the serial comma only in English). */
fun listToFriendlyString(list: List<String>): String {
    val and = Tr.get("and")
    if (list.size == 2) return "${list[0]} $and ${list[1]}"
    val english = Tr.languageCode == "en"
    return list.mapIndexed { index, value ->
        value + when (index) {
            list.size - 1 -> ""
            list.size - 2 -> "${if (english) "," else ""} $and "
            else -> ", "
        }
    }.joinToString("")
}
