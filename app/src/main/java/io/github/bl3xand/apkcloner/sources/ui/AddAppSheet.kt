package io.github.bl3xand.apkcloner.sources.ui

import io.github.bl3xand.apkcloner.sources.core.androidVersionName
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.SearchMatch
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.form.cloneItems
import io.github.bl3xand.apkcloner.sources.form.defaultValuesOf
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.source.AppSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.actionButton
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.anyOfChips
import io.github.bl3xand.apkcloner.ui.addDivider
import io.github.bl3xand.apkcloner.ui.addHeading
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.dp
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.filterChip
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.openUrl
import io.github.bl3xand.apkcloner.ui.scrollable
import io.github.bl3xand.apkcloner.ui.scrollableUnderHandle
import io.github.bl3xand.apkcloner.ui.show
import io.github.bl3xand.apkcloner.ui.showError
import io.github.bl3xand.apkcloner.ui.switchRow
import io.github.bl3xand.apkcloner.ui.themeColor
import io.github.bl3xand.apkcloner.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** What the search called each app it found, for a source that knows only the package name. */
    private val foundNames = HashMap<String, String>()
    private var pickedSource: AppSource? = null
    private var additionalSettings: MutableMap<String, Any?> = LinkedHashMap()
    private var settingsValid = true
    private var inferAppId = true
    private var categories: Set<String> = emptySet()
    private var busy = false

    private var optionsExpanded = false

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlEdit: TextInputEditText
    private lateinit var urlNote: TextView
    private lateinit var addButton: MaterialButton
    private lateinit var findButton: MaterialButton
    private var searchText = ""
    private lateinit var optionsToggle: MaterialButton
    private lateinit var optionsContainer: LinearLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var searchProgress: LinearProgressIndicator

    /** Searches run here, so that one a source never answers does not keep the sheet waiting. */
    private val searches = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searching = false
    private lateinit var noteLabel: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        dialogs = SourcesDialogs(context)
        val settings = viewModel.repo.settings
        val root = context.column(Spacing.SHEET)
        root.add(context.label(Tr.get("addApp"), MaterialR.attr.textAppearanceHeadlineSmall))

        // ---- by link ----
        root.addHeading(Tr.get("addByLink"), afterDivider = false)
        urlLayout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply {
            hint = Tr.get("addLinkField")
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        urlEdit = TextInputEditText(urlLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            doAfterTextChanged { onInputChanged(it?.toString() ?: "") }
        }
        urlNote = context.label(Tr.get("addLinkHint"), colorAttr = MaterialR.attr.colorOnSurfaceVariant)
        urlLayout.addView(urlEdit)
        root.add(urlLayout, topMargin = Spacing.UNDER_HEADING - 1)
        root.add(urlNote, topMargin = 4)
        noteLabel = context.label("", colorAttr = MaterialR.attr.colorOnSurfaceVariant).apply { isVisible = false }
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
            setOnClickListener {
                val running = download
                if (busy && running != null) viewModel.installer.cancelDownload(running.first) else addApp()
            }
        }
        root.add(addButton, topMargin = Spacing.UNDER_LABEL - Spacing.BUTTON_INSET)

        // ---- by search: only while no link is typed ----
        searchContainer = context.column()
        searchContainer.addDivider(after = addButton)
        searchContainer.addHeading(Tr.get("addBySearch"))
        val searchLayout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply {
            hint = Tr.get("addSearchField")
            setStartIconDrawable(R.drawable.ic_search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val searchEdit = TextInputEditText(searchLayout.context).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { view, _, _ ->
                runSearch(view.text.toString())
                true
            }
        }
        searchLayout.addView(searchEdit)
        searchLayout.setStartIconOnClickListener { runSearch(searchEdit.text?.toString() ?: "") }
        searchContainer.add(searchLayout, topMargin = Spacing.UNDER_HEADING - 4)
        // The search shows that it runs where it was started, not up at the link.
        searchProgress = LinearProgressIndicator(context).apply {
            isIndeterminate = true
            isVisible = false
        }
        searchContainer.add(searchProgress, topMargin = Spacing.UNDER_LABEL)
        // A name that was pasted has no key of the keyboard to send it off with.
        findButton = MaterialButton(context).apply {
            text = Tr.get("addFindNow")
            setIconResource(R.drawable.ic_search)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            minimumHeight = context.dp(Spacing.ROW)
            isEnabled = false
            setOnClickListener { runSearch(searchEdit.text?.toString() ?: "") }
        }
        searchEdit.doAfterTextChanged {
            searchText = it?.toString().orEmpty()
            render()
        }
        searchContainer.add(findButton, topMargin = Spacing.UNDER_LABEL - Spacing.BUTTON_INSET)
        searchContainer.add(
            context.label(Tr.get("addWhere"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = Spacing.UNDER_HEADING,
        )
        // "Everywhere" by default; picking sources one by one narrows the search to them.
        val searchable = SourceRegistry.sources.filter { it.canSearch }
        val where = context.anyOfChips(
            Tr.get("addEverywhere"), searchable.map { it.name to it.shortName },
            if (settings.searchEverywhere) null else searchable.map { it.name }.filter { it !in settings.searchDeselected }.toSet(),
        ) { picked ->
            settings.searchEverywhere = picked == null
            if (picked != null) settings.searchDeselected = searchable.map { it.name }.filter { it !in picked }
        }
        searchContainer.add(where, topMargin = Spacing.UNDER_LABEL)
        // For this device only: the stores that answer for a device are asked, and what they
        // find is kept when it has a version this device can run. Which sources to ask is then
        // not a choice, so the chips are out of reach.
        val forDeviceSources = searchable.filter { it.answersForDevice }
        fun lockSources(locked: Boolean) {
            for (i in 0 until where.childCount) where.getChildAt(i).isEnabled = !locked
            where.alpha = if (locked) LOCKED_ALPHA else 1f
        }
        searchContainer.add(
            context.switchRow(
                Tr.get("addForDevice"), settings.searchForDevice,
                Tr.get(
                    "addForDeviceNote", androidVersionName(SourceEnv.platform.sdkInt),
                    SourceEnv.platform.supportedAbis.firstOrNull().orEmpty(), forDeviceSources.joinToString { it.shortName },
                ),
            ) { on ->
                settings.searchForDevice = on
                lockSources(on)
            },
            topMargin = Spacing.UNDER_LABEL,
        )
        lockSources(settings.searchForDevice)
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
        return root.scrollableUnderHandle()
    }

    override fun onStart() {
        super.onStart()
        expandFully()
        dialog?.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)?.layoutParams?.height =
            ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** Puts a link into the sheet that is already showing. */
    fun setUrl(url: String) {
        if (view != null) urlEdit.setText(url)
    }

    /** What is being looked up on the net right now: adding by a link, or a search by name. */
    private var lookup: Job? = null

    /** Drops what is being looked up, a download that is part of it included. */
    private fun cancelLookup() {
        lookup?.cancel()
        lookup = null
        download?.let { viewModel.installer.cancelDownload(it.first) }
    }

    override fun onDestroyView() {
        // Leaving the sheet is saying "never mind".
        cancelLookup()
        super.onDestroyView()
    }

    private fun onInputChanged(input: String) {
        // The link that was being looked up is no longer the one in the field.
        if (busy && input.trim() != userInput) cancelLookup()
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
            source != null -> Tr.get("addDetected", source.shortName)
            else -> Tr.get("addLinkHint")
        }
        urlNote.setTextColor(
            requireContext().themeColor(
                if (failed) AppCompatR.attr.colorError else MaterialR.attr.colorOnSurfaceVariant,
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
            .map { it.sourceIdentifier to it.shortName }
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

    /** The download that is finding out what the app is, while one runs: its id and how far it has got. */
    private var download: Pair<String, DownloadState>? = null

    private fun render() {
        val source = pickedSource
        val valid = (source != null) && settingsValid && requiredFilled(source)
        // While a file is coming down, the button is the way to stop it.
        val downloading = busy && download != null
        addButton.isEnabled = downloading || (valid && !busy && !searching)
        addButton.text = Tr.get(if (downloading) "cancel" else "add")
        addButton.setIconResource(if (downloading) R.drawable.ic_close else R.drawable.ic_add)
        progress.show(busy, download?.second?.progress)
        progress.isVisible = busy
        searchProgress.isVisible = searching
        findButton.isEnabled = searchText.isNotBlank() && !searching && !busy
        searchContainer.isVisible = source == null && userInput.isEmpty()
        // Options that must be filled in cannot stay hidden.
        val mustShow = source != null && (!settingsValid || !requiredFilled(source))
        optionsToggle.isVisible = source != null && !mustShow
        optionsContainer.isVisible = source != null && (optionsExpanded || mustShow)
    }

    override fun onDestroy() {
        super.onDestroy()
        searches.cancel()
    }

    private fun setSearching(value: Boolean) {
        searching = value
        if (view != null) render()
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
        lookup = viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            // Finding out what the app is can take a whole download; its progress is shown.
            val watcher = launch {
                repo.downloads.collect { running ->
                    download = running.entries.firstOrNull()?.toPair()
                    if (view != null) render()
                }
            }
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
                var apkHashes = emptySet<String>()
                var app = withContext(Dispatchers.IO) {
                    SourceRegistry.getApp(
                        source, userInput, settingsSnapshot,
                        trackOnlyOverride = trackOnly,
                        sourceIsOverriden = overrideSource != null,
                        inferAppIdIfOptional = inferAppId,
                    )
                }
                // A source that offers several things side by side has the user choose one first.
                withContext(Dispatchers.IO) { source.trackingChoice(app, userInput) }?.let { choice ->
                    // There may be hundreds on offer, so the list can be searched.
                    val picked = choice.chosen ?: dialogs.pickFromList(
                        choice.title, choice.options.map { PickItem(it.value, it.label, it.description, null) },
                    )?.firstOrNull() ?: return@launch
                    settingsSnapshot[choice.settingKey] = picked
                    app = withContext(Dispatchers.IO) {
                        SourceRegistry.getApp(
                            source, userInput, settingsSnapshot,
                            trackOnlyOverride = trackOnly,
                            sourceIsOverriden = overrideSource != null,
                            inferAppIdIfOptional = inferAppId,
                        )
                    }
                }
                if (app.name.isBlank() || app.name == app.id) {
                    (foundNames[userInput.trim()] ?: foundNames[app.url])?.let { app = app.copy(name = it) }
                }
                if (app.hasTempId && !app.settings.getBool(SettingKeys.TRACK_ONLY)) {
                    // The package name is still unknown: the APK itself has to tell. Any file of
                    // the release says it, so nothing is asked here - which file to install is
                    // asked when the app is installed.
                    val picked = installer.confirmAppFileUrl(app, prompts = null, pickAnyAsset = false)
                        ?: throw SourceError(Tr.get("cancelled"))
                    app = app.copy(preferredApkIndex = app.apkUrls.indexOfFirst { it.url == picked.url })
                    // Some sources can tell from a small part of the file; the others have it downloaded.
                    val peek = withContext(Dispatchers.IO) {
                        runCatching { source.peekAsset(picked.url, app.additionalSettings) }.getOrNull()
                    }
                    if (peek != null) {
                        app = app.copy(id = peek.packageName)
                        apkHashes = peek.certHashes
                    } else {
                        val downloaded = withContext(Dispatchers.IO) { installer.downloadApp(app, background = false) }
                        app = app.copy(id = downloaded.appId)
                        apkHashes = withContext(Dispatchers.IO) { installer.signerHashesOf(downloaded) }
                    }
                }
                // The very same source already tracked just opens its page.
                val existing = repo.entry(app.id)
                if (existing != null && existing.app.url == app.url) {
                    viewModel.emit(SourcesEvent.OpenApp(app.id))
                    dismissAllowingStateLoss()
                    return@launch
                }
                if (app.settings.getBool(SettingKeys.TRACK_ONLY) || !app.settings.getBool(SettingKeys.VERSION_DETECTION)) {
                    // Without a way to tell, what is on the device is taken for the latest. A
                    // source that can tell is asked: an older build then gets its update offered.
                    val onDevice = withContext(Dispatchers.IO) { repo.installedInfo(app.id) }
                    val isLatest = onDevice?.let { source.isLatestBuildInstalled(app, it.versionName, it.versionCodeLong) }
                    app = app.copy(installedVersion = if (isLatest == false) onDevice.versionName else app.latestVersion)
                }
                app = app.copy(categories = categories.toList())
                // A build of this package may already be on the device under a different signer
                // (e.g. a fork). This one cannot go over it, so it is only shown - with the way to
                // make room for it - and nothing is tracked or replaced until the user does that.
                if (withContext(Dispatchers.IO) { installer.clashesWithInstalled(app.id, apkHashes) }) {
                    AppLog.info("${app.id} from ${app.url} clashes with the installed build's signer; not tracked yet")
                    viewModel.propose(app, apkHashes)
                    dismissAllowingStateLoss()
                    return@launch
                }
                // The package is tracked from somewhere else already. Taking that entry over is the
                // user's call; declined, the entry stays as it is and its page opens.
                if (existing != null && !context.confirm(
                        Tr.get("addReplaceSource"), Tr.get("addReplaceSourceText", existing.name, existing.app.url),
                    )
                ) {
                    viewModel.emit(SourcesEvent.OpenApp(app.id))
                    dismissAllowingStateLoss()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    // The signer on record was the old source's.
                    if (existing != null) repo.forgetApkCertHashes(app.id)
                    repo.storeApkCertHashes(app.id, apkHashes)
                    repo.saveApps(listOf(app), onlyIfExists = false)
                }
                AppLog.info("Added ${app.id} from ${app.url}, latest ${app.latestVersion}")
                viewModel.emit(SourcesEvent.OpenApp(app.id))
                dismissAllowingStateLoss()
            } catch (e: CancellationSignal) {
                AppLog.info("Adding $userInput was cancelled")
            } catch (e: CancellationException) {
                // The link was cleared or the sheet left: nothing went wrong, and nothing is said.
                AppLog.debug("Adding $userInput was dropped")
                throw e
            } catch (e: Exception) {
                AppLog.error("Adding $userInput failed", e)
                context.showError(e)
            } finally {
                watcher.cancel()
                download = null
                setBusy(false)
            }
        }
    }

    /** Searches the chosen sources side by side and offers the results as one readable list. */
    private fun runSearch(query: String) {
        val context = requireContext()
        val settings = viewModel.repo.settings
        if (query.isBlank() || searching) return
        lookup = viewLifecycleOwner.lifecycleScope.launch {
            setSearching(true)
            try {
                val forDevice = settings.searchForDevice
                val everywhere = settings.searchEverywhere
                // Everywhere means every source that can simply be asked: one that wants an
                // address or a token first is searched when it is picked by name.
                val picked = SourceRegistry.sources.filter {
                    it.canSearch && when {
                        forDevice -> it.answersForDevice
                        everywhere -> !it.includeAdditionalOptsInMainSearch
                        else -> it.name !in settings.searchDeselected
                    }
                }
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
                    if (!context.confirm(Tr.get("searchX", source.shortName), view = view.scrollable())) continue
                    querySettings[source.sourceIdentifier] = LinkedHashMap(form.values)
                }

                val failed = ArrayList<String>()
                val slow = ArrayList<String>()
                // Each source is asked on its own and the answers are gathered until the time is
                // up: one that hangs - a store that is far away or blocked - is left behind
                // instead of holding back what the others have found.
                val asked = picked.filter { !it.includeAdditionalOptsInMainSearch || querySettings.containsKey(it.sourceIdentifier) }
                val pending = asked.map { source ->
                    source to searches.async {
                        runCatching {
                            val found = source.search(query, querySettings[source.sourceIdentifier] ?: emptyMap())
                            if (forDevice) forThisDevice(source, found) else found
                        }
                    }
                }
                // Everywhere, nobody waits for the slowest source. Sources picked by hand are
                // the ones wanted, so they get the time a far-away store may need.
                val deadline = System.currentTimeMillis() + if (forDevice || !everywhere) DEVICE_SEARCH_LIMIT_MS else SEARCH_LIMIT_MS
                val results = pending.mapNotNull { (source, answer) ->
                    val found = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) { answer.await() }
                    when {
                        found == null -> null.also { slow.add(source.shortName) }
                        found.isFailure -> null.also { failed.add(source.shortName) }
                        else -> source to found.getOrThrow()
                    }
                }
                // The closest match first, whichever source it is from; among equals one result
                // from each source in turn, so that no source crowds out the others.
                val hits = ArrayList<SearchHit>()
                for ((order, result) in results.withIndex()) {
                    val (source, found) = result
                    for ((index, hit) in found.entries.withIndex()) {
                        val title = hit.value.getOrNull(0)?.takeIf { it.isNotBlank() } ?: hit.key
                        val description = hit.value.getOrNull(1)?.takeIf { it.isNotBlank() && it != title } ?: hit.key
                        hits += SearchHit(hit.key, title, description, source, SearchMatch.rank(query, title, description), index, order)
                    }
                }
                val merged = LinkedHashMap<String, PickItem>()
                val sourceOf = HashMap<String, String>()
                for (hit in hits.sortedWith(compareBy({ it.rank }, { it.index }, { it.order }))) {
                    if (merged.containsKey(hit.url)) continue
                    merged[hit.url] = PickItem(hit.url, hit.title, hit.description, hit.source.shortName)
                    sourceOf[hit.url] = hit.source.sourceIdentifier
                    foundNames[hit.url] = hit.title
                }
                if (merged.isEmpty()) {
                    throw SourceError(
                        listOfNotNull(
                            Tr.get("noResults"),
                            "${Tr.get("error")}: ${failed.joinToString()}".takeIf { failed.isNotEmpty() },
                            Tr.get("addSearchSlow", slow.joinToString()).takeIf { slow.isNotEmpty() },
                        ).joinToString("\n\n"),
                    )
                }
                val notes = listOfNotNull(
                    "${Tr.get("error")}: ${failed.joinToString()}".takeIf { failed.isNotEmpty() },
                    Tr.get("addSearchSlow", slow.joinToString()).takeIf { slow.isNotEmpty() },
                )
                if (notes.isNotEmpty()) context.toast(notes.joinToString("\n"))
                val chosen = dialogs.pickFromList("${Tr.get("search")}: $query", merged.values.toList())?.firstOrNull() ?: return@launch
                overrideSource = SourceRegistry.overrideFor(chosen, sourceOf[chosen])
                urlEdit.setText(chosen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.showError(e)
            } finally {
                setSearching(false)
            }
        }
    }

    /**
     * Of what a search [found], the apps [source] has a version of for this device. The only way
     * to know is to ask for each, so only the first few are asked about, side by side.
     */
    private suspend fun forThisDevice(source: AppSource, found: Map<String, List<String>>): Map<String, List<String>> = coroutineScope {
        found.entries.take(DEVICE_SEARCH_RESULTS).map { hit ->
            hit to async(Dispatchers.IO) {
                runCatching { SourceRegistry.getApp(source, hit.key, defaultValuesOf(source.combinedAppSpecificSettingFormItems)) }.isSuccess
            }
        }.filter { it.second.await() }.associate { it.first.key to it.first.value }
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
                val site = listOfNotNull(source.hosts.firstOrNull() ?: Tr.get("addAnyHost"), source.supportedNote).joinToString(" · ")
                PickItem(source.sourceIdentifier, source.shortName, site, traits)
            }
            val chosen = dialogs.pickFromList(Tr.get("addSupported"), items, filterable = false)?.firstOrNull() ?: return@launch
            SourceRegistry.sources.first { it.sourceIdentifier == chosen }.hosts.firstOrNull()?.let { context.openUrl("https://$it") }
        }
    }

    companion object {
        /** How long a search waits for its sources. */
        private const val SEARCH_LIMIT_MS = 8000L

        // Looking for apps for this device asks about every result, which takes longer.
        private const val DEVICE_SEARCH_LIMIT_MS = 20000L
        private const val DEVICE_SEARCH_RESULTS = 10
        private const val LOCKED_ALPHA = 0.4f

        const val TAG = "AddAppSheet"
        private const val ARG_URL = "url"

        fun newInstance(url: String? = null) = AddAppSheet().apply { arguments = Bundle().apply { putString(ARG_URL, url) } }
    }
}

/** One result of a search by name, with what it takes to put it in its place among the others. */
private class SearchHit(
    val url: String,
    val title: String,
    val description: String,
    val source: AppSource,
    val rank: Int,
    /** Its place in what its source returned. */
    val index: Int,
    /** The place of its source among those asked. */
    val order: Int,
)
