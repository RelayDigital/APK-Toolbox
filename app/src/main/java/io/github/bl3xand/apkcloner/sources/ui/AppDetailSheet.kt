package io.github.bl3xand.apkcloner.sources.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.SheetSourceDetailBinding
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.RepositoryRenamedError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.formatBytes
import io.github.bl3xand.apkcloner.sources.core.formatDownloadSize
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.form.cloneItems
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.Downloader
import io.github.bl3xand.apkcloner.ui.bind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tracked app: what is installed, what is available, and the two things usually done with
 * it. Everything else sits behind "More".
 */
class AppDetailSheet : BottomSheetDialogFragment() {
    private val viewModel: SourcesViewModel by activityViewModels()
    private var _binding: SheetSourceDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var dialogs: SourcesDialogs
    private val appId: String get() = requireArguments().getString(ARG_ID)!!
    private var probedSize: Long? = null
    private var probedKey: String? = null
    private var lastRendered: Pair<TrackedApp, DownloadState?>? = null
    private var buttonLook: Pair<String, Int>? = null
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSourceDetailBinding.inflate(inflater, container, false)
        buttonLook = null
        binding.buttonDelete.text = Tr.get("remove")
        dialogs = SourcesDialogs(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.repo.apps, viewModel.repo.downloads) { _, downloads -> downloads[appId] }.collect { download ->
                    val entry = viewModel.repo.entry(appId)
                    if (entry == null) {
                        dismissAllowingStateLoss()
                    } else if (lastRendered != entry.app to download) {
                        lastRendered = entry.app to download
                        render(entry, download)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun rerender() {
        lastRendered = null
        if (_binding == null) return
        viewModel.repo.entry(appId)?.let { render(it, viewModel.repo.downloads.value[appId]) }
    }

    /** Asks the server how big the download is, once per release. */
    private fun probeSize(entry: AppEntry) {
        val app = entry.app
        val chosen = app.apkUrls.getOrNull(app.preferredApkIndex.coerceAtLeast(0))
        val key = "${app.latestVersion}|${chosen?.url}|${app.releaseUrl}"
        if (key == probedKey) return
        probedKey = key
        probedSize = null
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching {
                    val source = viewModel.repo.sourceOf(app)
                    if (chosen != null && chosen.url != "placeholder") {
                        ApkFilter.splitMultiApkUrl(chosen.url).mapNotNull { raw ->
                            val url = source.assetUrlPrefetchModifier(raw, app.url, app.additionalSettings)
                            Downloader.getDownloadSize(
                                url, source.getRequestHeaders(app.additionalSettings, url, forAPKDownload = true),
                                source.requestOptions(app.additionalSettings),
                            )
                        }.takeIf { it.isNotEmpty() }?.sum()
                    } else {
                        source.resolveDownloadSize(app.url, app.additionalSettings, app.releaseUrl)
                    }
                }.getOrNull()
            }
            if (size != null && probedKey == key) {
                probedSize = size
                rerender()
            }
        }
    }

    /** The notes of the latest release, cut short; the whole text is one tap away. */
    private fun renderChanges(entry: AppEntry) {
        val app = entry.app
        val source = runCatching { viewModel.repo.sourceOf(app) }.getOrNull()
        val notes = app.changeLog?.trim()?.takeIf { it.isNotEmpty() && !Regex("^(http|ftp|https)://\\S+$").matches(it) }
        val hasPage = !app.changeLog.isNullOrBlank() || !app.releaseUrl.isNullOrEmpty() ||
            source?.changeLogPageFromStandardUrl(app.url) != null
        binding.cardChanges.isVisible = notes != null || hasPage
        if (!binding.cardChanges.isVisible) return
        binding.textChangesTitle.text = "${Tr.get("detWhatsNew")} · ${app.latestVersion}"
        binding.textChanges.isVisible = notes != null
        if (notes != null) {
            binding.textChanges.text = if (source?.changeLogIfAnyIsMarkDown == true) markdownToSpanned(notes) else notes
        }
        binding.textChangesMore.text = Tr.get(if (notes != null) "detShowAll" else "detReleasePage")
        binding.cardChanges.setOnClickListener { dialogs.showChanges(entry) }
    }

