package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.compat.parcelableList
import io.github.bl3xand.apkcloner.compat.parcelable
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ActivityMainBinding
import io.github.bl3xand.apkcloner.sources.ui.SheetAction
import io.github.bl3xand.apkcloner.sources.ui.SourcesDialogs
import io.github.bl3xand.apkcloner.sources.ui.SourcesTab
import io.github.bl3xand.apkcloner.sources.ui.SourcesViewModel
import io.github.bl3xand.apkcloner.update.UpdateNotifications
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** The tracked apps; a clone that a source keeps current is changed through them. */
    private val sourcesViewModel: SourcesViewModel by viewModels()
    private lateinit var sourcesTab: SourcesTab

    /** Before Android 11: the answer comes back through onResume, which looks at the grant again. */
    private val requestStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.cloning.loadFile(uri)
    }

    private val pickToInstall = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.splits.loadFiles(uris, SplitMode.INSTALL)
    }
    private val pickToMerge = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.splits.loadFiles(uris, SplitMode.MERGE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val listBottomPadding = binding.listApps.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.bottomBar.updatePadding(bottom = bars.bottom)
            binding.listClones.updatePadding(bottom = listBottomPadding + bars.bottom)
            insets
        }

        val icons = IconLoader(lifecycleScope, packageManager)
        val appAdapter = AppAdapter(icons, viewModel.cloning::select)
        val cloneAdapter = CloneAdapter(
            icons,
            onClick = { clone ->
                if (supportFragmentManager.findFragmentByTag(CloneDetailSheet.TAG) == null) {
                    CloneDetailSheet.newInstance(clone).show(supportFragmentManager, CloneDetailSheet.TAG)
                }
            },
            // Held down, a clone is filed under categories, as a tracked app is from its menu.
            onLongClick = { clone ->
                lifecycleScope.launch {
                    val chosen = askCloneCategories(clone.categories) ?: return@launch
                    val tracked = clone.tracked
                    if (tracked != null) sourcesViewModel.update(listOf(tracked.app.id)) { it.copy(categories = chosen.toList()) }
                    else viewModel.setCloneCategories(clone, chosen)
                }
            },
        )
        val installAdapter = AppAdapter(icons) { viewModel.splits.selectApp(it, SplitMode.EXPORT) }
        val splitAdapter = AppAdapter(icons) { viewModel.splits.selectApp(it, SplitMode.MERGE) }
        binding.listApps.adapter = appAdapter
        binding.listInstall.adapter = installAdapter
        binding.listSplit.adapter = splitAdapter
        binding.listClones.adapter = cloneAdapter

        for (title in TAB_TITLES) binding.tabs.addTab(binding.tabs.newTab().setText(title))
        sourcesTab = SourcesTab(this, binding)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!sourcesTab.onBackPressed()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        if (savedInstanceState == null) handleIntent(intent)
        showTab()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.tab = when (tab.position) {
                    POSITION_SOURCES -> MainTabs.SOURCES
                    POSITION_CLONING -> viewModel.cloningSide
                    POSITION_INSTALL -> MainTabs.INSTALL
                    else -> MainTabs.SPLIT
                }
                render(viewModel.uiState.value)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.toggleCloning.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val side = if (checkedId == R.id.buttonSideClones) MainTabs.CLONES else MainTabs.APPS
            viewModel.cloningSide = side
            if ((viewModel.tab == MainTabs.APPS || viewModel.tab == MainTabs.CLONES) && viewModel.tab != side) {
                viewModel.tab = side
                render(viewModel.uiState.value)
            }
        }

        binding.buttonClearSearch.setOnClickListener { binding.editSearch.text?.clear() }
        binding.editSearch.doAfterTextChanged {
            binding.buttonClearSearch.isVisible = !it.isNullOrEmpty()
            // Each side filters its own lists by the same box.
            viewModel.setQuery(it?.toString().orEmpty())
            sourcesTab.onQuery(it?.toString().orEmpty())
        }
        binding.chipClonesRefresh.setOnClickListener { viewModel.refreshClones() }
        binding.chipSystem.setOnCheckedChangeListener { _, checked -> viewModel.setShowSystem(checked) }
        binding.chipClonesFilter.setOnClickListener {
            lifecycleScope.launch { askClonesFilter(viewModel.clonesFilter)?.let(::applyClonesFilter) }
        }
        binding.chipClonesClearFilter.setOnClickListener { applyClonesFilter(ClonesFilter()) }
        // Every tab has a button that says what the tab is for.
        val showTabInfo = { _: View ->
            val (title, text) = TAB_INFO.getValue(viewModel.tab)
            showSheet(getString(title), message = getString(text))
            Unit
        }
        binding.buttonTabInfo.setOnClickListener(showTabInfo)
        binding.buttonSourcesInfo.setOnClickListener(showTabInfo)
        binding.buttonGrantFiles.setOnClickListener {
            // Access to all files is a switch in the settings since Android 11; before that it
            // was a permission asked for like any other.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            else requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        binding.buttonGrantInstall.setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        }
        binding.buttonPickApk.setOnClickListener {
            // Bundles have no MIME type of their own, so the pickers for them accept anything.
            when (viewModel.tab) {
                MainTabs.SOURCES -> sourcesTab.onBarButton()
                MainTabs.INSTALL -> pickToInstall.launch(arrayOf("*/*"))
                MainTabs.SPLIT -> pickToMerge.launch(arrayOf("*/*"))
                else -> pickApk.launch(APK_MIME_TYPES)
            }
        }
        binding.buttonSettings.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(SettingsSheet.TAG) == null) {
                SettingsSheet().show(supportFragmentManager, SettingsSheet.TAG)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        render(state)
                        appAdapter.submitList(state.apps)
                        installAdapter.submitList(state.apps)
                        splitAdapter.submitList(state.splitApps)
                        cloneAdapter.submitList(state.clones)
                        cloneAdapter.updating = state.updatingClone
                    }
                }
                launch { viewModel.events.collect(::handle) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        showTab()
    }

    /** Brings the tab strip and the Cloning switch in line with the tab kept in the view model. */
    private fun showTab() {
        val tab = viewModel.tab
        if (tab == MainTabs.APPS || tab == MainTabs.CLONES) viewModel.cloningSide = tab
        binding.toggleCloning.check(if (viewModel.cloningSide == MainTabs.CLONES) R.id.buttonSideClones else R.id.buttonSideInstalled)
        val position = when (tab) {
            MainTabs.SOURCES -> POSITION_SOURCES
            MainTabs.INSTALL -> POSITION_INSTALL
            MainTabs.SPLIT -> POSITION_SPLIT
            else -> POSITION_CLONING
        }
        if (binding.tabs.selectedTabPosition != position) binding.tabs.getTabAt(position)?.select() else render(viewModel.uiState.value)
    }

    /**
     * Besides a plain launch: the "updates available" notification asks for the Clones tab, and
     * APK files can be shared or opened into the app to be installed or merged.
     */
    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_TAB)) viewModel.tab = intent.getIntExtra(EXTRA_TAB, MainTabs.APPS)
        // A shared link, a configuration link or a tapped notification of the Sources tab.
        if (sourcesTab.handleIntent(intent)) {
            viewModel.tab = MainTabs.SOURCES
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        viewModel.splits.offerFiles(uris)
    }

    override fun onResume() {
        super.onResume()
        // Both grants happen on system settings screens, so re-check every time we come back.
        // This also picks up apps installed, updated or removed in the meantime.
        UpdateNotifications.cancelOutdated(this)
        sourcesTab.onResume()
        viewModel.setPermissions(
            fileAccess = hasFileAccess(),
            canInstall = packageManager.canRequestPackageInstalls(),
        )
    }

    override fun onPause() {
        super.onPause()
        sourcesTab.onPause()
    }

    override fun onStart() {
        super.onStart()
        Messages.attach(this)
    }

    override fun onStop() {
        Messages.detach(this)
        super.onStop()
    }

    override fun onDestroy() {
        sourcesTab.onDestroy()
        super.onDestroy()
    }

    /** Opens the page of the clone [packageName] in the Clones tab, from wherever it is asked. */
    fun showClone(packageName: String) {
        viewModel.tab = MainTabs.CLONES
        showTab()
        (supportFragmentManager.findFragmentByTag(CloneDetailSheet.TAG) as? CloneDetailSheet)?.dismiss()
        CloneDetailSheet.newInstance(packageName).show(supportFragmentManager, CloneDetailSheet.TAG)
    }

    /** Opens the page of the tracked app [id] in the Sources tab, from wherever it is asked. */
    fun showTrackedApp(id: String) {
        viewModel.tab = MainTabs.SOURCES
        showTab()
        sourcesTab.openApp(id)
    }

    private fun applyClonesFilter(filter: ClonesFilter) {
        viewModel.clonesFilter = filter
        // The list may look the same with another filter; the chip that clears it must not.
        binding.chipClonesClearFilter.isVisible = !filter.isNeutral
    }

    private fun render(state: MainUiState) {
        val ready = state.permissionsGranted
        val tab = viewModel.tab
        val appsTab = tab == MainTabs.APPS
        binding.permissionGroup.isVisible = !ready
        binding.cardFiles.isVisible = !state.hasFileAccess
        binding.cardInstall.isVisible = !state.canInstall
        binding.contentGroup.isVisible = ready
        binding.toggleCloning.isVisible = tab == MainTabs.APPS || tab == MainTabs.CLONES
        binding.systemRow.isVisible = tab != MainTabs.SOURCES
        binding.chipSystem.isVisible = tab != MainTabs.CLONES
        // The Clones side has the same Refresh as Sources: check everything, update what is behind.
        binding.chipClonesRefresh.isVisible = tab == MainTabs.CLONES
        binding.chipClonesFilter.isVisible = tab == MainTabs.CLONES
        binding.chipClonesClearFilter.isVisible = tab == MainTabs.CLONES && !viewModel.clonesFilter.isNeutral
        binding.chipClonesRefresh.isEnabled = state.updatingClone == null
        binding.listApps.isVisible = appsTab
        binding.listClones.isVisible = tab == MainTabs.CLONES
        binding.listInstall.isVisible = tab == MainTabs.INSTALL
        binding.listSplit.isVisible = tab == MainTabs.SPLIT
        // The Clones side has nothing to pick; its one action is the Refresh chip.
        binding.bottomBar.isVisible = ready && tab != MainTabs.CLONES
        binding.buttonPickApk.isEnabled = true
        binding.buttonPickApk.setIconResource(R.drawable.ic_folder)
        binding.buttonPickApk.text = when (tab) {
            MainTabs.INSTALL -> getString(R.string.button_pick_install)
            MainTabs.SPLIT -> getString(R.string.button_pick_split)
            else -> getString(R.string.button_pick_apk)
        }
        binding.progress.isVisible = state.loading || (tab == MainTabs.CLONES && state.updatingClone != null)

        val empty = when (tab) {
            MainTabs.CLONES -> state.clones.isEmpty()
            MainTabs.SPLIT -> state.splitApps.isEmpty()
            else -> state.apps.isEmpty()
        }
        binding.textEmpty.isVisible = ready && !state.loading && empty
        binding.textEmpty.setText(
            when (tab) {
                MainTabs.CLONES -> R.string.empty_clones
                MainTabs.SPLIT -> R.string.empty_split
                else -> R.string.empty_list
            }
        )
        // The Sources tab draws its own state over the shared pieces of the screen.
        sourcesTab.setActive(tab == MainTabs.SOURCES, ready)
    }

    private fun handle(event: MainEvent) {
        val sheetShown = supportFragmentManager.findFragmentByTag(CloneSheet.TAG) != null ||
            supportFragmentManager.findFragmentByTag(SplitSheet.TAG) != null
        when (event) {
            is MainEvent.SourceReady -> if (!sheetShown) CloneSheet().show(supportFragmentManager, CloneSheet.TAG)
            MainEvent.SplitSourceReady -> if (!sheetShown) SplitSheet().show(supportFragmentManager, SplitSheet.TAG)
            MainEvent.FilesReceived -> askWhatToDoWithFiles()
            is MainEvent.Message -> Messages.show(getString(event.text, event.argument))
        }
    }

    /** The same files can be installed as they are or merged into one APK first. */
    private fun askWhatToDoWithFiles() {
        val uris = viewModel.splits.pendingFiles
        if (uris.isEmpty()) return
        fun go(mode: SplitMode) {
            viewModel.tab = if (mode == SplitMode.INSTALL) MainTabs.INSTALL else MainTabs.SPLIT
            showTab()
            viewModel.splits.loadFiles(uris, mode)
        }
        // The sheet of actions every other menu of the app is.
        SourcesDialogs(this).showActions(
            getString(R.string.files_received_title),
            listOf(
                SheetAction(R.drawable.ic_install, getString(R.string.files_action_install)) { go(SplitMode.INSTALL) },
                SheetAction(R.drawable.ic_merge, getString(R.string.files_action_merge)) { go(SplitMode.MERGE) },
            ),
        )
    }

    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_TAB = "tab"
        // Where each tab sits in the strip; both sides of Cloning share one place.
        private const val POSITION_SOURCES = 0
        private const val POSITION_CLONING = 1
        private const val POSITION_INSTALL = 2
        private const val POSITION_SPLIT = 3
        /** For each tab, its name and what it is for. */
        private val TAB_INFO = mapOf(
            MainTabs.SOURCES to (R.string.tab_sources to R.string.hint_tab_sources),
            MainTabs.APPS to (R.string.tab_apps to R.string.hint_tab_apps),
            MainTabs.CLONES to (R.string.cloning_clones to R.string.hint_tab_clones),
            MainTabs.INSTALL to (R.string.tab_install to R.string.hint_tab_install),
            MainTabs.SPLIT to (R.string.tab_split to R.string.hint_tab_split),
        )
        private val TAB_TITLES = listOf(R.string.tab_sources, R.string.tab_apps, R.string.tab_install, R.string.tab_split)

        // Many file managers report APKs as a generic binary.
        private val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    }
}
