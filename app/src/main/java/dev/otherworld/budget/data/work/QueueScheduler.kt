package dev.otherworld.budget.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Lets ViewModels schedule work without depending on WorkManager. */
interface QueueScheduling {
    fun scheduleExtraction()
    fun schedulePost()
    fun schedulePrune()
    fun cancelAll()
}

@Singleton
class QueueScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : QueueScheduling {

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun scheduleExtraction() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ExtractWorker.NAME,
            // KEEP, not REPLACE: a second capture while extraction is already
            // running must not cancel and restart the in-flight drain.
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExtractWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun schedulePost() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            PostWorker.NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PostWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun schedulePrune() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PruneWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PruneWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(ExtractWorker.NAME)
            cancelUniqueWork(PostWorker.NAME)
            cancelUniqueWork(PruneWorker.NAME)
        }
    }
}
