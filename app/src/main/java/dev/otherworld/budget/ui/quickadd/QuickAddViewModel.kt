package dev.otherworld.budget.ui.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.PostAlreadyInFlightException
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.Category
import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.domain.model.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class QuickAddUiState(
    val loading: Boolean = true,
    val amountText: String = "",
    val merchant: String = "",
    val dateText: String = "",
    val currency: String = "GBP",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val amountError: String? = null,
    val saveError: String? = null,
    /**
     * Why there is nothing in the Account picker, when there is nothing in it. Without this the
     * screen renders an empty dropdown and a permanently disabled Save with no explanation --
     * the same "a control that silently does nothing" failure the rest of this app has been
     * careful to avoid. A failed fetch and a genuinely empty account list are different answers
     * and are said differently, exactly as [dev.otherworld.budget.ui.capture.CaptureViewModel]
     * already distinguishes them: telling an offline user to go and add an account sends them
     * looking for a problem they do not have.
     */
    val accountsMessage: String? = null,
    /**
     * The entry now exists as a queue row that this screen no longer owns.
     *
     * Set when a post fails, which -- for every failure reachable from here -- leaves a durable
     * row behind: FAILED, which `PostWorker` re-posts unattended, or AWAITING_REVIEW, which the
     * Capture banner surfaces and Review re-opens. Either way the transaction is already the
     * queue's responsibility, and pressing Save again is the one action that could turn it into
     * two. See [QuickAddViewModel.onSaveClicked].
     */
    val handedToQueue: Boolean = false,
) {
    /**
     * Amount and account only. Merchant, date and category are genuinely optional: this screen
     * exists for the transaction you did not get a receipt for, and refusing to record £4.20 of
     * parking because the user cannot be bothered to name the car park is exactly the friction
     * that sends people back to not recording it at all. Merchant falls back to "Unknown" and
     * date to today at save time, matching Review.
     *
     * [handedToQueue] and [saved] are the exceptions to "this screen only validates": once a
     * row exists in the queue, saving again from here is not a retry, it is a second
     * transaction. `!saved` matters even though a successful save closes the screen -- the
     * close is a LaunchedEffect-then-popBackStack, which lands a frame or more after the state
     * flips, and in that window the success branch has already set `saving = false`. Without
     * `!saved` the button re-arms in exactly the gap a rhythm of taps on a slow connection
     * lands in, and the next tap enqueues and posts a *second, new* row -- fresh id, so every
     * id-keyed guard downstream sees a clean slate.
     */
    val canSave: Boolean
        get() = !saving && !handedToQueue && !saved && selectedAccountId != null &&
            Money.parse(amountText, currency) != null
}

