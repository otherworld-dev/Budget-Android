package dev.otherworld.budget.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.ReceiptQueue

@HiltWorker
class ExtractWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: ReceiptQueue,
    private val notifier: ReceiptNotifying,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var readyForReview = 0
        repeat(MAX_ITEMS_PER_RUN) {
            when (queue.extractNext()) {
                is ExtractOutcome.Idle -> {
                    if (readyForReview > 0) notifier.notifyReadyForReview(readyForReview)
                    return Result.success()
                }
                is ExtractOutcome.Extracted, is ExtractOutcome.Failed -> readyForReview++
                is ExtractOutcome.Retry -> {
                    if (readyForReview > 0) notifier.notifyReadyForReview(readyForReview)
                    return Result.retry()
                }
            }
        }
        if (readyForReview > 0) notifier.notifyReadyForReview(readyForReview)
        return Result.success()
    }

    companion object {
        const val NAME = "extract-receipts"
        /** Safety bound: one run never processes an unbounded queue. */
        const val MAX_ITEMS_PER_RUN = 25
    }
}
