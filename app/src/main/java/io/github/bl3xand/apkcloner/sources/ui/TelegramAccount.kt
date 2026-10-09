package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.telegram.TelegramAuth
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.actionButton
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.confirm
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.showError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val STATE_TIMEOUT_MS = 20_000L
private const val NEXT_STEP_TIMEOUT_MS = 5_000L
private const val FIELD = "value"

/**
 * The Telegram account in the Sources settings: where signing in stands and the button that
 * changes it. Lives as long as [scope] does.
 */
fun LinearLayout.addTelegramAccount(scope: CoroutineScope) {
    val context = context
    add(context.label(Tr.get("telegramDesc"), colorAttr = MaterialR.attr.colorOnSurfaceVariant), topMargin = Spacing.BLOCK / 2)
    val status = context.label("")
    add(status, topMargin = Spacing.BLOCK / 2)
    var signedIn = false
    val button = context.actionButton(Tr.get("telegramSignIn"), R.drawable.ic_open) {
        scope.launch { if (signedIn) context.signOutOfTelegram() else context.signInToTelegram() }
    }
    add(button, topMargin = 8)
    scope.launch {
        // A session left by an earlier run is picked up, so the state shown is the real one.
        if (TelegramClient.hasSession) withContext(Dispatchers.IO) { TelegramClient.start() }
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

/** Asks for one line of text; null when the user backs out. */
private suspend fun Context.askText(label: String, password: Boolean = false): String? {
    val item = TextItem(FIELD, FIELD, required = true, password = password).also { it.labelOverride = { label } }
    val form = FormView(this, listOf(listOf<SettingItem>(item)))
    val view = column(Spacing.SHEET).apply { add(form) }
    if (!confirm(Tr.get("telegramTitle"), view = view)) return null
    return (form.values[FIELD] as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Walks through signing in, one question per step Telegram asks for: the phone number, the code
 * it sends, and the password if the account has one. Nothing typed here is stored or logged; it
 * goes to the Telegram client and nowhere else.
 */
private suspend fun Context.signInToTelegram() {
    withContext(Dispatchers.IO) { TelegramClient.start() }
    while (true) {
        val state = withTimeoutOrNull(STATE_TIMEOUT_MS) { TelegramClient.auth.first { it !is TelegramAuth.Starting } }
        val (label, password, submit) = when (state) {
            TelegramAuth.WaitPhone -> Triple(Tr.get("telegramPhone"), false, TelegramClient::submitPhone)
            TelegramAuth.WaitCode -> Triple(Tr.get("telegramCode"), false, TelegramClient::submitCode)
            is TelegramAuth.WaitPassword -> Triple(
                Tr.get("telegramPassword") + if (state.hint.isEmpty()) "" else "\n" + Tr.get("telegramPasswordHint", state.hint),
                true, TelegramClient::submitPassword,
            )
            TelegramAuth.Ready -> return
            is TelegramAuth.Unsupported -> return showError(SourceError(Tr.get("telegramUnsupported", state.step)))
            TelegramAuth.NotConfigured -> return showError(SourceError(Tr.get("telegramNotConfigured")))
            TelegramAuth.Starting, null -> return showError(SourceError(Tr.get("telegramNoAnswer")))
        }
        val value = askText(label, password) ?: return
        try {
            withContext(Dispatchers.IO) { submit(value) }
        } catch (e: Exception) {
            // A wrong number, code or password: said as it is, and asked again.
            showError(e)
            continue
        }
        // Where the client stands next arrives on its own; give it a moment to say so.
        withTimeoutOrNull(NEXT_STEP_TIMEOUT_MS) { TelegramClient.auth.first { it != state } }
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
