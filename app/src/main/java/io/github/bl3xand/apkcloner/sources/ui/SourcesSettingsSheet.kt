package io.github.bl3xand.apkcloner.sources.ui

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.data.SourcesBackup
import io.github.bl3xand.apkcloner.sources.data.SourcesSettings
import io.github.bl3xand.apkcloner.sources.form.DropdownItem
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.source.MassAppUrlSource
import io.github.bl3xand.apkcloner.sources.source.SourceRegistry
import io.github.bl3xand.apkcloner.sources.source.minimumUpdateAgeOptions
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.actionButton
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.addDivider
import io.github.bl3xand.apkcloner.ui.addHeading
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.openUrl
import io.github.bl3xand.apkcloner.ui.scrollableUnderHandle
import io.github.bl3xand.apkcloner.ui.settingBlock
import io.github.bl3xand.apkcloner.ui.showError
import io.github.bl3xand.apkcloner.ui.showSheet
import io.github.bl3xand.apkcloner.ui.switchRow
import io.github.bl3xand.apkcloner.ui.toast
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What is specific to the Sources tab: access tokens, how releases are picked, the list, and
 * moving the list in and out. Schedule and install method live in the app's main settings.
 */
class SourcesSettingsSheet : BottomSheetDialogFragment() {
    private val viewModel: SourcesViewModel by activityViewModels()
    private lateinit var settings: SourcesSettings
    private lateinit var dialogs: SourcesDialogs