/**
 * Manual entry for a transaction with no receipt photo.
 *
 * The one thing to keep in mind when changing this class: **it does not talk to the API.** It
 * enqueues a photo-less row via [ReceiptQueue.enqueueWithoutPhoto] and posts it through
 * [ReceiptQueue.post], the identical path a captured receipt takes. That path carries five
 * protections against posting the same transaction twice -- [ReceiptQueue.reconcileInterrupted]
 * for rows stranded by process death, the in-flight guard inside `post` itself, the
 * never-sent/unknown-outcome split that decides whether a failure may be auto-retried, the
 * single-sweep lock in [ReceiptQueue.retryFailedPosts], and this class's own Save re-entrancy
 * guard -- and a direct `BudgetApi.createTransaction` call from here would inherit none of them.
 * Duplicate financial transactions are this project's worst failure mode. It also means a Quick
 * Add entry survives a post that cannot be made: it leaves a genuine queue row behind, which
 * `PostWorker` re-sends or the Capture banner surfaces for review.
 *
 * Note what "works offline" does *and does not* mean here. Once the entry is saved, delivery is
 * the queue's problem and needs no connectivity. Reaching the point of saving does: the account
 * and category pickers are populated from the server and [CatalogRepository] caches only for the
 * process lifetime, so a cold start with no connectivity has no account to post to. That is spec
 * section 7's recorded "deliberate v1 simplification", inherited from Review rather than newly
 * introduced -- but [QuickAddUiState.accountsMessage] now says so on screen instead of leaving a
 * dead Save button unexplained.
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val queue: ReceiptQueue,
    private val catalog: CatalogRepository,
    private val lastAccount: LastAccountStore,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    // dateText is seeded here, not in load(): load() makes up to three network calls, and the form
    // is live from the first frame, so writing the date when those return would silently overwrite
    // a date the user had already picked in the meantime. Everything load() sets is either a copy
    // that preserves user edits or, like the account list, genuinely unavailable until it returns.
    private val _uiState = MutableStateFlow(QuickAddUiState(dateText = clock().toString()))
    val uiState: StateFlow<QuickAddUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        // getOrNull() is kept, not `.orEmpty()`d away: a failed fetch and a server with no accounts
        // are different answers and are reported differently below -- the same distinction
        // CaptureViewModel.refresh() makes, and for the same reason.
        val accounts = catalog.accounts().getOrNull()
        val categories = catalog.categories().getOrNull().orEmpty()
        _uiState.update {
            it.copy(
                loading = false,
                currency = catalog.capabilities().getOrNull()?.currency ?: it.currency,
                accounts = accounts.orEmpty(),
                categories = categories,
                // Same rule as Review, and the same reason: the account someone posted to last is
                // overwhelmingly the one they mean now. The takeIf guards a remembered id that no
                // longer exists on the server (deleted account, different server).
                selectedAccountId = lastAccount.get()?.takeIf { id -> accounts.orEmpty().any { it.id == id } }
                    ?: accounts?.firstOrNull()?.id,
                // Without this the screen is a silent dead end when there are no accounts to pick:
                // an empty dropdown and a Save button that can never enable, with nothing on screen
                // saying why. Note this is the one spec-recorded limitation Quick Add inherits from
                // Review -- the pickers come from the server, so a cold start with no connectivity
                // cannot save (spec section 7's "deliberate v1 simplification").
                accountsMessage = when {
                    accounts == null ->
                        "Can't reach your Budget server, so there are no accounts to choose from yet."
                    accounts.isEmpty() ->
                        "Add an account in Budget on Nextcloud to start saving transactions."
                    else -> null
                },
            )
        }
    }

    fun onMerchantChanged(value: String) = _uiState.update { it.copy(merchant = value) }
    fun onDateChanged(value: String) = _uiState.update { it.copy(dateText = value) }
    fun onAccountSelected(id: Long) = _uiState.update { it.copy(selectedAccountId = id) }
    fun onCategorySelected(id: Long?) = _uiState.update { it.copy(selectedCategoryId = id) }

    fun onAmountChanged(value: String) = _uiState.update { state ->
        val invalid = value.isNotBlank() && Money.parse(value, state.currency) == null
        state.copy(amountText = value, amountError = if (invalid) "Enter an amount like 24.31" else null)
    }

    fun onSaveClicked() {
        val state = _uiState.value
        // Re-entrancy guard, identical in shape to ReviewViewModel.onSaveClicked and for the same
        // reason: a double-tap can dispatch two onClick events before recomposition disables the
        // button, so `enabled = canSave` alone cannot prevent a second post. Nothing between this
        // check and the `saving = true` write below suspends -- onSaveClicked is not `suspend`,
        // and the coroutine is only launched afterwards -- so a second, immediately-following call
        // is guaranteed to observe the flag.
        if (state.saving) return
        // The other half of the guard, and the one that matters across ViewModel instances rather
        // than across two taps. Unlike Review -- which posts a row that already exists -- this
        // screen *creates* one, so a second Save after a failed first attempt would enqueue a
        // second row and post that: two transactions from one entry the user believes they saved
        // once. Neither the flag above (the first post has finished) nor post()'s in-flight set
        // (the ids differ) can catch that. Once a row exists, this screen is done with it.
        if (state.handedToQueue) return
        // Same rule after a *successful* save: the screen is closing (saved drives the
        // LaunchedEffect that pops), but until the pop lands a tap here would mint a second row
        // and post it -- see canSave's KDoc. Mirrors the button's `!saved` so the guard holds
        // even if a stale recomposition delivers the click.
        if (state.saved) return
        val total = Money.parse(state.amountText, state.currency) ?: return
        val accountId = state.selectedAccountId ?: return
        val date = runCatching { LocalDate.parse(state.dateText) }.getOrElse { clock() }

        _uiState.update { it.copy(saving = true, saveError = null) }

        viewModelScope.launch {
            val id = queue.enqueueWithoutPhoto(
                DraftTransaction(
                    merchant = state.merchant.ifBlank { null },
                    date = date,
                    total = total,
                    suggestedCategoryId = state.selectedCategoryId,
                )
            )

            queue.post(
                id,
                CreateTransactionRequest(
                    accountId = accountId,
                    categoryId = state.selectedCategoryId,
                    date = date,
                    // Same fallback as Review: `merchant` is required on the wire, optional here.
                    merchant = state.merchant.ifBlank { "Unknown" },
                    total = total,
                    photo = null,
                ),
            ).onSuccess {
                lastAccount.set(accountId)
                _uiState.update { it.copy(saving = false, saved = true) }
            }.onFailure { error ->
                // handedToQueue, not just an error message: the row is durable in every failure
                // reachable here (FAILED, which PostWorker re-posts unattended, or AWAITING_REVIEW,
                // which the Capture banner surfaces and Review re-opens pre-filled), so the entry
                // is not lost -- it has simply stopped being this screen's to send. Leaving Save
                // pressable would make "try again" mean "enqueue a second one".
                _uiState.update {
                    it.copy(saving = false, handedToQueue = true, saveError = messageFor(error))
                }
            }
        }
    }

    /**
     * The same underlying rule as Review's: a failure the queue will retry on its own is safe to
     * describe as queued, and it now covers the read timeouts and 5xx that once had to warn the
     * user -- the idempotency key means a replay can only ever join the transaction, never
     * duplicate it. The rule lives in [dev.otherworld.budget.data.remote.BudgetApiError.isRetryable],
     * deliberately in one place, so this cannot drift from what the queue itself will do with the
     * row.
     *
     * Unlike Review, this text is not what stands between the user and a second transaction --
     * [QuickAddUiState.handedToQueue] is. It only has to tell them where their money went.
     */
    private fun messageFor(error: Throwable): String = when {
        error is PostAlreadyInFlightException -> "Still saving this transaction…"
        (error as? BudgetApiError)?.isRetryable() == true ->
            "Couldn't reach your server. This transaction is queued and will be sent automatically."
        error is BudgetApiError.IdempotencyKeyConflict -> ReceiptRepository.KEY_CONFLICT_MESSAGE
        else -> ReceiptRepository.INTERRUPTED_POST_MESSAGE
    }
}
