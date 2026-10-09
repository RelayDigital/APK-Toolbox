package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.ui.AppIcons
import io.github.bl3xand.apkcloner.ui.Messages
import io.github.bl3xand.apkcloner.ui.bind
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.install.InstallPrompts
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** One row of [SourcesDialogs.pickFromList]. */
class PickItem(val key: String, val title: String, val description: String, val badge: String?)

/** One row of [SourcesDialogs.showActions]. */
class SheetAction(val icon: Int, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** The dialogs of the Sources tab. [context] must be an activity (or themed like one). */
class SourcesDialogs(private val context: Context) : InstallPrompts {
    private val repo = SourcesRepository.get(context)
    private val settings = repo.settings

    private fun body(): LinearLayout = context.column(Spacing.SHEET).apply { setPadding(context.dp(Spacing.SHEET), context.dp(2), context.dp(Spacing.SHEET), 0) }

    // ---- install prompts -----------------------------------------------------------------

    override suspend fun pickFile(
        app: TrackedApp,
        choices: List<NamedUrl>,
        preselected: NamedUrl?,
        anyAsset: Boolean,
    ): NamedUrl? = withContext(Dispatchers.Main) {
        val abis = Build.SUPPORTED_ABIS.toList()
        val note = if (abis.size == 1) Tr.get("deviceSupportsXArch", abis[0])
        else "${Tr.get("deviceSupportsFollowingArchs")} ${abis.joinToString(", ")}"
        val picked = pickFromList(
            if (anyAsset) Tr.get("selectX", Tr.get("releaseAsset").lowercase()) else Tr.get("pickAnAPK"),
            choices.mapIndexed { index, choice ->
                PickItem(index.toString(), choice.name, "", Tr.get("fltSuggested").takeIf { choice == preselected })
            },
            filterable = false, note = note, header = repo.entry(app.id)?.let(context::appCard),
        )?.firstOrNull()?.toIntOrNull()
        picked?.let { choices[it] }
    }

    override suspend fun confirmOrigin(sourceUrl: String, apkUrl: String): Boolean = withContext(Dispatchers.Main) {
        context.confirm(
            Tr.get("warning"),
            Tr.get("sourceIsXButPackageFromYPrompt", android.net.Uri.parse(sourceUrl).host ?: sourceUrl,
                android.net.Uri.parse(apkUrl.lines().first()).host ?: apkUrl),
        )
    }

    override suspend fun signingMismatch(
        appName: String,
        expected: Set<String>,
        actual: Set<String>,
        hardBlock: Boolean,
    ): Boolean = withContext(Dispatchers.Main) {
        val view = body().apply {
            fun hashes(title: String, values: Set<String>) {
                add(context.sectionTitle(title), topMargin = 12)
                for (hash in values.ifEmpty { setOf(Tr.get("unknown")) }) {
                    add(
                        context.label(hash, com.google.android.material.R.attr.textAppearanceBodySmall).apply {
                            typeface = Typeface.MONOSPACE
                            setTextIsSelectable(true)
                        },
                        topMargin = 4,
                    )
                }
            }
            add(context.label(Tr.get(if (hardBlock) "signingCertMismatchHardBlockBody" else "signingCertMismatchWarningBody", appName)))
            hashes(Tr.get("expectedSigningCertHash"), expected)
            hashes(Tr.get("actualSigningCertHash"), actual)
        }
        if (hardBlock) {
            context.confirm(Tr.get("signingCertMismatchTitle"), confirmText = Tr.get("ok"), cancelText = null, view = view.scrollable())
            false
        } else {
            context.confirm(
                Tr.get("signingCertMismatchTitle"), confirmText = Tr.get("installAnyway"),
                view = view.scrollable(),
            )
        }
    }

    // ---- list actions ----------------------------------------------------------------------

    /** Returns (uninstall, removeFromList), or null when cancelled. */
    /**
     * Both "Uninstall from device" and "Remove from list" are ticked by default; the user unticks
     * whichever they want to keep.
     */
    suspend fun askRemove(apps: List<TrackedApp>): Pair<Boolean, Boolean>? {
        val canUninstall = apps.any { it.installedVersion != null && !it.settings.getBool(SettingKeys.TRACK_ONLY) }
        var removeEntry = true
        var uninstall = true
        val view = if (!canUninstall) null else body().apply {
            add(context.switchRow(Tr.get("uninstallFromDevice"), uninstall) { uninstall = it })
            add(context.switchRow(Tr.get("removeFromObtainium"), removeEntry) { removeEntry = it })
        }
        val confirmed = context.confirm(
            Tr.plural("removeAppQuestion", apps.size), view = view,
            header = apps.singleOrNull()?.let { repo.entry(it.id) }?.let(context::appCard),
        )
        if (!confirmed) return null
        return (uninstall && canUninstall) to (removeEntry || !canUninstall)
    }

    /** Lets the user tick what to update, install or mark; null when cancelled. */
    suspend fun askBulkUpdate(updates: List<String>, installs: List<String>, trackOnly: List<String>): List<String>? {
        val chosen = LinkedHashSet<String>(updates + trackOnly)
        if (updates.isEmpty()) chosen.addAll(installs)
        val all = updates + installs + trackOnly
        val boxes = ArrayList<Pair<String, CheckBox>>()
        val view = body().apply {
            fun section(title: String, ids: List<String>) {
                if (ids.isEmpty()) return
                add(context.sectionTitle("$title (${ids.size})"), topMargin = 12)
                for (id in ids) {
                    val entry = repo.entry(id) ?: continue
                    val box = CheckBox(context).apply {
                        text = "${entry.name}  ·  ${Tr.get("byX", entry.author)}"
                        isChecked = id in chosen
                        setOnCheckedChangeListener { _, checked -> if (checked) chosen.add(id) else chosen.remove(id) }
                    }
                    boxes.add(id to box)
                    add(box)
                }
            }
            add(
                context.textButton(Tr.get("selectAll")) {
                    val select = chosen.size != all.size
                    boxes.forEach { it.second.isChecked = select }
                },
                width = ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            section(Tr.get("updates"), updates)
            section(Tr.get("nonInstalledApps"), installs)
            section(Tr.get("trackOnly"), trackOnly)
        }
        val confirmed = context.confirm(
            Tr.get("changeX", Tr.plural("apps", all.size).lowercase()), view = view.scrollable(),
        )
        return if (confirmed) chosen.toList() else null
    }

    /** Narrowing the list: by state, by source and by category, all as chips. */
    suspend fun askFilter(current: AppsFilter): AppsFilter? {
        var filter = current
        fun chip(text: String, checked: Boolean, color: Int? = null, onChange: (Boolean) -> Unit) =
            context.filterChip(text, checked, onChange).apply {
                if (color != null) {
                    setChipIconResource(io.github.bl3xand.apkcloner.R.drawable.ic_label)
                    chipIconTint = android.content.res.ColorStateList.valueOf(color or 0xFF000000.toInt())
                    isChipIconVisible = true
                }
            }
        val view = body().apply {
            add(context.sectionTitle(Tr.get("fltState")))
            add(
                ChipGroup(context).apply {
                    addView(chip(Tr.get("fltUpToDate"), filter.includeUpToDate) { filter = filter.copy(includeUpToDate = it) })
                    addView(chip(Tr.get("fltNotInstalled"), filter.includeNonInstalled) { filter = filter.copy(includeNonInstalled = it) })
                },
                topMargin = Spacing.UNDER_HEADING,
            )
            // Only the sources that are actually in use.
            val used = repo.all().mapNotNull { it.sourceType }.distinct()
            if (used.size > 1) {
                addDivider()
                addHeading(Tr.get("fltSource"))
                val group = ChipGroup(context).apply { isSingleSelection = true }
                val names = SourceRegistry.sources.associate { it.sourceIdentifier to it.name }
                group.addView(chip(Tr.get("fltAll"), filter.source.isEmpty()) { if (it) filter = filter.copy(source = "") })
                for (id in used) {
                    group.addView(chip(names[id] ?: id, filter.source == id) { if (it) filter = filter.copy(source = id) })
                }
                add(group, topMargin = Spacing.UNDER_HEADING)
            }
            val categories = settings.categories
            if (categories.isNotEmpty()) {
                addDivider()
                addHeading(Tr.get("categories"))
                val group = ChipGroup(context)
                for ((name, color) in categories.entries.sortedBy { it.key.lowercase() }) {
                    group.addView(
                        chip(name, name in filter.categories, color) { checked ->
                            filter = filter.copy(categories = if (checked) filter.categories + name else filter.categories - name)
                        },
                    )
                }
                add(group, topMargin = Spacing.UNDER_HEADING)
            }
        }
        if (!context.confirm(Tr.get("filterApps"), view = view.scrollable())) return null
        return filter
    }

    /** Chips for every known category; with [allowCreate] a new one can be added on the spot. */
    fun categorySelector(
        selected: Set<String>,
        allowCreate: Boolean = true,
        showTitle: Boolean = true,
        onChange: (Set<String>) -> Unit,
    ): View {
        val current = LinkedHashSet(selected)
        val group = ChipGroup(context)
        fun rebuild() {
            group.removeAllViews()
            val categories = settings.categories
            for ((name, color) in categories.entries.sortedBy { it.key.lowercase() }) {
                group.addView(
                    context.filterChip(name, name in current) { checked ->
                        if (checked) current.add(name) else current.remove(name)
                        onChange(current.toSet())
                    }.apply {
                        // The colour of the category, as on the rows of the list.
                        setChipIconResource(io.github.bl3xand.apkcloner.R.drawable.ic_label)
                        chipIconTint = android.content.res.ColorStateList.valueOf(color or 0xFF000000.toInt())
                        isChipIconVisible = true
                        // Held down, a category can be removed right here.
                        setOnLongClickListener {
                            confirmCategoryRemoval(name) {
                                current.remove(name)
                                onChange(current.toSet())
                                rebuild()
                            }
                            true
                        }
                    },
                )
            }
            if (allowCreate) {
                group.addView(
                    Chip(context).apply {
                        text = Tr.get("addCategory")
                        setChipIconResource(io.github.bl3xand.apkcloner.R.drawable.ic_add)
                        setEnsureMinTouchTargetSize(false)
                        setOnClickListener {
                            askNewCategory { name ->
                                current.add(name)
                                onChange(current.toSet())
                                rebuild()
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        return context.column().apply {
            if (showTitle) add(context.sectionTitle(Tr.get("categories")))
            if (settings.categories.isEmpty() && !allowCreate) {
                add(context.label(Tr.get("noCategories"), colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant), topMargin = 4)
            }
            add(group, topMargin = if (showTitle) Spacing.UNDER_HEADING else 0)
        }
    }

    fun promptText(title: String, hint: String, initial: String = "", onResult: (String) -> Unit) {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            setSingleLine()
            setText(initial)
        }
        layout.addView(edit)
        context.showSheet(title, content = body().apply { add(layout) }, positive = Tr.get("continue"), negative = Tr.get("cancel")) {
            onResult(edit.text?.toString() ?: "")
            true
        }
    }

    /** Rename, recolour or delete categories. */
    /** Colours a new category starts with. */
    private val palette = listOf(
        0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD, 0xFF7986CB, 0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1,
        0xFF4DB6AC, 0xFF81C784, 0xFFAED581, 0xFFDCE775, 0xFFFFD54F, 0xFFFFB74D, 0xFFFF8A65, 0xFFA1887F,
    ).map { it.toInt() }

    private fun dot(color: Int, size: Int): View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color or 0xFF000000.toInt())
        }
        layoutParams = ViewGroup.MarginLayoutParams(context.dp(size), context.dp(size))
    }

    /**
     * The colour picker used wherever a category gets its colour: the palette, and under it the
     * chosen colour shown in a frame of its own. [onChange] hears every pick.
     */
    private fun colorPicker(initial: Int, onChange: (Int) -> Unit): View {
        val preview = dot(initial, PREVIEW_DOT)
        // The chosen colour, in a square frame of its own.
        val frame = com.google.android.material.card.MaterialCardView(
            context, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle,
        ).apply {
            addView(
                preview,
                android.widget.FrameLayout.LayoutParams(context.dp(PREVIEW_DOT), context.dp(PREVIEW_DOT), android.view.Gravity.CENTER),
            )
        }
        val field = PaletteView(context) { color ->
            (preview.background as android.graphics.drawable.GradientDrawable).setColor(color)
            onChange(color)
        }
        return context.column().apply {
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(PALETTE_HEIGHT)))
            addView(
                frame,
                LinearLayout.LayoutParams(context.dp(PREVIEW_FRAME), context.dp(PREVIEW_FRAME)).apply {
                    topMargin = context.dp(Spacing.BLOCK)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                },
            )
        }
    }

    private fun pickColor(current: Int, onPicked: (Int) -> Unit) {
        var chosen = current or 0xFF000000.toInt()
        val view = body().apply { add(colorPicker(chosen) { chosen = it }) }
        context.showSheet(Tr.get("catColor"), content = view, positive = Tr.get("ok"), negative = Tr.get("cancel")) {
            onPicked(chosen)
            true
        }
    }

    /** A small question before a category is removed; apps simply lose it. */
    private fun confirmCategoryRemoval(name: String, onRemoved: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(Tr.get("catRemoveQuestion", name))
            .setPositiveButton(Tr.get("remove")) { _, _ ->
                repo.setCategories(settings.categories - name)
                onRemoved()
            }
            .setNegativeButton(Tr.get("cancel"), null)
            .show()
    }

    /** A new category: its name and its colour in one sheet. Names already taken are refused. */
    private fun askNewCategory(onCreated: (String) -> Unit) {
        var color = palette.random()
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = Tr.get("catName") }
        val edit = TextInputEditText(layout.context).apply { setSingleLine() }
        layout.addView(edit)
        val view = body().apply {
            add(layout)
            add(context.label(Tr.get("catColor"), com.google.android.material.R.attr.textAppearanceTitleMedium), topMargin = Spacing.BLOCK)
            add(colorPicker(color) { color = it }, topMargin = Spacing.UNDER_HEADING)
        }
        context.showSheet(Tr.get("addCategory"), content = view, positive = Tr.get("add"), negative = Tr.get("cancel")) {
            val name = edit.text?.toString()?.trim().orEmpty()
            when {
                name.isEmpty() -> {
                    layout.error = Tr.get("hintRequired")
                    false
                }
                name in settings.categories -> {
                    layout.error = Tr.get("catExists")
                    false
                }
                else -> {
                    repo.setCategories(settings.categories + (name to color))
                    onCreated(name)
                    true
                }
            }
        }
    }

    /** Categories: a tap on the colour changes it, on the name renames, the bin removes. */
    fun showCategoryManager(onChanged: () -> Unit) {
        val list = context.column()
        fun rebuild() {
            list.removeAllViews()
            val categories = settings.categories
            if (categories.isEmpty()) {
                list.add(context.label(Tr.get("noCategories"), colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            categories.entries.sortedBy { it.key.lowercase() }.forEachIndexed { index, (name, color) ->
                if (index > 0) list.add(com.google.android.material.divider.MaterialDivider(context))
                val row = LinearLayout(context).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(Spacing.ROW)
                }
                row.addView(
                    dot(color, 28).apply {
                        setOnClickListener {
                            pickColor(color) { picked ->
                                repo.setCategories(settings.categories + (name to picked))
                                rebuild()
                                onChanged()
                            }
                        }
                    },
                    LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply { marginEnd = context.dp(16) },
                )
                row.addView(
                    context.label(name, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setOnClickListener {
                            promptText(Tr.get("category"), Tr.get("catName"), name) { newName ->
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty() && trimmed != name) {
                                    val renamed = settings.categories.mapKeys { if (it.key == name) trimmed else it.key }
                                    // Apps keep the category under its new name.
                                    val apps = repo.all().map { it.app }.filter { name in it.categories }
                                        .map { it.copy(categories = it.categories.map { c -> if (c == name) trimmed else c }) }
                                    settings.categories = renamed
                                    repo.saveApps(apps)
                                    rebuild()
                                    onChanged()
                                }
                            }
                        }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    com.google.android.material.button.MaterialButton(
                        context, null, com.google.android.material.R.attr.materialIconButtonStyle,
                    ).apply {
                        setIconResource(io.github.bl3xand.apkcloner.R.drawable.ic_delete)
                        iconTint = android.content.res.ColorStateList.valueOf(context.themeColor(androidx.appcompat.R.attr.colorError))
                        contentDescription = Tr.get("remove")
                        setOnClickListener {
                            confirmCategoryRemoval(name) {
                                rebuild()
                                onChanged()
                            }
                        }
                    },
                )
                list.add(row)
            }
        }
        rebuild()
        val view = body().apply {
            add(context.label(Tr.get("catHint"), colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant))
            add(list, topMargin = 8)
            add(
                context.actionButton(Tr.get("addCategory"), io.github.bl3xand.apkcloner.R.drawable.ic_add) {
                    askNewCategory {
                        rebuild()
                        onChanged()
                    }
                },
                topMargin = 12,
            )
        }
        context.showSheet(Tr.get("categories"), content = view, positive = Tr.get("close"))
    }

    fun showChanges(entry: AppEntry) {
        val app = entry.app
        val source = repo.sourceOf(app)
        var changeLog = app.changeLog?.trim()?.takeIf { it.isNotEmpty() }
        var url: String? = null
        // A change log that is nothing but a link is a link.
        if (changeLog != null && Regex("^(http|ftp|https)://\\S+$").matches(changeLog)) {
            url = changeLog
            changeLog = null
        }
        url = url ?: app.releaseUrl?.takeIf { it.isNotEmpty() } ?: source.changeLogPageFromStandardUrl(app.url)
        if (changeLog == null) {
            url?.let(context::openUrl)
            return
        }
        val view = body().apply {
            if (url != null) {
                add(
                    context.label(url, colorAttr = androidx.appcompat.R.attr.colorPrimary).apply {
                        setTypeface(null, Typeface.ITALIC)
                        setOnClickListener { context.openUrl(url) }
                    },
                )
            }
            add(
                context.label(
                    if (source.changeLogIfAnyIsMarkDown) {
                        markdownToSpanned(changeLog, android.net.Uri.parse(app.url).let { "${it.scheme}://${it.host}" })
                    } else changeLog,
                ).apply {
                    enableLinks()
                    setTextIsSelectable(true)
                },
                topMargin = 12,
            )
        }
        context.showSheet(
            "${Tr.get("detWhatsNew")} · ${app.latestVersion}", content = view, positive = Tr.get("close"), header = context.appCard(entry),
        )
    }

    /**
     * A readable list to pick from. With [multiple] every row has a check box and the choice is
     * confirmed with a button; otherwise a tap on a row picks it. Null when cancelled.
     */
    suspend fun pickFromList(
        title: String,
        items: List<PickItem>,
        multiple: Boolean = false,
        filterable: Boolean = true,
        note: String? = null,
        header: View? = null,
    ): List<String>? =
        suspendCancellableCoroutine { continuation ->
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
            Messages.track(dialog)
            val chosen = LinkedHashSet<String>()
            var answered = false
            fun finish(result: List<String>?) {
                if (answered) return
                answered = true
                dialog.dismiss()
                if (continuation.isActive) continuation.resume(result)
            }
            val list = context.column()
            fun rebuild(query: String) {
                list.removeAllViews()
                val words = query.lowercase().split(' ').filter { it.isNotBlank() }
                for (item in items) {
                    val haystack = "${item.title} ${item.description} ${item.key}".lowercase()
                    if (words.any { !haystack.contains(it) }) continue
                    // Every choice is a card of its own, so that it is clear where one ends and
                    // the next begins.
                    val card = com.google.android.material.card.MaterialCardView(
                        context, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle,
                    )
                    val row = LinearLayout(context).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(Spacing.ROW)
                        setPadding(context.dp(Spacing.BLOCK), context.dp(CHOICE_PADDING), context.dp(Spacing.BLOCK), context.dp(CHOICE_PADDING))
                    }
                    card.addView(row)
                    val texts = context.column().apply {
                        add(context.label(item.title, com.google.android.material.R.attr.textAppearanceTitleMedium))
                        if (item.description.isNotBlank()) {
                            add(
                                context.label(item.description, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant).apply {
                                    maxLines = 3
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                },
                                topMargin = 2,
                            )
                        }
                        if (item.badge != null) {
                            add(
                                context.label(item.badge, com.google.android.material.R.attr.textAppearanceLabelMedium, androidx.appcompat.R.attr.colorPrimary),
                                topMargin = 4,
                            )
                        }
                    }
                    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (multiple) {
                        val box = CheckBox(context).apply {
                            isChecked = item.key in chosen
                            setOnCheckedChangeListener { _, checked -> if (checked) chosen.add(item.key) else chosen.remove(item.key) }
                        }
                        row.addView(box)
                        card.setOnClickListener { box.toggle() }
                    } else {
                        card.setOnClickListener { finish(listOf(item.key)) }
                    }
                    list.addView(
                        card,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(Spacing.BLOCK / 2))
                        },
                    )
                }
            }
            rebuild("")
            // The card, the title and the rows scroll as one piece.
            val scrolling = context.column().apply {
                if (header != null) {
                    addView(
                        header,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(context.dp(Spacing.SHEET), context.dp(4), context.dp(Spacing.SHEET), context.dp(16))
                        },
                    )
                }
                add(
                    context.label(
                        title,
                        if (header != null) com.google.android.material.R.attr.textAppearanceTitleLarge
                        else com.google.android.material.R.attr.textAppearanceHeadlineSmall,
                    ).apply {
                        setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                    },
                )
                if (note != null) {
                    add(
                        context.label(note, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant).apply {
                            setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                        },
                    )
                }
                if (filterable && items.size > 6) {
                    val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                        hint = Tr.get("filter")
                        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
                    }
                    layout.addView(TextInputEditText(layout.context).apply {
                        setSingleLine()
                        doAfterTextChanged { rebuild(it?.toString() ?: "") }
                    })
                    addView(
                        layout,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                        },
                    )
                }
                addView(list)
            }
            val root = context.column().apply {
                addView(com.google.android.material.bottomsheet.BottomSheetDragHandleView(context))
                addView(scrolling.scrollable(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                if (multiple) {
                    addView(
                        com.google.android.material.button.MaterialButton(context).apply {
                            text = Tr.get("continue")
                            setOnClickListener { finish(chosen.toList()) }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(context.dp(Spacing.SHEET), context.dp(8), context.dp(Spacing.SHEET), context.dp(16))
                        },
                    )
                }
            }
            dialog.setContentView(root)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.setOnDismissListener { finish(null) }
            dialog.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

    /** A sheet of actions, each with an icon, in the manner of a context menu. */
    fun showActions(title: String?, actions: List<SheetAction>, entry: AppEntry? = null) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        Messages.track(dialog)
        val root = context.column().apply {
            addView(com.google.android.material.bottomsheet.BottomSheetDragHandleView(context))
            if (entry != null) {
                addView(
                    context.appCard(entry),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(context.dp(Spacing.SHEET), context.dp(4), context.dp(Spacing.SHEET), context.dp(8))
                    },
                )
            } else if (title != null) {
                add(
                    context.label(title, com.google.android.material.R.attr.textAppearanceTitleLarge).apply {
                        setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
            }
            for (action in actions) {
                val color = context.themeColor(
                    if (action.danger) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurface,
                )
                val row = LinearLayout(context).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(Spacing.ROW)
                    setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), 0)
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        dialog.dismiss()
                        action.onClick()
                    }
                }
                row.addView(
                    android.widget.ImageView(context).apply {
                        setImageResource(action.icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            if (action.danger) color else context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                        )
                    },
                    LinearLayout.LayoutParams(context.dp(Spacing.SHEET), context.dp(Spacing.SHEET)).apply { marginEnd = context.dp(Spacing.SHEET) },
                )
                row.addView(
                    context.label(action.label, com.google.android.material.R.attr.textAppearanceBodyLarge).apply { setTextColor(color) },
                )
                addView(row)
            }
            add(View(context), topMargin = 12)
        }
        dialog.setContentView(root.scrollable())
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /** Errors of a bulk import: which URL failed and why. */
    fun showImportErrors(total: Int, errors: List<Pair<String, String>>) {
        val view = body().apply {
            add(context.label(Tr.get("importedXOfYApps", (total - errors.size).toString(), total.toString())))
            add(context.label(Tr.get("followingURLsHadErrors"), com.google.android.material.R.attr.textAppearanceTitleSmall), topMargin = 12)
            for ((url, error) in errors) {
                add(context.label(url), topMargin = 8)
                add(context.label(error, colorAttr = androidx.appcompat.R.attr.colorError).apply { setTypeface(null, Typeface.ITALIC) })
            }
        }
        context.showSheet(Tr.get("importErrors"), content = view, positive = Tr.get("close"))
    }
}

