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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadApkFile(uri)
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
        binding.listApps.adapter = appAdapter
        binding.listClones.adapter = cloneAdapter

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_apps))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_clones))
        if (savedInstanceState == null) selectTabFrom(intent)
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
        binding.buttonPickApk.setOnClickListener { pickApk.launch(APK_MIME_TYPES) }
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
        selectTabFrom(intent)
        binding.tabs.getTabAt(viewModel.tab)?.select()
    }

    /** The "updates available" notification asks for the Clones tab. */
    private fun selectTabFrom(intent: Intent) {
        if (intent.hasExtra(EXTRA_TAB)) viewModel.tab = intent.getIntExtra(EXTRA_TAB, TAB_APPS)
    }

    override fun onResume() {
        super.onResume()
        // Both grants happen on system settings screens, so re-check every time we come back.
        // This also picks up apps installed, updated or removed in the meantime.
        viewModel.setPermissions(
            fileAccess = Environment.isExternalStorageManager(),
            canInstall = packageManager.canRequestPackageInstalls(),
        )
    }

    private fun render(state: MainUiState) {
        val ready = state.permissionsGranted
        val appsTab = viewModel.tab == TAB_APPS
        binding.permissionGroup.isVisible = !ready
        binding.cardFiles.isVisible = !state.hasFileAccess
        binding.cardInstall.isVisible = !state.canInstall
        binding.contentGroup.isVisible = ready
        binding.chipSystem.isVisible = appsTab
        binding.listApps.isVisible = appsTab
        binding.listClones.isVisible = !appsTab
        binding.bottomBar.isVisible = ready && appsTab
        binding.progress.isVisible = state.loading

        val empty = if (appsTab) state.apps.isEmpty() else state.clones.isEmpty()
        binding.textEmpty.isVisible = ready && !state.loading && empty
        binding.textEmpty.setText(if (appsTab) R.string.empty_list else R.string.empty_clones)
    }

    private fun handle(event: MainEvent) {
        val sheetShown = supportFragmentManager.findFragmentByTag(CloneSheet.TAG) != null
        when (event) {
            is MainEvent.SourceReady -> if (!sheetShown) CloneSheet().show(supportFragmentManager, CloneSheet.TAG)
            // While the sheet is up it covers this window and reports messages itself.
            is MainEvent.Message -> if (!sheetShown) {
                Snackbar.make(binding.root, getString(event.text, event.argument), Snackbar.LENGTH_LONG)
                    .apply { if (binding.bottomBar.isVisible) anchorView = binding.bottomBar }
                    .show()
            }
        }
    }

    private fun openSettings(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_APPS = 0
        const val TAB_CLONES = 1

        // Many file managers report APKs as a generic binary.
        private val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    }
}
