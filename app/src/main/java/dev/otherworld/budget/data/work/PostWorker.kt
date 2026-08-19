package dev.otherworld.budget.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.otherworld.budget.data.repo.ReceiptQueue

/** Re-sends transactions the user already reviewed but whose upload failed. */
@HiltWorker
class PostWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: ReceiptQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (queue.retryFailedPosts()) Result.success() else Result.retry()

    companion object { const val NAME = "post-receipts" }
}
