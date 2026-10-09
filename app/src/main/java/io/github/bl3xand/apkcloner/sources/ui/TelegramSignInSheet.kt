package io.github.bl3xand.apkcloner.sources.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.R as AppCompatR
import androidx.core.view.isInvisible
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.errorText
import io.github.bl3xand.apkcloner.sources.telegram.TelegramAuth
import io.github.bl3xand.apkcloner.sources.telegram.TelegramClient
import io.github.bl3xand.apkcloner.ui.Messages
import io.github.bl3xand.apkcloner.ui.Spacing
import io.github.bl3xand.apkcloner.ui.add
import io.github.bl3xand.apkcloner.ui.column
import io.github.bl3xand.apkcloner.ui.dp
import io.github.bl3xand.apkcloner.ui.expandFully
import io.github.bl3xand.apkcloner.ui.label
import io.github.bl3xand.apkcloner.ui.scrollable
import io.github.bl3xand.apkcloner.ui.themeColor
import io.github.bl3xand.apkcloner.ui.toast
import io.github.bl3xand.apkcloner.ui.tonalButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val QR_SIZE_DP = 220
private const val QR_MARGIN_MODULES = 2

/** How long the sheet stays locked for an answer that never comes before it lets go again. */
private const val ANSWER_TIMEOUT_MS = 20_000L

/**
 * Signing in to Telegram, in one sheet that follows what Telegram asks for next: a phone number
 * or a QR code scanned from another device, then the code, an e-mail step and the password if
 * the account has them. While an answer is awaited everything in the sheet is locked and a bar runs;
 * the sheet itself can still be left by going back.
 *
 * Nothing typed here is stored or logged; it goes to the Telegram client and nowhere else.
 */
