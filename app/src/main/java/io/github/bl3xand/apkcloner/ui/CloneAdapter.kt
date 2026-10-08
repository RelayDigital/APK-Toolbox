package io.github.bl3xand.apkcloner.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.CloneInfo
import io.github.bl3xand.apkcloner.databinding.ItemCloneBinding
import kotlinx.coroutines.Job

class CloneAdapter(
    private val icons: IconLoader,
    private val onClick: (CloneInfo) -> Unit,
) : ListAdapter<CloneInfo, CloneAdapter.Holder>(Diff) {

    /** Package of the clone being updated right now, if any. */
    var updating: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class Holder(val binding: ItemCloneBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val clone = getItem(position)
        val binding = holder.binding
        val context = binding.root.context

        binding.textLabel.text = clone.app.label
        binding.textPackage.text = clone.app.packageName
        binding.textStatus.text = when {
            clone.original == null -> context.getString(R.string.clone_original_missing, clone.originalPackage)
            clone.updateAvailable -> context.getString(
                R.string.clone_update_available,
                clone.app.versionName.orEmpty(),
                clone.original.versionName.orEmpty(),
            )
            else -> context.getString(R.string.clone_up_to_date, clone.app.versionName.orEmpty())
        }
        binding.progress.isVisible = updating == clone.app.packageName
        binding.root.setOnClickListener { onClick(clone) }

        // Outdated clones stand out as a filled card in the accent colour.
        val highlight = clone.updateAvailable
        val color = { attr: Int -> MaterialColors.getColor(binding.root, attr) }
        binding.root.setCardBackgroundColor(
            if (highlight) color(androidx.appcompat.R.attr.colorPrimary) else Color.TRANSPARENT
        )
        val primaryText = color(
            if (highlight) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurface
        )
        val secondaryText = color(
            if (highlight) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        binding.textLabel.setTextColor(primaryText)
        binding.textPackage.setTextColor(secondaryText)
        binding.textStatus.setTextColor(secondaryText)
        binding.progress.setIndicatorColor(primaryText)
        binding.root.rippleColor = ColorStateList.valueOf(primaryText).withAlpha(40)

        holder.iconJob?.cancel()
        holder.iconJob = icons.load(clone.app, binding.imageIcon)
    }

    private object Diff : DiffUtil.ItemCallback<CloneInfo>() {
        override fun areItemsTheSame(old: CloneInfo, new: CloneInfo) = old.app.packageName == new.app.packageName
        override fun areContentsTheSame(old: CloneInfo, new: CloneInfo) =
            old.app.label == new.app.label && old.app.versionCode == new.app.versionCode &&
                old.original?.versionCode == new.original?.versionCode
    }
}
