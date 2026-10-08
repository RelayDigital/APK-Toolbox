package io.github.bl3xand.apkcloner.sources.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.SwitchItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.form.cloneItems
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds an app by its URL, or finds one by searching the sources that can be searched. */
class AddAppSheet : BottomSheetDialogFragment() {
    private val viewModel: SourcesViewModel by activityViewModels()
    private lateinit var dialogs: SourcesDialogs

    private var userInput = ""
    private var overrideSource: String? = null
    private var pickedSource: AppSource? = null
    private var additionalSettings: MutableMap<String, Any?> = LinkedHashMap()
    private var settingsValid = true
    private var inferAppId = true
    private var categories: Set<String> = emptySet()
    private var busy = false

    private var optionsExpanded = false

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlEdit: TextInputEditText
    private lateinit var urlNote: android.widget.TextView
    private lateinit var addButton: MaterialButton
    private lateinit var optionsToggle: MaterialButton
    private lateinit var optionsContainer: LinearLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var noteLabel: android.widget.TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        dialogs = SourcesDialogs(context)
        val settings = viewModel.repo.settings
        val root = context.column(Spacing.SHEET)
        root.add(BottomSheetDragHandleView(context))
        root.add(context.label(Tr.get("addApp"), com.google.android.material.R.attr.textAppearanceHeadlineSmall))

        // ---- by link ----
        root.addHeading(Tr.get("addByLink"), afterDivider = false)
        urlLayout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = Tr.get("addLinkField")
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        urlEdit = TextInputEditText(urlLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            doAfterTextChanged { onInputChanged(it?.toString() ?: "") }
        }
        urlNote = context.label(Tr.get("addLinkHint"), colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant)
        urlLayout.addView(urlEdit)
        root.add(urlLayout, topMargin = Spacing.UNDER_HEADING - 1)
        root.add(urlNote, topMargin = 4)
        noteLabel = context.label("", colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant).apply { isVisible = false }
        root.add(noteLabel, topMargin = Spacing.BLOCK / 2)

        progress = LinearProgressIndicator(context).apply {
            isIndeterminate = true
            isVisible = false
        }
        root.add(progress, topMargin = Spacing.UNDER_LABEL)

        addButton = MaterialButton(context).apply {
            text = Tr.get("add")
            setIconResource(R.drawable.ic_add)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            minimumHeight = context.dp(Spacing.ROW)
            setOnClickListener { addApp() }
        }
        root.add(addButton, topMargin = Spacing.UNDER_LABEL - Spacing.BUTTON_INSET)

