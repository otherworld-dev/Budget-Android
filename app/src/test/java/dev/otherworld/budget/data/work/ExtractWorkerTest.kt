package dev.otherworld.budget.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.domain.model.DraftTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// See PendingReceiptDaoTest for why sdk is pinned per-class rather than globally
// (Robolectric 4.14.1 tops out at API 35; this project targets API 36), and
// RobolectricTestApplication's KDoc for why application is overridden too -- without it,
// the real BudgetApp's onCreate() schedules a real WorkManager PruneWorker against a real
// Room database for every test in this module, which is what produced the stray
// "Illegal connection pointer" exception on a WorkManager thread this class used to show.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class ExtractWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(repo: ReceiptQueue, notifier: ReceiptNotifying) =
        TestListenableWorkerBuilder<ExtractWorker>(context)
            .setWorkerFactory(TestWorkerFactory(repo, notifier))
            .build()

    @Test
    fun `drains the queue until idle then succeeds`() = runBlocking {
        val repo = FakeReceiptRepository(
            outcomes = listOf(ExtractOutcome.Extracted(1), ExtractOutcome.Extracted(2), ExtractOutcome.Idle)
        )
        val notifier = RecordingNotifier()

        val result = worker(repo, notifier).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.notified == 2)
    }

    @Test
    fun `retries when the outcome is a transport failure`() = runBlocking {
        val repo = FakeReceiptRepository(outcomes = listOf(ExtractOutcome.Retry))
        val result = worker(repo, RecordingNotifier()).doWork()
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `a definitive failure still counts as work done and notifies for review`() = runBlocking {
        val repo = FakeReceiptRepository(outcomes = listOf(
            ExtractOutcome.Failed(1, BudgetApiError.OcrNotConfigured), ExtractOutcome.Idle
        ))
        val notifier = RecordingNotifier()

        val result = worker(repo, notifier).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.notified == 1)
    }

    @Test
    fun `stops after the safety bound so a repeating outcome cannot spin forever`() = runBlocking {
        val repo = FakeReceiptRepository(outcomes = List(500) { ExtractOutcome.Extracted(it.toLong()) })
        val result = worker(repo, RecordingNotifier()).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(repo.calls <= ExtractWorker.MAX_ITEMS_PER_RUN)
    }
}

/**
 * Hand-rolled fake -- this project uses no mocking library. Scripts a fixed list of
 * [ExtractOutcome]s for [extractNext], one per call, and counts how many calls were made
 * so the safety-bound test can assert the worker actually stopped early. Only [extractNext]
 * is driven by [ExtractWorkerTest]; every other member is unused here.
 */
class FakeReceiptRepository(private val outcomes: List<ExtractOutcome>) : ReceiptQueue {
    var calls = 0
        private set

    override suspend fun extractNext(): ExtractOutcome {
        val outcome = outcomes[calls]
        calls++
        return outcome
    }

    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long = TODO()
    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = TODO()
    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> = TODO()
    override suspend fun retryFailedPosts(): Boolean = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange(): Unit = TODO()
    override suspend fun discard(id: Long): Unit = TODO()
    override suspend fun clearAll(): Unit = TODO()
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}

/** Records how many receipts were reported ready for review, without touching Android's notification manager. */
class RecordingNotifier : ReceiptNotifying {
    var notified = 0
        private set

    override fun notifyReadyForReview(count: Int) {
        notified += count
    }

    override fun notifyStaleQueue(count: Int) {}

    override fun clear() {}

    override fun clearStaleQueue() {}
}

/**
 * Builds an [ExtractWorker] directly from a fake repo and recording notifier, bypassing
 * Hilt entirely -- [TestListenableWorkerBuilder] only needs a [WorkerFactory], not the
 * production `HiltWorkerFactory`.
 */
class TestWorkerFactory(
    private val repo: ReceiptQueue,
    private val notifier: ReceiptNotifying,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = ExtractWorker(appContext, workerParameters, repo, notifier)
}
