package dev.otherworld.budget.ui.quickadd

import dev.otherworld.budget.core.FakeStringResources
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.ui.review.FakeLastAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.net.ConnectException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 3, 20)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        queue: FakeQuickAddQueue = FakeQuickAddQueue(),
        api: FakeBudgetApi = FakeBudgetApi(),
        lastAccountId: Long? = null,
    ) = QuickAddViewModel(
        queue = queue,
        catalog = CatalogRepository(api),
        lastAccount = FakeLastAccount(lastAccountId),
        strings = FakeStringResources(),
        clock = { today },
    ) to queue

    /** Fills in only what the screen requires, leaving every optional field untouched. */
    private fun QuickAddViewModel.enterMinimum(amount: String = "12.34") {
        onAmountChanged(amount)
    }

    // --- What gates Save -------------------------------------------------------------------

    @Test
    fun `saving is blocked until an amount is entered`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()

        // The account has already defaulted itself, so the amount is the only thing missing.
        assertNotNull(vm.uiState.value.selectedAccountId)
        assertFalse(vm.uiState.value.canSave)

        vm.onAmountChanged("12.34")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `an unparseable amount blocks saving and says so`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()

        vm.onAmountChanged("abc")
        assertFalse(vm.uiState.value.canSave)
        assertNotNull(vm.uiState.value.amountError)

        vm.onAmountChanged("9.99")
        assertTrue(vm.uiState.value.canSave)
        assertNull(vm.uiState.value.amountError)
    }

    @Test
    fun `saving is blocked when the server has no account to post to`() = runTest(dispatcher) {
        // Not a validation message but a real precondition: `account_id` is required by the
        // contract, so with no accounts there is nothing this screen can post.
        val api = FakeBudgetApi().apply { accountsResult = emptyList() }
        val (vm, _) = viewModel(api = api); advanceUntilIdle()

        vm.onAmountChanged("12.34")

        assertNull(vm.uiState.value.selectedAccountId)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun `merchant, date and category are genuinely optional`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.enterMinimum()

        // Nothing but the amount has been touched, and Save is live.
        assertEquals("", vm.uiState.value.merchant)
        assertNull(vm.uiState.value.selectedCategoryId)
        assertTrue(vm.uiState.value.canSave)

        vm.onSaveClicked(); advanceUntilIdle()

        val posted = queue.posted.single().second
        assertEquals(BigDecimal("12.34"), posted.total.amount)
        // Merchant is required on the wire but not on the screen, so it falls back exactly as
        // Review's does rather than blocking the save.
        assertEquals("Unknown", posted.merchant)
        assertNull(posted.categoryId)
        assertEquals(today, posted.date)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `an explicitly cleared category still posts as none`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.enterMinimum()
        vm.onCategorySelected(15)
        vm.onCategorySelected(null)
        vm.onSaveClicked(); advanceUntilIdle()

        assertNull(queue.posted.single().second.categoryId)
    }

    // --- Defaults --------------------------------------------------------------------------

    @Test
    fun `the date defaults to today`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        assertEquals("2026-03-20", vm.uiState.value.dateText)
    }

    @Test
    fun `the account defaults to the last used one`() = runTest(dispatcher) {
        val (vm, _) = viewModel(lastAccountId = 2); advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `the account falls back to the first when nothing was used before`() = runTest(dispatcher) {
        val (vm, _) = viewModel(lastAccountId = null); advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `a remembered account that no longer exists is ignored`() = runTest(dispatcher) {
        // A different server, or a deleted account: posting to an id this server has never heard
        // of would fail, and silently pre-selecting it hides that from the user.
        val (vm, _) = viewModel(lastAccountId = 999); advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `a successful save remembers the account for next time`() = runTest(dispatcher) {
        val lastAccount = FakeLastAccount(null)
        val queue = FakeQuickAddQueue()
        val vm = QuickAddViewModel(queue, CatalogRepository(FakeBudgetApi()), lastAccount, FakeStringResources()) { today }
        advanceUntilIdle()

        vm.onAmountChanged("5.00")
        vm.onAccountSelected(2)
        vm.onSaveClicked(); advanceUntilIdle()

        assertEquals(2L, lastAccount.get())
    }

    // --- The architectural rule: everything goes through the queue -------------------------

    @Test
    fun `the row it enqueues has no photo and is the one that reaches post`() = runTest(dispatcher) {
        // The whole point of this screen's save path. A manual entry must travel the same route
        // as a captured receipt -- enqueue a photo-less row, then post *that row* -- so it
        // inherits the queue's duplicate-transaction protections instead of taking a second,
        // unguarded route to POST transactions.
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.onAmountChanged("24.31")
        vm.onMerchantChanged("Parking")
        vm.onSaveClicked(); advanceUntilIdle()

        val enqueuedId = queue.enqueuedWithoutPhoto.single().first
        assertNull(queue.rows.getValue(enqueuedId).photoPath)

        val (postedId, request) = queue.posted.single()
        assertEquals(enqueuedId, postedId)
        assertNull(request.photo)
        // Nothing was enqueued down the photo path either.
        assertTrue(queue.enqueuedPhotos.isEmpty())
    }

    @Test
    fun `the enqueued row carries the user's entry so a crash before posting does not lose it`() =
        runTest(dispatcher) {
            val (vm, queue) = viewModel(); advanceUntilIdle()
            vm.onAmountChanged("24.31")
            vm.onMerchantChanged("Parking")
            vm.onCategorySelected(15)
            vm.onSaveClicked(); advanceUntilIdle()

            val draft = queue.enqueuedWithoutPhoto.single().second
            assertEquals("Parking", draft.merchant)
            assertEquals(BigDecimal("24.31"), draft.total!!.amount)
            assertEquals(today, draft.date)
            assertEquals(15L, draft.suggestedCategoryId)
        }

    @Test
    fun `a double tap posts only once`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.enterMinimum()

        // Two calls back-to-back, before advanceUntilIdle lets the first save's coroutine (or any
        // recomposition) run -- a double-tap racing the Save button's `enabled` state, which
        // depends on a recomposition that is not guaranteed to land between the two taps.
        vm.onSaveClicked()
        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals(1, queue.posted.size)
        // And only one row exists to be posted at all -- a second row would be a second
        // transaction that no in-flight guard downstream could catch, since the ids would differ.
        assertEquals(1, queue.enqueuedWithoutPhoto.size)
    }

    @Test
    fun `a failed save hands the entry to the queue and refuses to enqueue a second one`() =
        runTest(dispatcher) {
            // The duplicate this closes. Unlike Review -- which posts a row that already exists --
            // this screen *creates* one, so a second Save after a failure would enqueue a second
            // row and post that: two transactions from one entry. Neither the `saving` flag (the
            // first post has finished) nor post()'s in-flight set (the ids differ) catches it, and
            // remembering the id in the ViewModel would not survive the screen being left and
            // re-entered. The entry is not lost either way -- the row is durable in the queue.
            val queue = FakeQuickAddQueue(
                postResult = Result.failure(BudgetApiError.Network(ConnectException("refused"))),
            )
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.enterMinimum()

            vm.onSaveClicked(); advanceUntilIdle()
            assertFalse(vm.uiState.value.saved)
            assertNotNull(vm.uiState.value.saveError)
            assertTrue(vm.uiState.value.handedToQueue)
            assertFalse(vm.uiState.value.canSave)

            // Even if the button were somehow pressed again -- a stale recomposition, a test, a
            // future refactor that forgets `enabled` -- nothing more is enqueued or posted.
            vm.onSaveClicked(); advanceUntilIdle()

            assertEquals(1, queue.enqueuedWithoutPhoto.size)
            assertEquals(1, queue.posted.size)
        }

    @Test
    fun `a tap landing after a successful save does not mint a second transaction`() = runTest(dispatcher) {
        // The success-to-pop window: onSuccess sets saving = false and saved = true, and the
        // screen only closes when the LaunchedEffect keyed on `saved` runs popBackStack -- a
        // frame or more later. A user tapping in rhythm on a slow connection lands a tap in
        // that gap; without the `saved` guard it enqueued and posted a second, NEW row (fresh
        // id, so post()'s in-flight set and the sweep mutex all saw a clean slate) with no
        // warning anywhere and no queue row left behind to notice.
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.enterMinimum()
        vm.onSaveClicked(); advanceUntilIdle()
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.canSave)

        vm.onSaveClicked(); advanceUntilIdle()

        assertEquals(1, queue.enqueuedWithoutPhoto.size)
        assertEquals(1, queue.posted.size)
    }

    @Test
    fun `a transient failure hands the entry to the queue and stops the screen posting again`() = runTest(dispatcher) {
        // A 5xx now auto-retries (the key makes the replay safe), so the row is FAILED and the
        // queue owns it -- either way the entry has left this screen. Save must not stay live, or
        // "try again" would enqueue a second one; that decision belongs to the queue, not a button
        // here.
        val queue = FakeQuickAddQueue(postResult = Result.failure(BudgetApiError.ServerError(504)))
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.enterMinimum()
        vm.onSaveClicked(); advanceUntilIdle()

        assertTrue(vm.uiState.value.handedToQueue)
        assertFalse(vm.uiState.value.canSave)
    }

    // --- Failure reporting -----------------------------------------------------------------

    @Test
    fun `a save that never left the device says it is queued`() = runTest(dispatcher) {
        val queue = FakeQuickAddQueue(
            postResult = Result.failure(BudgetApiError.Network(ConnectException("refused"))),
        )
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.enterMinimum()
        vm.onSaveClicked(); advanceUntilIdle()

        assertFalse(vm.uiState.value.saved)
        assertTrue(vm.uiState.value.saveError!!.contains("queued"))
        assertNotEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, vm.uiState.value.saveError)
    }

    @Test
    fun `a transient save failure reads as queued, matching Review`() =
        runTest(dispatcher) {
            // Same reasoning as Review's: with the idempotency key a gateway timeout is safe to
            // replay, so it reads as queued rather than the old "check Recent" warning.
            val queue = FakeQuickAddQueue(postResult = Result.failure(BudgetApiError.ServerError(504)))
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.enterMinimum()
            vm.onSaveClicked(); advanceUntilIdle()

            assertNotEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, vm.uiState.value.saveError)
            assertTrue(vm.uiState.value.saveError!!.contains("queued"))
        }

    @Test
    fun `a key conflict is reported with the check-Recent warning, not the queued line`() =
        runTest(dispatcher) {
            val queue = FakeQuickAddQueue(postResult = Result.failure(BudgetApiError.IdempotencyKeyConflict))
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.enterMinimum()
            vm.onSaveClicked(); advanceUntilIdle()

            assertEquals(ReceiptRepository.KEY_CONFLICT_MESSAGE, vm.uiState.value.saveError)
        }

    @Test
    fun `a failed save stops spinning and says what happened`() = runTest(dispatcher) {
        val queue = FakeQuickAddQueue(postResult = Result.failure(RuntimeException("boom")))
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.enterMinimum()
        vm.onSaveClicked(); advanceUntilIdle()

        assertFalse(vm.uiState.value.saving)
        assertNotNull(vm.uiState.value.saveError)
    }

    // --- Not a silent dead end ---------------------------------------------------------------

    @Test
    fun `an unreachable server is explained, not reported as having no accounts`() =
        runTest(dispatcher) {
            // Same distinction CaptureViewModel makes: an unreachable server says nothing about
            // whether accounts exist, and telling an offline user to go and add one sends them
            // looking for a problem they do not have.
            val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
            val (vm, _) = viewModel(api = api); advanceUntilIdle()

            assertFalse(vm.uiState.value.canSave)
            assertTrue(vm.uiState.value.accountsMessage!!.contains("Can't reach"))
        }

    @Test
    fun `a server with genuinely no accounts says so`() = runTest(dispatcher) {
        val api = FakeBudgetApi().apply { accountsResult = emptyList() }
        val (vm, _) = viewModel(api = api); advanceUntilIdle()

        assertTrue(vm.uiState.value.accountsMessage!!.contains("Add an account"))
    }

    @Test
    fun `no message at all once accounts have loaded`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        assertNull(vm.uiState.value.accountsMessage)
    }

    @Test
    fun `the date is usable before the catalog fetch returns, and a pick is not overwritten`() =
        runTest(dispatcher) {
            // The form is live from the first frame while load() makes up to three network calls.
            // Writing today's date when those return would silently discard a date the user had
            // already picked in that window -- offline, a window as long as the connect timeout.
            val api = FakeBudgetApi().apply { latencyMs = 5_000 }
            val (vm, _) = viewModel(api = api)

            assertEquals("2026-03-20", vm.uiState.value.dateText)
            vm.onDateChanged("2026-03-14")
            advanceUntilIdle()

            assertEquals("2026-03-14", vm.uiState.value.dateText)
        }
}