fun Context.showTelegramSignIn(scope: CoroutineScope) {
    val dialog = BottomSheetDialog(this)
    Messages.track(dialog)
    // A tap beside the sheet must not throw away a code that is being typed.
    dialog.setCanceledOnTouchOutside(false)
    val progress = LinearProgressIndicator(this).apply {
        isIndeterminate = true
        isInvisible = true
    }
    val step = column()
    val root = column().apply {
        addView(BottomSheetDragHandleView(context))
        addView(
            column(Spacing.SHEET).apply {
                add(context.label(Tr.get("telegramTitle"), MaterialR.attr.textAppearanceHeadlineSmall))
                add(progress, topMargin = Spacing.BLOCK / 2)
                add(step, topMargin = Spacing.BLOCK / 2)
                add(android.view.View(context), topMargin = Spacing.SHEET)
            },
        )
    }
    dialog.setContentView(root.scrollable())
    dialog.expandFully()

    /** What the current step is made of, to lock while an answer is awaited. */
    val controls = ArrayList<android.view.View>()
    var unlock: Job? = null
    fun setBusy(busy: Boolean) {
        progress.isInvisible = !busy
        controls.forEach { it.isEnabled = !busy }
        unlock?.cancel()
        // An answer that never comes must not leave the sheet locked for good.
        unlock = if (busy) scope.launch {
            delay(ANSWER_TIMEOUT_MS)
            setBusy(false)
        } else null
    }
    val error = label("", colorAttr = AppCompatR.attr.colorError)

    /** Runs [action] off the main thread; what Telegram asks for next arrives on its own. */
    fun send(action: () -> Unit) {
        error.text = ""
        setBusy(true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                // A wrong number, code or password: said under the field, to be tried again.
                error.text = errorText(e)
                setBusy(false)
            }
        }
    }

    /** Every action of the sheet is a button of the size the bottom buttons of other sheets have. */
    fun button(text: String, icon: Int? = null, onClick: () -> Unit): MaterialButton =
        tonalButton(text, icon, onClick).apply { minimumHeight = dp(Spacing.ROW) }.also { controls.add(it) }

    /** One question: what to enter, the field for it and the button that sends it. */
    fun ask(prompt: String, inputType: Int, note: String? = null, submit: (String) -> Unit) {
        val layout = TextInputLayout(this, null, MaterialR.attr.textInputOutlinedStyle).apply { hint = prompt }
        val edit = TextInputEditText(layout.context).apply {
            this.inputType = inputType
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(edit)
        fun go() {
            val value = edit.text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) send { submit(value) }
        }
        edit.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }
        if (note != null) step.add(label(note, colorAttr = MaterialR.attr.colorOnSurfaceVariant))
        step.add(layout, topMargin = if (note != null) Spacing.BLOCK / 2 else 0)
        step.add(error, topMargin = 4)
        step.add(button(Tr.get("continue")) { go() }, topMargin = Spacing.BLOCK / 2)
        controls.add(layout)
        edit.requestFocus()
    }

    fun startOverButton() = step.add(
        button(Tr.get("telegramStartOver")) { send { TelegramClient.startOver() } },
        topMargin = Spacing.BLOCK / 2,
    )

    /** The code on screen, while one is: Telegram replaces it every half a minute. */
    var qrView: ImageView? = null

    fun render(state: TelegramAuth) {
        // A fresh code goes into the picture that is there; redrawing the whole step would blink.
        val shown = qrView
        if (state is TelegramAuth.WaitOtherDevice && shown != null) {
            shown.setImageBitmap(qrCode(state.link, dp(QR_SIZE_DP)))
            setBusy(false)
            return
        }
        qrView = null
        step.removeAllViews()
        controls.clear()
        (error.parent as? LinearLayout)?.removeView(error)
        error.text = ""
        setBusy(state is TelegramAuth.Starting)
        when (state) {
            TelegramAuth.Starting -> step.add(label(Tr.get("telegramConnecting"), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
            TelegramAuth.WaitPhone -> {
                ask(Tr.get("telegramPhone"), InputType.TYPE_CLASS_PHONE) { TelegramClient.submitPhone(it) }
                // For when Telegram is at hand on another device: no number, no code.
                step.add(
                    button(Tr.get("telegramViaQr")) { send { TelegramClient.requestOtherDevice() } },
                    topMargin = Spacing.BLOCK / 2,
                )
            }
            is TelegramAuth.WaitOtherDevice -> {
                step.add(label(Tr.get("telegramQrHint"), colorAttr = MaterialR.attr.colorOnSurfaceVariant))
                val code = ImageView(this).apply { setImageBitmap(qrCode(state.link, dp(QR_SIZE_DP))) }
                step.addView(
                    code,
                    LinearLayout.LayoutParams(dp(QR_SIZE_DP), dp(QR_SIZE_DP)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dp(Spacing.BLOCK)
                    },
                )
                qrView = code
                step.add(error, topMargin = 4)
                step.add(
                    button(Tr.get("telegramByPhone")) { send { TelegramClient.startOver() } },
                    topMargin = Spacing.BLOCK / 2,
                )
            }
            is TelegramAuth.WaitCode -> {
                ask(
                    Tr.get("telegramCode"), InputType.TYPE_CLASS_NUMBER,
                    Tr.get(if (state.viaTelegram) "telegramCodeApp" else "telegramCodeSms"),
                ) { TelegramClient.submitCode(it) }
                startOverButton()
            }
            TelegramAuth.WaitEmail -> {
                ask(Tr.get("telegramEmail"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { TelegramClient.submitEmail(it) }
                startOverButton()
            }
            TelegramAuth.WaitEmailCode -> {
                ask(Tr.get("telegramEmailCode"), InputType.TYPE_CLASS_NUMBER) { TelegramClient.submitEmailCode(it) }
                startOverButton()
            }
            is TelegramAuth.WaitPassword -> {
                ask(
                    Tr.get("telegramPassword"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    state.hint.takeIf { it.isNotEmpty() }?.let { Tr.get("telegramPasswordHint", it) },
                ) { TelegramClient.submitPassword(it) }
                startOverButton()
            }
            TelegramAuth.Ready -> {
                toast(Tr.get("telegramSignedIn"))
                dialog.dismiss()
            }
            is TelegramAuth.Unsupported -> {
                step.add(label(Tr.get("telegramUnsupported", state.step), colorAttr = AppCompatR.attr.colorError))
                startOverButton()
            }
            TelegramAuth.NotConfigured -> step.add(label(Tr.get("telegramNotConfigured"), colorAttr = AppCompatR.attr.colorError))
        }
    }

    val watching = scope.launch {
        withContext(Dispatchers.IO) {
            TelegramClient.start()
            // The sheet always opens on the phone number; a code left from last time is dropped.
            if (TelegramClient.auth.value is TelegramAuth.WaitOtherDevice) TelegramClient.startOver()
        }
        TelegramClient.auth.collect { render(it) }
    }
    dialog.setOnDismissListener {
        watching.cancel()
        unlock?.cancel()
    }
    dialog.show()
}

/** [text] as a QR code of [size] pixels, dark on white whatever the theme: scanners expect that. */
private fun qrCode(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to QR_MARGIN_MODULES))
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
