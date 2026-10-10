package io.github.bl3xand.apkcloner.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.R as AppCompatR
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.databinding.ItemAppBinding
import io.github.bl3xand.apkcloner.sources.data.SourcesRepository
import kotlinx.coroutines.Job

class CloneAdapter(
    private val icons: IconLoader,
    private val onClick: (CloneInfo) -> Unit,
    private val onLongClick: (CloneInfo) -> Unit,
) : ListAdapter<CloneInfo, CloneAdapter.Holder>(Diff) {

    /** Package of the clone being updated right now, if any. */
    var updating: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clone = getItem(position)
        val binding = holder.binding
        val context = binding.root.context

        binding.textLabel.text = clone.app.label
        // The same lines as a tracked app: the name, the package, the version - with the one it
        // can be updated to when there is one - and a note, which here says where updates come from.
        binding.textPackage.text = clone.app.packageName
        val version = clone.app.versionName.orEmpty()
        val newer = clone.tracked?.app?.latestVersion ?: clone.original?.versionName.orEmpty()
        binding.textStatus.text = when {
            clone.frozen -> context.getString(R.string.version_no_updates, version)
            clone.updateAvailable -> "$version → $newer"
            else -> version
        }
        binding.textStatus.setTextColor(
            context.themeColor(if (clone.wantsUpdate) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant),
        )
        binding.textNote.isVisible = true
        binding.textNote.text = when {
            clone.tracked != null -> context.getString(R.string.clone_from_source, clone.tracked.sourceName())
            clone.original != null -> context.getString(R.string.clone_from_original)
            // Nothing to rebuild it from: it stays at this version.
            else -> context.getString(R.string.clone_no_updates)
        }
        binding.textNote.setTextColor(
            context.themeColor(if (clone.canRebuild) MaterialR.attr.colorOnSurfaceVariant else AppCompatR.attr.colorError),
        )
        binding.stripes.removeAllViews()
        val colors = SourcesRepository.get(context).settings.categories
        for (category in clone.categories.sorted()) {
            val color = colors[category] ?: continue
            binding.stripes.addView(
                View(context).apply { setBackgroundColor(color or 0xFF000000.toInt()) },
                LinearLayout.LayoutParams(context.dp(4), ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        binding.progress.isVisible = updating == clone.app.packageName
        binding.root.setOnClickListener { onClick(clone) }
        binding.root.setOnLongClickListener {
            onLongClick(clone)
            true
        }

        holder.iconJob?.cancel()
        holder.iconJob = icons.load(clone.app, binding.imageIcon)
    }

    private object Diff : DiffUtil.ItemCallback<CloneInfo>() {
        override fun areItemsTheSame(old: CloneInfo, new: CloneInfo) = old.app.packageName == new.app.packageName
        override fun areContentsTheSame(old: CloneInfo, new: CloneInfo) =
            old.app.label == new.app.label && old.app.versionCode == new.app.versionCode &&
                old.original?.versionCode == new.original?.versionCode && old.tracked?.app == new.tracked?.app && old.frozen == new.frozen && old.categories == new.categories
    }
}
