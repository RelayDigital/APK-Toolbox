package io.github.bl3xand.apkcloner.sources.form

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
