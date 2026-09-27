package dev.otherworld.budget.data.remote.fake

import dev.otherworld.budget.data.remote.*
import dev.otherworld.budget.domain.model.*
import kotlinx.coroutines.delay
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Reference implementation of [BudgetApi]. Drives unit tests and Compose previews.
 * Every failure mode in the contract is reachable by setting a knob.
 */
class FakeBudgetApi(
    var ocrAvailable: Boolean = true,
    var nextError: BudgetApiError? = null,
    var latencyMs: Long = 0,
    var accountsResult: List<Account> = FakeCheckData.accounts,
    var splitsAvailable: Boolean = true,
    /**
     * Forces [createTransaction] to report a split rejection on a *newly created* transaction --
     * the server recorded it but could not split it by item. A replay of an already-seen key never
     * re-reports it (see below). Null (the default) means splits succeeded, which is always until
     * splits are actually sent.
     */
    var splitsError: String? = null,
    /** Absent (false) on an older server -- see [Capabilities.checkAvailable]. */
    var checkAvailable: Boolean = true,
    var budgetResult: BudgetStatus = FakeCheckData.budget,
    var billsResult: List<UpcomingBill> = FakeCheckData.bills,
    var recentResult: List<RecentTransaction> = FakeCheckData.recent,
    /**
     * Simulates a part-upgraded server: it answers `accounts`/`recentTransactions` as usual but
     * 404s the two check-only routes, exactly as a server running the current app version but an
     * older API would.
     */
    var unsupportedCheckRoutes: Boolean = false,
    /**
     * Fails [upcomingBills] alone with a [BudgetApiError.Network], leaving every other route
     * answering -- the per-section failure case [nextError] cannot reach, since it fails them all.
     */
    var failBills: Boolean = false,
    /**
     * Fails [budgetStatus] alone with a 404, leaving [upcomingBills] answering --
     * [unsupportedCheckRoutes] 404s both together, so this is the only way to prove a
     * part-upgraded server that has *only* lost the budget route doesn't also flip bills
     * unsupported, or (Overview's own [dev.otherworld.budget.data.repo.CheckRepository]
     * consumer) the old-server aggregate that requires both.
     */
    var unsupportedBudgetOnly: Boolean = false,
) : BudgetApi {

    /** One entry per *distinct* transaction created -- a replay of a seen key adds nothing here. */
    val created = mutableListOf<CreateTransactionRequest>()

    /**
     * Every idempotency key received, in call order, repeats included. Lets a test prove the client
     * sends the *same* key across a retry (all equal) without controlling the value the repository
     * minted. Recorded even for a call the [nextError] knob fails, so a failed-then-retried post
     * shows both keys.
     */
    val idempotencyKeys = mutableListOf<String>()

    // Mirrors the server's reserve-before-write dedup: a key already seen replays its transaction
    // (same id, no new `created` entry) instead of creating a second. A blank key means "unkeyed"
    // -- the server treats an absent/empty key as no dedup at all -- so those always create.
    private val committedByKey = mutableMapOf<String, Long>()
    private var nextTransactionId = 9002L

    /** Lets tests prove a cache actually prevents a second call, e.g. [CatalogRepository]. */
    var accountCalls = 0
    var budgetCalls = 0
    var billsCalls = 0
    var recentCalls = 0

    private suspend fun <T> respond(value: () -> T): Result<T> {
        if (latencyMs > 0) delay(latencyMs)
        nextError?.let { return Result.failure(it) }
        // Structural guarantee of the contract: nothing escapes as a thrown
        // exception, whatever the value producer does.
        return runCatching { value() }
            .recoverCatching { throw BudgetApiError.ServerError(0) }
    }

    override suspend fun capabilities() =
        respond { Capabilities(ocrAvailable, "GBP", "2.41.0", splitsAvailable, checkAvailable) }

    override suspend fun accounts(): Result<List<Account>> {
        accountCalls++
        return respond { accountsResult }
    }

    override suspend fun categories() = respond {
        listOf(
            Category(14, "Groceries", null),
            Category(15, "Household", null),
            Category(16, "Fuel", null),
        )
    }

    override suspend fun recentTransactions(limit: Int): Result<List<RecentTransaction>> {
        recentCalls++
        return respond { recentResult.take(limit.coerceAtLeast(0)) }
    }

    override suspend fun extract(photo: File): Result<DraftTransaction> {
        if (!ocrAvailable) return Result.failure(BudgetApiError.OcrNotConfigured)
        return respond {
            DraftTransaction(
                merchant = "Tesco",
                date = LocalDate.of(2026, 3, 12),
                total = Money(BigDecimal("24.31"), "GBP"),
                suggestedCategoryId = 14,
                lineItems = listOf(
                    LineItem("Milk 2L", Money(BigDecimal("1.20"), "GBP")),
                    LineItem("Bread", Money(BigDecimal("1.10"), "GBP")),
                    LineItem("Chicken 1kg", Money(BigDecimal("6.49"), "GBP")),
                ),
                subtotal = Money(BigDecimal("22.10"), "GBP"),
                tax = Money(BigDecimal("2.21"), "GBP"),
            )
        }
    }

    override suspend fun createTransaction(
        request: CreateTransactionRequest,
        idempotencyKey: String,
    ): Result<CreatedTransaction> {
        idempotencyKeys += idempotencyKey        // before respond(): captured even on a forced failure
        return respond {
            committedByKey[idempotencyKey]?.takeIf { idempotencyKey.isNotBlank() }
                // A replay never re-reports a split error: the split was already accepted or rejected
                // on the first, distinct create.
                ?.let { return@respond CreatedTransaction(it, null) }
            created += request
            val id = nextTransactionId++
            if (idempotencyKey.isNotBlank()) committedByKey[idempotencyKey] = id
            CreatedTransaction(id, splitsError)
        }
    }

    override suspend fun budgetStatus(month: String?): Result<BudgetStatus> {
        budgetCalls++
        // Checked before respond(), like ocrAvailable above -- respond()'s recoverCatching would
        // otherwise flatten a thrown ServerError(404) into ServerError(0).
        if (unsupportedCheckRoutes || unsupportedBudgetOnly) return Result.failure(BudgetApiError.ServerError(404))
        return respond { budgetResult }
    }

    override suspend fun upcomingBills(days: Int): Result<List<UpcomingBill>> {
        billsCalls++
        if (unsupportedCheckRoutes) return Result.failure(BudgetApiError.ServerError(404))
        if (failBills) return Result.failure(BudgetApiError.Network(null))
        return respond { billsResult }
    }

    override suspend fun transactionSplits(id: Long): Result<List<SplitLine>> = respond {
        recentResult.firstOrNull { it.id == id }?.splits ?: emptyList()
    }
}

