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
