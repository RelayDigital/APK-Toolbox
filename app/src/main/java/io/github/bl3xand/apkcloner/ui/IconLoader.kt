package io.github.bl3xand.apkcloner.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import io.github.bl3xand.apkcloner.data.ApkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decodes app icons off the main thread - a few milliseconds each is too slow while scrolling. */
class IconLoader(private val scope: CoroutineScope, private val packageManager: PackageManager) {

    private val cache = LruCache<String, Drawable>(200)

    /** Returns the job to cancel if [view] is recycled before the icon arrives. */
    fun load(app: ApkSource, view: ImageView): Job? = load(app.packageName, app.appInfo, view)

    /** The same for any installed app; [placeholder] is shown until its icon has been decoded. */
    fun load(packageName: String, appInfo: ApplicationInfo, view: ImageView, placeholder: Drawable? = null): Job? {
        // An update replaces the files the old icon came from, so the key names the build.
        val key = "$packageName@${appInfo.sourceDir}"
        val cached = cache[key]
        view.setImageDrawable(cached?.constantState?.newDrawable() ?: cached ?: placeholder)
        if (cached != null) return null
        return scope.launch {
            // An app can be gone by the time its icon is asked for; the row then keeps what it shows.
            val icon = withContext(Dispatchers.IO) { runCatching { AppIcons.load(packageManager, appInfo) }.getOrNull() }
                ?: return@launch
            cache.put(key, icon)
            view.setImageDrawable(icon.constantState?.newDrawable() ?: icon)
        }
    }
}
