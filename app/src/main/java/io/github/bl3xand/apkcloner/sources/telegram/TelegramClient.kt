package io.github.bl3xand.apkcloner.sources.telegram

import android.content.Context
import android.os.Build
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.log.AppLog
import io.github.bl3xand.apkcloner.sources.core.CancellationSignal
import io.github.bl3xand.apkcloner.sources.core.NoApkError
import io.github.bl3xand.apkcloner.sources.core.RangeReader
import io.github.bl3xand.apkcloner.sources.core.RateLimitError
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.net.ProgressListener
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
    private const val KEY_ENCRYPTED = "encrypted"

    /** The file TDLib keeps the session in. */
    private const val DATABASE_FILE = "td.binlog"
    private const val SESSION_DIR = "telegram"
    private const val REQUEST_TIMEOUT_S = 30L
    private const val PART_TIMEOUT_S = 90L
    private const val START_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 300L
    private const val DOWNLOAD_PRIORITY = 32

    /** The most messages Telegram gives in one answer. */
    private const val PAGE_SIZE = 100

    /** A download that brings nothing for this long is taken for stuck and started again. */
    private const val STALL_MS = 90_000L
    private const val DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_PAUSE_MS = 3_000L
    private const val PROGRESS_EVERY_MS = 250L
    private const val VERIFY_EVERY_MS = 60_000L
    private const val VERIFY_TIMEOUT_S = 10L

    /** How TDLib counts messages against the numbers in their links. */
    private const val MESSAGE_ID_SHIFT = 20

    private lateinit var appContext: Context

    @Volatile
    private var client: Client? = null

    /** Set while the user is signing out or starting over, so that the end of the session is no surprise. */
    @Volatile
    private var closingOnRequest = false

    @Volatile
    private var lastVerified = 0L

    private val _answers = MutableStateFlow(true)

    /** False while the last check of the session got no answer; says nothing about the session itself. */
    val answers: StateFlow<Boolean> = _answers.asStateFlow()

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** Set while an unfinished sign-in is being dropped, which also ends in a closed client. */
    @Volatile
    private var startingOver = false

    private val _auth = MutableStateFlow<TelegramAuth>(TelegramAuth.Starting)
    val auth: StateFlow<TelegramAuth> = _auth.asStateFlow()

    /** Called when the session is ended by someone else than the user of this app. */
    @Volatile
    var onSessionEnded: (() -> Unit)? = null

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

    /** Where the client keeps the files it has fetched, whole or in part. */
    fun downloadsDir(context: Context): File = File(context.cacheDir, SESSION_DIR)

    /** Starts the client if it is not running; where it stands then arrives through [auth]. */
    @Synchronized
    fun start() {
        if (!isConfigured || client != null) return
        try {
            // TDLib writes its own log to logcat unless told not to.
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(0)) }
            AppLog.debug("Telegram: starting the client")
            client = Client.create(::onUpdate, ::onClientFailure, ::onClientFailure)
        } catch (e: Throwable) {
            // The native library did not load: nothing of Telegram can work on this device.
            AppLog.error("Telegram: the client cannot be started", e)
            _auth.value = TelegramAuth.Unsupported(e.javaClass.simpleName)
        }
    }

    private fun onClientFailure(error: Throwable) {
        AppLog.error("Telegram: the client failed", error)
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorization(update.authorizationState)
            is TdApi.UpdateFile -> fileListeners[update.file.id]?.invoke(update.file)
        }
    }

    private fun onAuthorization(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> open()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TelegramAuth.WaitPhone
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> _auth.value = TelegramAuth.WaitOtherDevice(state.link)
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TelegramAuth.WaitCode(
                viaTelegram = state.codeInfo?.type is TdApi.AuthenticationCodeTypeTelegramMessage,
            )
            is TdApi.AuthorizationStateWaitEmailAddress -> _auth.value = TelegramAuth.WaitEmail
            is TdApi.AuthorizationStateWaitEmailCode -> _auth.value = TelegramAuth.WaitEmailCode
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TelegramAuth.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                prefs.edit().putBoolean(KEY_SIGNED_IN, true).apply()
                AppLog.info("Telegram: signed in")
                _auth.value = TelegramAuth.Ready
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> _auth.value = TelegramAuth.Starting
            is TdApi.AuthorizationStateClosed -> onClosed()
            else -> _auth.value = TelegramAuth.Unsupported(state.javaClass.simpleName.removePrefix("AuthorizationState"))
        }
    }

    /** A closed client is of no further use; a new one starts from the beginning of signing in. */
    private fun onClosed() {
        // A session that was dropped has its new client running already.
        if (client == null && _auth.value !is TelegramAuth.Starting) return
        val hadSession = prefs.getBoolean(KEY_SIGNED_IN, false)
        prefs.edit().putBoolean(KEY_SIGNED_IN, false).apply()
        if (startingOver) {
            AppLog.debug("Telegram: the unfinished sign-in was dropped")
        } else if (closingOnRequest) {
            AppLog.info("Telegram: signed out")
        } else if (hadSession) {
            // Ended from another device, revoked by Telegram, or the account was closed.
            AppLog.warn("Telegram: the session was ended from outside; apps tracked from channels need a new sign-in")
            onSessionEnded?.invoke()
        }
        closingOnRequest = false
        startingOver = false
        client = null
        _auth.value = TelegramAuth.Starting
        start()
    }

    private fun sessionDir(): File = File(appContext.filesDir, SESSION_DIR)

    /**
     * Opens the session, encrypted with a key of this device (see [SessionKey]). A session left
     * by a version that did not encrypt it is opened the old way once and encrypted then.
     */
    private fun open() {
        val running = client ?: return
        val leftUnencrypted = !prefs.getBoolean(KEY_ENCRYPTED, false) && File(sessionDir(), DATABASE_FILE).exists()
        val key = try {
            SessionKey.get(appContext, PREFS)
        } catch (e: Exception) {
            // The keystore's key is gone, and with it any way to open what it locked.
            AppLog.error("Telegram: the session key cannot be read; the session is dropped", e)
            dropSession()
            return
        }
        running.send(parameters(if (leftUnencrypted) ByteArray(0) else key)) { result ->
            when {
                result is TdApi.Error && !leftUnencrypted && prefs.getBoolean(KEY_ENCRYPTED, false) -> {
                    AppLog.error("Telegram: the session cannot be opened (${result.message}); it is dropped")
                    dropSession()
                }
                result is TdApi.Error -> {
                    AppLog.error("Telegram: the client did not start: ${result.message}")
                    _auth.value = TelegramAuth.Unsupported(result.message)
                }
                leftUnencrypted -> running.send(TdApi.SetDatabaseEncryptionKey(key)) { changed ->
                    if (changed is TdApi.Error) {
                        AppLog.warn("Telegram: the session could not be encrypted: ${changed.message}")
                    } else {
                        prefs.edit().putBoolean(KEY_ENCRYPTED, true).apply()
                        AppLog.info("Telegram: the session is now encrypted")
                    }
                }
                else -> prefs.edit().putBoolean(KEY_ENCRYPTED, true).apply()
            }
        }
    }

    /** Throws away a session that cannot be opened; the client then starts from signing in. */
    private fun dropSession() {
        val running = client
        client = null
        runCatching { running?.send(TdApi.Close()) {} }
        sessionDir().deleteRecursively()
        SessionKey.forget(appContext, PREFS)
        prefs.edit().putBoolean(KEY_SIGNED_IN, false).putBoolean(KEY_ENCRYPTED, false).apply()
        _auth.value = TelegramAuth.Starting
        start()
    }

    private fun parameters(key: ByteArray): TdApi.SetTdlibParameters {
        val dir = sessionDir().apply { mkdirs() }
        return TdApi.SetTdlibParameters(
            false, dir.path, downloadsDir(appContext).path, key,
            // Nothing of the account is kept but the session itself: no chats, no messages, no files.
            false, false, false, false,
            BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH,
            Locale.getDefault().language, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME,
        )
    }

    /** Sends [function] and waits for its answer; an error answer is thrown, in words of its own. */
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
        if (result is TdApi.Error) throw errorOf(result)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun errorOf(error: TdApi.Error): SourceError {
        AppLog.warn("Telegram answered with an error: ${error.code} ${error.message}")
        return TelegramErrors.toError(error.code, error.message.orEmpty())
    }

    // ---- signing in and out ------------------------------------------------------------------

    fun submitPhone(phone: String) {
        request(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    /** Asks for a sign-in that is confirmed from a Telegram app that is signed in already. */
    fun requestOtherDevice() {
        request(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    fun submitCode(code: String) {
        request(TdApi.CheckAuthenticationCode(code))
    }

    fun submitEmail(address: String) {
        request(TdApi.SetAuthenticationEmailAddress(address))
    }

    fun submitEmailCode(code: String) {
        request(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }

    fun submitPassword(password: String) {
        request(TdApi.CheckAuthenticationPassword(password))
    }

    /**
     * Asks Telegram who is signed in, to find out whether the session still holds: one that was
     * ended from another device is only noticed by asking, and the client then says so through
     * [auth]. No answer at all - a bad connection, say - is told through [answers]; the session
     * is left alone then, and the next check that gets through puts things right.
     *
     * Asked once a minute at most unless [force]d. Returns at once; what comes of it arrives later.
     */
    fun verifySession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_auth.value !is TelegramAuth.Ready || (!force && now - lastVerified < VERIFY_EVERY_MS)) return
        lastVerified = now
        val running = client ?: return
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        running.send(TdApi.GetMe()) { result ->
            answered.set(true)
            if (result is TdApi.Error) {
                AppLog.warn("Telegram: asked who is signed in, answered ${result.code} ${result.message}")
                // A refused session is closed by the client itself, which is told through [auth].
                _answers.value = TelegramErrors.isAccountRefused(result.code, result.message.orEmpty())
            } else {
                _answers.value = true
            }
        }
        watchdog.schedule({
            if (!answered.get()) {
                AppLog.warn("Telegram: no answer to the check of the session")
                _answers.value = false
            }
        }, VERIFY_TIMEOUT_S, TimeUnit.SECONDS)
    }

    fun signOut() {
        closingOnRequest = true
        request(TdApi.LogOut())
    }

    /**
     * Drops a sign-in that was begun and not finished, so that it can be begun another way. What
     * was begun is kept by the client across restarts, so its data is wiped, not just closed.
     */
    fun startOver() {
        if (_auth.value is TelegramAuth.Ready) return
        startingOver = true
        _auth.value = TelegramAuth.Starting
        val running = client ?: return start()
        running.send(TdApi.Destroy()) {}
    }

    // ---- the gateway -------------------------------------------------------------------------

    /** Starts the client for a session left earlier and waits until it is usable. */
    override val isSignedIn: Boolean
        get() {
            if (!hasSession) return false
            start()
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (_auth.value is TelegramAuth.Starting && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
            verifySession()
            return _auth.value is TelegramAuth.Ready
        }

    override fun channel(username: String, limit: Int, query: String): TelegramChannelInfo {
        val chat = request(TdApi.SearchPublicChat(username))
        val files = ArrayList<TelegramFile>()
        var from = 0L
        // Telegram hands the messages out a page at a time, newest first.
        while (files.size < limit) {
            val found = request(
                TdApi.SearchChatMessages(
                    chat.id, null, query, null, from, 0, minOf(PAGE_SIZE, limit - files.size), TdApi.SearchMessagesFilterDocument(),
                ),
            )
            found.messages.mapNotNullTo(files) { fileOf(username, it) }
            from = found.nextFromMessageId
            if (from == 0L || found.messages.isEmpty()) break
        }
        return TelegramChannelInfo(chat.title, files)
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

    /**
     * A way to read parts of the file of a message without fetching all of it, and the file's
     * size. What is read stays with the client, so a later download of the whole goes on from it.
     */
    override fun reader(channel: String, messageId: Long): Pair<Long, RangeReader>? {
        val remote = (message(channel, messageId)?.content as? TdApi.MessageDocument)?.document?.document ?: return null
        val size = remote.size.takeIf { it > 0 } ?: return null
        return size to RangeReader { offset, length ->
            // Answers once this stretch is there; then it can be read back.
            request(TdApi.DownloadFile(remote.id, DOWNLOAD_PRIORITY, offset, length.toLong(), true), PART_TIMEOUT_S)
            request(TdApi.ReadFilePart(remote.id, offset, length.toLong())).data
        }
    }

    /**
     * Downloads the file of a message. A download that fails or stops moving is started again a
     * few times - TDLib keeps what it already has, so it goes on from there - before giving up.
     */
    override fun download(
        channel: String,
        messageId: Long,
        destination: File,
        onProgress: ProgressListener?,
        isCancelled: () -> Boolean,
    ): File {
        var attempt = 1
        while (true) {
            try {
                return downloadOnce(channel, messageId, destination, onProgress, isCancelled)
            } catch (e: Exception) {
                // Cancelled by the user, told to wait, or out of attempts: nothing to try again.
                if (e is CancellationSignal || e is RateLimitError || attempt >= DOWNLOAD_ATTEMPTS || !hasSession) throw e
                AppLog.warn("Telegram: download of $channel/$messageId failed (attempt $attempt), trying again: ${e.message}")
                attempt++
                Thread.sleep(RETRY_PAUSE_MS)
            }
        }
    }

    private fun downloadOnce(
        channel: String,
        messageId: Long,
        destination: File,
        onProgress: ProgressListener?,
        isCancelled: () -> Boolean,
    ): File {
        // Asked for anew on every attempt: the reference to a file goes stale after a while.
        val document = (message(channel, messageId)?.content as? TdApi.MessageDocument)?.document ?: throw NoApkError()
        val remote = document.document
        val total = remote.size.takeIf { it > 0 } ?: remote.expectedSize
        if (destination.exists() && total > 0 && destination.length() == total) return destination
        val running = client ?: throw SourceError(Tr.get("telegramSignInFirst"))
        val answer = AtomicReference<TdApi.Object>()
        val latch = CountDownLatch(1)
        val lastMoved = AtomicLong(System.currentTimeMillis())
        val lastReceived = AtomicLong(-1)
        val lastTold = AtomicLong(0)
        fileListeners[remote.id] = { file ->
            val received = file.local.downloadedSize
            val now = System.currentTimeMillis()
            if (lastReceived.getAndSet(received) != received) lastMoved.set(now)
            // Telegram reports many times a second; a few are enough to draw a bar from.
            if (now - lastTold.get() >= PROGRESS_EVERY_MS) {
                lastTold.set(now)
                onProgress?.invoke(if (total > 0) received * 100.0 / total else null, received, total.takeIf { it > 0 })
            }
        }
        try {
            AppLog.debug("Telegram: downloading ${document.fileName} from $channel/$messageId")
            // Answers only once the whole file is there, however long that takes.
            running.send(TdApi.DownloadFile(remote.id, DOWNLOAD_PRIORITY, 0, 0, true)) { result ->
                answer.set(result)
                latch.countDown()
            }
            while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                val stalled = System.currentTimeMillis() - lastMoved.get() > STALL_MS
                if (isCancelled() || stalled || client !== running) {
                    running.send(TdApi.CancelDownloadFile(remote.id, false)) {}
                    // What was fetched so far stays with the client, so the download can go on from it.
                    if (isCancelled()) throw CancellationSignal()
                    throw SourceError(Tr.get(if (stalled) "telegramNoAnswer" else "telegramErrSession"))
                }
            }
            val result = answer.get()
            if (result is TdApi.Error) throw errorOf(result)
            val local = (result as TdApi.File).local
            if (!local.isDownloadingCompleted || local.path.isNullOrEmpty()) throw SourceError(Tr.get("telegramNoAnswer"))
            // Copied under another name first, so that a copy cut short is never taken for the file.
            destination.parentFile?.mkdirs()
            val partial = File(destination.path + ".part")
            File(local.path).copyTo(partial, overwrite = true)
            if (!partial.renameTo(destination)) {
                destination.delete()
                if (!partial.renameTo(destination)) throw SourceError(Tr.get("telegramNoAnswer"))
            }
            // TDLib's own copy is of no further use.
            running.send(TdApi.DeleteFile(remote.id)) {}
            return destination
        } finally {
            fileListeners.remove(remote.id)
        }
    }
}
