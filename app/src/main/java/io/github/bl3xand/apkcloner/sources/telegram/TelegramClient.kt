package io.github.bl3xand.apkcloner.sources.telegram

import android.content.Context
import android.os.Build
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * The app's Telegram client, on TDLib. It acts for the account the user signs in with; the
 * session stays in the app's private storage and goes when the user signs out.
 *
 * The client is started only when it is needed - by the settings, or by an app tracked from a
 * channel - since a running client keeps a connection to Telegram open.
 */
object TelegramClient : TelegramGateway {
    private const val PREFS = "telegram"
    private const val KEY_SIGNED_IN = "signed_in"
    private const val SESSION_DIR = "telegram"
    private const val REQUEST_TIMEOUT_S = 30L
    private const val START_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 300L
    private const val DOWNLOAD_PRIORITY = 32

    /** How TDLib counts messages against the numbers in their links. */
    private const val MESSAGE_ID_SHIFT = 20

    private lateinit var appContext: Context

    @Volatile
    private var client: Client? = null

    private val _auth = MutableStateFlow<TelegramAuth>(TelegramAuth.Starting)
    val auth: StateFlow<TelegramAuth> = _auth.asStateFlow()

    /** Downloads in progress, by TDLib's file id, each with what to tell about its progress. */
    private val fileListeners = ConcurrentHashMap<Int, (TdApi.File) -> Unit>()

