package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.compat.versionCodeLong
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.databinding.SheetCloneBinding
import io.github.bl3xand.apkcloner.settings.AppSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CloneSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCloneBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    // A generic type keeps the picker from rewriting the extension, which differs between a
    // single APK and an archive of splits.
    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.cloning.saveResult(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCloneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.cloning.selected ?: return dismiss()

        // An APK picked from storage is not installed, so it has no settings page to open.
        val installed = !source.apkPaths.first().startsWith(requireContext().cacheDir.path)
        binding.appCard.bind(source, source.packageName.takeIf { installed })
        if (savedInstanceState == null) {
            val (packageName, name) = viewModel.cloning.suggest(source)
            binding.options.editPackage.setText(packageName)
            binding.options.editName.setText(name)
        }

        val settings = AppSettings(requireContext())
        binding.options.switchBadge.isChecked = settings.cloneBadge
        binding.options.switchBadge.setOnCheckedChangeListener { _, checked -> settings.cloneBadge = checked }
        binding.options.editPackage.doAfterTextChanged {
            binding.options.layoutPackage.error = null
            renderConflict()
        }
        binding.buttonClone.setOnClickListener { startClone() }
        binding.buttonInstall.setOnClickListener { viewModel.cloning.installResult() }
        binding.options.buttonPermissions.setOnClickListener { pickPermissions(source) }
        renderPermissions()
        binding.buttonSave.setOnClickListener { save() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cloning.state.combine(viewModel.installing, ::Pair).collect { (state, installing) ->
                        render(state, installing)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is MainEvent.Message) {
                            binding.textStatus.text = getString(event.text, event.argument)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        expandFully()
    }

    override fun onResume() {
        super.onResume()
        // The app in the way may have been removed in the meantime.
        renderConflict()
    }

    /**
     * Says so when the package the clone is to have is held by an app that is not a clone made
     * here: the system would refuse to install over it. That app can be removed from the card,
     * unless it is the very one being cloned.
     */
    private fun renderConflict(): Boolean {
        val binding = _binding ?: return false
        val target = binding.options.editPackage.text?.toString().orEmpty().trim()
        val conflict = viewModel.isTakenByOther(target)
        binding.notice.root.isVisible = conflict
        if (!conflict) return false
        binding.notice.look(trouble = true)
        binding.notice.textNotice.setText(R.string.clone_conflict)
        val source = viewModel.cloning.selected
        val isSource = source != null && source.packageName == target && !source.apkPaths.first().startsWith(requireContext().cacheDir.path)
        // Once the clone is built, the app it was made from is no longer needed for it.
        binding.notice.buttonNotice.isVisible = !isSource || viewModel.cloning.state.value is CloneState.Done
        binding.notice.buttonNotice.setText(R.string.clone_conflict_remove)
        binding.notice.buttonNotice.setOnClickListener {
            viewModel.uninstallTaken(target) {
                renderConflict()
                _binding?.let { render(viewModel.cloning.state.value, viewModel.installing.value) }
            }
        }
        return true
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Also called when the sheet is torn down for a rotation, where the result must survive.
        if (activity?.isChangingConfigurations != true) viewModel.cloning.discard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: CloneState, installing: Boolean) {
        val running = state is CloneState.Running
        val done = state is CloneState.Done
        renderConflict()
        isCancelable = !running && !installing
        binding.options.layoutPackage.isEnabled = !running && !done
        binding.options.layoutName.isEnabled = !running && !done
        binding.options.switchBadge.isEnabled = !running && !done
        binding.options.buttonPermissions.isEnabled = !running && !done
        binding.buttonClone.isEnabled = !running
        binding.buttonClone.isVisible = !done
        binding.resultActions.isVisible = done
        // Over a clone that is already there this is an update - and nothing at all to do when
        // that clone already has the version just built.
        val target = binding.options.editPackage.text?.toString().orEmpty().trim()
        val installed = runCatching { requireContext().packageManager.getPackageInfo(target, 0) }.getOrNull()
        val sameVersion = installed != null && installed.versionCodeLong == viewModel.cloning.selected?.versionCode
        binding.buttonInstall.setText(if (installed != null) R.string.button_update else R.string.button_install)
        binding.buttonInstall.isEnabled = !installing && !sameVersion && !viewModel.isTakenByOther(target)
        binding.buttonSave.isEnabled = !installing
        binding.progress.isInvisible = !(running || installing)
        when (state) {
            CloneState.Idle -> Unit
            // While it runs the bar says so; words are kept for how it ended.
            is CloneState.Running -> binding.textStatus.text = ""
            is CloneState.Failed -> binding.textStatus.text = getString(R.string.status_failed, state.message)
            // Left alone otherwise, so the outcome of an install or a save stays on screen.
            is CloneState.Done -> if (lastRunning || binding.textStatus.text.isEmpty()) binding.textStatus.setText(R.string.status_done)
        }
        val hadStatus = binding.textStatus.isVisible
        binding.textStatus.isVisible = binding.textStatus.text.isNotEmpty()
        // The line appears under everything else; it is brought into view when it does.
        if (binding.textStatus.isVisible && !hadStatus) binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        lastRunning = running
    }

    private fun renderPermissions() {
        binding.options.buttonPermissions.text = requireContext().clonePermissionsLabel(viewModel.cloning.removedPermissions.size)
    }

    private fun pickPermissions(source: ApkSource) {
        val context = requireContext()
        context.pickClonePermissions(context.requestedPermissionsOf(source.apkPaths.first()), viewModel.cloning.removedPermissions) { removed ->
            viewModel.cloning.removedPermissions = removed
            renderPermissions()
        }
    }

    /** Whether the previous render was still cloning, i.e. "Done" is news. */
    private var lastRunning = false

    private fun startClone() {
        val newPackage = binding.options.editPackage.text?.toString().orEmpty().trim()
        if (!CloneRequest.PACKAGE_NAME.matches(newPackage)) {
            binding.options.layoutPackage.error = getString(R.string.error_invalid_package)
            return
        }
        val newName = binding.options.editName.text?.toString().orEmpty().trim()
            .ifEmpty { viewModel.cloning.selected?.label.orEmpty() }
        viewModel.cloning.start(newPackage, newName, binding.options.switchBadge.isChecked)
    }

    private fun save() {
        val state = viewModel.cloning.state.value as? CloneState.Done ?: return
        val name = binding.options.editName.text?.toString().orEmpty().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifEmpty { "clone" }
        saveAs.launch(if (state.apks.size == 1) "$name.apk" else "$name.apks")
    }

    companion object {
        const val TAG = "clone"
    }
}
