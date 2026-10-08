package io.github.bl3xand.apkclonner.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
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

/** Decodes app icons off the main thread - a few milliseconds each is too slow while scrolling. */
class IconLoader(private val scope: CoroutineScope, private val packageManager: PackageManager) {

    private val cache = LruCache<String, Drawable>(200)

    /** Returns the job to cancel if [view] is recycled before the icon arrives. */
    fun load(app: ApkSource, view: ImageView): Job? {
        val cached = cache[app.packageName]
        view.setImageDrawable(cached)
        if (cached != null) return null
        return scope.launch {
            val icon = withContext(Dispatchers.IO) { app.appInfo.loadIcon(packageManager) }
            cache.put(app.packageName, icon)
            view.setImageDrawable(icon)
        }
    }
}

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
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.iconJob?.cancel()
        holder.iconJob = icons.load(app, holder.binding.imageIcon)
    }

    private object Diff : DiffUtil.ItemCallback<ApkSource>() {
        override fun areItemsTheSame(old: ApkSource, new: ApkSource) = old.packageName == new.packageName
        override fun areContentsTheSame(old: ApkSource, new: ApkSource) =
            old.label == new.label && old.versionCode == new.versionCode
    }
}
