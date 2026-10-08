package io.github.bl3xand.apkcloner.sources.model

import org.json.JSONArray
import org.json.JSONObject

/** Converts between org.json trees and plain Kotlin maps, lists and scalars. */
object JsonValues {
    fun toKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap(value)
        is JSONArray -> toList(value)
        else -> value
    }

    fun toMap(json: JSONObject): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (key in json.keys()) result[key] = toKotlin(json.opt(key))
        return result
    }

    fun toList(json: JSONArray): List<Any?> = (0 until json.length()).map { toKotlin(json.opt(it)) }

    fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) obj.put(k.toString(), toJson(v))
        }
        is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
        is Array<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
        else -> value
    }

    fun parse(text: String): Any? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> toKotlin(JSONObject(trimmed))
            trimmed.startsWith("[") -> toKotlin(JSONArray(trimmed))
            else -> toKotlin(JSONArray("[$trimmed]").opt(0))
        }
    }

    fun parseObject(text: String): MutableMap<String, Any?> = toMap(JSONObject(text))

    fun stringify(value: Any?): String = when (val json = toJson(value)) {
        is JSONObject -> json.toString()
        is JSONArray -> json.toString()
        is String -> JSONObject.quote(json)
        else -> json.toString()
    }
}

/** Reads a nested value: `json.dig("data", "versions", 0, "version")`. */
fun Any?.dig(vararg path: Any): Any? {
    var current: Any? = this
    for (step in path) {
        current = when {
            current is Map<*, *> && step is String -> current[step]
            current is List<*> && step is Int -> current.getOrNull(step)
            else -> return null
        }
    }
    return current
}

fun Any?.asMap(): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?>
}

fun Any?.asList(): List<Any?>? = this as? List<Any?>
