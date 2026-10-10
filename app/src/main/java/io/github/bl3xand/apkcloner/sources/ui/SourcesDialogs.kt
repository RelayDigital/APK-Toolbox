package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.R as AppCompatR
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.NamedUrl
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import io.github.bl3xand.apkcloner.sources.install.InstallPrompts
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.ui.Messages
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.actionButton
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.addDivider
import io.github.bl3xand.apkcloner.ui.addHeading
import io.github.bl3xand.apkcloner.ui.anyOfChips
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.dp
import io.github.bl3xand.apkcloner.ui.enableLinks
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.filterChip
import io.github.bl3xand.apkcloner.ui.inScrollingRow
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.markdownToSpanned
import io.github.bl3xand.apkcloner.ui.openUrl
import io.github.bl3xand.apkcloner.ui.scrollable
import io.github.bl3xand.apkcloner.ui.scrollableUnderHandle
import io.github.bl3xand.apkcloner.ui.sectionTitle
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.switchRow
import io.github.bl3xand.apkcloner.ui.themeColor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
            Tr.get("sourceIsXButPackageFromYPrompt", Uri.parse(sourceUrl).host ?: sourceUrl,
                Uri.parse(apkUrl.lines().first()).host ?: apkUrl),
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
                        context.label(hash, MaterialR.attr.textAppearanceBodySmall).apply {
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
        // Chips at the top switch everything, or one group, on and off; each is on while all of
        // what it stands for is. Only the groups that have something in them get a chip.
        val groups = listOf(Tr.get("updates") to updates, Tr.get("nonInstalledApps") to installs, Tr.get("trackOnly") to trackOnly)
            .filter { it.second.isNotEmpty() }
        val toggles = LinkedHashMap<String, MaterialSwitch>()
        val chips = ArrayList<Pair<Chip, List<String>>>()
        var syncing = false
        fun syncChips() {
            syncing = true
            chips.forEach { (chip, ids) -> chip.isChecked = chosen.containsAll(ids) }
            syncing = false
        }
        fun chipFor(label: String, ids: List<String>): Chip = context.filterChip(label, chosen.containsAll(ids)) { on ->
            if (syncing) return@filterChip
            syncing = true
            ids.forEach { toggles[it]?.isChecked = on }
            syncing = false
            syncChips()
        }.also { chips.add(it to ids) }
        val view = body().apply {
            add(
                ChipGroup(context).apply {
                    addView(chipFor(Tr.get("selectAll"), all))
                    if (groups.size > 1) groups.forEach { (title, ids) -> addView(chipFor(title, ids)) }
                },
            )
            // The rows of the settings: a name with a line under it, and a switch.
            for ((index, group) in groups.withIndex()) {
                val (title, ids) = group
                if (index > 0) addDivider()
                addHeading("$title (${ids.size})", afterDivider = index > 0)
                for (id in ids) {
                    val entry = repo.entry(id) ?: continue
                    val versions = listOfNotNull(entry.app.installedVersion, entry.app.latestVersion).distinct().joinToString(" → ")
                    val row = context.switchRow(
                        entry.name, id in chosen, listOf(entry.author, versions).filter { it.isNotBlank() }.joinToString(" · "),
                    ) { on ->
                        if (on) chosen.add(id) else chosen.remove(id)
                        if (!syncing) syncChips()
                    }
                    toggles[id] = row.getChildAt(1) as MaterialSwitch
                    add(row)
                }
            }
        }
        val confirmed = context.confirm(
            Tr.get("bulkUpdateTitle"), view = view.scrollable(),
        )
        return if (confirmed) chosen.toList() else null
    }

    /** Narrowing the list: by state, by source and by category, all as chips. */
    suspend fun askFilter(current: AppsFilter): AppsFilter? {
        var filter = current
        // Chips of a filter wrap, like the categories under them: all of a filter is in sight at once.
        val view = body().apply {
            add(context.sectionTitle(Tr.get("fltState")))
            add(
                context.anyOfChips(Tr.get("fltAll"), AppKind.entries.map { it to Tr.get(it.label) }, filter.kinds) {
                    filter = filter.copy(kinds = it)
                },
                topMargin = Spacing.UNDER_HEADING,
            )
            // Only the sources that are actually in use.
            val used = repo.all().mapNotNull { it.sourceType }.distinct()
            if (used.size > 1) {
                addDivider()
                addHeading(Tr.get("fltSource"))
                val names = SourceRegistry.sources.associate { it.sourceIdentifier to it.shortName }
                add(
                    context.anyOfChips(Tr.get("fltAll"), used.map { it to (names[it] ?: it) }, filter.sources) {
                        filter = filter.copy(sources = it)
                    },
                    topMargin = Spacing.UNDER_HEADING,
                )
            }
            // The same chips as everywhere categories are picked: one can be made right here, and
            // held down to be removed. They wrap, and the sheet scrolls when there are many.
            addDivider()
            addHeading(Tr.get("categories"))
            add(
                categorySelector(emptySet(), showTitle = false, selectedOrAll = filter.categories, onFilter = { filter = filter.copy(categories = it) }),
                topMargin = Spacing.UNDER_HEADING,
            )
        }
        if (!context.confirm(Tr.get("filterApps"), view = view.scrollable())) return null
        return filter
    }

    /** Chips for every known category; with [allowCreate] a new one can be added on the spot. */
    fun categorySelector(
        selected: Set<String>,
        allowCreate: Boolean = true,
        showTitle: Boolean = true,
        /**
         * For a filter: a first chip stands for every category and is on by itself while nothing
         * narrows the list. It is given the choice instead of [onChange]: null while that chip is
         * on, a set of categories otherwise - an empty one when everything was switched off.
         */
        onFilter: ((Set<String>?) -> Unit)? = null,
        /** For a filter: null stands for every category. */
        selectedOrAll: Set<String>? = selected,
        onChange: (Set<String>) -> Unit = {},
    ): View {
        val withAll = onFilter != null
        var everything = withAll && selectedOrAll == null
        val current = LinkedHashSet(if (withAll) selectedOrAll.orEmpty() else selected)
        fun report() {
            if (onFilter != null) onFilter(if (everything) null else current.toSet()) else onChange(current.toSet())
        }
        val group = ChipGroup(context)
        fun rebuild() {
            group.removeAllViews()
            val categories = settings.categories
            if (withAll && categories.isNotEmpty()) {
                group.addView(
                    context.filterChip(Tr.get("fltAll"), everything) { on ->
                        everything = on
                        current.clear()
                        report()
                        group.post { rebuild() }
                    },
                )
            }
            for ((name, color) in categories.entries.sortedBy { it.key.lowercase() }) {
                group.addView(
                    context.filterChip(name, name in current) { checked ->
                        if (checked) current.add(name) else current.remove(name)
                        everything = false
                        report()
                        if (withAll) group.post { rebuild() }
                    }.apply {
                        // The colour of the category, as on the rows of the list.
                        setChipIconResource(R.drawable.ic_label)
                        chipIconTint = ColorStateList.valueOf(color or 0xFF000000.toInt())
                        isChipIconVisible = true
                        // Held down, a category can be removed right here.
                        setOnLongClickListener {
                            confirmCategoryRemoval(name) {
                                current.remove(name)
                                report()
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
                        setChipIconResource(R.drawable.ic_add)
                        setEnsureMinTouchTargetSize(false)
                        setOnClickListener {
                            askNewCategory { name ->
                                current.add(name)
                                report()
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
                add(context.label(Tr.get("noCategories"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 4)
            }
            add(group, topMargin = if (showTitle) Spacing.UNDER_HEADING else 0)
        }
    }

    fun promptText(title: String, hint: String, initial: String = "", onResult: (String) -> Unit) {
        val layout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply { this.hint = hint }
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
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
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
        val frame = MaterialCardView(
            context, null, MaterialR.attr.materialCardViewOutlinedStyle,
        ).apply {
            addView(
                preview,
                FrameLayout.LayoutParams(context.dp(PREVIEW_DOT), context.dp(PREVIEW_DOT), Gravity.CENTER),
            )
        }
        val field = PaletteView(context) { color ->
            (preview.background as GradientDrawable).setColor(color)
            onChange(color)
        }
        return context.column().apply {
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(PALETTE_HEIGHT)))
            addView(
                frame,
                LinearLayout.LayoutParams(context.dp(PREVIEW_FRAME), context.dp(PREVIEW_FRAME)).apply {
                    topMargin = context.dp(Spacing.BLOCK)
                    gravity = Gravity.CENTER_HORIZONTAL
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
        context.showSheet(Tr.get("catRemoveQuestion", name), positive = Tr.get("remove"), negative = Tr.get("cancel")) {
            repo.setCategories(settings.categories - name)
            onRemoved()
            true
        }
    }

    /** A new category: its name and its colour in one sheet. Names already taken are refused. */
    private fun askNewCategory(onCreated: (String) -> Unit) {
        var color = palette.random()
        val layout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply { hint = Tr.get("catName") }
        val edit = TextInputEditText(layout.context).apply { setSingleLine() }
        layout.addView(edit)
        val view = body().apply {
            add(layout)
            add(context.label(Tr.get("catColor"), MaterialR.attr.textAppearanceTitleMedium), topMargin = Spacing.BLOCK)
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
                list.add(context.label(Tr.get("noCategories"), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
            }
            categories.entries.sortedBy { it.key.lowercase() }.forEachIndexed { index, (name, color) ->
                if (index > 0) list.add(MaterialDivider(context))
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
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
                    context.label(name, MaterialR.attr.textAppearanceBodyLarge).apply {
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
                    MaterialButton(
                        context, null, MaterialR.attr.materialIconButtonStyle,
                    ).apply {
                        setIconResource(R.drawable.ic_delete)
                        iconTint = ColorStateList.valueOf(context.themeColor(AppCompatR.attr.colorError))
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
            add(context.label(Tr.get("catHint"), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
            add(list, topMargin = 8)
            add(
                context.actionButton(Tr.get("addCategory"), R.drawable.ic_add) {
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
                    context.label(url, colorAttr = AppCompatR.attr.colorPrimary).apply {
                        setTypeface(null, Typeface.ITALIC)
                        setOnClickListener { context.openUrl(url) }
                    },
                )
            }
            add(
                context.label(
                    if (source.changeLogIfAnyIsMarkDown) {
                        markdownToSpanned(changeLog, Uri.parse(app.url).let { "${it.scheme}://${it.host}" })
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
            val dialog = BottomSheetDialog(context)
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
                    val card = MaterialCardView(
                        context, null, MaterialR.attr.materialCardViewOutlinedStyle,
                    )
                    val row = LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(Spacing.ROW)
                        setPadding(context.dp(Spacing.BLOCK), context.dp(CHOICE_PADDING), context.dp(Spacing.BLOCK), context.dp(CHOICE_PADDING))
                    }
                    card.addView(row)
                    val texts = context.column().apply {
                        add(context.label(item.title, MaterialR.attr.textAppearanceTitleMedium))
                        if (item.description.isNotBlank()) {
                            add(
                                context.label(item.description, colorAttr = MaterialR.attr.colorOnSurfaceVariant),
                                topMargin = 2,
                            )
                        }
                        if (item.badge != null) {
                            add(
                                context.label(item.badge, MaterialR.attr.textAppearanceLabelMedium, AppCompatR.attr.colorPrimary),
                                topMargin = 4,
                            )
                        }
                    }
                    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (item.current) {
                        row.addView(
                            ImageView(context).apply {
                                setImageResource(R.drawable.ic_check)
                                imageTintList = ColorStateList.valueOf(context.themeColor(AppCompatR.attr.colorPrimary))
                            },
                            LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginStart = context.dp(Spacing.BLOCK) },
                        )
                    }
                    if (multiple) {
                        val box = MaterialCheckBox(context).apply {
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
                        if (header != null) MaterialR.attr.textAppearanceTitleLarge
                        else MaterialR.attr.textAppearanceHeadlineSmall,
                    ).apply {
                        setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                    },
                )
                if (note != null) {
                    add(
                        context.label(note, colorAttr = MaterialR.attr.colorOnSurfaceVariant).apply {
                            setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                        },
                    )
                }
                // A handful is read at a glance; more than that is looked through by typing.
                if (filterable && items.size > SEARCH_FROM) {
                    val layout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply {
                        hint = Tr.get("search")
                        setStartIconDrawable(R.drawable.ic_search)
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
                addView(BottomSheetDragHandleView(context))
                addView(scrolling.scrollable(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                if (multiple) {
                    addView(
                        MaterialButton(context).apply {
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
            dialog.expandFully()
            dialog.setOnDismissListener { finish(null) }
            dialog.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

    /** A sheet of actions, each with an icon, in the manner of a context menu. */
    fun showActions(title: String?, actions: List<SheetAction>, entry: AppEntry? = null) {
        val dialog = BottomSheetDialog(context)
        Messages.track(dialog)
        val root = context.column().apply {
            if (entry != null) {
                addView(
                    context.appCard(entry),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(context.dp(Spacing.SHEET), context.dp(4), context.dp(Spacing.SHEET), context.dp(8))
                    },
                )
            } else if (title != null) {
                add(
                    context.label(title, MaterialR.attr.textAppearanceTitleLarge).apply {
                        setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), context.dp(8))
                    },
                )
            }
            for (action in actions) {
                val color = context.themeColor(
                    if (action.danger) AppCompatR.attr.colorError else MaterialR.attr.colorOnSurface,
                )
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(Spacing.ROW)
                    setPadding(context.dp(Spacing.SHEET), 0, context.dp(Spacing.SHEET), 0)
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        dialog.dismiss()
                        action.onClick()
                    }
                }
                row.addView(
                    ImageView(context).apply {
                        setImageResource(action.icon)
                        imageTintList = ColorStateList.valueOf(
                            if (action.danger) color else context.themeColor(MaterialR.attr.colorOnSurfaceVariant),
                        )
                    },
                    LinearLayout.LayoutParams(context.dp(Spacing.SHEET), context.dp(Spacing.SHEET)).apply { marginEnd = context.dp(Spacing.SHEET) },
                )
                row.addView(
                    context.label(action.label, MaterialR.attr.textAppearanceBodyLarge).apply { setTextColor(color) },
                )
                addView(row)
            }
            add(View(context), topMargin = 12)
        }
        dialog.setContentView(root.scrollableUnderHandle())
        dialog.expandFully()
        dialog.show()
    }

    /** Errors of a bulk import: which URL failed and why. */
    fun showImportErrors(total: Int, errors: List<Pair<String, String>>) {
        val view = body().apply {
            add(context.label(Tr.get("importedXOfYApps", (total - errors.size).toString(), total.toString())))
            add(context.label(Tr.get("followingURLsHadErrors"), MaterialR.attr.textAppearanceTitleSmall), topMargin = 12)
            for ((url, error) in errors) {
                add(context.label(url), topMargin = 8)
                add(context.label(error, colorAttr = AppCompatR.attr.colorError).apply { setTypeface(null, Typeface.ITALIC) })
            }
        }
        context.showSheet(Tr.get("importErrors"), content = view, positive = Tr.get("close"))
    }
}

private const val CHOICE_PADDING = 12

/** How many choices a list holds before it gets a search field. */
private const val SEARCH_FROM = 10
private const val PREVIEW_DOT = 40
private const val PREVIEW_FRAME = 72