    /** Whether this build carries the app's API id and hash at all. */
    val isConfigured: Boolean get() = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty()

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a session was left by an earlier run; tells without starting the client. */
    val hasSession: Boolean get() = isConfigured && prefs.getBoolean(KEY_SIGNED_IN, false)

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (!isConfigured) _auth.value = TelegramAuth.NotConfigured
    }

    /** Starts the client if it is not running; where it stands then arrives through [auth]. */
    @Synchronized
    fun start() {
        if (!isConfigured || client != null) return
        // TDLib writes its own log to logcat unless told not to.
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(0)) }
        AppLog.debug("Telegram: starting the client")
        client = Client.create(::onUpdate, null, null)
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorization(update.authorizationState)
            is TdApi.UpdateFile -> fileListeners[update.file.id]?.invoke(update.file)
        }
    }

    private fun onAuthorization(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> client?.send(parameters()) { result ->
                if (result is TdApi.Error) AppLog.error("Telegram: the client did not start: ${result.message}")
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TelegramAuth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TelegramAuth.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TelegramAuth.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                prefs.edit().putBoolean(KEY_SIGNED_IN, true).apply()
                AppLog.info("Telegram: signed in")
                _auth.value = TelegramAuth.Ready
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> _auth.value = TelegramAuth.Starting
            is TdApi.AuthorizationStateClosed -> {
                // A closed client is of no further use; a new one starts from the phone number.
                prefs.edit().putBoolean(KEY_SIGNED_IN, false).apply()
                AppLog.info("Telegram: signed out")
                client = null
                _auth.value = TelegramAuth.Starting
                start()
            }
            else -> _auth.value = TelegramAuth.Unsupported(state.javaClass.simpleName.removePrefix("AuthorizationState"))
        }
    }

    private fun parameters(): TdApi.SetTdlibParameters {
        val dir = File(appContext.filesDir, SESSION_DIR).apply { mkdirs() }
        return TdApi.SetTdlibParameters(
            false, dir.path, File(appContext.cacheDir, SESSION_DIR).path, null,
            // Nothing of the account is kept but the session itself: no chats, no messages, no files.
            false, false, false, false,
            BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH,
            Locale.getDefault().language, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME,
        )
    }

    /** Sends [function] and waits for its answer; an error answer is thrown. */
    private fun <T : TdApi.Object> request(function: TdApi.Function<T>, timeoutSeconds: Long = REQUEST_TIMEOUT_S): T {
        val running = client ?: throw SourceError(Tr.get("telegramSignInFirst"))
        val answer = AtomicReference<TdApi.Object>()
        val latch = CountDownLatch(1)
        running.send(function) { result ->
            answer.set(result)
            latch.countDown()
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) throw SourceError(Tr.get("telegramNoAnswer"))
        val result = answer.get()
        if (result is TdApi.Error) throw SourceError("Telegram: ${result.message}")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---- signing in and out ------------------------------------------------------------------

    fun submitPhone(phone: String) {
        request(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    fun submitCode(code: String) {
        request(TdApi.CheckAuthenticationCode(code))
    }

    fun submitPassword(password: String) {
        request(TdApi.CheckAuthenticationPassword(password))
    }

    fun signOut() {
        request(TdApi.LogOut())
    }

    // ---- the gateway -------------------------------------------------------------------------

    /** Starts the client for a session left earlier and waits until it is usable. */
    override val isSignedIn: Boolean
        get() {
            if (!hasSession) return false
            start()
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (_auth.value is TelegramAuth.Starting && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            return _auth.value is TelegramAuth.Ready
        }

    override fun channel(username: String, limit: Int): TelegramChannelInfo {
        val chat = request(TdApi.SearchPublicChat(username))
        val found = request(
            TdApi.SearchChatMessages(chat.id, null, "", null, 0, 0, limit, TdApi.SearchMessagesFilterDocument()),
        )
        return TelegramChannelInfo(chat.title, found.messages.mapNotNull { fileOf(username, it) })
    }

    override fun file(channel: String, messageId: Long): TelegramFile? = message(channel, messageId)?.let { fileOf(channel, it) }

    private fun message(channel: String, messageId: Long): TdApi.Message? =
        request(TdApi.GetMessageLinkInfo("https://t.me/$channel/$messageId")).message

    private fun fileOf(channel: String, message: TdApi.Message): TelegramFile? {
        val document = (message.content as? TdApi.MessageDocument) ?: return null
        val file = document.document.document
        return TelegramFile(
            channel, message.id shr MESSAGE_ID_SHIFT, document.document.fileName,
            file.size.takeIf { it > 0 } ?: file.expectedSize,
            Instant.ofEpochSecond(message.date.toLong()), document.caption?.text.orEmpty(),
        )
    }

    override fun download(
        channel: String,
        messageId: Long,
        destination: File,
        onProgress: ProgressListener?,
        isCancelled: () -> Boolean,
    ): File {
        val document = (message(channel, messageId)?.content as? TdApi.MessageDocument)?.document ?: throw NoApkError()
        val remote = document.document
        val total = remote.size.takeIf { it > 0 } ?: remote.expectedSize
        if (destination.exists() && total > 0 && destination.length() == total) return destination
        val running = client ?: throw SourceError(Tr.get("telegramSignInFirst"))
        val answer = AtomicReference<TdApi.Object>()
        val latch = CountDownLatch(1)
        fileListeners[remote.id] = { file ->
            val received = file.local.downloadedSize
            onProgress?.invoke(if (total > 0) received * 100.0 / total else null, received, total.takeIf { it > 0 })
        }
        try {
            AppLog.debug("Telegram: downloading ${document.fileName} from $channel/$messageId")
            // Answers only once the whole file is there, however long that takes.
            running.send(TdApi.DownloadFile(remote.id, DOWNLOAD_PRIORITY, 0, 0, true)) { result ->
                answer.set(result)
                latch.countDown()
            }
            while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    running.send(TdApi.CancelDownloadFile(remote.id, false)) {}
                    throw CancellationSignal()
                }
            }
            val result = answer.get()
            if (result is TdApi.Error) throw SourceError("Telegram: ${result.message}")
            val local = (result as TdApi.File).local
            if (!local.isDownloadingCompleted || local.path.isNullOrEmpty()) throw SourceError(Tr.get("telegramNoAnswer"))
            destination.parentFile?.mkdirs()
            File(local.path).copyTo(destination, overwrite = true)
            // TDLib's own copy is of no further use.
            running.send(TdApi.DeleteFile(remote.id)) {}
            return destination
        } finally {
            fileListeners.remove(remote.id)
        }
    }
}
