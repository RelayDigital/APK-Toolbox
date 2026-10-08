package io.github.bl3xand.apkcloner.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.bl3xand.apkcloner.BuildConfig
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Installs packages through a shell-privileged process provided by Shizuku, which needs no
 * confirmation from the user.
 *
 * The UserService connection is bound once and reused: binding and unbinding it around every
 * call trips a ConcurrentModificationException inside Shizuku's own connection bookkeeping.
 */
object ShizukuBridge {

    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    // daemon(true): one persistent process shared across app launches, since we never unbind.
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, PrivilegedUserService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private const val BINDER_WAIT_STEPS = 25
    private const val BINDER_WAIT_STEP_MS = 200L

    private val bindMutex = Mutex()
    @Volatile
    private var boundService: IPrivilegedService? = null

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (_: Throwable) {
        State.NOT_RUNNING
    }

    /**
     * [state] for a process that has only just started: Shizuku hands its binder over a moment
     * after launch, and until then it looks exactly like Shizuku not running.
     */
    suspend fun stateAfterStartup(): State {
        repeat(BINDER_WAIT_STEPS) {
            if (state() != State.NOT_RUNNING) return state()
            delay(BINDER_WAIT_STEP_MS)
        }
        return state()
    }

    fun requestPermission(requestCode: Int) {
        if (state() == State.NO_PERMISSION) Shizuku.requestPermission(requestCode)
    }

    /** Returns null on success, otherwise the reason the install failed. */
    suspend fun install(apks: List<File>): String? {
        val service = getOrBindService() ?: return "Shizuku is not available"
        return withContext(Dispatchers.IO) {
            try {
                val descriptors = apks.map { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                try {
                    service.install(
                        descriptors.toTypedArray(),
                        apks.map { it.name }.toTypedArray(),
                        apks.map { it.length() }.toLongArray(),
                        BuildConfig.APPLICATION_ID,
                        Process.myUserHandle().hashCode(),
                    ).ifEmpty { null }
                } finally {
                    descriptors.forEach { runCatching { it.close() } }
                }
            } catch (e: Exception) {
                // Binder likely died without onServiceDisconnected firing yet - drop it so the next call rebinds.
                boundService = null
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    private suspend fun getOrBindService(): IPrivilegedService? {
        if (state() != State.READY) return null
        boundService?.let { return it }
        return bindMutex.withLock {
            boundService?.let { return@withLock it }
            val connected = CompletableDeferred<IPrivilegedService?>()
            val oneShotConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val service = IPrivilegedService.Stub.asInterface(binder)
                    boundService = service
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    boundService = null
                    connected.complete(null)
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, oneShotConnection)
            } catch (_: Throwable) {
                return@withLock null
            }
            // If this never connects, unbind the dangling connection instead of leaving it
            // registered with Shizuku forever.
            try {
                withTimeoutOrNull(10_000) { connected.await() }
            } finally {
                if (boundService == null) {
                    try {
                        Shizuku.unbindUserService(userServiceArgs, oneShotConnection, true)
                    } catch (_: Throwable) {
                        // Best effort.
                    }
                }
            }
        }
    }
}
