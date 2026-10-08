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
import io.github.bl3xand.apkcloner.update.UpdateNotifications
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

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

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_apps))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_clones))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_install))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_split))
        if (savedInstanceState == null) handleIntent(intent)
        binding.tabs.getTabAt(viewModel.tab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.tab = tab.position
                render(viewModel.uiState.value)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.editSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.chipSystem.setOnCheckedChangeListener { _, checked -> viewModel.setShowSystem(checked) }
        binding.buttonGrantFiles.setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        }
        binding.buttonGrantInstall.setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        }
        binding.buttonPickApk.setOnClickListener {
            // Bundles have no MIME type of their own, so the pickers for them accept anything.
            when (viewModel.tab) {
                TAB_CLONES -> viewModel.updateAll()
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
        binding.tabs.getTabAt(viewModel.tab)?.select()
    }

    /**
     * Besides a plain launch: the "updates available" notification asks for the Clones tab, and
     * APK files can be shared or opened into the app to be installed or merged.
     */
    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_TAB)) viewModel.tab = intent.getIntExtra(EXTRA_TAB, TAB_APPS)
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
        viewModel.setPermissions(
            fileAccess = Environment.isExternalStorageManager(),
            canInstall = packageManager.canRequestPackageInstalls(),
        )
    }

    private fun render(state: MainUiState) {
        val ready = state.permissionsGranted
        val tab = viewModel.tab
        val appsTab = tab == TAB_APPS
        binding.permissionGroup.isVisible = !ready
        binding.cardFiles.isVisible = !state.hasFileAccess
        binding.cardInstall.isVisible = !state.canInstall
        binding.contentGroup.isVisible = ready
        binding.chipSystem.isVisible = tab != TAB_CLONES
        binding.listApps.isVisible = appsTab
        binding.listClones.isVisible = tab == TAB_CLONES
        binding.listInstall.isVisible = tab == TAB_INSTALL
        binding.listSplit.isVisible = tab == TAB_SPLIT
        // On the Clones tab the bar only appears when there is something to update.
        binding.bottomBar.isVisible = ready && (tab != TAB_CLONES || state.outdatedClones > 0)
        binding.buttonPickApk.isEnabled = tab != TAB_CLONES || state.updatingClone == null
        binding.buttonPickApk.setIconResource(if (tab == TAB_CLONES) R.drawable.ic_update else R.drawable.ic_folder)
        binding.buttonPickApk.text = when (tab) {
            TAB_CLONES -> getString(R.string.button_update_all, state.outdatedClones)
            TAB_INSTALL -> getString(R.string.button_pick_install)
            TAB_SPLIT -> getString(R.string.button_pick_split)
            else -> getString(R.string.button_pick_apk)
        }
        binding.progress.isVisible = state.loading

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
                binding.tabs.getTabAt(viewModel.tab)?.select()
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

        // Many file managers report APKs as a generic binary.
        private val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    }
}
