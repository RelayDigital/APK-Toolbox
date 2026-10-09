package io.github.bl3xand.apkcloner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.databinding.ItemAppBinding
import kotlinx.coroutines.Job

class AppAdapter(
    private val icons: IconLoader,
    private val onClick: (ApkSource) -> Unit,
) : ListAdapter<ApkSource, AppAdapter.Holder>(Diff) {

    class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        holder.binding.textLabel.text = app.label
        holder.binding.textPackage.text = app.packageName
        // The same third line as a clone has, minus its "up to date" part.
        holder.binding.textStatus.text =
            holder.binding.root.context.getString(R.string.app_version, app.versionName ?: app.versionCode.toString())
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.iconJob?.cancel()
        holder.iconJob = icons.load(app, holder.binding.imageIcon)
    }

    private object Diff : DiffUtil.ItemCallback<ApkSource>() {
        override fun areItemsTheSame(old: ApkSource, new: ApkSource) = old.packageName == new.packageName
        override fun areContentsTheSame(old: ApkSource, new: ApkSource) =
            old.label == new.label && old.versionCode == new.versionCode && old.versionName == new.versionName
    }
}
