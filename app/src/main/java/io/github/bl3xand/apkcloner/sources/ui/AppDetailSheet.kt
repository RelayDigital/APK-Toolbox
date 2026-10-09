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
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputEditText
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.databinding.SheetSourceDetailBinding
import io.github.bl3xand.apkcloner.sources.core.ApkFilter
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.formatBytes
import io.github.bl3xand.apkcloner.sources.data.AppEntry
import io.github.bl3xand.apkcloner.sources.data.DownloadState
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.sources.model.TrackedApp
import io.github.bl3xand.apkcloner.sources.net.Downloader
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.clonePermissionsLabel
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.dp
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.markdownToSpanned
import io.github.bl3xand.apkcloner.ui.openUrl
import io.github.bl3xand.apkcloner.ui.pickClonePermissions
import io.github.bl3xand.apkcloner.ui.show
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
                // Signing in or out changes what the page of an app of that source offers.
                launch { TelegramClient.auth.collect { rerender() } }
                launch { viewModel.repo.checkErrors.collect { rerender() } }
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
                            // The link of a file the source fetches itself is a page, not the
                            // file: asking the web for its size would give the size of the page.
                            source.assetSize(url, app.additionalSettings) ?: if (source.ownsAsset(url)) null else Downloader.getDownloadSize(
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

    /** A line of the info card with the value under its name, for a value of several lines. */
    private fun infoBlock(name: String, value: CharSequence, highlight: Boolean = false) {
        val context = requireContext()
        binding.infoRows.addView(MaterialDivider(context))
        val block = context.column().apply { setPadding(0, context.dp(10), 0, context.dp(10)) }
        block.add(context.label(name, colorAttr = MaterialR.attr.colorOnSurfaceVariant))
        block.add(
            context.label(value, MaterialR.attr.textAppearanceBodyLarge).apply {
                if (highlight) setTextColor(context.themeColor(AppCompatR.attr.colorError))
            },
            topMargin = 4,
        )
        binding.infoRows.addView(block)
    }

    /**
     * Installing the app as a clone of itself. One switch; behind it the controls a clone is made
     * with while it is not installed, and the ones an installed clone is changed with once it is.
     */
    private fun renderClone(entry: AppEntry, offered: Boolean, busy: Boolean) {
        val context = requireContext()
        val app = entry.app
        // Installed as a clone, or only set up to be: until the clone is on the device the page
        // stays about the app that is there.
        val active = offered && app.clonePackage != null
        val draft = offered && !active && app.cloneTarget != null
        val originalInstalled = offered && (if (active) viewModel.repo.installedInfo(app.id) else entry.installedInfo) != null
        // The switch is for an install that is still ahead. For an app that is there already,
        // going over to a clone is a reinstall: a button says so and asks first, and only then
        // is there a clone to set up.
        binding.switchClone.isVisible = draft || (offered && !active && !originalInstalled)
        binding.switchClone.text = Tr.get("actCloneInstall")
        binding.switchClone.setOnCheckedChangeListener(null)
        binding.switchClone.isChecked = draft
        binding.switchClone.isEnabled = !busy
        binding.switchClone.setOnCheckedChangeListener { _, on -> switchClone(app, on) }

        val reinstall = active || (offered && !draft && originalInstalled)
        binding.buttonCloneMode.isVisible = reinstall
        binding.buttonCloneMode.isEnabled = !busy
        binding.buttonCloneMode.text = Tr.get(if (active) "cloneBackToApp" else "cloneReinstall")
        // Under the info card it is the first button; under the clone's own buttons, one more of them.
        binding.buttonCloneMode.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = context.dp(if (active) 4 else 12) }
        binding.buttonCloneMode.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val message = Tr.get(if (active) "cloneBackToAppMessage" else "cloneReinstallMessage")
                if (context.confirm(binding.buttonCloneMode.text, message)) viewModel.setCloneMode(appId, !active)
            }
        }
        binding.buttonDeleteOriginal.isVisible = active && originalInstalled
        binding.buttonDeleteOriginal.isEnabled = !busy
        binding.buttonDeleteOriginal.text = Tr.get("cloneDeleteOriginal")
        binding.buttonDeleteOriginal.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.uninstallPackage(app.id)
                rerender()
            }
        }
        binding.cloneButtonsEnd.isVisible = reinstall

        val options = binding.cloneOptions
        options.root.isVisible = draft
        if (options.root.isVisible) {
            // What is being typed is left alone: a save in between must not move the cursor.
            fun show(edit: TextInputEditText, value: String) {
                if (!edit.hasFocus() && edit.text?.toString() != value) edit.setText(value)
                edit.setOnFocusChangeListener { _, focused -> if (!focused) saveCloneOptions(install = false) }
            }
            show(options.editPackage, app.cloneTarget.orEmpty())
            // The app's own name unless another one was given.
            show(options.editName, app.settings.getString(SettingKeys.CLONE_NAME).ifEmpty { entry.name })
            options.editPackage.doAfterTextChanged { options.layoutPackage.error = null }
            options.switchBadge.setOnCheckedChangeListener(null)
            options.switchBadge.isChecked = app.settings.getBool(SettingKeys.CLONE_BADGE)
            options.switchBadge.setOnCheckedChangeListener { _, _ -> saveCloneOptions(install = false) }
            options.buttonPermissions.text = context.clonePermissionsLabel(app.cloneRemovedPermissions.size)
            options.buttonPermissions.isEnabled = !busy
            options.buttonPermissions.setOnClickListener { pickClonePermissions() }
        }

        val permissions = binding.clonePermissions
        permissions.root.isVisible = active
        permissions.buttonPermissions.text = context.clonePermissionsLabel(app.cloneRemovedPermissions.size)
        permissions.buttonPermissions.isEnabled = !busy
        permissions.buttonPermissions.setOnClickListener { pickClonePermissions() }
        permissions.buttonResetPermissions.isVisible = app.cloneRemovedPermissions.isNotEmpty()
        permissions.buttonResetPermissions.isEnabled = !busy
        permissions.buttonResetPermissions.setOnClickListener {
            viewModel.setClonePermissions(appId, emptySet(), app.cloneRequestedPermissions)
        }
    }

    private fun switchClone(app: TrackedApp, on: Boolean) {
        // The clone is named after the app's package, which some sources only tell with the APK:
        // that is fetched first, and the app comes back under its real id.
        if (on && app.hasTempId) {
            val fragments = parentFragmentManager
            viewModel.clonePermissions(app.id) { id, _ ->
                viewModel.setCloneMode(id, true)
                if (id != app.id && !fragments.isStateSaved) newInstance(id).show(fragments, TAG)
            }
        } else {
            viewModel.setCloneMode(app.id, on)
        }
    }

    /** Stores what the fields say, if the package can be one; with [install] the clone is then installed. */
    private fun saveCloneOptions(install: Boolean) {
        val binding = _binding ?: return
        val entry = viewModel.repo.entry(appId) ?: return
        val app = entry.app
        val options = binding.cloneOptions
        val target = options.editPackage.text?.toString().orEmpty().trim()
        // Left as it was offered, the name is the app's own, whatever a later release calls it.
        val name = options.editName.text?.toString().orEmpty().trim().takeIf { it != entry.name }.orEmpty()
        val badge = options.switchBadge.isChecked
        val error = when {
            !CloneRequest.PACKAGE_NAME.matches(target) || target == app.id -> getString(R.string.error_invalid_package)
            // Free, or a clone of this very app: anything else there is someone else's.
            target != app.cloneTarget && viewModel.isTakenForClone(app.id, target) -> Tr.get("cloneInstallTaken", target)
            else -> null
        }
        options.layoutPackage.error = error
        if (error != null) return
        val changed = target != app.cloneTarget || name != app.settings.getString(SettingKeys.CLONE_NAME) ||
            badge != app.settings.getBool(SettingKeys.CLONE_BADGE)
        if (changed || install) viewModel.setCloneOptions(appId, target, name, badge, install)
    }

    /** The same picker a clone is made with; the list comes from the APK, fetched if none was seen yet. */
    private fun pickClonePermissions() {
        viewModel.clonePermissions(appId) { id, requested ->
            val context = context ?: return@clonePermissions
            val app = viewModel.repo.entry(id)?.app ?: return@clonePermissions
            context.pickClonePermissions(requested, app.cloneRemovedPermissions, fresh = app.cloneNewPermissions) {
                viewModel.setClonePermissions(id, it, requested)
            }
        }
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
        // What the source needs before it can be asked for this app, if anything; and why the
        // last check failed, if it did.
        val signInNote = source?.signInNote?.takeUnless { conflict }
        val checkError = repo.checkErrors.value[appId]?.takeUnless { conflict || signInNote != null }
        val trouble = conflict || signInNote != null || checkError != null
        binding.cardNotice.isVisible = app.hasPendingRepoRename || trouble
        binding.buttonNotice.isVisible = binding.cardNotice.isVisible
        // Something wrong gets the bright error colours and a button as wide as the card; a
        // repository that moved is only worth knowing and keeps the quieter secondary ones.
        binding.buttonNotice.minimumHeight = if (trouble) resources.getDimensionPixelSize(R.dimen.action_button_height) else 0
        binding.buttonNotice.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (trouble) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val noticeBg = if (trouble) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorSecondaryContainer
        val noticeFg = if (trouble) MaterialR.attr.colorOnErrorContainer else MaterialR.attr.colorOnSecondaryContainer
        binding.cardNotice.setCardBackgroundColor(context.themeColor(noticeBg))
        binding.textNotice.setTextColor(context.themeColor(noticeFg))
        // On the error card the button is the card's colours the other way round, so that it reads.
        binding.buttonNotice.backgroundTintList = ColorStateList.valueOf(context.themeColor(if (trouble) noticeFg else noticeBg))
        binding.buttonNotice.setTextColor(context.themeColor(if (trouble) noticeBg else noticeFg))
        if (conflict) {
            binding.textNotice.text = Tr.get("detSignerConflict")
            // Removing the installed build is the way forward; once it is gone the card becomes an
            // ordinary install.
            binding.buttonNotice.text = Tr.get("detSignerRemoveCurrent")
            binding.buttonNotice.setOnClickListener { viewModel.uninstallConflicting(appId) }
        } else if (signInNote != null) {
            // What is shown of the app is what was known when it was last checked.
            binding.textNotice.text = Tr.get("telegramSignInFirst")
            binding.buttonNotice.text = Tr.get("telegramSignIn")
            binding.buttonNotice.setOnClickListener { context.showTelegramSignIn(viewLifecycleOwner.lifecycleScope) }
        } else if (checkError != null) {
            binding.textNotice.text = Tr.get("srcCheckFailed", checkError)
            binding.buttonNotice.text = Tr.get("srcCheckAgain")
            binding.buttonNotice.setOnClickListener { viewModel.refresh(appId) }
        } else if (app.hasPendingRepoRename) {
            binding.textNotice.text = "${Tr.get("repoRenamedExplanation")}\n\n${app.pendingRepoRenameUrl}"
            binding.buttonNotice.text = Tr.get("updateUrl")
            binding.buttonNotice.setOnClickListener {
                viewModel.update(listOf(appId)) { it.copy(url = it.pendingRepoRenameUrl ?: it.url, pendingRepoRenameUrl = null) }
            }
        }

        binding.infoRows.removeAllViews()
        infoRow(
            Tr.get("detSource"), listOfNotNull(source?.shortName, entry.author.takeIf { it.isNotBlank() && it != source?.name && it != source?.shortName }).joinToString(" · "), first = true,
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

        // What the clone the app is installed as goes without, the way a clone's own page shows it.
        if (app.clonePackage != null) {
            fun names(permissions: Set<String>) = permissions.sorted().joinToString("\n") { it.substringAfterLast('.') }
            app.cloneRemovedPermissions.takeIf { it.isNotEmpty() }?.let { infoBlock(getString(R.string.detail_removed_permissions), names(it)) }
            app.cloneNewPermissions.takeIf { it.isNotEmpty() }?.let { infoBlock(Tr.get("cloneNewPermissions"), names(it), highlight = true) }
        }

        renderChanges(entry)

        // A running download, install or uninstall shows as the bar alone: what it is, is plain from
        // what was tapped.
        val busy = download != null || removing
        renderClone(entry, offered = !candidate && !trackOnly, busy = busy)
        // Only an app that is there has a version to stay at.
        binding.switchNoUpdates.isVisible = !candidate && !trackOnly && installed != null && !conflict
        binding.switchNoUpdates.setOnCheckedChangeListener(null)
        binding.switchNoUpdates.isChecked = app.updatesOff
        binding.switchNoUpdates.setOnCheckedChangeListener { _, on ->
            viewModel.update(listOf(appId)) { it.withSetting(SettingKeys.NO_UPDATES, on) }
        }
        binding.actions.progress.show(busy, download?.progress.takeUnless { removing })

        // A clone that is set up is installed next to whatever is there, so neither the version
        // of that nor a clash of signers stands in its way.
        val cloneAhead = binding.cloneOptions.root.isVisible
        val canAct = !busy && signInNote == null && !repo.areDownloadsRunning() &&
            (cloneAhead || (!conflict && (installed == null || installed != app.latestVersion)))
        // A conflict is shown as a fresh install of the added build (the installed one must go
        // first), so the action reads "Install" and is blocked until the clashing build is removed.
        val asInstall = installed == null || conflict || cloneAhead
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
            when {
                cancellable -> viewModel.installer.cancelDownload(appId)
                // A clone that is still being set up is installed with what the fields say.
                binding.cloneOptions.root.isVisible -> saveCloneOptions(install = true)
                else -> viewModel.obtain(listOf(appId))
            }
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
