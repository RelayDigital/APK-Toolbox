package io.github.bl3xand.apkcloner.sources.model

/** Type-safe reads from an app's settings map; values may be stored as strings. */
class TypedSettings(private val raw: Map<String, Any?>) {
    fun getBool(key: String, defaultValue: Boolean = false): Boolean = when (val value = raw[key]) {
        null -> defaultValue
        is Boolean -> value
        is String -> value == "true"
        else -> defaultValue
    }

    fun getIntOrNull(key: String): Int? = when (val value = raw[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    fun getStringOrNull(key: String): String? = when (val value = raw[key]) {
        null -> null
        is String -> value.ifEmpty { null }
        else -> value.toString()
    }

    fun getString(key: String, defaultValue: String = ""): String = getStringOrNull(key) ?: defaultValue

    override fun toString(): String = raw.toString()
}
