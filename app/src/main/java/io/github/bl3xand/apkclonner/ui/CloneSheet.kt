package io.github.bl3xand.apkclonner.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkclonner.R
import io.github.bl3xand.apkclonner.clone.SignatureMode
import io.github.bl3xand.apkclonner.databinding.SheetCloneBinding
import io.github.bl3xand.apkclonner.install.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloneSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCloneBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCloneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.selected ?: return dismiss()

        binding.textLabel.text = source.label
        binding.textPackage.text = listOfNotNull(source.packageName, source.versionName).joinToString(" · ")
        binding.imageIcon.setImageDrawable(source.appInfo.loadIcon(requireContext().packageManager))
        if (savedInstanceState == null) {
            binding.editPackage.setText(getString(R.string.default_clone_package, source.packageName))
            binding.editName.setText(source.label)
            binding.toggleSignature.check(R.id.buttonSignDebug)
        }

        binding.editPackage.doAfterTextChanged { binding.layoutPackage.error = null }
        binding.toggleSignature.addOnButtonCheckedListener { _, _, _ -> renderSignatureHint() }
        renderSignatureHint()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cloneState.collect(::render)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun signatureMode() =
        if (binding.toggleSignature.checkedButtonId == R.id.buttonSignKeep) SignatureMode.KEEP_ORIGINAL
        else SignatureMode.DEBUG

    private fun renderSignatureHint() {
        binding.textSignatureHint.setText(
            if (signatureMode() == SignatureMode.DEBUG) R.string.signature_debug_hint
            else R.string.signature_keep_hint
        )
    }

    private fun render(state: CloneState) {
        val running = state is CloneState.Running
        isCancelable = !running
        binding.layoutPackage.isEnabled = !running
        binding.layoutName.isEnabled = !running
        binding.buttonSignDebug.isEnabled = !running
        binding.buttonSignKeep.isEnabled = !running
        binding.buttonAction.isEnabled = !running
        binding.progress.isVisible = running
        binding.textStatus.isVisible = state !is CloneState.Idle

        when (state) {
            CloneState.Idle -> showCloneAction()
            is CloneState.Running -> {
                binding.textStatus.text =
                    getString(R.string.status_running, state.file, maxOf(state.index, 1), state.total)
                showCloneAction()
            }
            is CloneState.Failed -> {
                binding.textStatus.text = getString(R.string.status_failed, state.message)
                showCloneAction()
            }
            is CloneState.Done -> {
                binding.textStatus.text = getString(R.string.status_done, state.apks.first().parent)
                binding.buttonAction.setText(R.string.button_install)
                binding.buttonAction.setOnClickListener { install(state) }
            }
        }
    }

    private fun showCloneAction() {
        binding.buttonAction.setText(R.string.button_clone)
        binding.buttonAction.setOnClickListener { startClone() }
    }

    private fun startClone() {
        val newPackage = binding.editPackage.text?.toString().orEmpty().trim()
        if (!PACKAGE_NAME.matches(newPackage)) {
            binding.layoutPackage.error = getString(R.string.error_invalid_package)
            return
        }
        val newName = binding.editName.text?.toString().orEmpty().trim()
            .ifEmpty { viewModel.selected?.label.orEmpty() }
        viewModel.startClone(newPackage, newName, signatureMode())
    }

    private fun install(state: CloneState.Done) {
        val context = requireContext().applicationContext
        binding.buttonAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { ApkInstaller.install(context, state.apks) }.exceptionOrNull()
            }
            val view = _binding ?: return@launch
            view.buttonAction.isEnabled = true
            if (error != null) view.textStatus.text = getString(R.string.install_failed, error.message.orEmpty())
        }
    }

    companion object {
        const val TAG = "clone"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
