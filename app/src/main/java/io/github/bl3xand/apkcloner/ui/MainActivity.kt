package io.github.bl3xand.apkcloner.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ActivityMainBinding
import io.github.bl3xand.apkcloner.sources.ui.SourcesTab
import io.github.bl3xand.apkcloner.sources.ui.showSheet
import io.github.bl3xand.apkcloner.update.UpdateNotifications
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var sourcesTab: SourcesTab

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadApkFile(uri)
    }

    private val pickToInstall = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.loadSplitFiles(uris, SplitMode.INSTALL)
    }
    private val pickToMerge = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.loadSplitFiles(uris, SplitMode.MERGE)
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
        val appAdapter = AppAdapter(icons, viewModel::select)
        val cloneAdapter = CloneAdapter(icons) { clone ->
            if (supportFragmentManager.findFragmentByTag(CloneDetailSheet.TAG) == null) {
                CloneDetailSheet.newInstance(clone).show(supportFragmentManager, CloneDetailSheet.TAG)
            }
        }
        val installAdapter = AppAdapter(icons) { viewModel.selectSplitApp(it, SplitMode.EXPORT) }
        val splitAdapter = AppAdapter(icons) { viewModel.selectSplitApp(it, SplitMode.MERGE) }
        binding.listApps.adapter = appAdapter
        binding.listInstall.adapter = installAdapter
        binding.listSplit.adapter = splitAdapter
        binding.listClones.adapter = cloneAdapter

        for (title in TAB_TITLES) binding.tabs.addTab(binding.tabs.newTab().setText(title))
        sourcesTab = SourcesTab(this, binding)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
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
                    POSITION_SOURCES -> TAB_SOURCES
                    POSITION_CLONING -> viewModel.cloningSide
                    POSITION_INSTALL -> TAB_INSTALL
                    else -> TAB_SPLIT
                }
                render(viewModel.uiState.value)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.toggleCloning.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val side = if (checkedId == R.id.buttonSideClones) TAB_CLONES else TAB_APPS
            viewModel.cloningSide = side
            if ((viewModel.tab == TAB_APPS || viewModel.tab == TAB_CLONES) && viewModel.tab != side) {
                viewModel.tab = side
                render(viewModel.uiState.value)
            }
        }

        binding.editSearch.doAfterTextChanged {
            // Each side filters its own lists by the same box.
            viewModel.setQuery(it?.toString().orEmpty())
            sourcesTab.onQuery(it?.toString().orEmpty())
        }
        binding.chipClonesRefresh.setOnClickListener { viewModel.refreshClones() }
        binding.chipSystem.setOnCheckedChangeListener { _, checked -> viewModel.setShowSystem(checked) }
        // Every tab has a button that says what the tab is for.
        val showTabInfo = { _: android.view.View ->
            val (title, text) = TAB_INFO.getValue(viewModel.tab)
            showSheet(getString(title), message = getString(text))
            Unit
        }
        binding.buttonTabInfo.setOnClickListener(showTabInfo)
        binding.buttonSourcesInfo.setOnClickListener(showTabInfo)
        binding.buttonGrantFiles.setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        }
        binding.buttonGrantInstall.setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        }
        binding.buttonPickApk.setOnClickListener {
            // Bundles have no MIME type of their own, so the pickers for them accept anything.
            when (viewModel.tab) {
                TAB_SOURCES -> sourcesTab.onBarButton()
                TAB_INSTALL -> pickToInstall.launch(arrayOf("*/*"))
                TAB_SPLIT -> pickToMerge.launch(arrayOf("*/*"))
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
        if (tab == TAB_APPS || tab == TAB_CLONES) viewModel.cloningSide = tab
        binding.toggleCloning.check(if (viewModel.cloningSide == TAB_CLONES) R.id.buttonSideClones else R.id.buttonSideInstalled)
        val position = when (tab) {
            TAB_SOURCES -> POSITION_SOURCES
            TAB_INSTALL -> POSITION_INSTALL
            TAB_SPLIT -> POSITION_SPLIT
            else -> POSITION_CLONING
        }
        if (binding.tabs.selectedTabPosition != position) binding.tabs.getTabAt(position)?.select() else render(viewModel.uiState.value)
    }

    /**
     * Besides a plain launch: the "updates available" notification asks for the Clones tab, and
     * APK files can be shared or opened into the app to be installed or merged.
     */
    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_TAB)) viewModel.tab = intent.getIntExtra(EXTRA_TAB, TAB_APPS)
        // A shared link, a configuration link or a tapped notification of the Sources tab.
        if (sourcesTab.handleIntent(intent)) {
            viewModel.tab = TAB_SOURCES
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        viewModel.offerFiles(uris)
    }

    override fun onResume() {
        super.onResume()
        // Both grants happen on system settings screens, so re-check every time we come back.
        // This also picks up apps installed, updated or removed in the meantime.
        UpdateNotifications.cancelOutdated(this)
        sourcesTab.onResume()
        viewModel.setPermissions(
            fileAccess = Environment.isExternalStorageManager(),
            canInstall = packageManager.canRequestPackageInstalls(),
        )
    }

    override fun onPause() {
        super.onPause()
        sourcesTab.onPause()
    }

    override fun onDestroy() {
        sourcesTab.onDestroy()
        super.onDestroy()
    }

    private fun render(state: MainUiState) {
        val ready = state.permissionsGranted
        val tab = viewModel.tab
        val appsTab = tab == TAB_APPS
        binding.permissionGroup.isVisible = !ready
        binding.cardFiles.isVisible = !state.hasFileAccess
        binding.cardInstall.isVisible = !state.canInstall
        binding.contentGroup.isVisible = ready
        binding.toggleCloning.isVisible = tab == TAB_APPS || tab == TAB_CLONES
        binding.systemRow.isVisible = tab != TAB_SOURCES
        binding.chipSystem.isVisible = tab != TAB_CLONES
        // The Clones side has the same Refresh as Sources: check everything, update what is behind.
        binding.chipClonesRefresh.isVisible = tab == TAB_CLONES
        binding.chipClonesRefresh.isEnabled = state.updatingClone == null
        binding.listApps.isVisible = appsTab
        binding.listClones.isVisible = tab == TAB_CLONES
        binding.listInstall.isVisible = tab == TAB_INSTALL
        binding.listSplit.isVisible = tab == TAB_SPLIT
        // The Clones side has nothing to pick; its one action is the Refresh chip.
        binding.bottomBar.isVisible = ready && tab != TAB_CLONES
        binding.buttonPickApk.isEnabled = true
        binding.buttonPickApk.setIconResource(R.drawable.ic_folder)
        binding.buttonPickApk.text = when (tab) {
            TAB_INSTALL -> getString(R.string.button_pick_install)
            TAB_SPLIT -> getString(R.string.button_pick_split)
            else -> getString(R.string.button_pick_apk)
        }
        binding.progress.isVisible = state.loading || (tab == TAB_CLONES && state.updatingClone != null)

        val empty = when (tab) {
            TAB_CLONES -> state.clones.isEmpty()
            TAB_SPLIT -> state.splitApps.isEmpty()
            else -> state.apps.isEmpty()
        }
        binding.textEmpty.isVisible = ready && !state.loading && empty
        binding.textEmpty.setText(
            when (tab) {
                TAB_CLONES -> R.string.empty_clones
                TAB_SPLIT -> R.string.empty_split
                else -> R.string.empty_list
            }
        )
        // The Sources tab draws its own state over the shared pieces of the screen.
        sourcesTab.setActive(tab == TAB_SOURCES, ready)
    }

    private fun handle(event: MainEvent) {
        val sheetShown = supportFragmentManager.findFragmentByTag(CloneSheet.TAG) != null ||
            supportFragmentManager.findFragmentByTag(SplitSheet.TAG) != null
        when (event) {
            is MainEvent.SourceReady -> if (!sheetShown) CloneSheet().show(supportFragmentManager, CloneSheet.TAG)
            MainEvent.SplitSourceReady -> if (!sheetShown) SplitSheet().show(supportFragmentManager, SplitSheet.TAG)
            MainEvent.FilesReceived -> askWhatToDoWithFiles()
            // While the sheet is up it covers this window and reports messages itself.
            is MainEvent.Message -> if (!sheetShown) {
                Snackbar.make(binding.root, getString(event.text, event.argument), Snackbar.LENGTH_LONG)
                    .apply { if (binding.bottomBar.isVisible) anchorView = binding.bottomBar }
                    .show()
            }
        }
    }

    /** The same files can be installed as they are or merged into one APK first. */
    private fun askWhatToDoWithFiles() {
        val uris = viewModel.pendingFiles
        if (uris.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_received_title)
            .setItems(arrayOf(getString(R.string.files_action_install), getString(R.string.files_action_merge))) { _, which ->
                val mode = if (which == 0) SplitMode.INSTALL else SplitMode.MERGE
                viewModel.tab = if (which == 0) TAB_INSTALL else TAB_SPLIT
                showTab()
                viewModel.loadSplitFiles(uris, mode)
            }
            .show()
    }

    private fun openSettings(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_APPS = 0
        const val TAB_CLONES = 1
        const val TAB_INSTALL = 2
        const val TAB_SPLIT = 3
        const val TAB_SOURCES = 4

        // Where each tab sits in the strip; both sides of Cloning share one place.
        private const val POSITION_SOURCES = 0
        private const val POSITION_CLONING = 1
        private const val POSITION_INSTALL = 2
        private const val POSITION_SPLIT = 3
        /** For each tab, its name and what it is for. */
        private val TAB_INFO = mapOf(
            TAB_SOURCES to (R.string.tab_sources to R.string.hint_tab_sources),
            TAB_APPS to (R.string.tab_apps to R.string.hint_tab_apps),
            TAB_CLONES to (R.string.cloning_clones to R.string.hint_tab_clones),
            TAB_INSTALL to (R.string.tab_install to R.string.hint_tab_install),
            TAB_SPLIT to (R.string.tab_split to R.string.hint_tab_split),
        )
        private val TAB_TITLES = listOf(R.string.tab_sources, R.string.tab_apps, R.string.tab_install, R.string.tab_split)

        // Many file managers report APKs as a generic binary.
        private val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    }
}
