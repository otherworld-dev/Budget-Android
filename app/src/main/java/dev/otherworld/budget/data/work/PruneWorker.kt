package dev.otherworld.budget.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.otherworld.budget.data.repo.ReceiptQueue

/** Keeps the capture queue inside its bounds. */
@HiltWorker
class PruneWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: ReceiptQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        queue.prune()          // spec §7: 100 items or 30 days, whichever comes first
        return Result.success()
    }

    companion object { const val NAME = "prune-receipts" }
}
