package com.example.studycapturehelper.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        applicationContext.cacheDir
            .resolve("captures")
            .listFiles()
            ?.filter { file ->
                System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
            }
            ?.forEach { it.delete() }
        return Result.success()
    }

    private companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L
    }
}

@Singleton
class MaintenanceScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "capture-cache-maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
