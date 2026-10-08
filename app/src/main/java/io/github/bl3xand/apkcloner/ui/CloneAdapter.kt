package io.github.bl3xand.apkcloner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
