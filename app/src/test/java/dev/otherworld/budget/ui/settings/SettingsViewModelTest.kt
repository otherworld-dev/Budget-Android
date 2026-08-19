package dev.otherworld.budget.ui.settings

import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.auth.Session
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun store() = InMemoryCredentialStore().apply {
        save(Credentials("https://cloud.example.com", "adam", "pw"))
    }

    @Test
    fun `shows the connected server and user`() = runTest(dispatcher) {
        val vm = SettingsViewModel(store(), FakeSession(), FakeSettingsQueue()); advanceUntilIdle()
        assertEquals("https://cloud.example.com", vm.uiState.value.server)
        assertEquals("adam", vm.uiState.value.loginName)
    }

    @Test
    fun `sign out clears credentials and finishes`() = runTest(dispatcher) {
        val store = store()
        val session = FakeSession(result = Result.success(Unit), store = store)
        val vm = SettingsViewModel(store, session, FakeSettingsQueue())

        vm.onSignOutClicked(); advanceUntilIdle()

        assertTrue(vm.uiState.value.signedOut)
        assertNull(store.load())
    }

    @Test
    fun `sign out still clears local state when revocation fails`() = runTest(dispatcher) {
        val store = store()
        val session = FakeSession(result = Result.failure(RuntimeException("offline")), store = store)
        val vm = SettingsViewModel(store, session, FakeSettingsQueue())

        vm.onSignOutClicked(); advanceUntilIdle()

        assertTrue(vm.uiState.value.signedOut)
        assertNull(store.load())
    }

    @Test
    fun `reports queue depth with Capture's partition, so one row never has two names`() = runTest(dispatcher) {
        // queued must mean what Capture's queued banner means (everything not awaiting review,
        // FAILED included) and awaiting-review must match Capture's tappable banner. Settings
        // used to count AWAITING_REVIEW rows as "waiting", so the same row read as "ready to
        // review" on one screen and "waiting" on the other.
        val queue = FakeSettingsQueue(captured = 3, failed = 1, awaitingReview = 2)
        val vm = SettingsViewModel(store(), FakeSession(), queue); advanceUntilIdle()

        assertEquals(4, vm.uiState.value.queued)          // 3 captured + 1 failed
        assertEquals(2, vm.uiState.value.awaitingReview)  // waiting on the user, not the server
        assertEquals(1, vm.uiState.value.failed)          // subset of queued with a retry button
    }
}

/** Clears the supplied store regardless of [result], mirroring SessionManager's real behaviour. */
class FakeSession(
    private val result: Result<Unit> = Result.success(Unit),
    private val store: CredentialStore? = null,
) : Session {
    override suspend fun signIn(credentials: Credentials) {
        store?.save(credentials)
    }

    override suspend fun signOut(): Result<Unit> {
        store?.clear()
        return result
    }
}

/**
 * Hand-rolled fake -- same pattern as FakeReviewQueue in ReviewViewModelTest. Only
 * [observeQueue] is exercised by SettingsViewModelTest; every other member is unused here.
 */
class FakeSettingsQueue(captured: Int = 0, failed: Int = 0, awaitingReview: Int = 0) : ReceiptQueue {
    private val rows = MutableStateFlow(
        List(captured) { i -> PendingReceipt(i.toLong(), "", 0, CaptureState.CAPTURED, null, 0, null) } +
            List(failed) { i -> PendingReceipt(1_000L + i, "", 0, CaptureState.FAILED, null, 0, null) } +
            List(awaitingReview) { i -> PendingReceipt(2_000L + i, "", 0, CaptureState.AWAITING_REVIEW, null, 0, null) }
    )

    override fun observeQueue(): Flow<List<PendingReceipt>> = rows
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long = TODO()
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
