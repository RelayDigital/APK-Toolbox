package io.github.bl3xand.apkcloner.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.SheetSplitBinding
import io.github.bl3xand.apkcloner.merge.SplitSelection
import io.github.bl3xand.apkcloner.merge.SplitSource
import io.github.bl3xand.apkcloner.settings.AppSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One sheet for everything that starts from a set of split APKs: installing them, exporting an
 * installed app as an .apks archive, or merging them into a single APK.
 */
class SplitSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSplitBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private val boxes = LinkedHashMap<String, MaterialCheckBox>()
    private var lastRunning = false

    // A generic type keeps the picker from rewriting the extension.
    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.splits.saveMerged(uri)
    }
    private val exportAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.splits.export(selected(), uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSplitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.splits.source ?: return dismiss()
        val settings = AppSettings(requireContext())

        val subtitle = listOfNotNull(source.packageName, source.versionName).joinToString(" · ")
        // Only an installed app has a settings page to open.
        val installed = source.bundle == null && source.entries.firstOrNull()?.file?.path?.startsWith("/data/app") == true
        binding.appCard.bind(
            source.label, subtitle, source.appInfo?.let { AppIcons.load(requireContext().packageManager, it) },
            source.packageName.takeIf { installed },
        )

        buildSplitList(source)
        if (savedInstanceState == null) select(source.entries.map { it.name }.toSet())
        binding.chipAll.setOnClickListener { select(source.entries.map { it.name }.toSet()) }
        binding.chipDevice.setOnClickListener {
            select(SplitSelection.forDevice(requireContext(), source.entries.map { it.name }, source.baseName))
        }

        val mode = viewModel.splits.mode
        val fileName = source.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "app" }
        binding.switchSign.isVisible = mode != SplitMode.EXPORT
        binding.switchForce.isVisible = mode == SplitMode.MERGE
        binding.textSignDescription.isVisible = binding.switchSign.isVisible
        binding.textForceDescription.isVisible = binding.switchForce.isVisible
        binding.textForceDescription.setText(R.string.merge_force_description)
        when (mode) {
            SplitMode.INSTALL -> {
                binding.switchSign.setText(R.string.install_sign)
                binding.textSignDescription.setText(R.string.install_sign_description)
                binding.switchSign.isChecked = settings.installSign
                binding.switchSign.setOnCheckedChangeListener { _, checked -> settings.installSign = checked }
                binding.buttonAction.setText(R.string.button_install)
                binding.buttonAction.setIconResource(R.drawable.ic_install)
                if (source.obbEntries.isNotEmpty()) {
                    val size = Formatter.formatShortFileSize(requireContext(), source.obbEntries.sumOf { it.size })
                    binding.switchObb.isVisible = true
                    binding.switchObb.text = getString(R.string.install_copy_obb, source.obbEntries.size, size)
                }
                binding.buttonAction.setOnClickListener {
                    viewModel.splits.startInstall(
                        selected(), binding.switchSign.isChecked,
                        copyObb = binding.switchObb.isVisible && binding.switchObb.isChecked,
                    )
                }
            }
            SplitMode.EXPORT -> {
                binding.buttonAction.setText(R.string.button_export)
                binding.buttonAction.setIconResource(R.drawable.ic_folder)
                binding.buttonAction.setOnClickListener {
                    exportAs.launch(listOfNotNull(fileName, source.versionName).joinToString("_") + ".apks")
                }
            }
            SplitMode.MERGE -> {
                binding.switchSign.setText(R.string.merge_sign)
                binding.textSignDescription.setText(R.string.merge_sign_description)
                binding.switchSign.isChecked = settings.mergeSign
                binding.switchForce.isChecked = settings.mergeForce
                binding.switchSign.setOnCheckedChangeListener { _, checked -> settings.mergeSign = checked }
                binding.switchForce.setOnCheckedChangeListener { _, checked -> settings.mergeForce = checked }
                binding.buttonAction.setText(R.string.button_merge)
                binding.buttonAction.setIconResource(R.drawable.ic_merge)
                binding.buttonAction.setOnClickListener {
                    viewModel.splits.startMerge(selected(), binding.switchSign.isChecked, binding.switchForce.isChecked)
                }
            }
        }
        binding.buttonInstall.setOnClickListener { viewModel.splits.installMerged() }
        binding.buttonSave.setOnClickListener { saveAs.launch(fileName + MERGED_SUFFIX) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.splits.state.combine(viewModel.installing, ::Pair).collect { (state, installing) ->
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
        expandFully()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Also called when the sheet is torn down for a rotation, where the result must survive.
        if (activity?.isChangingConfigurations != true) viewModel.splits.discard()
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
        val base = viewModel.splits.source?.baseName
        for ((name, box) in boxes) box.isChecked = name == base || name in names
        updateTitle()
    }

    private fun selected(): Set<String> = boxes.filterValues { it.isChecked }.keys

    private fun updateTitle() {
        binding.textSplitsTitle.text = getString(R.string.merge_splits_title, selected().size, boxes.size)
    }

    private fun render(state: SplitState, installing: Boolean) {
        val running = state is SplitState.Running
        val done = state as? SplitState.Done
        isCancelable = !running && !installing

        val editable = !running && !installing && done == null
        boxes.forEach { (name, box) -> box.isEnabled = editable && name != viewModel.splits.source?.baseName }
        binding.chipAll.isEnabled = editable
        binding.chipDevice.isEnabled = editable
        binding.switchSign.isEnabled = editable
        binding.switchForce.isEnabled = editable
        binding.switchObb.isEnabled = editable
        binding.buttonAction.isEnabled = !running && !installing
        binding.buttonAction.isVisible = done == null
        binding.resultActions.isVisible = done != null
        // An unsigned APK cannot be installed, only saved.
        binding.buttonInstall.isEnabled = done?.result?.signed == true && !installing
        binding.buttonSave.isEnabled = !installing
        binding.progress.isInvisible = !(running || installing)

        when (state) {
            // Idle is also where installing and exporting end up; their outcome arrives as a message.
            SplitState.Idle -> Unit
            // While it runs the bar says so; words are kept for how it ended.
            is SplitState.Running -> binding.textStatus.text = ""
            is SplitState.Failed -> binding.textStatus.text = getString(R.string.merge_failed, state.message)
            is SplitState.TooLarge -> binding.textStatus.text = getString(R.string.merge_too_large, state.megabytes)
            is SplitState.Mismatch ->
                binding.textStatus.text = getString(R.string.merge_mismatch, state.splits.joinToString())
            // Left alone otherwise, so the outcome of an install or a save stays on screen.
            is SplitState.Done -> if (lastRunning || binding.textStatus.text.isEmpty()) {
                binding.textStatus.setText(
                    when {
                        state.result.pairip -> R.string.merge_done_pairip
                        state.result.signed -> R.string.merge_done
                        else -> R.string.merge_done_unsigned
                    }
                )
            }
        }
        val hadStatus = binding.textStatus.isVisible
        binding.textStatus.isVisible = binding.textStatus.text.isNotEmpty()
        // The line appears under everything else; it is brought into view when it does.
        if (binding.textStatus.isVisible && !hadStatus) binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        lastRunning = running
    }

    companion object {
        const val TAG = "merge"
        private const val MERGED_SUFFIX = "_antisplit.apk"
    }
}
