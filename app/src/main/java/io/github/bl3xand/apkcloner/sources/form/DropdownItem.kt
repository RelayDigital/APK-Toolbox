package io.github.bl3xand.apkcloner.sources.form

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
