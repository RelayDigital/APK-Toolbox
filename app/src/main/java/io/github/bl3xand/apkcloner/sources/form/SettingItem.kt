package io.github.bl3xand.apkcloner.sources.form

import io.github.bl3xand.apkcloner.sources.core.Tr

/**
 * One field of a generated settings form. [labelKey] is a text key; [labelSuffix] and
 * [labelArgs] cover the few composed labels.
 */
sealed class SettingItem(
    val key: String,
    var labelKey: String,
    var defaultValue: Any?,
) {
    var labelArgs: List<String> = emptyList()
    var labelOverride: (() -> String)? = null

    val label: String get() = labelOverride?.invoke() ?: Tr.get(labelKey, *labelArgs.toTypedArray())

    /** Coerces a stored value to this item's type. */
    abstract fun ensureType(value: Any?): Any?

    abstract fun copy(): SettingItem

    protected fun <T : SettingItem> T.copyLabelFrom(other: SettingItem): T {
        labelArgs = other.labelArgs
        labelOverride = other.labelOverride
        return this
    }
}

class TextItem(
    key: String,
    labelKey: String,
    value: String = "",
    var required: Boolean = true,
    val maxLines: Int = 1,
    val hint: String? = null,
    val password: Boolean = false,
    val autoCompleteOptions: List<String>? = null,
    val helpUrl: String? = null,
    val validators: List<(String?) -> String?> = emptyList(),
) : SettingItem(key, labelKey, value) {
    override fun ensureType(value: Any?): Any? = value.toString()

    override fun copy(): TextItem = TextItem(
        key, labelKey, defaultValue as String, required, maxLines, hint, password,
        autoCompleteOptions, helpUrl, validators,
    ).copyLabelFrom(this)

    fun validate(value: String?): String? {
        if (required && value.isNullOrBlank()) return Tr.get("requiredInBrackets")
        for (validator in validators) validator(value)?.let { return it }
        return null
    }
}

/** [options] maps a stored value to a text key (or, with [rawLabels], to a ready label). */
class DropdownItem(
    key: String,
    labelKey: String,
    val options: List<Pair<String, String>>,
    value: String = "",
    val helpUrl: String? = null,
    val rawLabels: Boolean = false,
) : SettingItem(key, labelKey, value) {
    override fun ensureType(value: Any?): Any? = value.toString()

    override fun copy(): DropdownItem =
        DropdownItem(key, labelKey, options, defaultValue as String, helpUrl, rawLabels).copyLabelFrom(this)
}

class SwitchItem(
    key: String,
    labelKey: String,
    value: Boolean = false,
    var disabled: Boolean = false,
) : SettingItem(key, labelKey, value) {
    override fun ensureType(value: Any?): Any? = when (value) {
        is Boolean -> value
        is String -> value.lowercase() == "true"
        else -> false
    }

    override fun copy(): SwitchItem =
        SwitchItem(key, labelKey, defaultValue as Boolean, disabled).copyLabelFrom(this)
}

/** [stops] maps a stored value to a label: a text key, or a plain day count. */
class SliderItem(
    key: String,
    labelKey: String,
    val stops: List<Pair<String, String>>,
    value: String = "",
) : SettingItem(key, labelKey, value) {
    override fun ensureType(value: Any?): Any? = value.toString()

    override fun copy(): SliderItem =
        SliderItem(key, labelKey, stops, defaultValue as String).copyLabelFrom(this)
}

/** A repeatable group of rows; its value is a list of maps. */
class SubFormItem(
    key: String,
    labelKey: String,
    val items: List<List<SettingItem>>,
    value: List<Map<String, Any?>> = emptyList(),
) : SettingItem(key, labelKey, value) {
    override fun ensureType(value: Any?): Any? = if (value is List<*>) value else emptyList<Any?>()

    override fun copy(): SubFormItem {
        @Suppress("UNCHECKED_CAST")
        return SubFormItem(key, labelKey, cloneItems(items), defaultValue as List<Map<String, Any?>>)
            .copyLabelFrom(this)
    }
}

fun cloneItems(items: List<List<SettingItem>>): List<List<SettingItem>> =
    items.map { row -> row.map { it.copy() } }

fun defaultValuesOf(items: List<List<SettingItem>>): MutableMap<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for (row in items) {
        for (item in row) {
            result[item.key] = when (item) {
                is SwitchItem -> item.defaultValue ?: false
                is SubFormItem -> item.defaultValue ?: emptyList<Any?>()
                else -> item.defaultValue ?: ""
            }
        }
    }
    return result
}
