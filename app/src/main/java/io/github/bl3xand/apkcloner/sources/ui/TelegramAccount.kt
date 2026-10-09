package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
    // Asks Telegram again whether the session holds, for when the state shown is in doubt.
    val refresh = MaterialButton(context, null, MaterialR.attr.materialIconButtonStyle).apply {
        setIconResource(R.drawable.ic_update)
        contentDescription = Tr.get("telegramCheckAgain")
        setOnClickListener {
            isEnabled = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    TelegramClient.start()
                    TelegramClient.verifySession(force = true)
                }
                delay(CHECK_PAUSE_MS)
                isEnabled = true
            }
        }
    }
    val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(refresh)
    add(row, topMargin = Spacing.BLOCK / 2)
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
        TelegramClient.auth.combine(TelegramClient.answers, ::Pair).collect { (state, answers) ->
            signedIn = state is TelegramAuth.Ready
            button.isVisible = state !is TelegramAuth.NotConfigured
            button.text = Tr.get(if (signedIn) "telegramSignOut" else "telegramSignIn")
            refresh.isVisible = signedIn
            status.text = when {
                state is TelegramAuth.NotConfigured -> Tr.get("telegramNotConfigured")
                // Signed in as far as this device knows, but Telegram could not be asked.
                signedIn && !answers -> Tr.get("telegramNoAnswerState")
                signedIn -> Tr.get("telegramSignedIn")
                state is TelegramAuth.Starting && TelegramClient.hasSession -> Tr.get("telegramConnecting")
                else -> Tr.get("telegramSignedOut")
            }
        }
    }
}

/** How long the check button rests after a tap: an answer takes a moment to come. */
private const val CHECK_PAUSE_MS = 2_000L

private suspend fun Context.signOutOfTelegram() {
    if (!confirm(Tr.get("telegramSignOutTitle"), Tr.get("telegramSignOutConfirm"))) return
    try {
        withContext(Dispatchers.IO) { TelegramClient.signOut() }
    } catch (e: Exception) {
        showError(e)
    }
}
