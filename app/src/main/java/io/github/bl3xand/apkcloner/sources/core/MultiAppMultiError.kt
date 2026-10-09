package io.github.bl3xand.apkcloner.sources.core

import java.net.SocketException

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
