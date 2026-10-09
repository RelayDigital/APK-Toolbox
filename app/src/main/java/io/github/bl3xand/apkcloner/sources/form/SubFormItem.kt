package io.github.bl3xand.apkcloner.sources.form

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