        // ---- by search: only while no link is typed ----
        searchContainer = context.column()
        searchContainer.addDivider(after = addButton)
        searchContainer.addHeading(Tr.get("addBySearch"))
        val searchLayout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = Tr.get("addSearchField")
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_search)
        }
        val searchEdit = TextInputEditText(searchLayout.context).apply {
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { view, _, _ ->
                runSearch(view.text.toString())
                true
            }
        }
        searchLayout.addView(searchEdit)
        searchLayout.setEndIconOnClickListener { runSearch(searchEdit.text?.toString() ?: "") }
        searchContainer.add(searchLayout, topMargin = Spacing.UNDER_HEADING - 4)
        searchContainer.add(
            context.label(Tr.get("addWhere"), colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant), topMargin = Spacing.UNDER_HEADING,
        )
        val where = ChipGroup(context)
        for (source in SourceRegistry.sources.filter { it.canSearch }) {
            where.addView(
                context.filterChip(source.name, source.name !in settings.searchDeselected) { checked ->
                    settings.searchDeselected =
                        if (checked) settings.searchDeselected - source.name else (settings.searchDeselected + source.name).distinct()
                },
            )
        }
        searchContainer.add(where, topMargin = Spacing.UNDER_LABEL)
        searchContainer.addDivider()
        searchContainer.add(
            context.actionButton(Tr.get("addSupported"), R.drawable.ic_info_outline) { showSupportedSources() },
            topMargin = Spacing.GROUP - Spacing.BUTTON_INSET,
        )
        root.add(searchContainer)

        // ---- options of the detected source, folded away until asked for ----
        optionsToggle = context.actionButton(Tr.get("addMore"), R.drawable.ic_tune) {
            optionsExpanded = !optionsExpanded
            render()
        }
        root.add(optionsToggle, topMargin = Spacing.BLOCK - 2 * Spacing.BUTTON_INSET)
        optionsContainer = context.column()
        root.add(optionsContainer)
        root.add(View(context), topMargin = 24)

        arguments?.getString(ARG_URL)?.takeIf { it.isNotEmpty() }?.let { urlEdit.setText(it) }
        render()
        return root.scrollable()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.layoutParams?.height =
            ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** Puts a link into the sheet that is already showing. */
    fun setUrl(url: String) {
        if (view != null) urlEdit.setText(url)
    }

    private fun onInputChanged(input: String) {
        userInput = input.trim()
        resolveSource()
    }

    /** Works out which source the typed URL belongs to and rebuilds its options when it changes. */
    private fun resolveSource() {
        val previous = pickedSource
        var error: String? = null
        val source = if (userInput.isEmpty()) null else try {
            SourceRegistry.getSource(userInput, overrideSource)
        } catch (e: Exception) {
            error = errorText(e)
            null
        }
        // Said under the field in a line of our own: the field's built-in one is indented.
        val failed = userInput.isNotEmpty() && source == null && error != null
        urlNote.text = when {
            failed -> error
            source != null -> Tr.get("addDetected", source.name)
            else -> Tr.get("addLinkHint")
        }
        urlNote.setTextColor(
            requireContext().themeColor(
                if (failed) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ),
        )
        val changed = previous?.sourceIdentifier != source?.sourceIdentifier ||
            previous?.hostChanged != source?.hostChanged || previous?.hosts?.firstOrNull() != source?.hosts?.firstOrNull()
        pickedSource = source
        if (changed) {
            additionalSettings = if (source != null) {
                defaultValuesOf(source.combinedAppSpecificSettingFormItems).also {
                    it.putAll(source.runOnAddAppInputChange(userInput))
                }
            } else LinkedHashMap()
            inferAppId = true
            buildOptions()
            loadSourceNote(source)
        } else if (source != null) {
            // The same source: only values derived from the URL follow the typing.
            val derived = source.runOnAddAppInputChange(userInput)
            if (derived.isNotEmpty() && derived.any { additionalSettings[it.key] != it.value }) {
                additionalSettings.putAll(derived)
                buildOptions()
            }
        }
        render()
    }

    private fun loadSourceNote(source: AppSource?) {
        noteLabel.text = ""
        noteLabel.isVisible = false
        if (source == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val note = withContext(Dispatchers.IO) { runCatching { source.getSourceNote() }.getOrNull() }
            if (pickedSource === source && !note.isNullOrBlank()) {
                noteLabel.text = note
                noteLabel.isVisible = true
            }
        }
    }

    private fun requiredFilled(source: AppSource): Boolean = source.flatCombinedFormItems.all { item ->
        item !is TextItem || !item.required || !additionalSettings[item.key]?.toString().isNullOrBlank()
    }

    private fun buildOptions() {
        val context = requireContext()
        optionsContainer.removeAllViews()
        val source = pickedSource ?: return
        val settings = viewModel.repo.settings

        val items: List<List<SettingItem>> = cloneItems(source.combinedAppSpecificSettingFormItems) +
            (if (overrideSource != null) source.sourceConfigSettingFormItems.map { listOf(it) } else emptyList())
        val initial = LinkedHashMap(additionalSettings)
        if (settings.includePrereleasesByDefault) initial[SettingKeys.INCLUDE_PRERELEASES] = true
        if (settings.shizukuPretendToBeGooglePlay) initial[SettingKeys.PRETEND_GOOGLE_PLAY] = true
        val form = FormView(context, items, initial, grouped = true, afterDivider = true) { values, valid ->
            additionalSettings = LinkedHashMap(values)
            settingsValid = valid
            render()
        }
        additionalSettings = LinkedHashMap(form.values)
        settingsValid = form.isValid
        optionsContainer.addDivider(after = optionsToggle)
        optionsContainer.add(form)

        optionsContainer.addDivider()
        optionsContainer.add(dialogs.categorySelector(categories) { categories = it }, topMargin = Spacing.GROUP - Spacing.HEADING_INSET)

        // Self-hosted instances of a known kind of site are picked here.
        optionsContainer.addDivider()
        optionsContainer.addHeading(Tr.get("grpInstance"))
        val overrideChoices = listOf("" to Tr.get("none")) + SourceRegistry.sources
            .filter { it.allowOverride || it.sourceIdentifier == source.sourceIdentifier }
            .map { it.sourceIdentifier to it.name }
        optionsContainer.add(
            FormView(
                context,
                listOf(listOf(DropdownItem("overrideSource", "overrideSource", overrideChoices, rawLabels = true))),
                mapOf("overrideSource" to (overrideSource ?: "")),
            ) { values, _ ->
                val chosen = (values["overrideSource"] as? String)?.takeIf { it.isNotEmpty() }
                if (chosen != overrideSource) {
                    overrideSource = chosen
                    resolveSource()
                }
            },
        )

        if (source.appIdInferIsOptional) {
            optionsContainer.add(
                context.switchRow(Tr.get("tryInferAppIdFromCode"), inferAppId) { inferAppId = it }, topMargin = 8,
            )
        }
        if (source.enforceTrackOnly) {
            // A track-only app is never installed from here, so its package name cannot be learnt
            // from an APK; it can be given by hand to tie the entry to an installed app.
            val packageName = Regex("^([A-Za-z]{1}[A-Za-z\\d_]*\\.)+[A-Za-z][A-Za-z\\d_]*$")
            val appIdItem = TextItem(
                "appId", "appId", required = false,
                validators = listOf { value ->
                    if (value.isNullOrEmpty() || packageName.matches(value)) null else Tr.get("invalidInput")
                },
            ).also { it.labelOverride = { "${Tr.get("appId")} - ${Tr.get("custom")}" } }
            optionsContainer.add(
                FormView(context, listOf(listOf<SettingItem>(appIdItem))) { values, valid ->
                    additionalSettings[SettingKeys.APP_ID] = values[SettingKeys.APP_ID]
                    settingsValid = settingsValid && valid
                    render()
                },
                topMargin = 8,
            )
        }
    }

    private fun render() {
        val source = pickedSource
        val valid = (source != null) && settingsValid && requiredFilled(source)
        addButton.isEnabled = valid && !busy
        progress.isVisible = busy
        searchContainer.isVisible = source == null && userInput.isEmpty()
        // Options that must be filled in cannot stay hidden.
        val mustShow = source != null && (!settingsValid || !requiredFilled(source))
        optionsToggle.isVisible = source != null && !mustShow
        optionsContainer.isVisible = source != null && (optionsExpanded || mustShow)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (view != null) render()
    }

    private fun addApp() {
        val source = pickedSource ?: return
        val context = requireContext()
        val repo = viewModel.repo
        val installer = viewModel.installer
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            try {
                val userPickedTrackOnly = additionalSettings[SettingKeys.TRACK_ONLY] == true
                val trackOnly = source.enforceTrackOnly || userPickedTrackOnly
                if (trackOnly && !repo.settings.hideTrackOnlyWarning) {
                    var hide = false
                    val view = context.column(Spacing.SHEET).apply {
                        add(context.switchRow(Tr.get("dontShowAgain"), false) { hide = it })
                    }
                    val confirmed = context.confirm(
                        Tr.get("xIsTrackOnly", Tr.get(if (source.enforceTrackOnly) "source" else "app")),
                        "${Tr.get(if (source.enforceTrackOnly) "appsFromSourceAreTrackOnly" else "youPickedTrackOnly")}\n\n" +
                            Tr.get("trackOnlyAppDescription"),
                        view = view,
                    )
                    if (!confirmed) return@launch
                    repo.settings.hideTrackOnlyWarning = hide
                }
                if (additionalSettings[SettingKeys.RELEASE_DATE_AS_VERSION] == true &&
                    !context.confirm(Tr.get("releaseDateAsVersion"), Tr.get("releaseDateAsVersionExplanation"))
                ) {
                    return@launch
                }
                val settingsSnapshot = LinkedHashMap(additionalSettings)
                var app = withContext(Dispatchers.IO) {
                    SourceRegistry.getApp(
                        source, userInput, settingsSnapshot,
                        trackOnlyOverride = trackOnly,
                        sourceIsOverriden = overrideSource != null,
                        inferAppIdIfOptional = inferAppId,
                    )
                }
                if (app.hasTempId && !app.settings.getBool(SettingKeys.TRACK_ONLY)) {
                    // The package name is still unknown: the APK itself has to tell.
                    val picked = installer.confirmAppFileUrl(app, dialogs, pickAnyAsset = false)
                        ?: throw SourceError(Tr.get("cancelled"))
                    app = app.copy(preferredApkIndex = app.apkUrls.indexOfFirst { it.url == picked.url })
                    val downloaded = withContext(Dispatchers.IO) { installer.downloadApp(app, background = false) }
                    app = app.copy(id = downloaded.appId)
                }
                repo.entry(app.id)?.let {
                    throw SourceError("${Tr.get("appAlreadyAdded")}: ${it.app.name} (${app.id})")
                }
                if (app.settings.getBool(SettingKeys.TRACK_ONLY) || !app.settings.getBool(SettingKeys.VERSION_DETECTION)) {
                    app = app.copy(installedVersion = app.latestVersion)
                }
                app = app.copy(categories = categories.toList())
                withContext(Dispatchers.IO) { repo.saveApps(listOf(app), onlyIfExists = false) }
                viewModel.emit(SourcesEvent.OpenApp(app.id))
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                context.showError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    /** Searches the chosen sources side by side and offers the results as one readable list. */
    private fun runSearch(query: String) {
        val context = requireContext()
        val settings = viewModel.repo.settings
        if (query.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            try {
                val picked = SourceRegistry.sources.filter { it.canSearch && it.name !in settings.searchDeselected }
                if (picked.isEmpty()) throw SourceError(Tr.get("selectX", Tr.plural("source", 2).lowercase()))

                // Sources with options of their own (an instance URL, a token) ask for them first.
                val querySettings = HashMap<String, Map<String, Any?>>()
                for (source in picked.filter { it.includeAdditionalOptsInMainSearch }) {
                    val host = source.hosts.firstOrNull() ?: ""
                    val known = viewModel.repo.all().filter { it.sourceType == source.sourceIdentifier }
                        .map { Url.parse(it.app.url).let { url -> "${url.origin}${url.path}" } }
                    val urlItem = TextItem(
                        "url", if (source.hosts.isNotEmpty()) "overrideSource" else "url", value = host,
                        autoCompleteOptions = listOfNotNull(host.takeIf { it.isNotEmpty() }) + known,
                    ).also { if (source.hosts.isEmpty()) it.labelOverride = { "URL" } }
                    val form = FormView(
                        context, source.searchQuerySettingItemsForUrl(host).map { listOf(it) } + listOf(listOf<SettingItem>(urlItem)),
                    )
                    val view = context.column(Spacing.SHEET).apply { add(form) }
                    if (!context.confirm(Tr.get("searchX", source.name), view = view.scrollable())) continue
                    querySettings[source.sourceIdentifier] = LinkedHashMap(form.values)
                }

                val failed = ArrayList<String>()
                val results = withContext(Dispatchers.IO) {
                    coroutineScope {
                        picked.filter { !it.includeAdditionalOptsInMainSearch || querySettings.containsKey(it.sourceIdentifier) }
                            .map { source ->
                                async {
                                    try {
                                        source to source.search(query, querySettings[source.sourceIdentifier] ?: emptyMap())
                                    } catch (e: Exception) {
                                        synchronized(failed) { failed.add(source.name) }
                                        null
                                    }
                                }
                            }.awaitAll().filterNotNull()
                    }
                }
                // One result from each source in turn, so no source crowds out the others.
                val merged = LinkedHashMap<String, PickItem>()
                val sourceOf = HashMap<String, String>()
                var index = 0
                while (results.any { it.second.size > index }) {
                    for ((source, found) in results) {
                        val hit = found.entries.elementAtOrNull(index) ?: continue
                        if (merged.containsKey(hit.key)) continue
                        val title = hit.value.getOrNull(0)?.takeIf { it.isNotBlank() } ?: hit.key
                        val description = hit.value.getOrNull(1)?.takeIf { it.isNotBlank() && it != title } ?: hit.key
                        merged[hit.key] = PickItem(hit.key, title, description, source.name)
                        sourceOf[hit.key] = source.sourceIdentifier
                    }
                    index++
                }
                if (merged.isEmpty()) {
                    throw SourceError(Tr.get("noResults") + if (failed.isEmpty()) "" else "\n\n${Tr.get("error")}: ${failed.joinToString()}")
                }
                if (failed.isNotEmpty()) context.toast("${Tr.get("error")}: ${failed.joinToString()}")
                val chosen = dialogs.pickFromList("${Tr.get("search")}: $query", merged.values.toList())?.firstOrNull() ?: return@launch
                overrideSource = sourceOf[chosen]
                urlEdit.setText(chosen)
            } catch (e: Exception) {
                context.showError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    /** What can be added: every source with what it is good for; a tap opens its site. */
    private fun showSupportedSources() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val items = SourceRegistry.sources.map { source ->
                val traits = listOfNotNull(
                    Tr.get(if (source.enforceTrackOnly) "traitTrack" else "traitInstall"),
                    Tr.get("traitSearch").takeIf { source.canSearch },
                ).joinToString(" · ")
                PickItem(source.sourceIdentifier, source.name, source.hosts.firstOrNull() ?: Tr.get("addAnyHost"), traits)
            }
            val chosen = dialogs.pickFromList(Tr.get("addSupported"), items, filterable = false)?.firstOrNull() ?: return@launch
            SourceRegistry.sources.first { it.sourceIdentifier == chosen }.hosts.firstOrNull()?.let { context.openUrl("https://$it") }
        }
    }

    companion object {
        const val TAG = "AddAppSheet"
        private const val ARG_URL = "url"

        fun newInstance(url: String? = null) = AddAppSheet().apply { arguments = Bundle().apply { putString(ARG_URL, url) } }
    }
}
