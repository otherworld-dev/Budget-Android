package dev.otherworld.budget.domain.model

import java.time.LocalDate

/**
 * Which way money moved on a [RecentTransaction]. Amounts are always positive (see [Money]);
 * this is what tells a credit from a debit, exactly as `type` does everywhere else in the v1
 * API. [UNKNOWN] is what an older server's row maps to -- it sends no `type` at all.
 */
enum class Direction { DEBIT, CREDIT, UNKNOWN }

/**
 * One part of a split transaction, inline on a [RecentTransaction] row or returned by
 * [dev.otherworld.budget.data.remote.BudgetApi.transactionSplits]. [categoryName] and
 * [description] are nullable because an uncategorised or undescribed part is exactly that on
 * the server, not a parse failure.
 */
data class SplitLine(val amount: Money, val categoryName: String?, val description: String?)

/**
 * The other half of a transfer pair, present on a [RecentTransaction] whenever the server can
 * identify it. [linkedAccountName] is null when the other account isn't visible to the caller
 * (not own or shared) -- the id is still known, but naming it would leak an account the caller
 * has no access to.
 */
data class TransferLink(val linkedTransactionId: Long, val linkedAccountName: String?)

/**
 * One category's slice of [BudgetStatus], mirroring `ApiSerializer::budgetLine()` (spec §1.2).
 * [remaining] can go negative -- an overspent category -- and that figure is exactly what the
 * server folds into [BudgetStatus.remaining]: the app must always display it as sent, never
 * re-derive it as `budgeted - spent`, so the same number matches the web Budget page.
 *
 * Every figure is the month's whatever the [period]: a weekly budget counts as 52/12 of its
 * amount, a quarterly one a third and a yearly one a twelfth, against the month's spending.
 * [periodToDate] adds a quarterly or yearly line's whole period so far, and is null otherwise.
 */
data class BudgetLine(
    val categoryId: Long,
    val name: String,
    val parentId: Long?,
    val type: String,
    val period: String,
    val budgeted: Money,
    val carried: Money,
    val spent: Money,
    val remaining: Money,
    val shared: Boolean,
    val periodToDate: PeriodToDate? = null,
)

/** A quarterly or yearly budget's whole quarter or year: its full [budgeted] amount and [spent] so far. */
data class PeriodToDate(val budgeted: Money, val spent: Money)

/**
 * The user's current budget month, from `GET /budget/status` (spec §1.2). [month] is the
 * server's own budget-month key (`"2026-09"`), which need not track the calendar month once a
 * custom `budget_start_day` is in play -- [startDate]/[endDate] are the actual window to show.
 * [lines] only ever holds categories with an effective budget; the server already omits the
 * rest.
 */
data class BudgetStatus(
    val month: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: String,
    val budgeted: Money,
    val spent: Money,
    val remaining: Money,
    val lines: List<BudgetLine>,
)

/**
 * One bill from `GET /bills/upcoming` (spec §1.3), own or shared. [overdue] is the server's own
 * verdict (`next_due_date` before today), not recomputed on-device. [accountName] is null
 * exactly when the bill isn't linked to an account, or that account isn't visible to the
 * caller -- the same visibility rule as [TransferLink.linkedAccountName].
 *
 * [estimated] is true when the bill's `amount_type` isn't `"fixed"`: the server only works out a
 * variable bill's real amount when it's paid, so until then [amount] is the stored figure and
 * must be shown as an estimate, not as what will actually leave the account.
 */
data class UpcomingBill(
    val id: Long,
    val name: String,
    val amount: Money,
    val nextDueDate: LocalDate,
    val overdue: Boolean,
    val frequency: String,
    val accountName: String?,
    val isTransfer: Boolean,
    val autoPay: Boolean,
    val shared: Boolean,
    val estimated: Boolean = false,
)
