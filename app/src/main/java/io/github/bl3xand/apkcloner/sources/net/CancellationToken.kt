package io.github.bl3xand.apkcloner.sources.net

import io.github.bl3xand.apkcloner.sources.core.CancellationSignal

/** Lets a running download be stopped from another thread. */
class CancellationToken {
    @Volatile
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationSignal()
    }
}
