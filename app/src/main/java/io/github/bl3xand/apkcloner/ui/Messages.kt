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
 * How the app says something in passing. Which of the two ways is used follows one rule:
 *
 * - [show] - a snackbar - answers something the user has just done on one of the app's own
 *   screens: saved, exported, copied, refused, failed. It appears on whatever is in front, a
 *   sheet if one is open and the main screen otherwise.
 * - [toast] reports what happens outside those screens and may arrive when the app is not in
 *   front at all: the system installer finishing, successfully or not.
 */
object Messages {
    private val main = Handler(Looper.getMainLooper())
    private var activity = WeakReference<FragmentActivity>(null)

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
            activity?.let { toast(it.applicationContext, text) }
            return
        }
        Snackbar.make(target, text, Snackbar.LENGTH_LONG).apply {
            // On the main screen it sits above the bottom button rather than over it.
            if (front == null) activity.findViewById<View>(R.id.bottomBar)?.takeIf { it.isShown }?.let { anchorView = it }
        }.show()
    }

    fun toast(context: Context, text: CharSequence) {
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}
