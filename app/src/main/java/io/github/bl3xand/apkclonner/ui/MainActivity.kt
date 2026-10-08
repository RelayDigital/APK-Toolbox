package io.github.bl3xand.apkclonner.ui

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
import io.github.bl3xand.apkclonner.R
import io.github.bl3xand.apkclonner.databinding.ActivityMainBinding
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
            binding.listApps.updatePadding(bottom = listBottomPadding + bars.bottom)
            insets
        }

        val adapter = AppAdapter(lifecycleScope, packageManager, viewModel::select)
        binding.listApps.adapter = adapter

        binding.editSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.chipSystem.setOnCheckedChangeListener { _, checked -> viewModel.setShowSystem(checked) }
        binding.buttonGrant.setOnClickListener { requestFileAccess() }
        binding.buttonPickApk.setOnClickListener { pickApk.launch(APK_MIME_TYPES) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        render(state)
                        adapter.submitList(state.apps)
                    }
                }
                launch { viewModel.events.collect(::handle) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The grant happens on a system settings screen, so re-check every time we come back.
        viewModel.setFileAccess(Environment.isExternalStorageManager())
    }

    private fun render(state: MainUiState) {
        binding.accessGroup.isVisible = !state.hasFileAccess
        binding.contentGroup.isVisible = state.hasFileAccess
        binding.bottomBar.isVisible = state.hasFileAccess
        binding.progress.isVisible = state.loading
        binding.textEmpty.isVisible = state.hasFileAccess && !state.loading && state.apps.isEmpty()
    }

    private fun handle(event: MainEvent) {
        when (event) {
            is MainEvent.SourceReady ->
                if (supportFragmentManager.findFragmentByTag(CloneSheet.TAG) == null) {
                    CloneSheet().show(supportFragmentManager, CloneSheet.TAG)
                }
            MainEvent.InvalidApk -> Snackbar.make(binding.root, R.string.error_invalid_apk, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.bottomBar).show()
        }
    }

    private fun requestFileAccess() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private companion object {
        // Many file managers report APKs as a generic binary.
        val APK_MIME_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    }
}