/**
 * Fixed fixture for the check side -- balances, budget, bills, splits and transfers -- one
 * self-consistent picture. The Groceries budget line matches the design spec's own worked
 * example verbatim (spec §1.2: budgeted 400.00, spent 431.20, remaining -31.20), and the Tesco
 * split below sums to its own transaction total (20.00 + 4.31 = 24.31), the same way a real
 * split must.
 */
object FakeCheckData {
    val accounts = listOf(
        Account(1, "Current Account", "GBP", balance = Money(BigDecimal("1234.56"), "GBP")),
        Account(2, "Joint Account", "GBP", balance = Money(BigDecimal("-45.10"), "GBP")),
        Account(3, "Old ISA", "GBP", type = "savings",
            balance = Money(BigDecimal("0.00"), "GBP"), closed = true),
    )

    // Descending date order, a transfer pair (9003/9004, linked both ways) ahead of a split
    // transaction (9001) and a plain one (9000).
    val recent = listOf(
        RecentTransaction(9003, "Transfer to Savings", LocalDate.of(2026, 3, 20),
            Money(BigDecimal("500.00"), "GBP"), "Current Account", accountId = 1,
            direction = Direction.DEBIT, transfer = TransferLink(9004, "Joint Account")),
        RecentTransaction(9004, "Transfer from Current", LocalDate.of(2026, 3, 19),
            Money(BigDecimal("500.00"), "GBP"), "Joint Account", accountId = 2,
            direction = Direction.CREDIT, transfer = TransferLink(9003, "Current Account")),
        RecentTransaction(9001, "Tesco", LocalDate.of(2026, 3, 12),
            Money(BigDecimal("24.31"), "GBP"), "Current Account", accountId = 1,
            direction = Direction.DEBIT,
            splits = listOf(
                SplitLine(Money(BigDecimal("20.00"), "GBP"), "Groceries", null),
                SplitLine(Money(BigDecimal("4.31"), "GBP"), "Household", null),
            )),
        RecentTransaction(9000, "Shell", LocalDate.of(2026, 3, 11),
            Money(BigDecimal("58.02"), "GBP"), "Current Account", accountId = 1,
            direction = Direction.DEBIT),
    )

    // Totals are the sum of the three lines below: budgeted 650.00, spent 576.20, remaining 73.80.
    val budget = BudgetStatus(
        month = "2026-09",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 30),
        currency = "GBP",
        budgeted = Money(BigDecimal("650.00"), "GBP"),
        spent = Money(BigDecimal("576.20"), "GBP"),
        remaining = Money(BigDecimal("73.80"), "GBP"),
        lines = listOf(
            BudgetLine(14, "Groceries", null, "expense", "monthly",
                Money(BigDecimal("400.00"), "GBP"), Money(BigDecimal("0.00"), "GBP"),
                Money(BigDecimal("431.20"), "GBP"), Money(BigDecimal("-31.20"), "GBP"), false),
            BudgetLine(16, "Fuel", null, "expense", "monthly",
                Money(BigDecimal("150.00"), "GBP"), Money(BigDecimal("0.00"), "GBP"),
                Money(BigDecimal("60.00"), "GBP"), Money(BigDecimal("90.00"), "GBP"), false),
            BudgetLine(15, "Household", null, "expense", "monthly",
                Money(BigDecimal("100.00"), "GBP"), Money(BigDecimal("0.00"), "GBP"),
                Money(BigDecimal("85.00"), "GBP"), Money(BigDecimal("15.00"), "GBP"), false),
        ),
    )

    val bills = listOf(
        UpcomingBill(1, "Council Tax", Money(BigDecimal("150.00"), "GBP"),
            LocalDate.of(2026, 9, 20), overdue = true, frequency = "monthly",
            accountName = "Current Account", isTransfer = false, autoPay = true, shared = false),
        UpcomingBill(2, "Phone", Money(BigDecimal("35.00"), "GBP"),
            LocalDate.of(2026, 10, 2), overdue = false, frequency = "monthly",
            accountName = "Current Account", isTransfer = false, autoPay = true, shared = false),
    )
}
