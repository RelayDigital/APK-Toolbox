package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.telegram.TelegramAuth
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.actionButton
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.showError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Telegram account in the Sources settings: where signing in stands and the button that
 * changes it. Lives as long as [scope] does.
 */
fun LinearLayout.addTelegramAccount(scope: CoroutineScope) {
    val context = context
    // Laid out like the tokens above it: a name, then what it is for.
    add(context.label(Tr.get("telegramTitle"), MaterialR.attr.textAppearanceTitleMedium), topMargin = Spacing.BLOCK)
    add(context.label(Tr.get("telegramDesc"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = 2)
    val status = context.label("")
    add(status, topMargin = Spacing.BLOCK / 2)
    var signedIn = false
    val button = context.actionButton(Tr.get("telegramSignIn"), R.drawable.ic_open) {
        if (signedIn) scope.launch { context.signOutOfTelegram() } else context.showTelegramSignIn(scope)
    }
    add(button, topMargin = 8)
    scope.launch {
        // A session left by an earlier run is picked up, so the state shown is the real one.
        if (TelegramClient.hasSession) {
            withContext(Dispatchers.IO) {
                TelegramClient.start()
                // The session may have been ended from another device since it was last used.
                TelegramClient.verifySession()
            }
        }
        TelegramClient.auth.collect { state ->
            signedIn = state is TelegramAuth.Ready
            button.isVisible = state !is TelegramAuth.NotConfigured
            button.text = Tr.get(if (signedIn) "telegramSignOut" else "telegramSignIn")
            status.text = when {
                state is TelegramAuth.NotConfigured -> Tr.get("telegramNotConfigured")
                signedIn -> Tr.get("telegramSignedIn")
                state is TelegramAuth.Starting && TelegramClient.hasSession -> Tr.get("telegramConnecting")
                else -> Tr.get("telegramSignedOut")
            }
        }
    }
}

private suspend fun Context.signOutOfTelegram() {
    if (!confirm(Tr.get("telegramSignOut"), Tr.get("telegramSignOutConfirm"))) return
    try {
        withContext(Dispatchers.IO) { TelegramClient.signOut() }
    } catch (e: Exception) {
        showError(e)
    }
}
