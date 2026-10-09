package io.github.bl3xand.apkcloner.sources.form

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
