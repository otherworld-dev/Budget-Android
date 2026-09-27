package dev.otherworld.budget.ui.review

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.otherworld.budget.core.AndroidStringResources
import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.local.SnapshotEntity
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.data.repo.SnapshotStore
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.domain.model.LineItem
import dev.otherworld.budget.domain.model.Money
import kotlinx.coroutines.flow.Flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Exercises [ReviewScreen] against a real [ReviewViewModel] built directly (no Hilt) --
 * same approach [ReviewViewModelTest] uses, just with a rendered UI on top. Only the
 * behaviours the Review screen must never get wrong: the total gates Save, and line items
 * are inert display, not a splitting UI.
 */
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun pending(lastError: String? = null) = PendingReceipt(
        id = 1,
        photoPath = "/tmp/r.jpg",
        capturedAt = 0,
        state = CaptureState.AWAITING_REVIEW,
        draft = DraftTransaction(
            merchant = "Tesco",
            date = LocalDate.of(2026, 3, 12),
            total = Money(BigDecimal("24.31"), "GBP"),
            suggestedCategoryId = 14,
            lineItems = listOf(LineItem("Milk 2L", Money(BigDecimal("1.20"), "GBP"))),
        ),
        attempts = 0,
        lastError = lastError,
    )

    private fun viewModel(lastError: String? = null) = ReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("receiptId" to 1L)),
        queue = FakeQueue(pending(lastError)),
        catalog = CatalogRepository(FakeBudgetApi(), fakeSnapshotStore()),
        lastAccount = FakeLastAccount(),
        // Real resources, not a fake: this test asserts on rendered text ("Total", "Save"), so
        // it needs the actual strings.xml values, not a stand-in.
        strings = AndroidStringResources(ApplicationProvider.getApplicationContext()),
        clock = { LocalDate.of(2026, 3, 20) },
    )

    @Test
    fun saveIsDisabledWhenTheTotalFieldIsCleared() {
        composeRule.setContent { ReviewScreen(viewModel = viewModel(), onDone = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Total").performTextClearance()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun enteringAValidTotalEnablesSave() {
        composeRule.setContent { ReviewScreen(viewModel = viewModel(), onDone = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Total").performTextClearance()
        composeRule.onNodeWithText("Total").performTextInput("24.31")
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun lineItemsRenderAsStaticTextWithNoClickAction() {
        composeRule.setContent { ReviewScreen(viewModel = viewModel(), onDone = {}) }
        composeRule.waitForIdle()

        // substring = true: the row renders "Milk 2L  £1.20" as a single Text (description
        // + formatted amount per the brief's exact format string), so an exact match would
        // never find it. This is the whole point of the display-only requirement: if a click
        // handler (e.g. a per-line category picker) were ever added to a line item row, this
        // assertion fails.
        composeRule.onNodeWithText("Milk 2L", substring = true).assertHasNoClickAction()
    }

    @Test
    fun aReceiptWhosePostWasInterruptedShowsItsWarningOnOpen() {
        // The message is stored on the row by ReceiptRepository.post when the server's state is
        // unknown, and is the only thing standing between the user and a duplicate charge when
        // they come back to the receipt. It has to be on screen without any button being pressed.
        composeRule.setContent {
            ReviewScreen(
                viewModel = viewModel(ReceiptRepository.INTERRUPTED_POST_MESSAGE),
                onDone = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ReceiptRepository.INTERRUPTED_POST_MESSAGE).assertIsDisplayed()
    }
}

/**
 * A [CatalogRepository] needs a [SnapshotStore] to satisfy its constructor, but this screen has
 * no interest in persistence -- so a hand-rolled in-memory [SnapshotDao], not Room, duplicated
 * here rather than shared across source sets (test and androidTest cannot see each other's
 * classes; see [FakeQueue] below), matching this project's per-test-file fake convention.
 */
private class FakeSnapshotDao : SnapshotDao {
    private val rows = mutableMapOf<String, SnapshotEntity>()
    override suspend fun get(kind: String): SnapshotEntity? = rows[kind]
    override suspend fun put(entity: SnapshotEntity) { rows[entity.kind] = entity }
    override suspend fun clear() { rows.clear() }
}

private fun fakeSnapshotStore() = SnapshotStore(
    FakeSnapshotDao(),
    InMemoryCredentialStore(Credentials("https://cloud.example", "adam", "pw")),
    now = { Instant.now() },
)

/**
 * Hand-rolled fake -- same pattern as ReviewViewModelTest's FakeReviewQueue, duplicated here
 * rather than shared across source sets (test and androidTest cannot see each other's
 * classes), matching this project's per-test-file fake convention.
 */
private class FakeQueue(private val pending: PendingReceipt) : ReceiptQueue {
    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = pending.takeIf { it.id == id }
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

private class FakeLastAccount : LastAccountStore {
    private var value: Long? = null
    override fun get(): Long? = value
    override fun set(id: Long) { value = id }
    override fun clear() { value = null }
}
