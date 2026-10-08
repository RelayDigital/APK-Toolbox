package io.github.bl3xand.apkcloner.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkcloner.R
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
        if (uri != null) viewModel.saveResult(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCloneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.selected ?: return dismiss()

        // An APK picked from storage is not installed, so it has no settings page to open.
        val installed = !source.apkPaths.first().startsWith(requireContext().cacheDir.path)
        binding.appCard.bind(source, source.packageName.takeIf { installed })
        if (savedInstanceState == null) {
            val (packageName, name) = viewModel.suggestClone(source)
            binding.editPackage.setText(packageName)
            binding.editName.setText(name)
        }

        val settings = AppSettings(requireContext())
        binding.switchBadge.isChecked = settings.cloneBadge
        binding.switchBadge.setOnCheckedChangeListener { _, checked -> settings.cloneBadge = checked }
        binding.editPackage.doAfterTextChanged { binding.layoutPackage.error = null }
        binding.buttonClone.setOnClickListener { startClone() }
        binding.buttonInstall.setOnClickListener { viewModel.installResult() }
        binding.buttonSave.setOnClickListener { save() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cloneState.combine(viewModel.installing, ::Pair).collect { (state, installing) ->
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
        // The form is short; a half-open sheet would hide the action button below the fold.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Also called when the sheet is torn down for a rotation, where the result must survive.
        if (activity?.isChangingConfigurations != true) viewModel.discardResult()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: CloneState, installing: Boolean) {
        val running = state is CloneState.Running
        val done = state is CloneState.Done
        isCancelable = !running && !installing
        binding.layoutPackage.isEnabled = !running && !done
        binding.layoutName.isEnabled = !running && !done
        binding.switchBadge.isEnabled = !running && !done
        binding.buttonClone.isEnabled = !running
        binding.buttonClone.isVisible = !done
        binding.resultActions.isVisible = done
        binding.buttonInstall.isEnabled = !installing
        binding.buttonSave.isEnabled = !installing
        binding.progress.isVisible = running || installing
        binding.textStatus.isVisible = state !is CloneState.Idle

        when (state) {
            CloneState.Idle -> Unit
            is CloneState.Running -> binding.textStatus.text =
                getString(R.string.status_running, state.file, maxOf(state.index, 1), state.total)
            is CloneState.Failed -> binding.textStatus.text = getString(R.string.status_failed, state.message)
            // Left alone otherwise, so the outcome of an install or a save stays on screen.
            is CloneState.Done -> if (installing) {
                binding.textStatus.setText(R.string.status_installing)
            } else if (lastRunning || binding.textStatus.text.toString() in setOf("", getString(R.string.status_installing))) {
                binding.textStatus.setText(R.string.status_done)
            }
        }
        lastRunning = running
    }

    /** Whether the previous render was still cloning, i.e. "Done" is news. */
    private var lastRunning = false

    private fun startClone() {
        val newPackage = binding.editPackage.text?.toString().orEmpty().trim()
        if (!PACKAGE_NAME.matches(newPackage)) {
            binding.layoutPackage.error = getString(R.string.error_invalid_package)
            return
        }
        val newName = binding.editName.text?.toString().orEmpty().trim()
            .ifEmpty { viewModel.selected?.label.orEmpty() }
        viewModel.startClone(newPackage, newName, binding.switchBadge.isChecked)
    }

    private fun save() {
        val state = viewModel.cloneState.value as? CloneState.Done ?: return
        val name = binding.editName.text?.toString().orEmpty().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifEmpty { "clone" }
        saveAs.launch(if (state.apks.size == 1) "$name.apk" else "$name.apks")
    }

    companion object {
        const val TAG = "clone"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