/** The framed card of a tracked app, as every screen shows an app. */
fun Context.appCard(entry: AppEntry): View =
    io.github.bl3xand.apkcloner.databinding.ViewAppCardBinding.inflate(android.view.LayoutInflater.from(this)).also { it.bindTracked(entry) }.root

/**
 * Fills the shared app card for a tracked app; a tap opens its page in the system settings.
 * With [treatAsNotInstalled] the installed copy is ignored - used for a signer conflict, where the
 * card is about the build being added, not the differently-signed one already on the device.
 */
fun io.github.bl3xand.apkcloner.databinding.ViewAppCardBinding.bindTracked(entry: AppEntry, treatAsNotInstalled: Boolean = false) {
    val context = root.context
    val app = entry.app
    val info = entry.installedInfo?.applicationInfo?.takeUnless { treatAsNotInstalled }
    val version = if (treatAsNotInstalled) app.latestVersion else app.installedVersion ?: app.latestVersion
    bind(
        label = entry.name,
        subtitle = listOfNotNull(app.id.takeIf { !app.hasTempId }, version).joinToString(" · "),
        icon = info?.let { AppIcons.load(context.packageManager, it) } ?: context.getDrawable(io.github.bl3xand.apkcloner.R.drawable.ic_install),
        settingsPackage = app.id.takeIf { info != null },
    )
    imageIcon.alpha = if (info != null) 1f else 0.4f
}

