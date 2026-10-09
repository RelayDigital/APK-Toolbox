package io.github.bl3xand.apkcloner.sources.core

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
