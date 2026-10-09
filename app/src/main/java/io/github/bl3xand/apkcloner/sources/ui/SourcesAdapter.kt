package io.github.bl3xand.apkcloner.sources.ui

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.ItemSourceAppBinding
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.capitalizeFirst
import io.github.bl3xand.apkcloner.sources.data.SourcesSettings
import io.github.bl3xand.apkcloner.sources.model.SettingKeys
import io.github.bl3xand.apkcloner.ui.AppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourcesAdapter(
    private val scope: CoroutineScope,
    private val packageManager: PackageManager,
    private val settings: SourcesSettings,
    private val listener: Listener,
) : ListAdapter<ListRow, RecyclerView.ViewHolder>(Diff) {

    interface Listener {
        fun onAppClick(row: ListRow.App)
        fun onAppLongClick(row: ListRow.App)
        fun onIconClick(row: ListRow.App)
        fun onUpdateClick(row: ListRow.App)
        fun onCancelDownload(row: ListRow.App)
        fun onGroupClick(row: ListRow.Group)
        fun onBannerClick()
    }

    private val icons = LruCache<String, Drawable>(200)

    class AppHolder(val binding: ItemSourceAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    class SimpleHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ListRow.App -> 0
        is ListRow.Group -> 1
        is ListRow.Banner -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            0 -> AppHolder(ItemSourceAppBinding.inflate(LayoutInflater.from(context), parent, false))
            1 -> SimpleHolder(
                TextView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(context.dp(Spacing.SHEET), context.dp(14), context.dp(Spacing.SHEET), context.dp(6))
                    setTextAppearance(context.textAppearance(com.google.android.material.R.attr.textAppearanceTitleSmall))
                    setTextColor(context.themeColor(androidx.appcompat.R.attr.colorPrimary))
                },
            )
            else -> SimpleHolder(
                MaterialCardView(context, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(context.dp(16), context.dp(4), context.dp(16), context.dp(8)) }
                    setCardBackgroundColor(context.themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
                    val row = LinearLayout(context).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(context.dp(16), context.dp(12), context.dp(12), context.dp(12))
                    }
                    row.addView(
                        context.label("", com.google.android.material.R.attr.textAppearanceTitleMedium,
                            com.google.android.material.R.attr.colorOnPrimaryContainer).apply { tag = "title" },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    row.addView(MaterialButton(context).apply { tag = "button" })
                    addView(row)
                },
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ListRow.App -> bindApp(holder as AppHolder, row)
            is ListRow.Group -> (holder.itemView as TextView).apply {
                text = "${if (row.collapsed) "▸" else "▾"}  ${capitalizeFirst(row.title)}  ·  ${row.count}"
                setOnClickListener { listener.onGroupClick(row) }
            }
            is ListRow.Banner -> {
                holder.itemView.findViewWithTag<TextView>("title").text =
                    Tr.get(if (row.selectedOnly) "installUpdateSelectedApps" else "installUpdateApps")
                holder.itemView.findViewWithTag<MaterialButton>("button").apply {
                    text = Tr.get("update")
                    setOnClickListener { listener.onBannerClick() }
                }
            }
        }
    }

    private fun bindApp(holder: AppHolder, row: ListRow.App) {
        val binding = holder.binding
        val context = binding.root.context
        val app = row.entry.app
        binding.textName.text = row.entry.name
        binding.textName.typeface = if (app.pinned) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.textAuthor.text = row.entry.author
        binding.textNote.isVisible = app.hasPendingRepoRename
        if (app.hasPendingRepoRename) binding.textNote.text = Tr.get("repoRenamed")

        // Selected, pinned, or neither.
        binding.card.setCardBackgroundColor(
            when {
                row.selected -> context.themeColor(com.google.android.material.R.attr.colorSecondaryContainer)
                app.pinned -> context.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
                else -> android.graphics.Color.TRANSPARENT
            },
        )

        binding.stripes.removeAllViews()
        val colors = settings.categories
        for (category in app.categories) {
            val color = colors[category] ?: continue
            binding.stripes.addView(
                View(context).apply { setBackgroundColor(color or 0xFF000000.toInt()) },
                LinearLayout.LayoutParams(context.dp(4), ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

        val installed = app.installedVersion
        val differs = installed != null && installed != app.latestVersion
        binding.textVersion.text = when {
            differs -> "$installed → ${app.latestVersion}"
            else -> installed ?: Tr.get("notInstalled")
        }
        binding.textVersion.setTypeface(null, if (app.isVersionPseudo) Typeface.ITALIC else Typeface.NORMAL)
        val accent = context.themeColor(
            if (row.updatable) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        binding.textVersion.setTextColor(accent)
        val download = row.download
        binding.progressGroup.isVisible = download != null
        binding.buttonUpdate.isVisible = download == null && row.updatable
        if (download != null) {
            binding.progress.isIndeterminate = download.progress < 0
            if (download.progress >= 0) binding.progress.setProgressCompat(download.progress.toInt(), true)
            binding.imageCancel.isVisible = download.progress in 0.0..99.0
            binding.progressGroup.setOnClickListener { if (download.progress >= 0) listener.onCancelDownload(row) }
        }
        val trackOnly = app.settings.getBool(SettingKeys.TRACK_ONLY)
        binding.buttonUpdate.setIconResource(if (trackOnly) R.drawable.ic_check else R.drawable.ic_install)
        binding.buttonUpdate.contentDescription = Tr.get(if (trackOnly) "markUpdated" else "update")
        binding.buttonUpdate.setOnClickListener { listener.onUpdateClick(row) }

        binding.card.setOnClickListener { listener.onAppClick(row) }
        binding.card.setOnLongClickListener {
            listener.onAppLongClick(row)
            true
        }
        binding.imageIcon.alpha = if (row.entry.installedInfo != null) 1f else 0.4f
        binding.imageIcon.setOnClickListener { listener.onIconClick(row) }
        holder.iconJob?.cancel()
        val cached = icons[app.id]
        binding.imageIcon.setImageDrawable(cached ?: context.getDrawable(R.drawable.ic_install))
        val info = row.entry.installedInfo?.applicationInfo
        if (cached == null && info != null) {
            holder.iconJob = scope.launch {
                val icon = withContext(Dispatchers.IO) { AppIcons.load(packageManager, info) }
                icons.put(app.id, icon)
                binding.imageIcon.setImageDrawable(icon)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ListRow>() {
        override fun areItemsTheSame(old: ListRow, new: ListRow): Boolean = when {
            old is ListRow.App && new is ListRow.App -> old.entry.app.id == new.entry.app.id && old.groupKey == new.groupKey
            old is ListRow.Group && new is ListRow.Group -> old.key == new.key
            else -> old is ListRow.Banner && new is ListRow.Banner
        }

        override fun areContentsTheSame(old: ListRow, new: ListRow): Boolean = when {
            old is ListRow.App && new is ListRow.App -> old.entry.app == new.entry.app && old.download == new.download &&
                old.selected == new.selected && old.updatable == new.updatable &&
                (old.entry.installedInfo == null) == (new.entry.installedInfo == null)
            else -> old == new
        }
    }
}
