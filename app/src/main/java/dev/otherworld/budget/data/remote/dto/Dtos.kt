package dev.otherworld.budget.data.remote.dto

import dev.otherworld.budget.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class CapabilitiesDto(
    @SerialName("ocr_available") val ocrAvailable: Boolean,
    val currency: String,
    val version: String,
    // Absent on older servers; absence means the server does not accept splits.
    @SerialName("splits_available") val splitsAvailable: Boolean = false,
    // Absent on older servers; absence means the check side (balances, budget, bills) isn't
    // available and the Overview tab shows its "update your server" state instead.
    @SerialName("check_available") val checkAvailable: Boolean = false,
)

/**
 * The slice of Nextcloud *core*'s `GET /ocs/v2.php/cloud/capabilities` this app reads: the
 * theming colour. This is a standard core OCS route (not part of the Budget app contract), so it
 * answers 200 on any modern Nextcloud even where the Budget routes 404. Every other field the
 * route returns -- and there are many -- is dropped by `ignoreUnknownKeys`. Each level is nullable
 * so an older server, a theming-disabled instance, or a missing `color` simply yields `null`
 * rather than a parse failure; the caller then keeps the default colour.
 */
@Serializable data class CoreCapabilitiesDto(val capabilities: CoreCapabilities? = null)
@Serializable data class CoreCapabilities(val theming: ThemingDto? = null)
@Serializable data class ThemingDto(val color: String? = null)

@Serializable data class AccountDto(
    val id: Long,
    val name: String,
    val currency: String,
    val type: String = "",
    // Absent on an older server, meaning "not sent" rather than "zero" -- Account.balance stays
    // null in that case rather than a misleading Money(0).
    val balance: String? = null,
    @SerialName("balance_in_base_currency") val balanceInBase: String? = null,
    @SerialName("base_currency") val baseCurrency: String? = null,
    val closed: Boolean = false,
    val shared: Boolean = false,
) {
    fun toDomain() = Account(
        id = id,
        name = name,
        currency = currency,
        type = type,
        balance = balance?.let { Money.fromServer(it, currency) },
        // Only parsed when both the converted figure and its currency are present -- one
        // without the other isn't a value this app can render.
        balanceInBase = if (balanceInBase != null && baseCurrency != null) Money.fromServer(balanceInBase, baseCurrency) else null,
        closed = closed,
        shared = shared,
    )
}

@Serializable data class CategoryDto(
    val id: Long,
    val name: String,
    @SerialName("parent_id") val parentId: Long? = null,
) { fun toDomain() = Category(id, name, parentId) }

@Serializable data class LineItemDto(val description: String, val amount: String? = null) {
    fun toDomain(currency: String) = LineItem(description, amount?.let { Money.fromServer(it, currency) })
}

@Serializable data class DraftDto(
    val merchant: String? = null,
    val date: String? = null,
    val total: String? = null,
    val currency: String? = null,
    @SerialName("suggested_category_id") val suggestedCategoryId: Long? = null,
    @SerialName("line_items") val lineItems: List<LineItemDto> = emptyList(),
    val subtotal: String? = null,
    val tax: String? = null,
    val discount: String? = null,
) {
    fun toDomain(fallbackCurrency: String): DraftTransaction {
        val ccy = currency ?: fallbackCurrency
        return DraftTransaction(
            merchant = merchant,
            date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            total = total?.let { Money.fromServer(it, ccy) },
            suggestedCategoryId = suggestedCategoryId,
            lineItems = lineItems.map { it.toDomain(ccy) },
            subtotal = subtotal?.let { Money.fromServer(it, ccy) },
            tax = tax?.let { Money.fromServer(it, ccy) },
            discount = discount?.let { Money.fromServer(it, ccy) },
        )
    }
}

/**
 * One part of a split transaction (spec §1.1's `ApiSerializer::split()` shape), whether inline
 * on a [RecentDto] row or inside a [SplitsDto]. [toDomain] drops the part -- rather than fail
 * the whole row -- when [amount] doesn't parse, since a single garbled split shouldn't hide an
 * otherwise-good transaction.
 */
