package io.github.bl3xand.apkcloner.sources.core

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
