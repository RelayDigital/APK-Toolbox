package io.github.bl3xand.apkclonner.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.bl3xand.apkclonner.data.ApkSource
import io.github.bl3xand.apkclonner.databinding.ItemAppBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppAdapter(
    private val scope: CoroutineScope,
    private val packageManager: PackageManager,
    private val onClick: (ApkSource) -> Unit,
) : ListAdapter<ApkSource, AppAdapter.Holder>(Diff) {

    private val icons = LruCache<String, Drawable>(200)

    class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        holder.binding.textLabel.text = app.label
        holder.binding.textPackage.text = app.packageName
        holder.binding.root.setOnClickListener { onClick(app) }

        holder.iconJob?.cancel()
        val cached = icons[app.packageName]
        holder.binding.imageIcon.setImageDrawable(cached)
        if (cached == null) {
            // Decoding an icon takes a few milliseconds each - too slow to do while scrolling.
            holder.iconJob = scope.launch {
                val icon = withContext(Dispatchers.IO) { app.appInfo.loadIcon(packageManager) }
                icons.put(app.packageName, icon)
                holder.binding.imageIcon.setImageDrawable(icon)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ApkSource>() {
        override fun areItemsTheSame(old: ApkSource, new: ApkSource) = old.packageName == new.packageName
        override fun areContentsTheSame(old: ApkSource, new: ApkSource) =
            old.label == new.label && old.versionName == new.versionName
    }
}