@Serializable data class SplitDto(
    val amount: String,
    @SerialName("category_name") val categoryName: String? = null,
    val description: String? = null,
) {
    fun toDomain(currency: String): SplitLine? {
        val parsedAmount = Money.fromServer(amount, currency) ?: return null
        return SplitLine(parsedAmount, categoryName, description)
    }
}

@Serializable data class RecentDto(
    val id: Long,
    val merchant: String,
    val date: String,
    val amount: String,
    val currency: String,
    @SerialName("account_name") val accountName: String,
    // Everything below is absent on an older server; each falls back to the value that renders
    // the row exactly as it always has (spec §1.1).
    @SerialName("account_id") val accountId: Long? = null,
    val type: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val splits: List<SplitDto> = emptyList(),
    @SerialName("linked_transaction_id") val linkedTransactionId: Long? = null,
    @SerialName("linked_account_name") val linkedAccountName: String? = null,
) {
    fun toDomain(): RecentTransaction? {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val parsedAmount = Money.fromServer(amount, currency) ?: return null
        val direction = when (type) {
            "debit" -> Direction.DEBIT
            "credit" -> Direction.CREDIT
            else -> Direction.UNKNOWN
        }
        return RecentTransaction(
            id = id,
            merchant = merchant,
            date = parsedDate,
            amount = parsedAmount,
            accountName = accountName,
            accountId = accountId,
            direction = direction,
            categoryName = categoryName,
            splits = splits.mapNotNull { it.toDomain(currency) },
            transfer = linkedTransactionId?.let { TransferLink(it, linkedAccountName) },
        )
    }
}

/**
 * [idempotencyKey] is the server echoing back the key it accepted for this transaction, so the
 * client can prove the key was seen rather than infer it from later behaviour. Nullable: an older
 * server build simply omits it, and its absence is not treated as an error --
 * [dev.otherworld.budget.data.remote.BudgetApiRetrofit] only reacts when a *present* echo disagrees
 * with the key it sent.
 */
@Serializable data class CreatedDto(
    val id: Long,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    // Present when the transaction was recorded but its per-item splits were rejected (e.g. the
    // split amounts did not sum to the total). Absent -- and so null -- whenever there was no split
    // problem, which is always until splits are actually sent. The 201 also now carries `splits`,
    // `is_split` and `photo_error`, which stay unmodelled: the Retrofit Json ignores unknown keys.
    @SerialName("splits_error") val splitsError: String? = null,
    // Present when the server kept the transaction but not its category, because the account's
    // owner can't use it (one of the caller's own on someone else's shared account). It is saved
    // uncategorised rather than refused.
    @SerialName("category_error") val categoryError: String? = null,
)

@Serializable data class ErrorDataDto(@SerialName("error_code") val errorCode: String? = null)

/**
 * The wire shape of one entry in the `splits` JSON-array part of `POST transactions`
 * (`docs/server-api-contract.md`). Encoded with `explicitNulls = false` in
 * [dev.otherworld.budget.data.remote.BudgetApiRetrofit], so a null [categoryId] or [description] is
 * omitted from the JSON entirely -- that omission, not a `null` literal, is how the tax line carries
 * no category.
 */
@Serializable data class SplitWireDto(
    val amount: String,
    @SerialName("category_id") val categoryId: Long? = null,
    val description: String? = null,
)