    private val pickImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }
    private val createExportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        settings = viewModel.repo.settings
        dialogs = SourcesDialogs(context)
        val root = context.column(Spacing.SHEET)
        root.add(context.label(getString(R.string.settings_sources), MaterialR.attr.textAppearanceHeadlineSmall))

        var firstSection = true
        fun section(title: String) {
            if (!firstSection) root.addDivider()
            root.addHeading(title, afterDivider = !firstSection)
            firstSection = false
        }
        fun button(text: String, icon: Int, topMargin: Int = 8, onClick: () -> Unit) =
            root.add(context.actionButton(text, icon, onClick), topMargin = topMargin)

        // ---- access ----
        section(Tr.get("setAccess"))
        val tokens = SourceRegistry.sources.flatMap { it.sourceConfigSettingFormItems }.filterIsInstance<TextItem>()
        root.add(
            FormView(context, tokens.map { listOf<SettingItem>(it) }, tokens.associate { it.key to (settings.getString(it.key) ?: "") }) { values, _ ->
                for (item in tokens) (values[item.key] as? String)?.let { settings.setString(item.key, it) }
            },
        )

        root.addTelegramAccount(lifecycleScope)

        // ---- releases ----
        section(Tr.get("setReleases"))
        root.add(
            context.switchRow(Tr.get("includePrereleasesByDefault"), settings.includePrereleasesByDefault, Tr.get("setPrereleasesDesc")) {
                settings.includePrereleasesByDefault = it
            },
        )
        fun ageLabel(days: Int) = "${Tr.get("setMinAge")}: ${if (days == 0) Tr.get("none").lowercase() else Tr.plural("day", days)}"
        val ageSlider = Slider(context).apply {
            valueFrom = 0f
            valueTo = (minimumUpdateAgeOptions.size - 1).toFloat()
            stepSize = 1f
            value = minimumUpdateAgeOptions.indexOf(settings.minimumUpdateAgeDays).coerceAtLeast(0).toFloat()
            setLabelFormatter { index -> minimumUpdateAgeOptions[index.toInt()].let { if (it == 0) Tr.get("none") else Tr.plural("day", it) } }
        }
        val ageBlock = context.settingBlock(ageLabel(settings.minimumUpdateAgeDays), Tr.get("setMinAgeDesc"), ageSlider)
        ageSlider.addOnChangeListener { _, index, _ ->
            settings.minimumUpdateAgeDays = minimumUpdateAgeOptions[index.toInt()]
            (ageBlock.getChildAt(0) as TextView).text = ageLabel(settings.minimumUpdateAgeDays)
        }
        root.add(ageBlock)

        // ---- installing ----
        section(Tr.get("setInstall"))
        root.add(
            context.switchRow(Tr.get("setPretendPlay"), settings.shizukuPretendToBeGooglePlay, Tr.get("setPretendPlayDesc")) {
                settings.shizukuPretendToBeGooglePlay = it
            },
        )

        // ---- security ----
        section(Tr.get("setSecurity"))
        root.add(
            context.switchRow(Tr.get("verifySigningCertHashes"), settings.verifySigningCertHashes, Tr.get("verifySigningCertHashesHelp")) {
                settings.verifySigningCertHashes = it
            },
        )
        root.add(
            context.switchRow(Tr.get("enableCertificatePinning"), settings.enableCertificatePinning, Tr.get("setPinningDesc")) {
                settings.enableCertificatePinning = it
            },
        )

        // ---- list ----
        section(Tr.get("setList"))
        root.add(
            FormView(
                context,
                listOf(
                    listOf<SettingItem>(
                        DropdownItem(
                            "sortColumn", "appSortBy",
                            listOf("1" to "nameAuthor", "2" to "authorName", "3" to "releaseDate", "0" to "asAdded"),
                        ),
                    ),
                    listOf(DropdownItem("sortOrder", "appSortOrder", listOf("0" to "ascending", "1" to "descending"))),
                    listOf(DropdownItem("groupBy", "groupBy", listOf("none" to "none", "category" to "category", "source" to "source"))),
                ),
                mapOf(
                    "sortColumn" to settings.sortColumn.toString(), "sortOrder" to settings.sortOrder.toString(),
                    "groupBy" to settings.groupBy,
                ),
            ) { values, _ ->
                (values["sortColumn"] as? String)?.toIntOrNull()?.let { settings.sortColumn = it }
                (values["sortOrder"] as? String)?.toIntOrNull()?.let { settings.sortOrder = it }
                (values["groupBy"] as? String)?.let { settings.groupBy = it }
                viewModel.settingsChanged()
            },
        )
        button(Tr.get("categories"), R.drawable.ic_label, topMargin = Spacing.ITEM) { dialogs.showCategoryManager { viewModel.settingsChanged() } }

        // ---- data ----
        section(Tr.get("setData"))
        root.add(context.label(Tr.get("setDataDesc", "Obtainium"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 4)
        button(Tr.get("setImportFile"), R.drawable.ic_folder, topMargin = Spacing.UNDER_LABEL - Spacing.BUTTON_INSET) { pickImportFile.launch(arrayOf("*/*")) }
        button(Tr.get("setImportLinks"), R.drawable.ic_add) { askUrlList() }
        for (source in SourceRegistry.massUrlSources) button(source.name, R.drawable.ic_download) { massImport(source) }
        button(Tr.get("setExport"), R.drawable.ic_share) {
            createExportFile.launch("${SourcesBackup.EXPORT_FILE_PREFIX}-${Instant.now().toString().take(10)}.json")
        }

        root.add(
            context.label(
                CREDIT_LINE,
                MaterialR.attr.textAppearanceBodySmall, MaterialR.attr.colorOnSurfaceVariant,
            ).apply { setOnClickListener { context.openUrl(CREDIT_URL) } },
            topMargin = 24,
        )
        root.add(View(context), topMargin = 24)
        return root.scrollableUnderHandle()
    }

    override fun onStart() {
        super.onStart()
        expandFully()
    }

    private fun readText(uri: Uri): String =
        requireContext().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun importFile(uri: Uri) {
        val context = requireContext()
        val repo = viewModel.repo
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                val ids = try {
                    repo.backup.appIdsInImportJson(text)
                } catch (e: Exception) {
                    throw SourceError(Tr.get("invalidInput"))
                }
                // Importing overwrites apps that are already tracked: say so first.
                val conflicts = ids.count { repo.entry(it) != null }
                if (conflicts > 0 && !context.confirm(
                        Tr.get("importX", Tr.get("appsString").lowercase()),
                        Tr.get("importOverwriteWarning", conflicts.toString()),
                    )
                ) {
                    return@launch
                }
                val (apps, _) = withContext(Dispatchers.IO) { repo.backup.importJson(text).also { repo.addMissingCategories() } }
                viewModel.settingsChanged()
                context.toast(Tr.get("importedX", Tr.plural("apps", apps.size).lowercase()))
            } catch (e: Exception) {
                context.showError(e)
            }
        }
    }

    private fun export(uri: Uri) {
        val context = requireContext()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(viewModel.repo.backup.generateExportJson().toString(4).toByteArray())
                    }
                }
                context.toast(Tr.get("exportDone"))
            } catch (e: Exception) {
                context.showError(e)
            }
        }
    }

    /** A box for links, one per line. */
    private fun askUrlList() {
        val context = requireContext()
        val layout = TextInputLayout(context, null, MaterialR.attr.textInputOutlinedStyle).apply {
            hint = Tr.get("importLinksHint")
        }
        val edit = TextInputEditText(layout.context).apply {
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(edit)
        val view = context.column(Spacing.SHEET).apply {
            add(layout, topMargin = 8)
            add(context.label(Tr.get("importLinksNote"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 8)
        }
        context.showSheet(Tr.get("setImportLinks"), content = view, positive = Tr.get("import"), negative = Tr.get("cancel")) {
            val urls = (edit.text?.toString() ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            for ((index, url) in urls.withIndex()) {
                try {
                    SourceRegistry.getSource(url)
                } catch (e: Exception) {
                    context.showError("${Tr.get("line")} ${index + 1}: ${errorText(e)}")
                    return@showSheet false
                }
            }
            if (urls.isNotEmpty()) addUrls(urls)
            true
        }
    }

    private fun addUrls(urls: List<String>) {
        val context = requireContext()
        lifecycleScope.launch {
            context.toast(Tr.get("pleaseWait"))
            try {
                val errors = withContext(Dispatchers.IO) { viewModel.repo.addAppsByUrl(urls) }
                if (errors.isEmpty()) {
                    context.toast(Tr.get("importedX", Tr.plural("apps", urls.size).lowercase()))
                } else {
                    dialogs.showImportErrors(urls.size, errors)
                }
            } catch (e: Exception) {
                context.showError(e)
            }
        }
    }

    private fun massImport(source: MassAppUrlSource) {
        val context = requireContext()
        lifecycleScope.launch {
            try {
                val form = FormView(context, source.requiredArgs.map { arg ->
                    listOf<SettingItem>(TextItem(arg, arg).also { it.labelOverride = { arg } })
                })
                val view = context.column(Spacing.SHEET).apply { add(form, topMargin = 8) }
                if (!context.confirm(source.name, view = view) || !form.isValid) return@launch
                context.toast(Tr.get("pleaseWait"))
                val found = withContext(Dispatchers.IO) {
                    source.getUrlsWithDescriptions(source.requiredArgs.map { form.values[it].toString() })
                }
                val chosen = dialogs.pickFromList(source.name, found.map { (url, lines) ->
                    PickItem(url, lines.firstOrNull() ?: url, lines.drop(1).joinToString(" "), null)
                }, multiple = true) ?: return@launch
                if (chosen.isNotEmpty()) addUrls(chosen)
            } catch (e: Exception) {
                context.showError(e)
            }
        }
    }

    companion object {
        // The app this tab was ported from; its licence asks for the credit.
        private const val CREDIT_LINE = "Obtainium · GPL-3.0 · github.com/ImranR98/Obtainium"
        private const val CREDIT_URL = "https://github.com/ImranR98/Obtainium"

        const val TAG = "SourcesSettingsSheet"
    }
}
