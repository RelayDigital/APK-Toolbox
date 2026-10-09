package io.github.bl3xand.apkcloner.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.databinding.SheetCloneDetailBinding
import io.github.bl3xand.apkcloner.sources.ui.SourcesViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CloneDetailSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCloneDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    /** A clone that a source keeps current is updated and changed through the tracked apps. */
    private val sources: SourcesViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCloneDetailBinding.inflate(inflater, container, false)
        // A clone is rebuilt or removed in one go: there are no steps to count.
        binding.actions.progress.isIndeterminate = true
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.uiState, sources.repo.downloads, sources.lookingUp, ::Triple).collect { (state, downloads, lookingUp) ->
                    // Looked up on every change so the versions refresh right after an update,
                    // and the sheet goes away once the clone has been uninstalled.
                    val clone = state.clones.firstOrNull { it.app.packageName == packageName }
                    if (clone == null) dismiss()
                    else render(clone, state.updatingClone, state.uninstallingClone, working = clone.tracked?.app?.id in downloads || packageName in lookingUp)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(clone: CloneInfo, updating: String?, uninstalling: String?, working: Boolean) {
        val busy = updating == clone.app.packageName || uninstalling == clone.app.packageName || working
        val missing = getString(R.string.detail_not_installed)
        val context = requireContext()
        val tracked = clone.tracked

        binding.appCard.bind(clone.app, clone.app.packageName)
        binding.textOriginal.text = clone.original?.let { "${it.label}\n${it.packageName}" } ?: clone.originalPackage
        binding.textCloneVersion.text = clone.app.versionName.orEmpty()
        // What the clone is rebuilt from when there is something newer: a source, or the installed app.
        binding.textUpdatedFrom.setText(if (clone.original != null) R.string.detail_from_original else R.string.detail_from_nothing)
        tracked?.let { binding.textUpdatedFrom.text = it.sourceName() }
        binding.textOriginalVersionTitle.setText(if (tracked != null) R.string.detail_latest_version else R.string.detail_original_version)
        binding.textOriginalVersion.text = tracked?.app?.latestVersion ?: clone.original?.let { it.versionName.orEmpty() } ?: missing

        // Shows what the clone was built without, so it is not a mystery later why something is off.
        val removed = tracked?.app?.cloneRemovedPermissions ?: clone.removedPermissions
        binding.dividerPermissions.isVisible = removed.isNotEmpty()
        binding.rowPermissions.isVisible = removed.isNotEmpty()
        if (removed.isNotEmpty()) binding.textPermissions.text = removed.sorted().joinToString("\n") { it.substringAfterLast('.') }

        // The same editor as when the clone was made. A change is applied by building the clone
        // again and installing it over itself, so the data is kept. Needs something to build from.
        val original = clone.original
        binding.permissions.buttonPermissions.text = context.clonePermissionsLabel(removed.size)
        binding.permissions.buttonPermissions.isEnabled = clone.canRebuild && !busy
        binding.permissions.buttonPermissions.setOnClickListener {
            if (tracked != null) {
                sources.clonePermissions(tracked.app.id) { id, requested ->
                    val app = sources.repo.entry(id)?.app ?: return@clonePermissions
                    context.pickClonePermissions(requested, app.cloneRemovedPermissions, fresh = app.cloneNewPermissions) {
                        sources.setClonePermissions(id, it, requested)
                    }
                }
            } else if (original != null) {
                context.pickClonePermissions(context.requestedPermissionsOf(original.apkPaths.first()), removed) { viewModel.setClonePermissions(clone, it) }
            }
        }
        binding.permissions.buttonResetPermissions.isVisible = removed.isNotEmpty()
        binding.permissions.buttonResetPermissions.isEnabled = clone.canRebuild && !busy
        binding.permissions.buttonResetPermissions.setOnClickListener {
            if (tracked != null) sources.setClonePermissions(tracked.app.id, emptySet(), tracked.app.cloneRequestedPermissions)
            else viewModel.setClonePermissions(clone, emptySet())
        }
        // A clone lives on what it is rebuilt from. One that has only an installed original - an
        // app from Google Play, say - can be handed to a source that carries the same app, and
        // then goes on being updated when the original is gone.
        binding.buttonTrack.isVisible = tracked == null
        binding.buttonTrack.isEnabled = !busy
        binding.buttonTrack.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (context.confirm(getString(R.string.clone_track), getString(R.string.clone_track_message))) {
                    sources.trackClone(clone.originalPackage, clone.app.packageName)
                }
            }
        }

        binding.actions.progress.isInvisible = !busy
        binding.actions.buttonUpdate.isEnabled = clone.updateAvailable && !busy
        binding.actions.buttonDelete.isEnabled = !busy
        binding.actions.buttonUpdate.setOnClickListener {
            if (tracked != null) sources.obtain(listOf(tracked.app.id)) else viewModel.updateClone(clone)
        }
        // The system asks for confirmation; progress shows for the whole wait and the card closes
        // once the clone is gone.
        binding.actions.buttonDelete.setOnClickListener { viewModel.uninstallClone(clone) }
    }

    companion object {
        const val TAG = "clone-detail"
        private const val ARG_PACKAGE = "package"

        fun newInstance(clone: CloneInfo) = CloneDetailSheet().apply {
            arguments = bundleOf(ARG_PACKAGE to clone.app.packageName)
        }
    }
}