/** One line of `ApiSerializer::budgetLine()` (spec §1.2). All money fields share [BudgetStatusDto.currency]. */
@Serializable data class BudgetLineDto(
    @SerialName("category_id") val categoryId: Long,
    val name: String,
    @SerialName("parent_id") val parentId: Long? = null,
    val type: String,
    val period: String,
    val budgeted: String,
    val carried: String,
    val spent: String,
    val remaining: String,
    val shared: Boolean = false,
    // A quarterly or yearly line's whole period so far; null for any other period, and absent on
    // a server from before every line was measured over the month.
    @SerialName("period_to_date") val periodToDate: PeriodToDateDto? = null,
) {
    /**
     * Drops the line -- rather than fail the whole [BudgetStatusDto] -- when any amount doesn't
     * parse. A period so far that doesn't parse is left out on its own: the month's figures are
     * the line, that is only context beside them.
     */
    fun toDomain(currency: String): BudgetLine? {
        val budgetedM = Money.fromServer(budgeted, currency) ?: return null
        val carriedM = Money.fromServer(carried, currency) ?: return null
        val spentM = Money.fromServer(spent, currency) ?: return null
        val remainingM = Money.fromServer(remaining, currency) ?: return null
        return BudgetLine(
            categoryId, name, parentId, type, period, budgetedM, carriedM, spentM, remainingM, shared,
            periodToDate = periodToDate?.toDomain(currency),
        )
    }
}

/** `period_to_date` on a budget line: the quarter or year so far. Its dates aren't read. */
@Serializable data class PeriodToDateDto(val budgeted: String, val spent: String) {
    fun toDomain(currency: String): PeriodToDate? {
        val budgetedM = Money.fromServer(budgeted, currency) ?: return null
        val spentM = Money.fromServer(spent, currency) ?: return null
        return PeriodToDate(budgetedM, spentM)
    }
}

@Serializable data class BudgetTotalsDto(val budgeted: String, val spent: String, val remaining: String)

/** `GET /budget/status` response (spec §1.2). */
@Serializable data class BudgetStatusDto(
    val month: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val currency: String,
    val totals: BudgetTotalsDto,
    val categories: List<BudgetLineDto> = emptyList(),
) {
    /**
     * Null when the dates or totals fail to parse -- [dev.otherworld.budget.data.remote.BudgetApiRetrofit]
     * turns that into the same [dev.otherworld.budget.data.remote.BudgetApiError.ServerError] a
     * malformed body gets elsewhere, rather than a half-populated [BudgetStatus]. Unparseable
     * category lines are dropped individually instead.
     */
    fun toDomain(): BudgetStatus? {
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return null
        val budgetedM = Money.fromServer(totals.budgeted, currency) ?: return null
        val spentM = Money.fromServer(totals.spent, currency) ?: return null
        val remainingM = Money.fromServer(totals.remaining, currency) ?: return null
        return BudgetStatus(
            month = month,
            startDate = start,
            endDate = end,
            currency = currency,
            budgeted = budgetedM,
            spent = spentM,
            remaining = remainingM,
            lines = categories.mapNotNull { it.toDomain(currency) },
        )
    }
}

/** One bill of `ApiSerializer::bill()` (spec §1.3), own or shared. */
@Serializable data class UpcomingBillDto(
    val id: Long,
    val name: String,
    val amount: String,
    @SerialName("amount_type") val amountType: String? = null,
    val currency: String,
    val frequency: String,
    @SerialName("next_due_date") val nextDueDate: String,
    val overdue: Boolean = false,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("is_transfer") val isTransfer: Boolean = false,
    @SerialName("auto_pay") val autoPay: Boolean = false,
    val shared: Boolean = false,
) {
    /** Null when the due date or amount fails to parse -- dropped from the list rather than crashing it. */
    fun toDomain(): UpcomingBill? {
        val parsedDate = runCatching { LocalDate.parse(nextDueDate) }.getOrNull() ?: return null
        val parsedAmount = Money.fromServer(amount, currency) ?: return null
        return UpcomingBill(
            id, name, parsedAmount, parsedDate, overdue, frequency, accountName, isTransfer, autoPay, shared,
            // An older server omits amount_type; with nothing saying otherwise the amount is exact.
            estimated = amountType != null && amountType != "fixed",
        )
    }
}

/** `GET /bills/upcoming` response (spec §1.3). */
@Serializable data class UpcomingBillsDto(val days: Int, val bills: List<UpcomingBillDto> = emptyList())

/** `GET /transactions/{id}/splits` response (spec §1.1). */
@Serializable data class SplitsDto(val splits: List<SplitDto> = emptyList())
