package io.github.bl3xand.apkcloner.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.databinding.SheetCloneDetailBinding
import kotlinx.coroutines.launch

class CloneDetailSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCloneDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCloneDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Looked up on every change so the versions refresh right after an update,
                    // and the sheet goes away once the clone has been uninstalled.
                    val clone = state.clones.firstOrNull { it.app.packageName == packageName }
                    if (clone == null) dismiss() else render(clone, state.updatingClone, state.uninstallingClone)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(clone: CloneInfo, updating: String?, uninstalling: String?) {
        val busy = updating == clone.app.packageName || uninstalling == clone.app.packageName
        val missing = getString(R.string.detail_not_installed)

        binding.appCard.bind(clone.app, clone.app.packageName)
        binding.textOriginal.text = clone.original?.let { "${it.label}\n${it.packageName}" } ?: clone.originalPackage
        binding.textCloneVersion.text = clone.app.versionName.orEmpty()
        binding.textOriginalVersion.text = clone.original?.let { it.versionName.orEmpty() } ?: missing

        // Shows what the clone was built without, so it is not a mystery later why something is off.
        val removed = clone.removedPermissions
        binding.dividerPermissions.isVisible = removed.isNotEmpty()
        binding.rowPermissions.isVisible = removed.isNotEmpty()
        if (removed.isNotEmpty()) {
            binding.textPermissions.text = removed.sorted().joinToString("\n") { it.substringAfterLast('.') }
        }

        binding.progress.isVisible = busy
        binding.buttonUpdate.isEnabled = clone.updateAvailable && !busy
        binding.buttonDelete.isEnabled = !busy
        binding.buttonUpdate.setOnClickListener { viewModel.updateClone(clone) }
        // The system asks for confirmation; progress shows for the whole wait and the card closes
        // once the clone is gone.
        binding.buttonDelete.setOnClickListener { viewModel.uninstallClone(clone) }
    }

    companion object {
        const val TAG = "clone-detail"
        private const val ARG_PACKAGE = "package"

        fun newInstance(clone: CloneInfo) = CloneDetailSheet().apply {
            arguments = bundleOf(ARG_PACKAGE to clone.app.packageName)
        }
    }
}
