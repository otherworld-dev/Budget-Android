package dev.otherworld.budget.ui.capture

import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.local.SnapshotCodec
import dev.otherworld.budget.data.local.SnapshotKind
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.remote.fake.FakeCheckData
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.TestSnapshots
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reports OCR unavailable when the server says so`() = runTest(dispatcher) {
        val api = FakeBudgetApi().apply { ocrAvailable = false }
        val vm = CaptureViewModel(CatalogRepository(api, TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.ocrAvailable)
        assertEquals(CaptureMessage.OcrUnavailable, vm.uiState.value.message)
    }

    @Test
    fun `reports the no-accounts state and does not offer setup`() = runTest(dispatcher) {
        val api = FakeBudgetApi().apply { accountsResult = emptyList<Account>() }
        val vm = CaptureViewModel(CatalogRepository(api, TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasAccounts)
        // Spec-fixed copy, and it must stay reserved for the case where the server actually
        // answered "no accounts" -- see the offline test below for why that matters.
        assertEquals(CaptureMessage.NoAccounts, vm.uiState.value.message)
    }

    @Test
    fun `an unreachable server is reported as offline, not as having no accounts`() = runTest(dispatcher) {
        // The regression: a failed fetch and an empty list were collapsed by `.orEmpty()`, so a
        // first launch made offline told the user to "add an account in Budget on Nextcloud" --
        // sending them to look for a problem they do not have.
        val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
        val vm = CaptureViewModel(CatalogRepository(api, TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasAccounts)
        assertEquals(CaptureMessage.Offline, vm.uiState.value.message)
    }

    @Test
    fun `offline with cached accounts still says so`() = runTest(dispatcher) {
        // The catalog now answers from its persisted snapshot when the network fails, which is
        // what keeps Review and Quick Add usable offline -- but Capture must still tell the user
        // the server can't be reached, rather than looking as healthy as when it can.
        val snapshots = TestSnapshots.fake()
        snapshots.write(SnapshotKind.ACCOUNTS, SnapshotCodec.encodeAccounts(FakeCheckData.accounts), snapshots.currentOwner())
        val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
        val vm = CaptureViewModel(CatalogRepository(api, snapshots), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasAccounts)
        assertEquals(CaptureMessage.Offline, vm.uiState.value.message)
    }

    @Test
    fun `the offline message clears once the server can be reached again`() = runTest(dispatcher) {
        // Capture is the start destination and is never popped, so before refresh() could be
        // re-run (from the retry affordance, or on resume) this message was permanent for the
        // life of the process.
        val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
        val vm = CaptureViewModel(CatalogRepository(api, TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()
        assertEquals(CaptureMessage.Offline, vm.uiState.value.message)

        api.nextError = null
        vm.refresh(); advanceUntilIdle()

        assertNull(vm.uiState.value.message)
        assertTrue(vm.uiState.value.hasAccounts)
    }

    @Test
    fun `a failed capture is reported instead of vanishing`() = runTest(dispatcher) {
        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        vm.onCaptureFailed()

        assertEquals(CaptureMessage.CaptureFailed, vm.uiState.value.message)
    }

    @Test
    fun `a capture that works clears the failure message`() = runTest(dispatcher) {
        val queue = FakeQueue()
        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), queue, FakeScheduler())
        advanceUntilIdle()
        vm.onCaptureFailed()

        vm.onPhotoCaptured(File("receipt.jpg")); advanceUntilIdle()

        assertNull(vm.uiState.value.message)
        assertEquals(1, queue.enqueued.size)
    }

    @Test
    fun `a receipt awaiting review is not also counted as queued`() = runTest(dispatcher) {
        // One receipt appearing in both the "queued" and the "ready to review" banner is the
        // same item described two contradictory ways -- waiting on the server and waiting on
        // the user at once.
        val queue = FakeQueue()
        queue.setQueue(
            listOf(
                pendingReceipt(id = 1L, state = CaptureState.CAPTURED),
                pendingReceipt(id = 2L, state = CaptureState.AWAITING_REVIEW),
            )
        )
        queue.setAwaitingReview(listOf(pendingReceipt(id = 2L, state = CaptureState.AWAITING_REVIEW)))

        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), queue, FakeScheduler())
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.pendingCount)
        assertEquals(1, vm.uiState.value.awaitingReviewCount)
    }

    @Test
    fun `capture is still allowed when OCR is unavailable so manual entry works`() = runTest(dispatcher) {
        val api = FakeBudgetApi().apply { ocrAvailable = false }
        val queue = FakeQueue()
        val vm = CaptureViewModel(CatalogRepository(api, TestSnapshots.fake()), queue, FakeScheduler())
        advanceUntilIdle()

        vm.onPhotoCaptured(java.io.File("receipt.jpg"))
        advanceUntilIdle()

        assertEquals(1, queue.enqueued.size)
    }

    @Test
    fun `capturing enqueues the photo and schedules extraction`() = runTest(dispatcher) {
        val queue = FakeQueue()
        val scheduler = FakeScheduler()
        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), queue, scheduler)
        advanceUntilIdle()

        vm.onPhotoCaptured(java.io.File("receipt.jpg"))
        advanceUntilIdle()

        assertEquals(1, queue.enqueued.size)
        assertTrue(scheduler.extractionScheduled)
    }

    @Test
    fun `surfaces the awaiting-review count and oldest id from observeAwaitingReview`() = runTest(dispatcher) {
        val queue = FakeQueue()
        // Deliberately not id-ascending: observeAwaitingReview() (like the DAO query backing it)
        // is already ordered oldest-first by capturedAt, so the ViewModel must trust list order,
        // not assume/re-derive it from id.
        queue.setAwaitingReview(listOf(pendingReceipt(id = 7L), pendingReceipt(id = 3L)))

        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), queue, FakeScheduler())
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.awaitingReviewCount)
        assertEquals(7L, vm.uiState.value.oldestAwaitingReviewId)
    }

    @Test
    fun `reports no awaiting review when the queue has nothing to review`() = runTest(dispatcher) {
        val vm = CaptureViewModel(CatalogRepository(FakeBudgetApi(), TestSnapshots.fake()), FakeQueue(), FakeScheduler())
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.awaitingReviewCount)
        assertEquals(null, vm.uiState.value.oldestAwaitingReviewId)
    }
}

private fun pendingReceipt(id: Long, state: CaptureState = CaptureState.AWAITING_REVIEW) = PendingReceipt(
    id = id,
    photoPath = "/tmp/r$id.jpg",
    capturedAt = id,
    state = state,
    draft = null,
    attempts = 0,
    lastError = null,
)

/**
 * Hand-rolled fake -- this project uses no mocking library (see FakeReceiptRepository in
 * ExtractWorkerTest for the same pattern). [observeQueue] (an empty flow), [enqueue] (recorded
 * into [enqueued]), and [observeAwaitingReview] (backed by [setAwaitingReview], a live
 * MutableStateFlow so CaptureViewModel's collector -- started unconditionally in init -- always
 * has something to collect) are exercised by CaptureViewModelTest; every other member is unused
 * here.
 */
class FakeQueue : ReceiptQueue {
    val enqueued = mutableListOf<File>()
    private val awaitingReview = MutableStateFlow<List<PendingReceipt>>(emptyList())
    private val queued = MutableStateFlow<List<PendingReceipt>>(emptyList())

    fun setAwaitingReview(items: List<PendingReceipt>) {
        awaitingReview.value = items
    }

    /** [observeQueue] is every row in every state, exactly as the DAO's query is. */
    fun setQueue(items: List<PendingReceipt>) {
        queued.value = items
    }

    override fun observeQueue(): Flow<List<PendingReceipt>> = queued
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = awaitingReview
    override suspend fun byId(id: Long): PendingReceipt? = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long {
        enqueued += photo
        return enqueued.size.toLong()
    }
    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = TODO()
    override suspend fun extractNext(): ExtractOutcome = TODO()
    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> = TODO()
    override suspend fun retryFailedPosts(): Boolean = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange(): Unit = TODO()
    override suspend fun discard(id: Long): Unit = TODO()
    override suspend fun clearAll(): Unit = TODO()
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}

/** Hand-rolled fake -- only [scheduleExtraction] is exercised by CaptureViewModelTest. */
class FakeScheduler : QueueScheduling {
    var extractionScheduled = false
        private set

    override fun scheduleExtraction() { extractionScheduled = true }
    override fun schedulePost(): Unit = TODO()
    override fun schedulePrune(): Unit = TODO()
    override fun cancelAll(): Unit = TODO()
}