/**
 * Hand-rolled fake -- same pattern as FakeReviewQueue/FakeQueue elsewhere; this project uses no
 * mocking library. Records both halves of the save path so a test can assert that the row which
 * was enqueued is the row that was posted.
 */
class FakeQuickAddQueue(
    var postResult: Result<CreatedTransaction> = Result.success(CreatedTransaction(9002L, null)),
) : ReceiptQueue {
    val enqueuedWithoutPhoto = mutableListOf<Pair<Long, DraftTransaction>>()
    val enqueuedPhotos = mutableListOf<File>()
    val posted = mutableListOf<Pair<Long, CreateTransactionRequest>>()
    val rows = mutableMapOf<Long, PendingReceipt>()
    private var nextId = 1L

    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long {
        val id = nextId++
        enqueuedWithoutPhoto += id to draft
        rows[id] = PendingReceipt(id, null, 0, CaptureState.AWAITING_REVIEW, draft, 0, null)
        return id
    }

    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> {
        posted += id to request
        return postResult
    }

    override suspend fun enqueue(photo: File): Long {
        enqueuedPhotos += photo
        return nextId++
    }

    override suspend fun byId(id: Long): PendingReceipt? = rows[id]
    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun extractNext(): ExtractOutcome = TODO()
    override suspend fun retryFailedPosts(): Boolean = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange(): Unit = TODO()
    override suspend fun discard(id: Long): Unit = TODO()
    override suspend fun clearAll(): Unit = TODO()
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}
