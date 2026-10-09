package io.github.bl3xand.apkcloner.sources.form

import io.github.bl3xand.apkcloner.sources.core.Tr

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