    /** A "name — value" line of the info card, in the style of the clone details. */
    private fun infoRow(name: String, value: CharSequence, first: Boolean, onClick: (() -> Unit)? = null) {
        val context = requireContext()
        if (!first) binding.infoRows.addView(MaterialDivider(context))
        val row = LinearLayout(context).apply { setPadding(0, context.dp(10), 0, context.dp(10)) }
        row.addView(
            context.label(name, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f),
        )
        row.addView(
            context.label(value, com.google.android.material.R.attr.textAppearanceBodyLarge).apply {
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                if (onClick != null) {
                    setTextColor(context.themeColor(androidx.appcompat.R.attr.colorPrimary))
                    setOnClickListener { onClick() }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f),
        )
        binding.infoRows.addView(row)
    }

    private fun render(entry: AppEntry, download: DownloadState?) {
        val context = context ?: return
        val app = entry.app
        val repo = viewModel.repo
        val source = runCatching { repo.sourceOf(app) }.getOrNull()
        val trackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY)
        val installed = app.installedVersion

        // The same card as everywhere else; a tap opens the app's page in the system settings.
        binding.appCard.bindTracked(entry)

        val conflict = viewModel.installer.hasSignerConflict(entry)
        binding.cardNotice.isVisible = app.hasPendingRepoRename || conflict
        binding.buttonNotice.isVisible = app.hasPendingRepoRename
        // A signer conflict is a hard block, so it gets the bright error colours; the repo-rename
        // notice is only informational and keeps the quieter secondary colours.
        val noticeBg = if (conflict) {
            com.google.android.material.R.attr.colorErrorContainer
        } else {
            com.google.android.material.R.attr.colorSecondaryContainer
        }
        val noticeFg = if (conflict) {
            com.google.android.material.R.attr.colorOnErrorContainer
        } else {
            com.google.android.material.R.attr.colorOnSecondaryContainer
        }
        binding.cardNotice.setCardBackgroundColor(context.themeColor(noticeBg))
        binding.textNotice.setTextColor(context.themeColor(noticeFg))
        if (conflict) {
            binding.textNotice.text = Tr.get("detSignerConflict")
        } else if (app.hasPendingRepoRename) {
            binding.textNotice.text = "${Tr.get("repoRenamedExplanation")}\n\n${app.pendingRepoRenameUrl}"
            binding.buttonNotice.text = Tr.get("updateUrl")
            binding.buttonNotice.setOnClickListener {
                viewModel.update(listOf(appId)) { it.copy(url = it.pendingRepoRenameUrl ?: it.url, pendingRepoRenameUrl = null) }
            }
        }

        binding.infoRows.removeAllViews()
        infoRow(
            Tr.get("detSource"), listOfNotNull(source?.name, entry.author.takeIf { it.isNotBlank() && it != source?.name }).joinToString(" · "), first = true,
        ) { context.openUrl(app.url) }
        infoRow(
            Tr.get("detInstalled"),
            when {
                installed == null -> Tr.get("notInstalled")
                trackOnly -> "$installed · ${Tr.get("trackOnly")}"
                else -> installed
            },
            first = false,
        )
        infoRow(Tr.get("detLatest"), app.latestVersion, first = false)
        // Only while there is something to download.
        if (installed == null || installed != app.latestVersion) {
            probedSize?.let { infoRow(Tr.get("detSize"), formatBytes(it), first = false) }
        }
        app.releaseDate?.let { infoRow(Tr.get("detReleased"), dateFormat.format(it), first = false) }
        infoRow(Tr.get("detChecked"), app.lastUpdateCheck?.let(timeFormat::format) ?: Tr.get("never"), first = false)

        renderChanges(entry)

        // Progress of a running download or install.
        val busy = download != null
        binding.progress.isVisible = false
        binding.progress.isIndeterminate = (download?.progress ?: 0.0) < 0
        if (download != null && download.progress >= 0) binding.progress.setProgressCompat(download.progress.toInt(), false)
        binding.progress.isVisible = busy
        binding.textProgress.isVisible = download != null
        if (download != null) {
            binding.textProgress.text = if (download.progress < 0) Tr.get("installing")
            else "${download.progress.toInt()}%  ${formatDownloadSize(download.receivedBytes, download.totalBytes) ?: ""}"
        }

        val canAct = !busy && !conflict && (installed == null || installed != app.latestVersion) && !repo.areDownloadsRunning()
        val label = when {
            installed == null -> Tr.get(if (trackOnly) "markInstalled" else "install")
            else -> Tr.get(if (trackOnly) "markUpdated" else "update")
        }
        // The two buttons share a group that animates their widths, so they are only touched
        // when something about them really changes - not on every tick of a download.
        val cancellable = download != null && download.progress in 0.0..99.0
        val look = if (cancellable) Tr.get("cancel") to R.drawable.ic_close
        else label to (if (installed == null) R.drawable.ic_download else R.drawable.ic_update)
        if (buttonLook != look) {
            buttonLook = look
            binding.buttonUpdate.text = look.first
            binding.buttonUpdate.setIconResource(look.second)
        }
        val enabled = cancellable || canAct
        if (binding.buttonUpdate.isEnabled != enabled) binding.buttonUpdate.isEnabled = enabled
        binding.buttonUpdate.setOnClickListener {
            if (cancellable) viewModel.installer.cancelDownload(appId) else viewModel.obtain(listOf(appId))
        }
        if (canAct && !trackOnly) probeSize(entry)
        if (binding.buttonDelete.isEnabled == busy) binding.buttonDelete.isEnabled = !busy
        binding.buttonDelete.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val (uninstall, removeEntry) = dialogs.askRemove(listOf(app)) ?: return@launch
                viewModel.remove(listOf(appId), uninstall, removeEntry)
            }
        }
    }

    companion object {
        const val TAG = "AppDetailSheet"
        private const val ARG_ID = "id"

        fun newInstance(id: String) = AppDetailSheet().apply { arguments = Bundle().apply { putString(ARG_ID, id) } }
    }
}
