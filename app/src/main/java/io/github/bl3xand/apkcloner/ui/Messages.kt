package io.github.bl3xand.apkcloner.ui

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.snackbar.Snackbar
import io.github.bl3xand.apkcloner.R
import java.lang.ref.WeakReference

/**
 * How the app says something in passing: a snackbar on whatever is in front - a sheet if one
 * is open, the main screen otherwise. A toast is used only when nothing of the app is on screen
 * to carry a snackbar, as when the system installer reports back while the user is elsewhere.
 */
object Messages {
    private val main = Handler(Looper.getMainLooper())
    private var activity = WeakReference<FragmentActivity>(null)
    private var fallback = WeakReference<Context>(null)

    /** Sheets shown without a fragment, newest last. */
    private val dialogs = ArrayList<WeakReference<Dialog>>()

    fun attach(activity: FragmentActivity) {
        this.activity = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (this.activity.get() === activity) this.activity = WeakReference(null)
    }

    /** Makes messages land on [dialog] for as long as it is showing. */
    fun track(dialog: Dialog) {
        dialogs.add(WeakReference(dialog))
    }

    /** For callers without a screen of their own, such as a receiver; [context] serves the fallback. */
    fun show(context: Context, text: CharSequence) {
        fallback = WeakReference(context.applicationContext)
        show(text)
    }

    fun show(text: CharSequence) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(text) }
            return
        }
        dialogs.removeAll { it.get()?.isShowing != true }
        val activity = activity.get()
        val front = dialogs.lastOrNull()?.get()
            ?: activity?.supportFragmentManager?.fragments?.filterIsInstance<DialogFragment>()
                ?.lastOrNull { it.dialog?.isShowing == true }?.dialog
        val target: View? = front?.window?.decorView ?: activity?.findViewById(R.id.root)
        if (target == null || activity == null || activity.isFinishing) {
            // Nothing of the app is on screen to carry a snackbar.
            (activity?.applicationContext ?: fallback.get())?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
            return
        }
        Snackbar.make(target, text, Snackbar.LENGTH_LONG).apply {
            // On the main screen it sits above the bottom button rather than over it.
            if (front == null) activity.findViewById<View>(R.id.bottomBar)?.takeIf { it.isShown }?.let { anchorView = it }
        }.show()
    }
}
