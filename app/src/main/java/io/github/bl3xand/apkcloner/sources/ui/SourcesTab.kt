package io.github.bl3xand.apkcloner.sources.ui

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ActivityMainBinding
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.data.SourcesEnvironment
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.sources.work.SourcesNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The Sources tab as seen by the main screen: its list, its bar button, links that open it. */
class SourcesTab(private val activity: AppCompatActivity, private val binding: ActivityMainBinding) {
    val viewModel: SourcesViewModel = ViewModelProvider(activity)[SourcesViewModel::class.java]
    private val dialogs = SourcesDialogs(activity)
    private val menus = AppMenus(activity, viewModel)
    private val settings = viewModel.repo.settings
    private var state = SourcesUiState()
    private var active = false

    private val adapter = SourcesAdapter(
        activity.lifecycleScope, activity.packageManager, settings,
        object : SourcesAdapter.Listener {
            override fun onAppClick(row: ListRow.App) {
                if (state.selected.isNotEmpty()) viewModel.toggleSelected(row.entry.app.id) else openApp(row.entry.app.id)
            }

            override fun onAppLongClick(row: ListRow.App) {
                if (state.selected.isNotEmpty()) viewModel.toggleSelected(row.entry.app.id) else showActions(listOf(row.entry.app.id), selecting = false)
            }

            // A tap on the icon itself starts choosing several apps.
            override fun onIconClick(row: ListRow.App) = viewModel.toggleSelected(row.entry.app.id)
            override fun onUpdateClick(row: ListRow.App) = obtain(listOf(row.entry.app.id))
            override fun onCancelDownload(row: ListRow.App) = viewModel.installer.cancelDownload(row.entry.app.id)
            override fun onGroupClick(row: ListRow.Group) = viewModel.toggleGroup(row.key)
            override fun onBannerClick() = bulkUpdate()
        },
    )

