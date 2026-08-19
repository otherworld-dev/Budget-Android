package dev.otherworld.budget.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.otherworld.budget.RobolectricTestApplication
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

// See PendingReceiptDaoTest / ExtractWorkerTest for why sdk and application are pinned per-class.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class PostWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(queue: ReceiptQueue) =
        TestListenableWorkerBuilder<PostWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = PostWorker(appContext, workerParameters, queue)
            })
            .build()

    @Test
    fun `succeeds and does not reschedule when every failed post is re-sent`() = runBlocking {
        val queue = FakePostQueue(retryResult = true)

        val result = worker(queue).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(queue.retryCalls == 1)
    }

    @Test
    fun `retries when a failed post could not be re-sent`() = runBlocking {
        val queue = FakePostQueue(retryResult = false)

        val result = worker(queue).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(queue.retryCalls == 1)
    }
}

/**
 * Hand-rolled fake -- this project uses no mocking library. Only [retryFailedPosts] is exercised
 * by [PostWorkerTest]; it returns a scripted result and counts calls so the test can assert the
 * worker actually drove the sweep rather than short-circuiting.
 */
private class FakePostQueue(private val retryResult: Boolean) : ReceiptQueue {
    var retryCalls = 0
        private set

    override suspend fun retryFailedPosts(): Boolean {
        retryCalls++
        return retryResult
    }

    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long = TODO()
    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = TODO()
    override suspend fun extractNext(): ExtractOutcome = TODO()
    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange(): Unit = TODO()
    override suspend fun discard(id: Long): Unit = TODO()
    override suspend fun clearAll(): Unit = TODO()
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}
