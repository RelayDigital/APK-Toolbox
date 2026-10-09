package io.github.bl3xand.apkcloner.sources.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.bl3xand.apkcloner.log.AppLog

/** A later attempt at the apps whose check failed. */
class SourcesRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = inputData.getStringArray(SourcesBackground.KEY_IDS) ?: return Result.success()
        val attempts = inputData.getIntArray(SourcesBackground.KEY_ATTEMPTS) ?: IntArray(ids.size)
        return try {
            SourcesBackground.run(applicationContext, retry = ids.mapIndexed { i, id -> id to attempts.getOrElse(i) { 1 } })
            Result.success()
        } catch (e: Exception) {
            AppLog.error("Retry of the background check failed", e)
            Result.failure()
        }
    }
}
