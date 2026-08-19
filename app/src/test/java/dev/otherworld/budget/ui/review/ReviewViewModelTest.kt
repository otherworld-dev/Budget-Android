package dev.otherworld.budget.ui.review

import androidx.lifecycle.SavedStateHandle
import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.domain.model.*
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
class ReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 3, 20)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun draft(
        merchant: String? = "Tesco",
        date: LocalDate? = LocalDate.of(2026, 3, 12),
        total: Money? = Money(BigDecimal("24.31"), "GBP"),
        suggestedCategoryId: Long? = 14,
    ) = DraftTransaction(merchant, date, total, suggestedCategoryId, listOf(
        LineItem("Milk 2L", Money(BigDecimal("1.20"), "GBP")),
    ))

    /**
     * A reconciling, splits-ready draft: items 3.40 + 18.95 plus tax 1.42 sum exactly to the
     * total 23.77, so [dev.otherworld.budget.domain.model.SplitPlan] yields three rows. The
     * suggestedCategoryId (14) is in FakeBudgetApi's category list, so rows default to it.
     */
    private fun splitDraftQueue() = FakeReviewQueue(
        PendingReceipt(
            1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            DraftTransaction(
                merchant = "Tesco",
                date = LocalDate.of(2026, 3, 12),
                total = Money(BigDecimal("23.77"), "GBP"),
                suggestedCategoryId = 14,
                lineItems = listOf(
                    LineItem("Coffee beans", Money(BigDecimal("3.40"), "GBP")),
                    LineItem("Cheese", Money(BigDecimal("18.95"), "GBP")),
                ),
                subtotal = Money(BigDecimal("22.35"), "GBP"),
                tax = Money(BigDecimal("1.42"), "GBP"),
            ),
            0, null,
        )
    )

    /**
     * The same items and tax, but a discounted total (20.00) they no longer add up to, so the
     * receipt cannot be split by item.
     */
    private fun discountDraftQueue() = FakeReviewQueue(
        PendingReceipt(
            1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            DraftTransaction(
                merchant = "Tesco",
                date = LocalDate.of(2026, 3, 12),
                total = Money(BigDecimal("20.00"), "GBP"),
                suggestedCategoryId = 14,
                lineItems = listOf(
                    LineItem("Coffee beans", Money(BigDecimal("3.40"), "GBP")),
                    LineItem("Cheese", Money(BigDecimal("18.95"), "GBP")),
                ),
                subtotal = Money(BigDecimal("22.35"), "GBP"),
                tax = Money(BigDecimal("1.42"), "GBP"),
            ),
            0, null,
        )
    )

    /**
     * A loyalty-savings receipt: items 20.00 + 21.63 = 41.63 less a 4.50 discount is the 37.13 paid,
     * so [dev.otherworld.budget.domain.model.SplitPlan] reconciles by shape 3 and adds a negative
     * "Savings" row.
     */
    private fun savingsDraftQueue() = FakeReviewQueue(
        PendingReceipt(
            1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            DraftTransaction(
                merchant = "Tesco",
                date = LocalDate.of(2026, 3, 12),
                total = Money(BigDecimal("37.13"), "GBP"),
                suggestedCategoryId = 14,
                lineItems = listOf(
                    LineItem("Groceries", Money(BigDecimal("20.00"), "GBP")),
                    LineItem("Household", Money(BigDecimal("21.63"), "GBP")),
                ),
                discount = Money(BigDecimal("4.50"), "GBP"),
            ),
            0, null,
        )
    )

    /**
     * receiptId is passed via SavedStateHandle, not a constructor arg -- see the ViewModel's
     * own KDoc -- so it survives process death along with the user's unsaved edits. clock is
     * explicit here (it defaults to LocalDate.now() in production) so "today" is deterministic.
     * splitsAvailable seeds the fake capabilities' knob, so a test can put the review screen on a
     * server that does or does not offer per-item splits.
     */
    private fun viewModel(
        pending: PendingReceipt = PendingReceipt(
            1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null
        ),
        splitsAvailable: Boolean = true,
        api: FakeBudgetApi = FakeBudgetApi(splitsAvailable = splitsAvailable),
        queue: FakeReviewQueue = FakeReviewQueue(pending),
        lastAccountId: Long? = null,
    ) = ReviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("receiptId" to pending.id)),
        queue = queue,
        catalog = CatalogRepository(api),
        lastAccount = FakeLastAccount(lastAccountId),
        clock = { today },
    ) to queue

    @Test
    fun `prefills every field from the draft`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        val state = vm.uiState.value

        assertEquals("Tesco", state.merchant)
        assertEquals("2026-03-12", state.dateText)
        assertEquals("24.31", state.totalText)
        assertEquals(14L, state.selectedCategoryId)
        assertEquals(1, state.lineItems.size)
    }

    @Test
    fun `defaults the date to today when extraction found none`() = runTest(dispatcher) {
        val pending = PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            draft(date = null), 0, null)
        val (vm, _) = viewModel(pending); advanceUntilIdle()

        assertEquals("2026-03-20", vm.uiState.value.dateText)
    }

    @Test
    fun `a suggested category the server does not have is not silently posted`() = runTest(dispatcher) {
        // The dropdown renders blank for an unknown id while the raw id would still ride into
        // the POST -- wrong-looking on screen, wrong on the wire. Reachable whenever the draft
        // predates the fetched list: a category deleted server-side, or an AWAITING_REVIEW row
        // whose draft was written against a different server (a server switch scrubs FAILED
        // rows' ids, but drafts on already-reviewable rows survive).
        val pending = PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            draft(suggestedCategoryId = 999), 0, null)   // FakeBudgetApi has 14/15/16
        val (vm, queue) = viewModel(pending); advanceUntilIdle()

        assertNull(vm.uiState.value.selectedCategoryId)

        vm.onSaveClicked(); advanceUntilIdle()
        assertNull(queue.posted.single().categoryId)
    }

    @Test
    fun `defaults the account to the last used one`() = runTest(dispatcher) {
        val (vm, _) = viewModel(lastAccountId = 2); advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `falls back to the first account when nothing was used before`() = runTest(dispatcher) {
        val (vm, _) = viewModel(lastAccountId = null); advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `blocks saving only on a missing or unparseable total`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        assertTrue(vm.uiState.value.canSave)

        vm.onTotalChanged("")
        assertFalse(vm.uiState.value.canSave)

        vm.onTotalChanged("abc")
        assertFalse(vm.uiState.value.canSave)
        assertNotNull(vm.uiState.value.totalError)

        vm.onTotalChanged("9.99")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `an empty merchant does not block saving`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        vm.onMerchantChanged("")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `an empty draft is fully editable so manual entry works`() = runTest(dispatcher) {
        val pending = PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW,
            DraftTransaction(), 0, null)
        val (vm, _) = viewModel(pending); advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        vm.onTotalChanged("12.00")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `saving posts exactly what the user sees`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.onMerchantChanged("Tesco Express")
        vm.onTotalChanged("25.00")
        vm.onAccountSelected(2)
        vm.onCategorySelected(15)
        vm.onSaveClicked(); advanceUntilIdle()

        val posted = queue.posted.single()
        assertEquals("Tesco Express", posted.merchant)
        assertEquals(BigDecimal("25.00"), posted.total.amount)
        assertEquals(2L, posted.accountId)
        assertEquals(15L, posted.categoryId)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `a double tap posts only once`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()

        // Two calls back-to-back, before advanceUntilIdle lets the first post's coroutine (or
        // any recomposition) run -- reproduces a double-tap racing the Save button's enabled
        // state, which depends on a recomposition that isn't guaranteed to land between taps.
        vm.onSaveClicked()
        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals(1, queue.posted.size)
    }

    @Test
    fun `a save whose splits were rejected still finishes but notifies`() = runTest(dispatcher) {
        val queue = FakeReviewQueue(
            PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null),
            postResult = Result.success(dev.otherworld.budget.data.remote.CreatedTransaction(9002L, "couldn't split")),
        )
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.onSaveClicked(); advanceUntilIdle()

        assertTrue(vm.uiState.value.saved)
        assertEquals("Saved, but couldn't split it by item.", vm.uiState.value.postNotice)
    }

    // --- Split state and reconciliation gating ----------------------------------------------

    @Test
    fun `a reconciling receipt on a splits-capable server is splittable, defaulting rows to the suggestion`() =
        runTest(dispatcher) {
            // splitDraftQueue(): total 23.77, tax 1.42, items 3.40 + 18.95, suggestedCategoryId 14
            // (in the fetched list).
            val (vm, _) = viewModel(queue = splitDraftQueue()); advanceUntilIdle()
            assertTrue(vm.uiState.value.splittable)
            assertEquals(3, vm.uiState.value.splitRows.size)                 // 2 items + tax
            assertNull(vm.uiState.value.splitRows.last().categoryId)          // tax uncategorised
            assertEquals(14L, vm.uiState.value.splitRows.first().categoryId)  // defaulted to the suggestion
            assertFalse(vm.uiState.value.splitEnabled)                        // off by default
        }

    @Test
    fun `a non-reconciling receipt is not splittable and explains why`() = runTest(dispatcher) {
        val (vm, _) = viewModel(queue = discountDraftQueue()); advanceUntilIdle()
        assertFalse(vm.uiState.value.splittable)
        assertEquals(
            "Can't split this by item — the items don't add up to the total.",
            vm.uiState.value.splitBlockedNote,
        )
    }

    @Test
    fun `splits are not offered when the server does not support them`() = runTest(dispatcher) {
        val (vm, _) = viewModel(queue = splitDraftQueue(), splitsAvailable = false); advanceUntilIdle()
        assertFalse(vm.uiState.value.splittable)
        assertNull(vm.uiState.value.splitBlockedNote)   // silent when the feature is simply off
    }

    @Test
    fun `editing the total below reconciliation drops split mode`() = runTest(dispatcher) {
        val (vm, _) = viewModel(queue = splitDraftQueue()); advanceUntilIdle()
        vm.onSplitToggled(true); assertTrue(vm.uiState.value.splitEnabled)
        vm.onTotalChanged("30.00")
        assertFalse(vm.uiState.value.splitEnabled)
        assertFalse(vm.uiState.value.splittable)
    }

    @Test
    fun `a user-chosen split-row category survives a recompute that keeps the receipt splittable`() =
        runTest(dispatcher) {
            // splitDraftQueue(): total 23.77, items "Coffee beans" (3.40) + "Cheese" (18.95),
            // tax 1.42, suggestedCategoryId 14. Row 1 ("Cheese") defaults to 14 on load -- picking
            // 15 for it here is a real edit, not the suggestion, so a recompute that fell back to
            // the default instead of the user's pick would still pass a same-value assertion.
            val (vm, _) = viewModel(queue = splitDraftQueue()); advanceUntilIdle()
            vm.onSplitToggled(true)
            vm.onSplitCategorySelected(1, 15L)
            assertEquals(15L, vm.uiState.value.splitRows[1].categoryId)

            // Re-entering the same reconciling total forces recomputeSplit to run again while the
            // receipt stays splittable -- exactly the path that must match the row back up by
            // description rather than resetting it to the suggestion.
            vm.onTotalChanged("23.77")

            assertTrue(vm.uiState.value.splittable)
            assertEquals("Cheese", vm.uiState.value.splitRows[1].description)
            assertEquals(15L, vm.uiState.value.splitRows[1].categoryId)
        }

    @Test
    fun `saving with split on sends one part per row and omits the top-level category`() =
        runTest(dispatcher) {
            val queue = splitDraftQueue()
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.onSplitToggled(true)
            vm.onSplitCategorySelected(1, 15L)          // recategorise the second item
            vm.onSaveClicked(); advanceUntilIdle()

            val req = queue.posted.single()
            // Regression guard for the server team's "split path saves as Unknown" report: the
            // merchant is carried on the split path exactly as on the non-split one.
            assertEquals("Tesco", req.merchant)
            assertNull(req.categoryId)                  // splits define categorisation
            assertEquals(3, req.splits!!.size)
            assertEquals(15L, req.splits!![1].categoryId)
            assertNull(req.splits!!.last().categoryId)  // tax
        }

    @Test
    fun `a loyalty-savings receipt is splittable and posts a negative uncategorised savings part`() =
        runTest(dispatcher) {
            val queue = savingsDraftQueue()
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            assertTrue(vm.uiState.value.splittable)
            assertEquals(3, vm.uiState.value.splitRows.size)          // 2 items + a savings row
            val savingsRow = vm.uiState.value.splitRows.last()
            assertEquals("Savings", savingsRow.description)
            assertFalse(savingsRow.categorisable)

            vm.onSplitToggled(true)
            vm.onSaveClicked(); advanceUntilIdle()

            val req = queue.posted.single()
            assertEquals("Tesco", req.merchant)
            assertEquals(3, req.splits!!.size)
            val savingsPart = req.splits!!.last()
            assertEquals(-1, savingsPart.amount.amount.signum())     // negative part
            assertNull(savingsPart.categoryId)                       // uncategorised -- don't guess
            // The set sums exactly to the transaction amount the server checks against.
            assertEquals(
                0,
                req.splits!!.fold(BigDecimal.ZERO) { a, p -> a.add(p.amount.amount) }.compareTo(BigDecimal("37.13")),
            )
        }

    @Test
    fun `saving with split off sends no splits and keeps the single category`() =
        runTest(dispatcher) {
            val queue = splitDraftQueue()
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.onCategorySelected(14L)
            vm.onSaveClicked(); advanceUntilIdle()
            val req = queue.posted.single()
            assertNull(req.splits)
            assertEquals(14L, req.categoryId)
        }

    @Test
    fun `a failed save surfaces an error and stays on the screen`() = runTest(dispatcher) {
        val queue = FakeReviewQueue(
            PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null),
            postResult = Result.failure(RuntimeException("offline")),
        )
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.onSaveClicked(); advanceUntilIdle()

        assertFalse(vm.uiState.value.saved)
        assertNotNull(vm.uiState.value.saveError)
    }

    @Test
    fun `a transient save failure now reads as queued, not a check-Recent warning`() =
        runTest(dispatcher) {
            // A gateway timeout was once the worst case -- the server might have committed, and a
            // second press would double-charge -- so the message warned the user. The idempotency
            // key removes that: a replay under the same key can only join the transaction, so the
            // queue retries it on its own and the message reassures rather than warns.
            val queue = FakeReviewQueue(
                PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null),
                postResult = Result.failure(BudgetApiError.ServerError(504)),
            )
            val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
            vm.onSaveClicked(); advanceUntilIdle()

            assertNotEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, vm.uiState.value.saveError)
            assertTrue(vm.uiState.value.saveError!!.contains("queued"))
        }

    @Test
    fun `a save that never left the device also says it is queued`() = runTest(dispatcher) {
        val queue = FakeReviewQueue(
            PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null),
            postResult = Result.failure(BudgetApiError.Network(ConnectException("refused"))),
        )
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.onSaveClicked(); advanceUntilIdle()

        // Nothing reached the server; PostWorker will send it. Same advice as the transient case
        // above now -- with the key, both are simply "queued".
        assertNotEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, vm.uiState.value.saveError)
        assertTrue(vm.uiState.value.saveError!!.contains("queued"))
    }

    @Test
    fun `an idempotency key conflict warns the user to check Recent`() = runTest(dispatcher) {
        // The one post failure a same-key replay cannot fix: the server holds a different purchase
        // under this key. It is the case that still needs a human, so it gets the distinct warning
        // rather than the reassuring "queued" line.
        val queue = FakeReviewQueue(
            PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null),
            postResult = Result.failure(BudgetApiError.IdempotencyKeyConflict),
        )
        val (vm, _) = viewModel(queue = queue); advanceUntilIdle()
        vm.onSaveClicked(); advanceUntilIdle()

        assertEquals(ReceiptRepository.KEY_CONFLICT_MESSAGE, vm.uiState.value.saveError)
    }

    @Test
    fun `re-opening an interrupted post shows its warning before anything is pressed`() =
        runTest(dispatcher) {
            // The merge blocker. post() parks an ambiguous failure back in AWAITING_REVIEW with
            // this message stored, precisely so the user checks Recent before saving again -- but
            // nothing read it back, so re-opening the receipt showed an ordinary pre-filled form
            // with Save enabled and no hint the server might already hold the transaction. Two
            // taps, one duplicate charge.
            val pending = PendingReceipt(
                1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 1,
                ReceiptRepository.INTERRUPTED_POST_MESSAGE,
            )
            val (vm, queue) = viewModel(pending); advanceUntilIdle()

            assertEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, vm.uiState.value.saveError)
            // Still saveable -- the warning informs the decision, it does not make it. The user
            // may well have checked Recent and found nothing there.
            assertTrue(vm.uiState.value.canSave)
            assertTrue(queue.posted.isEmpty())
        }

    @Test
    fun `a receipt with nothing stored against it shows no warning`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        assertNull(vm.uiState.value.saveError)
    }

    // --- Not a silent dead end (the offline account picker) ---------------------------------

    @Test
    fun `an unreachable server is explained with a retry, not left as a dead Save button`() =
        runTest(dispatcher) {
            // The awaiting-review banner is DB-driven and shows regardless of connectivity, so
            // the app actively routes users here while offline. Without the message this was
            // an empty Account field and a Save that never enables, unexplained -- the exact
            // silent-dead-end failure Quick Add and Capture were already fixed for.
            val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
            val (vm, _) = viewModel(api = api); advanceUntilIdle()

            assertFalse(vm.uiState.value.canSave)
            assertTrue(vm.uiState.value.accountsMessage!!.contains("Can't reach"))
            assertTrue(vm.uiState.value.accountsRetryable)
        }

    @Test
    fun `a server with genuinely no accounts says so, without a pointless retry`() =
        runTest(dispatcher) {
            val api = FakeBudgetApi().apply { accountsResult = emptyList() }
            val (vm, _) = viewModel(api = api); advanceUntilIdle()

            assertTrue(vm.uiState.value.accountsMessage!!.contains("Add an account"))
            assertFalse(vm.uiState.value.accountsRetryable)
        }

    @Test
    fun `no picker message once accounts have loaded`() = runTest(dispatcher) {
        val (vm, _) = viewModel(); advanceUntilIdle()
        assertNull(vm.uiState.value.accountsMessage)
    }

    @Test
    fun `retrying after connectivity returns fills the pickers and keeps the user's edits`() =
        runTest(dispatcher) {
            val api = FakeBudgetApi().apply { nextError = BudgetApiError.Network(null) }
            val (vm, _) = viewModel(api = api); advanceUntilIdle()
            vm.onMerchantChanged("Typed while offline")
            vm.onTotalChanged("9.99")

            api.nextError = null
            vm.onRetryCatalogClicked(); advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.accountsMessage)
            assertEquals(2, state.accounts.size)
            assertNotNull(state.selectedAccountId)
            // Retry must not rebuild the form from the stored row -- edits survive.
            assertEquals("Typed while offline", state.merchant)
            assertEquals("9.99", state.totalText)
            assertTrue(state.canSave)
        }

    // --- Stored machine strings are translated at display time ------------------------------

    @Test
    fun `a stored OCR-not-configured error reads as the fixed neutral copy, not machine jargon`() =
        runTest(dispatcher) {
            // extractNext stores BudgetApiError messages ("OCR not configured") in lastError.
            // Shown raw, that is terse jargon in error styling AND it leaks the OCR term the
            // curated strings deliberately avoid. Review renders the same fixed sentence
            // Capture uses for this condition (spec section 5 -- the per-request error is
            // authoritative, so it must carry the mandated copy too).
            val pending = PendingReceipt(
                1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, DraftTransaction(), 0,
                "OCR not configured",
            )
            val (vm, _) = viewModel(pending); advanceUntilIdle()

            assertEquals(
                "Receipt scanning isn't set up on your Budget server — see Budget's settings in Nextcloud.",
                vm.uiState.value.saveError,
            )
        }

    @Test
    fun `a stored quota error reads as the same fixed neutral copy, with no commercial language`() =
        runTest(dispatcher) {
            // The plan pins the not-configured sentence verbatim for the quota case too -- it is
            // the one wording vetted against Play's anti-steering rule, so no new variant (and
            // certainly no price, plan, or nudge) is invented here.
            val pending = PendingReceipt(
                1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, DraftTransaction(), 0,
                "OCR quota exhausted",
            )
            val (vm, _) = viewModel(pending); advanceUntilIdle()

            val shown = vm.uiState.value.saveError!!
            assertEquals(
                "Receipt scanning isn't set up on your Budget server — see Budget's settings in Nextcloud.",
                shown,
            )
            assertFalse(shown.contains("quota"))
            assertFalse(shown.contains("OCR"))
        }

    @Test
    fun `a stored extraction failure invites manual entry instead of stating a code`() =
        runTest(dispatcher) {
            val pending = PendingReceipt(
                1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, DraftTransaction(), 0,
                "Extraction failed",
            )
            val (vm, _) = viewModel(pending); advanceUntilIdle()

            assertEquals(
                "Couldn't read this receipt — enter the details below.",
                vm.uiState.value.saveError,
            )
        }

    @Test
    fun `a receipt that no longer exists cannot be saved`() = runTest(dispatcher) {
        // The row was discarded, pruned, or posted from somewhere else, so byId() returns null
        // and there is no photo to attach. onSaveClicked() returns early in that case, so a
        // Save button left enabled here is a button that does nothing at all, for ever.
        val queue = FakeReviewQueue(
            PendingReceipt(1, "/tmp/r.jpg", 0, CaptureState.AWAITING_REVIEW, draft(), 0, null)
        )
        val vm = ReviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf("receiptId" to 999L)),   // no such row
            queue = queue,
            catalog = CatalogRepository(FakeBudgetApi()),
            lastAccount = FakeLastAccount(null),
            clock = { today },
        )
        advanceUntilIdle()

        assertNull(vm.uiState.value.photoPath)
        vm.onTotalChanged("12.00")
        assertFalse(vm.uiState.value.canSave)

        vm.onSaveClicked(); advanceUntilIdle()
        assertTrue(queue.posted.isEmpty())
    }

    @Test
    fun `a receipt that never had a photo is still savable`() = runTest(dispatcher) {
        // The other side of the test above, and the reason canSave's precondition had to become
        // "the row exists" rather than "it has a photo". A Quick Add manual entry whose post came
        // back ambiguous is parked in AWAITING_REVIEW exactly like a captured receipt, and lands
        // here with photoPath == null. Keeping the old check would have left the user staring at
        // a filled-in form with a permanently dead Save button.
        val pending = PendingReceipt(1, null, 0, CaptureState.AWAITING_REVIEW, draft(), 0, null)
        val (vm, queue) = viewModel(pending); advanceUntilIdle()

        assertNull(vm.uiState.value.photoPath)
        assertTrue(vm.uiState.value.canSave)

        vm.onSaveClicked(); advanceUntilIdle()

        assertNull(queue.posted.single().photo)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `a captured receipt whose photo file has vanished still saves, without it`() =
        runTest(dispatcher) {
            // Distinct from both cases above: the row is real and did have a photo, but the file
            // is gone (external cleanup, a restore onto a device without the app's files).
            // Attaching a File that does not exist fails the upload at body-write time and costs
            // the user an amount they had already reviewed, for an image that is gone regardless.
            val pending = PendingReceipt(
                1, "/tmp/definitely-not-here-${System.nanoTime()}.jpg", 0,
                CaptureState.AWAITING_REVIEW, draft(), 0, null,
            )
            val (vm, queue) = viewModel(pending); advanceUntilIdle()

            assertTrue(vm.uiState.value.canSave)
            vm.onSaveClicked(); advanceUntilIdle()

            assertNull(queue.posted.single().photo)
            assertTrue(vm.uiState.value.saved)
        }

    @Test
    fun `discarding removes the receipt`() = runTest(dispatcher) {
        val (vm, queue) = viewModel(); advanceUntilIdle()
        vm.onDiscardClicked(); advanceUntilIdle()
        assertEquals(listOf(1L), queue.discarded)
    }
}

/**
 * Hand-rolled fake -- same pattern as FakeQueue in CaptureViewModelTest. Only [byId], [post]
 * and [discard] are exercised by ReviewViewModelTest; every other member is unused here.
 */
class FakeReviewQueue(
    private val pending: PendingReceipt,
    private val postResult: Result<CreatedTransaction> = Result.success(CreatedTransaction(9002L, null)),
) : ReceiptQueue {
    val posted = mutableListOf<CreateTransactionRequest>()
    val discarded = mutableListOf<Long>()

    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = pending.takeIf { it.id == id }
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long = TODO()
    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = TODO()
    override suspend fun extractNext(): ExtractOutcome = TODO()
    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> {
        posted += request
        return postResult
    }
    override suspend fun retryFailedPosts(): Boolean = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange(): Unit = TODO()
    override suspend fun discard(id: Long) { discarded += id }
    override suspend fun clearAll(): Unit = TODO()
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}

/** Hand-rolled fake -- in-memory, mirrors what SharedPreferences gives production. */
class FakeLastAccount(private var value: Long?) : LastAccountStore {
    var cleared = false
        private set

    override fun get(): Long? = value
    override fun set(id: Long) { value = id }
    override fun clear() {
        value = null
        cleared = true
    }
}
