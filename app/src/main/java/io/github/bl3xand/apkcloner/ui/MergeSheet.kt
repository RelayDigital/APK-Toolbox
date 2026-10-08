package io.github.bl3xand.apkcloner.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.SheetMergeBinding
import io.github.bl3xand.apkcloner.merge.MergeStep
import io.github.bl3xand.apkcloner.merge.SplitSelection
import io.github.bl3xand.apkcloner.merge.SplitSource
import io.github.bl3xand.apkcloner.settings.AppSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MergeSheet : BottomSheetDialogFragment() {

    private var _binding: SheetMergeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private val boxes = LinkedHashMap<String, MaterialCheckBox>()
    private var lastRunning = false

    // A generic type keeps the picker from rewriting the .apk extension.
    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.saveMerged(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetMergeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.mergeSource ?: return dismiss()
        val settings = AppSettings(requireContext())

        val subtitle = listOfNotNull(source.packageName, source.versionName).joinToString(" · ")
        // Only an installed app has a settings page to open.
        val installed = source.bundle == null && source.entries.firstOrNull()?.file?.path?.startsWith("/data/app") == true
        binding.appCard.bind(
            source.label, subtitle, source.appInfo?.loadIcon(requireContext().packageManager),
            source.packageName.takeIf { installed },
        )

        buildSplitList(source)
        if (savedInstanceState == null) select(source.entries.map { it.name }.toSet())
        binding.chipAll.setOnClickListener { select(source.entries.map { it.name }.toSet()) }
        binding.chipDevice.setOnClickListener {
            select(SplitSelection.forDevice(requireContext(), source.entries.map { it.name }, source.baseName))
        }

        binding.switchSign.isChecked = settings.mergeSign
        binding.switchForce.isChecked = settings.mergeForce
        binding.switchSign.setOnCheckedChangeListener { _, checked -> settings.mergeSign = checked }
        binding.switchForce.setOnCheckedChangeListener { _, checked -> settings.mergeForce = checked }

        binding.buttonMerge.setOnClickListener {
            viewModel.startMerge(selected(), binding.switchSign.isChecked, binding.switchForce.isChecked)
        }
        binding.buttonInstall.setOnClickListener { viewModel.installMerged() }
        binding.buttonSave.setOnClickListener {
            val name = source.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "merged" }
            saveAs.launch(name + OUTPUT_SUFFIX)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.mergeState.combine(viewModel.installing, ::Pair).collect { (state, installing) ->
                        render(state, installing)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is MainEvent.Message) binding.textStatus.text = getString(event.text, event.argument)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Also called when the sheet is torn down for a rotation, where the result must survive.
        if (activity?.isChangingConfigurations != true) viewModel.discardMerge()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        boxes.clear()
        _binding = null
    }

    private fun buildSplitList(source: SplitSource) {
        for (entry in source.entries) {
            val box = MaterialCheckBox(requireContext()).apply {
                val size = Formatter.formatShortFileSize(context, entry.size)
                text = getString(R.string.merge_split_item, entry.name.substringAfterLast('/'), size)
                // Nothing can be merged without the base.
                isEnabled = entry.name != source.baseName
                setOnCheckedChangeListener { _, _ -> updateTitle() }
            }
            boxes[entry.name] = box
            binding.listSplits.addView(box)
        }
    }

    private fun select(names: Set<String>) {
        val base = viewModel.mergeSource?.baseName
        for ((name, box) in boxes) box.isChecked = name == base || name in names
        updateTitle()
    }

    private fun selected(): Set<String> = boxes.filterValues { it.isChecked }.keys

    private fun updateTitle() {
        binding.textSplitsTitle.text = getString(R.string.merge_splits_title, selected().size, boxes.size)
    }

    private fun render(state: MergeState, installing: Boolean) {
        val running = state is MergeState.Running
        val done = state as? MergeState.Done
        isCancelable = !running && !installing

        val editable = !running && done == null
        boxes.forEach { (name, box) -> box.isEnabled = editable && name != viewModel.mergeSource?.baseName }
        binding.chipAll.isEnabled = editable
        binding.chipDevice.isEnabled = editable
        binding.switchSign.isEnabled = editable
        binding.switchForce.isEnabled = editable
        binding.buttonMerge.isEnabled = !running
        binding.buttonMerge.isVisible = done == null
        binding.resultActions.isVisible = done != null
        // An unsigned APK cannot be installed, only saved.
        binding.buttonInstall.isEnabled = done?.result?.signed == true && !installing
        binding.buttonSave.isEnabled = !installing
        binding.progress.isVisible = running || installing
        binding.textStatus.isVisible = state !is MergeState.Idle

        when (state) {
            MergeState.Idle -> Unit
            is MergeState.Running -> binding.textStatus.setText(
                when (state.step) {
                    MergeStep.EXTRACTING -> R.string.merge_step_extracting
                    MergeStep.MERGING -> R.string.merge_step_merging
                    MergeStep.SAVING -> R.string.merge_step_saving
                    MergeStep.SIGNING -> R.string.merge_step_signing
                }
            )
            is MergeState.Failed -> binding.textStatus.text = getString(R.string.merge_failed, state.message)
            is MergeState.Mismatch ->
                binding.textStatus.text = getString(R.string.merge_mismatch, state.splits.joinToString())
            // Left alone otherwise, so the outcome of an install or a save stays on screen.
            is MergeState.Done -> if (installing) {
                binding.textStatus.setText(R.string.status_installing)
            } else if (lastRunning || binding.textStatus.text.toString() in setOf("", getString(R.string.status_installing))) {
                binding.textStatus.setText(
                    when {
                        state.result.pairip -> R.string.merge_done_pairip
                        state.result.signed -> R.string.merge_done
                        else -> R.string.merge_done_unsigned
                    }
                )
            }
        }
        lastRunning = running
    }

    companion object {
        const val TAG = "merge"
        private const val OUTPUT_SUFFIX = "_antisplit.apk"
    }
}