private const val PALETTE_HEIGHT = 200
private const val CHOICE_PADDING = 12
private const val PREVIEW_DOT = 40
private const val PREVIEW_FRAME = 72

/** A field of colours: hue runs left to right, top is pale and bottom is deep. */
private class PaletteView(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    private var bitmap: android.graphics.Bitmap? = null
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = android.graphics.Color.WHITE
        setShadowLayer(context.dp(2).toFloat(), 0f, 0f, android.graphics.Color.BLACK)
    }
    private var markX = -1f
    private var markY = -1f

    init {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.dp(16).toFloat())
            }
        }
    }

    private fun colorAt(x: Float, y: Float): Int {
        val hue = (x.coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
        val depth = y.coerceIn(0f, 1f)
        // Pale at the top, pure in the middle, darker at the bottom.
        val saturation = if (depth < 0.5f) 0.15f + depth * 1.7f else 1f
        val value = if (depth < 0.5f) 1f else 1f - (depth - 0.5f) * 1.2f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val columns = 120
        val rows = 60
        val pixels = IntArray(columns * rows) { colorAt((it % columns) / (columns - 1f), (it / columns) / (rows - 1f)) }
        bitmap = android.graphics.Bitmap.createBitmap(pixels, columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), paint) }
        if (markX >= 0) canvas.drawCircle(markX, markY, context.dp(10).toFloat(), ring)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        markX = event.x.coerceIn(0f, width.toFloat())
        markY = event.y.coerceIn(0f, height.toFloat())
        onPick(colorAt(markX / width, markY / height))
        invalidate()
        return true
    }
}
