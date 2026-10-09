package io.github.bl3xand.apkcloner.sources.form

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
