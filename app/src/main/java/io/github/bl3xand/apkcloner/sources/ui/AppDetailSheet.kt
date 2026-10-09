package io.github.bl3xand.apkcloner.sources.ui

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.R as AppCompatR
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.divider.MaterialDivider
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.SheetSourceDetailBinding
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.formatBytes
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.Downloader
import io.github.bl3xand.apkcloner.ui.dp
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.markdownToSpanned
import io.github.bl3xand.apkcloner.ui.openUrl
import io.github.bl3xand.apkcloner.ui.themeColor
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
    private var lastRendered: RenderKey? = null

    private data class RenderKey(
        val app: TrackedApp,
        val download: DownloadState?,
        val removing: Boolean,
        val installedVersionCode: Long?,
        val candidate: Boolean,
    )
    private var buttonLook: Pair<String, Int>? = null
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSourceDetailBinding.inflate(inflater, container, false)
        buttonLook = null
        binding.actions.buttonDelete.text = Tr.get("remove")
        dialogs = SourcesDialogs(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.repo.apps, viewModel.repo.downloads, viewModel.uninstalling, viewModel.candidate,
                ) { _, downloads, uninstalling, _ ->
                    downloads[appId] to (appId in uninstalling)
                }.collect { (download, removing) ->
                    val entry = viewModel.shownEntry(appId)
                    if (entry == null) {
                        dismissAllowingStateLoss()
                    } else {
                        // Re-render on an installedInfo change too (its version code), so the
                        // signer-conflict notice shows once the installed build is known.
                        val key = RenderKey(
                            entry.app, download, removing, entry.installedInfo?.longVersionCode, isCandidate(entry),
                        )
                        if (lastRendered != key) {
                            lastRendered = key
                            render(entry, download, removing)
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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Also called when the sheet is torn down for a rotation, where the candidate must survive.
        if (activity?.isChangingConfigurations != true) viewModel.discardCandidate(appId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Whether [entry] is an app still being added rather than a tracked one. */
    private fun isCandidate(entry: AppEntry): Boolean = viewModel.candidate.value?.entry === entry

    private fun rerender() {
        lastRendered = null
        if (_binding == null) return
        viewModel.shownEntry(appId)?.let {
            render(it, viewModel.repo.downloads.value[appId], appId in viewModel.uninstalling.value)
        }
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
    private fun infoRow(name: String, value: CharSequence, first: Boolean, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
        val context = requireContext()
        if (!first) binding.infoRows.addView(MaterialDivider(context))
        val row = LinearLayout(context).apply { setPadding(0, context.dp(10), 0, context.dp(10)) }
        row.addView(
            context.label(name, colorAttr = MaterialR.attr.colorOnSurfaceVariant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f),
        )
        row.addView(
            context.label(value, MaterialR.attr.textAppearanceBodyLarge).apply {
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                if (highlight) setTextColor(context.themeColor(AppCompatR.attr.colorError))
                if (onClick != null) {
                    setTextColor(context.themeColor(AppCompatR.attr.colorPrimary))
                    setOnClickListener { onClick() }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f),
        )
        binding.infoRows.addView(row)
    }

    private fun render(entry: AppEntry, download: DownloadState?, removing: Boolean) {
        val context = context ?: return
        val app = entry.app
        val repo = viewModel.repo
        val source = runCatching { repo.sourceOf(app) }.getOrNull()
        val trackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY)
        val installed = app.installedVersion

        // An app still being added is here only because of the clash; a tracked one is judged live.
        val candidate = isCandidate(entry)
        val conflict = candidate || viewModel.installer.hasSignerConflict(entry)

        // The same card as everywhere else; a tap opens the app's page in the system settings. On a
        // signer conflict it is the build being added, not the differently-signed one installed.
        binding.appCard.bindTracked(entry, treatAsNotInstalled = conflict)
        binding.cardNotice.isVisible = app.hasPendingRepoRename || conflict
        binding.buttonNotice.isVisible = app.hasPendingRepoRename || conflict
        // A signer conflict is a hard block, so it gets the bright error colours; the repo-rename
        // notice is only informational and keeps the quieter secondary colours.
        val noticeBg = if (conflict) {
            MaterialR.attr.colorErrorContainer
        } else {
            MaterialR.attr.colorSecondaryContainer
        }
        val noticeFg = if (conflict) {
            MaterialR.attr.colorOnErrorContainer
        } else {
            MaterialR.attr.colorOnSecondaryContainer
        }
        binding.cardNotice.setCardBackgroundColor(context.themeColor(noticeBg))
        binding.textNotice.setTextColor(context.themeColor(noticeFg))
        if (conflict) {
            binding.textNotice.text = Tr.get("detSignerConflict")
            // Removing the installed build is the way forward; once it is gone the card becomes an
            // ordinary install. Inverted error colours so the button reads on the error card.
            binding.buttonNotice.text = Tr.get("detSignerRemoveCurrent")
            binding.buttonNotice.backgroundTintList =
                ColorStateList.valueOf(context.themeColor(MaterialR.attr.colorOnErrorContainer))
            binding.buttonNotice.setTextColor(context.themeColor(MaterialR.attr.colorErrorContainer))
            binding.buttonNotice.setOnClickListener { viewModel.uninstallConflicting(appId) }
        } else if (app.hasPendingRepoRename) {
            binding.textNotice.text = "${Tr.get("repoRenamedExplanation")}\n\n${app.pendingRepoRenameUrl}"
            binding.buttonNotice.text = Tr.get("updateUrl")
            binding.buttonNotice.backgroundTintList =
                ColorStateList.valueOf(context.themeColor(MaterialR.attr.colorSecondaryContainer))
            binding.buttonNotice.setTextColor(context.themeColor(MaterialR.attr.colorOnSecondaryContainer))
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
                // The version of what is on the device, which is not a version of this app.
                conflict -> "${entry.installedInfo?.versionName ?: installed} · ${Tr.get("detSignerConflictMark")}"
                installed == null -> Tr.get("notInstalled")
                trackOnly -> "$installed · ${Tr.get("trackOnly")}"
                else -> installed
            },
            first = false,
            highlight = conflict,
        )
        infoRow(Tr.get("detLatest"), app.latestVersion, first = false)
        // Only while there is something to download.
        if (conflict || installed == null || installed != app.latestVersion) {
            probedSize?.let { infoRow(Tr.get("detSize"), formatBytes(it), first = false) }
        }
        app.releaseDate?.let { infoRow(Tr.get("detReleased"), dateFormat.format(it), first = false) }
        infoRow(Tr.get("detChecked"), app.lastUpdateCheck?.let(timeFormat::format) ?: Tr.get("never"), first = false)

        renderChanges(entry)

        // A running download, install or uninstall shows as the bar alone: what it is, is plain from
        // what was tapped.
        val busy = download != null || removing
        binding.actions.progress.isIndeterminate = removing || (download?.progress ?: 0.0) < 0
        if (download != null && download.progress >= 0) binding.actions.progress.setProgressCompat(download.progress.toInt(), false)
        binding.actions.progress.isInvisible = !busy

        val canAct = !busy && !conflict && (installed == null || installed != app.latestVersion) && !repo.areDownloadsRunning()
        // A conflict is shown as a fresh install of the added build (the installed one must go
        // first), so the action reads "Install" and is blocked until the clashing build is removed.
        val asInstall = installed == null || conflict
        val label = when {
            asInstall -> Tr.get(if (trackOnly) "markInstalled" else "install")
            else -> Tr.get(if (trackOnly) "markUpdated" else "update")
        }
        // The two buttons share a group that animates their widths, so they are only touched
        // when something about them really changes - not on every tick of a download.
        val cancellable = download != null && download.progress in 0.0..99.0
        val look = if (cancellable) Tr.get("cancel") to R.drawable.ic_close
        else label to (if (asInstall) R.drawable.ic_download else R.drawable.ic_update)
        if (buttonLook != look) {
            buttonLook = look
            binding.actions.buttonUpdate.text = look.first
            binding.actions.buttonUpdate.setIconResource(look.second)
        }
        val enabled = cancellable || canAct
        if (binding.actions.buttonUpdate.isEnabled != enabled) binding.actions.buttonUpdate.isEnabled = enabled
        binding.actions.buttonUpdate.setOnClickListener {
            if (cancellable) viewModel.installer.cancelDownload(appId) else viewModel.obtain(listOf(appId))
        }
        if ((canAct || conflict) && !trackOnly) probeSize(entry)
        // Nothing is tracked yet for an app still being added, so there is nothing to remove:
        // closing the sheet is all it takes to leave things as they were.
        if (binding.actions.buttonDelete.isVisible == candidate) {
            binding.actions.buttonDelete.isVisible = !candidate
            binding.actions.buttonUpdate.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = if (candidate) 0 else context.dp(8)
            }
        }
        if (binding.actions.buttonDelete.isEnabled == busy) binding.actions.buttonDelete.isEnabled = !busy
        binding.actions.buttonDelete.setOnClickListener {
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