    init {
        SourcesEnvironment.refresh(activity)
        viewModel.prompts = dialogs
        binding.listSources.adapter = adapter
        binding.chipSourcesRefresh.text = Tr.get("refresh")
        binding.chipSourcesRefresh.setOnClickListener { viewModel.refresh() }
        binding.chipSourcesFilter.setOnClickListener {
            activity.lifecycleScope.launch { dialogs.askFilter(state.filter)?.let(::applyFilter) }
        }
        binding.chipSourcesClearFilter.setOnClickListener { applyFilter(AppsFilter()) }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect {
                        state = it
                        adapter.submitList(it.rows)
                        if (active) render()
                    }
                }
                launch { viewModel.events.collect(::handle) }
            }
        }
    }

    private fun applyFilter(filter: AppsFilter) {
        viewModel.setFilter(filter)
        if (binding.editSearch.text.toString() != filter.name) binding.editSearch.setText(filter.name)
    }

    fun onResume() {
        viewModel.installer.isForeground = true
        viewModel.reload()
    }

    fun onPause() {
        viewModel.installer.isForeground = false
    }

    /** Lets go of what belongs to the activity, which the view model would otherwise keep alive. */
    fun onDestroy() {
        if (viewModel.prompts === dialogs) viewModel.prompts = null
        binding.listSources.adapter = null
    }

    fun onQuery(text: String) = viewModel.setQuery(text)

    /** Back leaves the selection first. */
    fun onBackPressed(): Boolean {
        if (!active || state.selected.isEmpty()) return false
        viewModel.clearSelection()
        return true
    }

    /** Shows or hides everything that belongs to this tab. */
    fun setActive(value: Boolean, ready: Boolean) {
        active = value
        binding.listSources.isVisible = value
        binding.sourcesChips.isVisible = value
        if (value) {
            render(ready)
        } else {
            // The other tabs only ever show the bar running without a measure.
            val visible = binding.progress.isVisible
            setProgressMode(indeterminate = true)
            binding.progress.isVisible = visible
        }
    }

    /** The indicator refuses to change its mode while it is shown. */
    private fun setProgressMode(indeterminate: Boolean) {
        if (binding.progress.isIndeterminate == indeterminate) return
        binding.progress.isVisible = false
        binding.progress.isIndeterminate = indeterminate
    }

    private fun render(ready: Boolean = true) {
        binding.chipSourcesClearFilter.isVisible = !state.filter.isNeutral
        binding.bottomBar.isVisible = ready
        binding.buttonPickApk.isEnabled = true
        val selecting = state.selected.isNotEmpty()
        binding.buttonPickApk.setIconResource(if (selecting) R.drawable.ic_more else R.drawable.ic_add)
        binding.buttonPickApk.text = if (selecting) Tr.get("actWithSelected")
        else activity.getString(R.string.sources_add)
        val progress = state.refreshProgress
        setProgressMode(indeterminate = progress == null || progress <= 0.0)
        binding.progress.isVisible = state.loading || progress != null
        if (progress != null && progress > 0.0) binding.progress.setProgressCompat((progress * 100).toInt(), true)
        binding.textEmpty.isVisible = ready && !state.loading && state.rows.isEmpty()
        binding.textEmpty.text = if (state.total == 0) activity.getString(R.string.sources_empty) else Tr.get("noAppsForFilter")
    }

    /** The bar button: add an app, or act on the selection. */
    fun onBarButton() {
        if (state.selected.isEmpty()) openAdd(null) else showActions(state.selected.toList(), selecting = true)
    }

    fun openAdd(url: String?) {
        val open = activity.supportFragmentManager.findFragmentByTag(AddAppSheet.TAG) as? AddAppSheet
        if (open == null) {
            AddAppSheet.newInstance(url).show(activity.supportFragmentManager, AddAppSheet.TAG)
        } else if (!url.isNullOrEmpty()) {
            // A link that arrives while the sheet is already up goes into it.
            open.setUrl(url)
        }
    }

    fun openApp(id: String) {
        if (viewModel.repo.entry(id) == null) return
        if (activity.supportFragmentManager.findFragmentByTag(AppDetailSheet.TAG) == null) {
            AppDetailSheet.newInstance(id).show(activity.supportFragmentManager, AppDetailSheet.TAG)
        }
    }

    private fun handle(event: SourcesEvent) {
        when (event) {
            is SourcesEvent.Error -> activity.showError(event.error)
            is SourcesEvent.Message -> activity.toast(event.text)
            is SourcesEvent.OpenApp -> openApp(event.id)
        }
    }

    private fun obtain(ids: List<String>) {
        if (viewModel.repo.areDownloadsRunning()) return
        viewModel.obtain(ids)
    }

    private fun bulkUpdate() {
        if (viewModel.repo.areDownloadsRunning()) return
        val all = state.pendingUpdates + state.pendingInstalls + state.pendingTrackOnly
        if (settings.skipBulkUpdateConfirmation) {
            obtain(all.distinct())
            return
        }
        activity.lifecycleScope.launch {
            dialogs.askBulkUpdate(state.pendingUpdates, state.pendingInstalls, state.pendingTrackOnly)?.let(::obtain)
        }
    }

    /**
     * The menu of a long press on one app, and of the bar button while several are selected:
     * the same actions either way.
     */
    private fun showActions(ids: List<String>, selecting: Boolean) {
        val entries = ids.mapNotNull { viewModel.repo.entry(it) }
        val apps = entries.map { it.app }
        if (apps.isEmpty()) return
        val single = apps.singleOrNull()
        // With several apps chosen the actions are named in the plural.
        val many = apps.size > 1
        val actions = ArrayList<SheetAction>()
        val obtainable = apps.filter { it.installedVersion == null || it.installedVersion != it.latestVersion }
        if (obtainable.isNotEmpty() && !viewModel.repo.areDownloadsRunning()) {
            val install = obtainable.all { it.installedVersion == null }
            actions.add(
                SheetAction(if (install) R.drawable.ic_download else R.drawable.ic_update, Tr.get(if (install) "actInstall" else "actUpdate")) {
                    if (single != null) obtain(ids) else bulkUpdate()
                },
            )
        }
        if (single != null) {
            actions.add(SheetAction(R.drawable.ic_search, Tr.get("actCheck")) { viewModel.refresh(single.id) })
            if (!single.changeLog.isNullOrBlank() || !single.releaseUrl.isNullOrEmpty()) {
                actions.add(SheetAction(R.drawable.ic_notes, Tr.get("actChanges")) { dialogs.showChanges(entries.first()) })
            }
        }
        if (!selecting && single != null) {
            if (runCatching { viewModel.repo.sourceOf(single).hasAppSpecificSettings }.getOrDefault(false)) {
                actions.add(SheetAction(R.drawable.ic_tune, Tr.get("actOptions")) {
                    viewModel.repo.entry(single.id)?.let(menus::editOptions)
                })
            }
        }
        val pin = apps.none { it.pinned }
        actions.add(SheetAction(R.drawable.ic_pin, Tr.get(if (pin) "pinToTop" else "unpinFromTop")) {
            viewModel.update(ids) { it.copy(pinned = pin) }
        })
        actions.add(SheetAction(R.drawable.ic_label, Tr.get("actCategories")) { categorize(ids) })
        // Only where the version cannot be read from the device.
        if (apps.any { it.installedVersion != null && it.installedVersion != it.latestVersion && !it.settings.getBool(SettingKeys.VERSION_DETECTION) }) {
            actions.add(SheetAction(R.drawable.ic_check, Tr.get("markUpdated")) { viewModel.markUpdated(ids) })
        }
        actions.add(SheetAction(R.drawable.ic_share, Tr.get(if (many) "actShareMany" else "actShare")) { shareText(apps.joinToString("\n") { it.url }) })
        actions.add(SheetAction(R.drawable.ic_folder, Tr.get(if (many) "actExportMany" else "actExport")) { shareExport(ids) })
        if (apps.any { it.apkUrls.isNotEmpty() || it.otherAssetUrls.isNotEmpty() }) {
            actions.add(SheetAction(R.drawable.ic_download, Tr.get(if (many) "actDownloadMany" else "actDownload")) {
                viewModel.downloadAssets(ids)
            })
        }
        if (!selecting && single != null) {
            actions.add(SheetAction(R.drawable.ic_info, Tr.get("actInfo")) { viewModel.repo.entry(single.id)?.let(menus::showInfo) })
        }
        if (!selecting && single != null) {
            actions.add(SheetAction(R.drawable.ic_select, Tr.get("actSelect")) { viewModel.toggleSelected(single.id) })
        } else {
            actions.add(SheetAction(R.drawable.ic_select, Tr.get("actSelectAll")) {
                state.rows.filterIsInstance<ListRow.App>().forEach { if (!it.selected) viewModel.toggleSelected(it.entry.app.id) }
            })
            actions.add(SheetAction(R.drawable.ic_close, Tr.get("cancel")) { viewModel.clearSelection() })
        }
        actions.add(SheetAction(R.drawable.ic_delete, Tr.get("remove"), danger = true) {
            activity.lifecycleScope.launch {
                val (uninstall, removeEntry) = dialogs.askRemove(apps) ?: return@launch
                viewModel.remove(ids, uninstall, removeEntry)
            }
        })
        dialogs.showActions(Tr.plural("actSelectedCount", ids.size), actions, entry = entries.first().takeIf { !selecting && single != null })
    }

    private fun categorize(ids: List<String>) {
        val apps = ids.mapNotNull { viewModel.repo.entry(it)?.app }
        val common = apps.map { it.categories.toSet() }.distinct()
        activity.lifecycleScope.launch {
            // Apps with different categories all end up with the new choice.
            if (common.size > 1 && !activity.confirm(Tr.get("actCategories"), Tr.get("selectedCategorizeWarning"))) return@launch
            var chosen = if (common.size == 1) common.first() else emptySet()
            var changed = false
            val view = activity.column(Spacing.SHEET).apply {
                add(dialogs.categorySelector(chosen, showTitle = false) {
                    chosen = it
                    changed = true
                }, topMargin = 8)
            }
            val header = ids.singleOrNull()?.let { viewModel.repo.entry(it) }?.let(activity::appCard)
            if (activity.confirm(Tr.get("actCategories"), view = view, header = header) && changed) {
                viewModel.update(ids) { it.copy(categories = chosen.toList()) }
            }
        }
    }

    private fun shareText(text: String) {
        activity.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
        )
    }

    private fun shareExport(ids: List<String>) {
        activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = java.io.File(activity.cacheDir, "sources_share").apply { mkdirs() }
                    java.io.File(dir, "apk-toolbox-sources-export-count-${ids.size}.json").apply {
                        writeText(viewModel.repo.generateExportJson(ids, overrideExportSettings = 0).toString(4))
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.sources", file)
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        null,
                    ),
                )
            } catch (e: Exception) {
                activity.showError(e)
            }
        }
    }

    /**
     * Intents meant for this tab: a tapped notification, a shared link, and links in the
     * reference app's scheme. Returns true when the intent was one of those.
     */
    fun handleIntent(intent: Intent): Boolean {
        intent.getStringExtra(SourcesNotifications.EXTRA_MESSAGE)?.let { message ->
            val lines = message.split('\n')
            activity.showSheet(lines.first(), message = lines.drop(1).joinToString("\n"), positive = Tr.get("close"))
            return true
        }
        intent.getStringExtra(SourcesNotifications.EXTRA_APP_ID)?.let { id ->
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { if (viewModel.repo.entry(id) == null) viewModel.repo.loadApps() }
                openApp(id)
            }
            return true
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = shared?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ';', '!', '?', ')') }
            if (url != null) addOrOpen(url) else activity.toast(Tr.get("urlMatchesNoSource"))
            return true
        }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "obtainium") {
            handleDeepLink(data)
            return true
        }
        return false
    }

    /** Opens the app tracked from [url], or the add sheet with the URL filled in. */
    private fun addOrOpen(url: String) {
        activity.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                if (viewModel.repo.all().isEmpty()) viewModel.repo.loadApps()
                val standard = runCatching { SourceRegistry.getSource(url).standardizeUrl(url) }.getOrNull()
                viewModel.repo.all().firstOrNull { it.app.url == standard || it.app.url == url }
            }
            if (existing != null) openApp(existing.app.id) else openAdd(url)
        }
    }

    private fun handleDeepLink(uri: Uri) {
        val action = uri.host
        val data = uri.getQueryParameter("url") ?: uri.path?.takeIf { it.length > 1 }?.substring(1) ?: ""
        when (action) {
            "add" -> addOrOpen(data)
            "app", "apps" -> activity.lifecycleScope.launch {
                // The configuration is shown before anything is imported.
                val confirmed = activity.confirm(
                    Tr.get("importX", Tr.get(if (action == "app") "app" else "appsString").lowercase()),
                    view = activity.column(Spacing.SHEET).apply {
                        add(activity.label(data, com.google.android.material.R.attr.textAppearanceBodySmall).apply {
                            typeface = android.graphics.Typeface.MONOSPACE
                        })
                    }.scrollable(),
                )
                if (!confirmed) return@launch
                try {
                    val payload = JSONObject().put(
                        "apps", if (action == "app") JSONArray().put(JSONObject(data)) else JSONArray(data),
                    ).toString()
                    val imported = withContext(Dispatchers.IO) { viewModel.repo.importJson(payload).first }
                    activity.toast(Tr.get("importedX", Tr.plural("apps", imported.size).lowercase()))
                } catch (e: Exception) {
                    activity.showError(if (e is org.json.JSONException) Tr.get("invalidInput") else errorText(e))
                }
            }
            "refresh" -> viewModel.refresh(uri.getQueryParameter("id"))
            else -> activity.showError(Tr.get("unknown"))
        }
    }
}
