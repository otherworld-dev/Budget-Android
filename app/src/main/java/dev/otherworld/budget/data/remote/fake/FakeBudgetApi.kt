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
    var accountsResult: List<Account> = listOf(
        Account(1, "Current Account", "GBP"),
        Account(2, "Joint Account", "GBP"),
    ),
    var splitsAvailable: Boolean = true,
    /**
     * Forces [createTransaction] to report a split rejection on a *newly created* transaction --
     * the server recorded it but could not split it by item. A replay of an already-seen key never
     * re-reports it (see below). Null (the default) means splits succeeded, which is always until
     * splits are actually sent.
     */
    var splitsError: String? = null,
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

    private suspend fun <T> respond(value: () -> T): Result<T> {
        if (latencyMs > 0) delay(latencyMs)
        nextError?.let { return Result.failure(it) }
        // Structural guarantee of the contract: nothing escapes as a thrown
        // exception, whatever the value producer does.
        return runCatching { value() }
            .recoverCatching { throw BudgetApiError.ServerError(0) }
    }

    override suspend fun capabilities() =
        respond { Capabilities(ocrAvailable, "GBP", "2.41.0", splitsAvailable) }

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

    override suspend fun recentTransactions(limit: Int) = respond {
        listOf(
            RecentTransaction(9001, "Tesco", LocalDate.of(2026, 3, 12),
                Money(BigDecimal("24.31"), "GBP"), "Current Account"),
            RecentTransaction(9000, "Shell", LocalDate.of(2026, 3, 11),
                Money(BigDecimal("58.02"), "GBP"), "Current Account"),
        ).take(limit.coerceAtLeast(0))
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
}
