package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SliderItem
import io.github.bl3xand.apkcloner.sources.form.SubFormItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf

/**
 * Renders a generated settings form and keeps its values. [onChange] is called with the values
 * and whether every field is valid, on every edit.
 */
class FormView(
    context: Context,
    private val items: List<List<SettingItem>>,
    initialValues: Map<String, Any?> = emptyMap(),
    private val grouped: Boolean = false,
    /** Whether whoever shows the form has drawn a line right above it. */
    private val afterDivider: Boolean = false,
    private val onChange: (values: Map<String, Any?>, valid: Boolean) -> Unit = { _, _ -> },
) : LinearLayout(context) {
    val values: MutableMap<String, Any?> = defaultValuesOf(items).also { defaults ->
        for ((key, value) in initialValues) if (defaults.containsKey(key) && value != null) defaults[key] = value
    }
    private val errors = HashMap<String, Boolean>()
    private var building = true

    val isValid: Boolean get() = errors.values.none { it }

    init {
        orientation = VERTICAL
        if (grouped) {
            // The same rows, gathered under headings with a line between the groups.
            val byGroup = items.groupBy { row -> OptionGroups.of(row.first().key) }
            var first = true
            for (group in OptionGroups.ORDER) {
                val rows = byGroup[group] ?: continue
                if (!first) addDivider()
                addHeading(Tr.get(group), afterDivider = !first || afterDivider)
                first = false
                for (row in rows) for (item in row) addItem(item)
            }
        } else {
            for (row in items) for (item in row) addItem(item)
        }
        building = false
    }

    private fun changed() {
        if (!building) onChange(values, isValid)
    }

    private fun optionLabel(option: String, raw: Boolean): String = when {
        raw -> option
        option.contains(" x ") -> option.split(" x ").joinToString(" x ") { Tr.get(it) }
        else -> Tr.get(option)
    }

    /** Our own shorter name for a setting where there is one, otherwise the usual label. */
    private fun title(item: SettingItem): String =
        (Tr.get("optTitle.${item.key}").takeIf { it != "optTitle.${item.key}" } ?: item.label).replace('\n', ' ')

    /** The short prompt inside an empty field: our own, the item's example, or one by its kind. */
    private fun hint(item: TextItem): String =
        Tr.get("optHint.${item.key}").takeIf { it != "optHint.${item.key}" }
            ?: item.hint?.let { Tr.get("hintExample", it) }
            ?: Tr.get(
                when {
                    item.password -> "hintToken"
                    item.key.contains("regex", ignoreCase = true) -> "hintRegex"
                    item.required -> "hintRequired"
                    else -> "hintOptional"
                },
            )

    /** What the setting is for, where we have written it down. */
    private fun description(item: SettingItem): String? = Tr.get("opt.${item.key}").takeIf { it != "opt.${item.key}" }

    private fun addItem(item: SettingItem) {
        when (item) {
            is SwitchItem -> add(
                context.switchRow(title(item), values[item.key] == true, description(item), enabled = !item.disabled) {
                    values[item.key] = it
                    changed()
                },
            )
            is TextItem -> addText(item)
            is DropdownItem -> addDropdown(item)
            is SliderItem -> addSlider(item)
            is SubFormItem -> addSubForm(item)
        }
    }

    private fun addText(item: TextItem) {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            // A hidden value gets the eye at the end; the link to the help then moves to the start.
            if (item.password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            if (item.helpUrl != null) {
                if (item.password) {
                    setStartIconDrawable(R.drawable.ic_info_outline)
                    setStartIconOnClickListener { context.openUrl(item.helpUrl) }
                } else {
                    endIconMode = TextInputLayout.END_ICON_CUSTOM
                    setEndIconDrawable(R.drawable.ic_info_outline)
                    setEndIconOnClickListener { context.openUrl(item.helpUrl) }
                }
            }
            // Every field says what goes into it, in a word or two.
            hint = hint(item)
        }
        val options = item.autoCompleteOptions
        val edit = if (options != null) {
            MaterialAutoCompleteTextView(layout.context).apply {
                setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, options.distinct()))
            }
        } else TextInputEditText(layout.context)
        edit.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or when {
                item.password -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                item.maxLines > 1 -> InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> 0
            }
            maxLines = item.maxLines
            setText(values[item.key]?.toString() ?: "")
        }
        fun validate(text: String) {
            val error = item.validate(text)
            errors[item.key] = error != null
            // A required field that is still empty is not shouted about before anything was typed.
            layout.error = if (error != null && !(text.isEmpty() && building)) error else null
        }
        validate(edit.text.toString())
        edit.doAfterTextChanged {
            values[item.key] = it?.toString() ?: ""
            validate(it?.toString() ?: "")
            changed()
        }
        layout.addView(edit)
        add(context.settingBlock(title(item) + if (item.required) " *" else "", description(item), layout))
    }

    private fun addDropdown(item: DropdownItem) {
        val layout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle,
        )
        val labels = item.options.map { optionLabel(it.second, item.rawLabels) }
        val edit = MaterialAutoCompleteTextView(layout.context).apply {
            inputType = InputType.TYPE_NULL
            setSimpleItems(labels.toTypedArray())
            val current = item.options.indexOfFirst { it.first == values[item.key] }
            if (current >= 0) setText(labels[current], false)
            setOnItemClickListener { _, _, position, _ ->
                values[item.key] = item.options[position].first
                changed()
            }
        }
        layout.addView(edit)
        add(context.settingBlock(title(item), description(item), layout))
    }

    private fun addSlider(item: SliderItem) {
        fun stopLabel(index: Int): String {
            val text = item.stops[index].second
            return text.toIntOrNull()?.let { Tr.plural("day", it) } ?: Tr.get(text)
        }
        val current = item.stops.indexOfFirst { it.first == values[item.key] }.coerceAtLeast(0)
        val name = title(item)
        val slider = Slider(context).apply {
            valueFrom = 0f
            valueTo = (item.stops.size - 1).toFloat()
            stepSize = 1f
            value = current.toFloat()
            setLabelFormatter { stopLabel(it.toInt()) }
        }
        val block = context.settingBlock("$name: ${stopLabel(current)}", description(item), slider)
        slider.addOnChangeListener { _, value, _ ->
            values[item.key] = item.stops[value.toInt()].first
            (block.getChildAt(0) as android.widget.TextView).text = "$name: ${stopLabel(value.toInt())}"
            changed()
        }
        add(block)
    }

    private fun addSubForm(item: SubFormItem) {
        val container = context.column()
        add(context.label(item.label, com.google.android.material.R.attr.textAppearanceTitleMedium), topMargin = Spacing.ITEM)
        add(container)
        val entries = ArrayList<FormView>()

        fun sync() {
            values[item.key] = entries.map { LinkedHashMap(it.values) }
            errors[item.key] = entries.any { !it.isValid }
            changed()
        }

        fun addEntry(initial: Map<String, Any?>) {
            lateinit var form: FormView
            val card = com.google.android.material.card.MaterialCardView(
                context, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle,
            )
            form = FormView(context, item.items, initial) { _, _ -> sync() }
            val body = context.column(12).apply {
                add(form)
                add(
                    context.textButton(Tr.get("remove")) {
                        entries.remove(form)
                        container.removeView(card)
                        sync()
                    },
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            card.addView(body)
            entries.add(form)
            container.add(card, topMargin = 8)
        }

        @Suppress("UNCHECKED_CAST")
        for (entry in (values[item.key] as? List<Map<String, Any?>>) ?: emptyList()) addEntry(entry)
        add(
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "${Tr.get("add")}: ${item.label}"
                setOnClickListener {
                    addEntry(emptyMap())
                    sync()
                }
            },
            topMargin = 8, width = ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        errors[item.key] = entries.any { !it.isValid }
        gravity = Gravity.NO_GRAVITY
    }
}

/** Which heading a per-app setting goes under. Anything unknown belongs to the source itself. */

object OptionGroups {
    const val SOURCE = "grpSource"
    const val FILES = "grpFiles"
    const val VERSION = "grpVersion"
    const val UPDATES = "grpUpdates"
    const val DISPLAY = "grpDisplay"
    val ORDER = listOf(SOURCE, FILES, VERSION, UPDATES, DISPLAY)

    private val groups = mapOf(
        FILES to listOf(
            "apkFilterRegEx", "invertAPKFilter", "autoApkFilterByArch", "includeZips", "zippedApkFilterRegEx",
            "includeTarballs", "tarballedApkFilterRegEx",
        ),
        VERSION to listOf(
            "versionExtractionRegEx", "matchGroupToUse", "versionDetection", "releaseDateAsVersion", "useVersionCodeAsOSVersion",
        ),
        UPDATES to listOf(
            "trackOnly", "exemptFromBackgroundUpdates", "skipUpdateNotifications", "refreshBeforeDownload",
            "minimumUpdateAgeDays", "shizukuPretendToBeGooglePlay", "allowInsecure", "allowedSigningCertHashes",
        ),
        DISPLAY to listOf("appName", "appAuthor", "about"),
    ).flatMap { (group, keys) -> keys.map { it to group } }.toMap()

    fun of(key: String): String = groups[key] ?: SOURCE
}
