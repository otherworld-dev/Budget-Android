package dev.otherworld.budget.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.R
import dev.otherworld.budget.core.StringResources
import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.PostAlreadyInFlightException
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * One fixed row of a splittable receipt as the screen holds it: the immutable amount/description and
 * whether it is [categorisable] (from [dev.otherworld.budget.domain.model.SplitRow]), plus the
 * [categoryId] the user assigns (or the defaulted suggestion). A non-categorisable row -- the tax
 * line, or a negative savings line -- always has a null [categoryId] and keeps it through every
 * recompute and pick.
 */
data class SplitRowUi(val amount: Money, val description: String, val categorisable: Boolean, val categoryId: Long?)

/**
 * One receipt line item as the user edits it, before it is parsed. [amountText] is held as raw text
 * (like [ReviewUiState.totalText]) rather than a [Money] so a half-typed amount is not lost or
 * prematurely coerced; reconciliation parses it on every change via [Money.parse]. An extraction that
 * misread a cost, a garbled name, a missing line, or an invented one is all corrected here, after
 * which [ReviewViewModel.recomputeSplit] re-checks whether the items reconcile into a valid split.
 */
data class EditableItemUi(val description: String, val amountText: String)

data class ReviewUiState(
    val loading: Boolean = true,
    val photoPath: String? = null,
    /**
     * Whether [ReceiptQueue.byId] actually found this row. Distinct from `photoPath != null`,
     * which used to stand in for it and no longer can: a Quick Add manual entry is a real,
     * savable row that legitimately has no photo, so a null path stopped meaning "this receipt
     * is gone" the moment photo-less rows existed. This is the precondition [canSave] cares
     * about -- there must be something to post *to*.
     */
    val receiptExists: Boolean = false,
    val merchant: String = "",
    val dateText: String = "",
    val totalText: String = "",
    val currency: String = "GBP",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    /** The receipt's line items, editable so a misread cost/name (or a missing/invented line) can be fixed. */
    val editableItems: List<EditableItemUi> = emptyList(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val totalError: String? = null,
    val saveError: String? = null,
    /**
     * A one-shot notice for a save that *succeeded* but whose per-item splits, or category, the
     * server dropped (the transaction was still recorded). Distinct from [saveError], which is a
     * failure to record at all: this fires alongside [saved] = true and the screen surfaces it on
     * its way out. Null whenever the server kept everything.
     */
    val postNotice: String? = null,
    /**
     * Why there is nothing in the Account picker, when there is nothing in it -- the same
     * distinction [dev.otherworld.budget.ui.quickadd.QuickAddUiState.accountsMessage] draws,
     * for the same reason: a failed fetch and a server with no accounts are different answers.
     * Without it this screen was the one transaction-recording surface left as a silent dead
     * end -- an empty dropdown and a Save that never enables, with the row's own stored notice
     * ("check Recent before saving again") as the only text on screen, explaining the wrong
     * thing.
     */
    val accountsMessage: String? = null,
    /**
     * True when the accounts fetch *failed* (rather than genuinely returning none), so a retry
     * can help and the screen shows one. [ReviewViewModel.onRetryCatalogClicked] refetches the
     * pickers without touching the user's edits -- unlike load(), which builds the whole state
     * from the row.
     */
    val accountsRetryable: Boolean = false,
    /**
     * Whether the connected server offers per-item splits at all
     * ([dev.otherworld.budget.data.remote.Capabilities.splitsAvailable]).
     * When false the split affordance never appears and [splitBlockedNote] stays silent: an
     * unreconciling receipt is only worth explaining on a server that could otherwise have split it.
     */
    val splitsAvailable: Boolean = false,
    /**
     * Whether *this* receipt can be split right now: the server offers splits AND the current line
     * items, tax and total reconcile into a valid [dev.otherworld.budget.domain.model.SplitPlan].
     * Gates the toggle and is re-evaluated whenever the total is edited, so lowering the total below
     * the items' sum turns splitting off.
     */
    val splittable: Boolean = false,
    /** Whether the user has turned splitting on. Forced back off the moment [splittable] goes false. */
    val splitEnabled: Boolean = false,
    /** The fixed rows to categorise when splitting -- empty unless [splittable]. */
    val splitRows: List<SplitRowUi> = emptyList(),
    /**
     * Why a splits-capable server still can't split this receipt -- set only when the items don't
     * reconcile *and* there are items to reconcile. Null when splits are off, when the receipt is
     * splittable, or when there are no line items at all (nothing to explain).
     */
    val splitBlockedNote: String? = null,
) {
    /**
     * Only the total gates saving among the *editable* fields -- an unreadable merchant is still
     * worth recording. [receiptExists] is not an editable field but a precondition: [onSaveClicked]
     * posts *a queue row*, so if the row has vanished (discarded elsewhere, pruned, or an id that
     * no longer exists) there is nothing for it to do, and omitting the check rendered Save
     * enabled on a screen where pressing it did nothing, for ever.
     *
     * This was `photoPath != null` until Quick Add, which was the same check by proxy back when
     * every row had a photo. It is deliberately *replaced* rather than dropped: the two cases a
     * null path can now mean -- "this row never had a photo", which is perfectly savable, and
     * "there is no row" -- have opposite answers, and collapsing them either bricks Quick Add's
     * own review screen or brings back the dead Save button.
     */
    val canSave: Boolean
        get() = !saving && receiptExists && selectedAccountId != null &&
            Money.parse(totalText, currency) != null
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val queue: ReceiptQueue,
    private val catalog: CatalogRepository,
    private val lastAccount: LastAccountStore,
    private val strings: StringResources,
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    /**
     * Read from the nav argument via SavedStateHandle rather than assisted
     * injection, so the id survives process death along with the user's
     * unsaved edits.
     */
    private val receiptId: Long = checkNotNull(savedStateHandle["receiptId"])

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    /**
     * The draft facts the split calculation needs beyond the (now editable) items and total, held
     * here so [recomputeSplit] can reach them without re-reading the queue row. Seeded once in
     * [load]; tax, discount and the suggested category are fixed for the receipt. The line items
     * themselves live in [ReviewUiState.editableItems] because the user can now edit them.
     */
    private var draftTax: Money? = null
    private var draftDiscount: Money? = null
    private var draftSuggestedCategoryId: Long? = null

    init { load() }

    /**
     * [ReviewUiState.saveError] is restored from the row's stored `lastError`, not left null for
     * a save attempt to fill in.
     *
     * A post whose outcome is unknown (read timeout, 5xx, or a process death mid-post) parks the
     * row back in AWAITING_REVIEW with
     * [dev.otherworld.budget.data.repo.ReceiptRepository.INTERRUPTED_POST_MESSAGE] in `lastError`
     * -- deliberately, so the user checks Recent before saving again. Until this line existed
     * nothing read it: re-opening that receipt showed an ordinary pre-filled form with Save
     * enabled and no hint that the server may already hold the transaction, so two taps produced
     * a duplicate charge. That warning has to survive the round trip through the database, or the
     * queue's careful "never auto-retry an ambiguous post" rule is undone by the user at the next
     * screen.
     *
     * Other stored `lastError` values reach the screen the same way -- an extraction that failed
     * or found no OCR backend leaves its message here -- which is why [ReviewScreen] renders this
     * as a notice about the receipt rather than as the outcome of a button press.
     */
    private fun load() = viewModelScope.launch {
        val receipt = queue.byId(receiptId)
        // getOrNull() kept nullable, not `.orEmpty()`d away: a failed fetch and a server with
        // no accounts are different answers and get different copy below -- the same
        // distinction Quick Add and Capture already draw.
        val accounts = catalog.accounts().getOrNull()
        val categories = catalog.categories().getOrNull().orEmpty()
        val capabilities = catalog.capabilities().getOrNull()
        val draft = receipt?.draft ?: DraftTransaction()
        val currency = draft.total?.currency
            ?: capabilities?.currency
            ?: "GBP"

        // Held for recomputeSplit -- the split's inputs beyond the editable items and total.
        draftTax = draft.tax
        draftDiscount = draft.discount
        draftSuggestedCategoryId = draft.suggestedCategoryId

        _uiState.value = ReviewUiState(
            loading = false,
            // Resolved once, here, rather than at each use: a row can record a path whose file has
            // since gone (external cleanup, a restore onto a device without the app's files), and
            // the screen must not then render a 160dp image placeholder that never loads, nor open
            // a full-size viewer onto nothing, nor attach a File that will fail the upload at
            // body-write time. Null from here on means the same thing everywhere -- "no photo to
            // show or send" -- whether the row never had one (Quick Add) or has lost it.
            // `exists()` is a disk read, so it belongs in this coroutine and not in the Save click.
            photoPath = receipt?.photoPath?.takeIf { File(it).exists() },
            receiptExists = receipt != null,
            merchant = draft.merchant.orEmpty(),
            dateText = (draft.date ?: clock()).toString(),
            totalText = draft.total?.amount?.toPlainString().orEmpty(),
            currency = currency,
            accounts = accounts.orEmpty(),
            categories = categories,
            selectedAccountId = lastAccount.get()?.takeIf { id -> accounts.orEmpty().any { it.id == id } }
                ?: accounts?.firstOrNull()?.id,
            // Same existence guard the account default gets: a suggestion the fetched list does
            // not contain would render a blank dropdown while the foreign id silently rode into
            // the POST. Reachable from any stale origin -- a category deleted server-side, or a
            // draft written while signed in to a different server (AWAITING_REVIEW rows cross a
            // server switch with their drafts intact; only FAILED rows get scrubbed). Category
            // is optional, so dropping an unresolvable suggestion is always safe.
            selectedCategoryId = draft.suggestedCategoryId?.takeIf { id -> categories.any { it.id == id } },
            editableItems = draft.lineItems.map {
                EditableItemUi(it.description, it.amount?.amount?.toPlainString().orEmpty())
            },
            saveError = displayError(receipt?.lastError),
            accountsMessage = accountsMessageFor(accounts),
            accountsRetryable = accounts == null,
            splitsAvailable = capabilities?.splitsAvailable == true,
        ).recomputeSplit()
    }

    /**
     * Rebuilds the split fields from the current [ReviewUiState.editableItems], draft tax, the parsed
     * [ReviewUiState.totalText] and [ReviewUiState.splitsAvailable]. A receipt is
     * [ReviewUiState.splittable] only when the server offers splits AND [SplitPlan] finds a reconciling
     * set of rows -- so correcting a misread item amount, or adding/removing a line, flips splittability
     * live, exactly as editing the total does. Each non-tax row defaults to the draft's suggested category
     * when that id is in the fetched [ReviewUiState.categories], but a category the user has already chosen
     * survives a recompute -- matched by row index and description, so an unrelated edit doesn't wipe the
     * picks. The tax row is never categorised, and any edit that breaks reconciliation drops
     * [ReviewUiState.splitEnabled] with [ReviewUiState.splittable].
     */
    private fun ReviewUiState.recomputeSplit(): ReviewUiState {
        // Items are parsed from their raw text on every recompute -- an unreadable one becomes a
        // null amount, which SplitPlan treats as "can't reconcile", so the split simply stays off
        // rather than erroring. tax/discount/suggestion come from the instance vars seeded in load().
        val items = editableItems.map { LineItem(it.description, Money.parse(it.amountText, currency)) }
        val total = Money.parse(totalText, currency)
        val plan = total?.let { SplitPlan.rows(items, draftTax, draftDiscount, it) }
        val splittable = splitsAvailable && plan != null
        val defaultCategory = draftSuggestedCategoryId?.takeIf { id -> categories.any { it.id == id } }
        val rows = plan?.mapIndexed { i, row ->
            SplitRowUi(
                amount = row.amount, description = row.description, categorisable = row.categorisable,
                categoryId = when {
                    !row.categorisable -> null
                    i < splitRows.size && splitRows[i].description == row.description -> splitRows[i].categoryId
                    else -> defaultCategory
                },
            )
        } ?: emptyList()
        return copy(
            splittable = splittable,
            splitEnabled = splitEnabled && splittable,
            splitRows = rows,
            splitBlockedNote =
                if (splitsAvailable && !splittable && items.isNotEmpty())
                    strings.get(R.string.review_split_unavailable)
                else null,
        )
    }

    /**
     * Re-fetches only the pickers, for the retry affordance next to [ReviewUiState.accountsMessage].
     * Deliberately not a re-[load]: load() rebuilds the whole state from the stored row, which
     * would discard whatever the user has already typed into the form while offline.
     */
    fun onRetryCatalogClicked() = viewModelScope.launch {
        val accounts = catalog.accounts().getOrNull()
        val categories = catalog.categories().getOrNull().orEmpty()
        _uiState.update { state ->
            state.copy(
                accounts = accounts.orEmpty(),
                categories = if (categories.isEmpty()) state.categories else categories,
                // Keep an account the user already picked; otherwise default exactly as load()
                // does now that there may be something to default to.
                selectedAccountId = state.selectedAccountId
                    ?: lastAccount.get()?.takeIf { id -> accounts.orEmpty().any { it.id == id } }
                    ?: accounts?.firstOrNull()?.id,
                accountsMessage = accountsMessageFor(accounts),
                accountsRetryable = accounts == null,
            )
        }
    }

    /** Word-for-word Quick Add's picker copy, so the same blockage reads the same everywhere. */
    private fun accountsMessageFor(accounts: List<Account>?): String? = when {
        accounts == null -> strings.get(R.string.catalog_offline_no_accounts)
        accounts.isEmpty() -> strings.get(R.string.capture_no_accounts)
        else -> null
    }

    /**
     * The row's stored `lastError` is written for the *queue's* purposes and some values are
     * machine-flavoured ("OCR not configured") -- terse jargon that also names OCR, a term the
     * user-facing strings deliberately avoid. Mapped to user copy at display time, per stored
     * value, so the database keeps its stable identifiers and only this screen decides how to
     * say them. The queue-authored sentences (interrupted post, server change) pass through:
     * they are already the user copy.
     */
    private fun displayError(stored: String?): String? = when (stored) {
        null -> null
        // Byte-for-byte the fixed neutral copy Capture shows (spec §5's mandated sentence; the
        // plan pins it verbatim for BOTH the not-configured and quota cases -- it is the one
        // wording vetted against Play's anti-steering rule, so no new variant is invented for
        // either). Do not edit without CaptureScreen's copy -- and never add a link.
        BudgetApiError.OcrNotConfigured.message,
        BudgetApiError.OcrQuotaExhausted.message ->
            strings.get(R.string.capture_ocr_unavailable)
        BudgetApiError.ExtractionFailed.message ->
            strings.get(R.string.review_extract_failed)
        else -> stored
    }

    fun onMerchantChanged(value: String) = _uiState.update { it.copy(merchant = value) }
    fun onDateChanged(value: String) = _uiState.update { it.copy(dateText = value) }
    fun onAccountSelected(id: Long) = _uiState.update { it.copy(selectedAccountId = id) }
    fun onCategorySelected(id: Long?) = _uiState.update { it.copy(selectedCategoryId = id) }

    fun onTotalChanged(value: String) = _uiState.update { state ->
        val invalid = value.isNotBlank() && Money.parse(value, state.currency) == null
        state.copy(totalText = value, totalError = if (invalid) strings.get(R.string.amount_hint) else null)
            // Lowering the total below the items' sum stops the receipt reconciling, so the rows,
            // splittable and (if it was on) splitEnabled all update.
            .recomputeSplit()
    }

    // Editing the line items: extraction can misread a cost, garble a name, miss a line or invent
    // one, any of which stops the items summing to the total and hides the split. Each edit re-runs
    // recomputeSplit, so a corrected receipt re-offers the split the instant the items reconcile.
    // A bad index is ignored rather than crashed; a blank/unreadable amount just keeps it non-split.

    fun onItemDescriptionChanged(index: Int, value: String) = _uiState.update { state ->
        state.copy(
            editableItems = state.editableItems.mapIndexed { i, item ->
                if (i == index) item.copy(description = value) else item
            },
        ).recomputeSplit()
    }

    fun onItemAmountChanged(index: Int, value: String) = _uiState.update { state ->
        state.copy(
            editableItems = state.editableItems.mapIndexed { i, item ->
                if (i == index) item.copy(amountText = value) else item
            },
        ).recomputeSplit()
    }

    /** Appends a blank row for a line extraction missed; it stays non-reconciling until filled in. */
    fun onItemAdded() = _uiState.update { state ->
        state.copy(editableItems = state.editableItems + EditableItemUi("", "")).recomputeSplit()
    }

    /** Drops the line at [index] -- e.g. a phantom item extraction invented. Ignores a bad index. */
    fun onItemRemoved(index: Int) = _uiState.update { state ->
        if (index !in state.editableItems.indices) return@update state
        state.copy(editableItems = state.editableItems.filterIndexed { i, _ -> i != index }).recomputeSplit()
    }

    /** Turns splitting on only when the receipt actually [ReviewUiState.splittable]; off is always honoured. */
    fun onSplitToggled(on: Boolean) = _uiState.update { it.copy(splitEnabled = on && it.splittable) }

    /**
     * Assigns [categoryId] to the split row at [index]. Guards the index and refuses a
     * non-categorisable row (the tax or savings line): those always post uncategorised regardless
     * of any stray call.
     */
    fun onSplitCategorySelected(index: Int, categoryId: Long?) = _uiState.update { state ->
        val rows = state.splitRows
        if (index !in rows.indices || !rows[index].categorisable) return@update state
        state.copy(splitRows = rows.toMutableList().also { it[index] = it[index].copy(categoryId = categoryId) })
    }

    fun onSaveClicked() {
        val state = _uiState.value
        // Reentrancy guard: a double-tap can dispatch two onClick events before recomposition
        // ever disables the Save button (its `enabled = uiState.canSave` is not guaranteed to
        // land between them), so the button alone cannot be trusted to prevent a second post.
        // onSaveClicked is not `suspend` -- nothing below can be preempted before the
        // `saving = true` write, so a second, immediately-following call always observes it.
        if (state.saving) return
        if (!state.receiptExists) return
        val total = Money.parse(state.totalText, state.currency) ?: return
        val accountId = state.selectedAccountId ?: return
        val date = runCatching { LocalDate.parse(state.dateText) }.getOrElse { clock() }
        // Null when there is no photo to send -- a Quick Add manual entry that never had one, or a
        // captured row whose file has gone missing (resolved in load(), see there). Posts fine
        // either way now that `photo` is optional on the wire, and the amount the user already
        // reviewed is not lost for the sake of an image that is gone regardless.
        val photo = state.photoPath?.let(::File)

        // Splitting is a whole-receipt choice: when it is on (and still valid), each row becomes a
        // part and the top-level category is dropped -- categorisation lives in the parts. Otherwise
        // the request is unchanged from before splits existed: no parts, the single chosen category.
        // The tax row posts uncategorised. splittable is re-checked so an edit that broke
        // reconciliation after the toggle can never send a non-summing split.
        val useSplits = state.splitEnabled && state.splittable
        val splits = if (useSplits) {
            state.splitRows.map { SplitPart(it.amount, if (!it.categorisable) null else it.categoryId, it.description) }
        } else null

        _uiState.update { it.copy(saving = true, saveError = null) }

        viewModelScope.launch {
            queue.post(receiptId, CreateTransactionRequest(
                accountId = accountId,
                categoryId = if (useSplits) null else state.selectedCategoryId,
                date = date,
                merchant = state.merchant.ifBlank { "Unknown" },
                total = total,
                photo = photo,
                splits = splits,
            )).onSuccess { created ->
                lastAccount.set(accountId)
                _uiState.update { it.copy(
                    saving = false, saved = true,
                    postNotice = postNoticeFor(created),
                ) }
            }.onFailure { error ->
                _uiState.update { it.copy(saving = false, saveError = messageFor(error)) }
            }
        }
    }

    /**
     * A split post omits the top-level category, so at most one of these can happen; the split
     * notice is checked first all the same.
     */
    private fun postNoticeFor(created: CreatedTransaction): String? = when {
        created.splitsError != null -> strings.get(R.string.review_split_saved_error)
        created.categoryError != null -> strings.get(R.string.save_category_dropped)
        else -> null
    }

    fun onDiscardClicked() = viewModelScope.launch {
        queue.discard(receiptId)
        _uiState.update { it.copy(saved = true) }
    }

    /**
     * The screen stays open after a failed save, with Save still enabled. A second press is now
     * duplicate-safe -- it re-posts the same row under the same idempotency key, which the server
     * dedupes -- so this text is informational rather than the last line of defence, but it still
     * has to say which situation the user is in. It uses the same rule the queue uses to decide
     * whether *it* may retry unattended ([dev.otherworld.budget.data.remote.BudgetApiError.isRetryable]),
     * not a separate judgement that could drift from it.
     *
     * The raw `error.message` ("Server error 504") was actively misleading here: it invited a press
     * the user feared would double-charge them. It no longer would, and the message now reflects
     * that -- a 504 or a read timeout reads as "queued, will be sent automatically", not a warning.
     *
     * "transaction", not "receipt": this screen now also hosts Quick Add manual entries, which
     * have no receipt and never had one, and these two lines are shown to those rows on exactly
     * the same failures. The word that is true of both is the one both screens use --
     * [dev.otherworld.budget.ui.quickadd.QuickAddViewModel]'s own `messageFor` says the same
     * thing verbatim, so the same failure reads the same sentence from either screen.
     */
    private fun messageFor(error: Throwable): String = when {
        error is PostAlreadyInFlightException -> strings.get(R.string.save_in_flight)
        (error as? BudgetApiError)?.isRetryable() == true -> strings.get(R.string.save_queued)
        error is BudgetApiError.IdempotencyKeyConflict -> ReceiptRepository.KEY_CONFLICT_MESSAGE
        else -> ReceiptRepository.INTERRUPTED_POST_MESSAGE
    }
}
