package dev.otherworld.budget.domain.model

import java.time.LocalDate

/**
 * [accountId] and everything below it are only populated once the server sends them (spec
 * §1.1) -- an older server's row still parses, with [direction] falling back to
 * [Direction.UNKNOWN], [splits] empty and [transfer] null, so the Activity screen renders it
 * exactly as it always has: unsigned, with no chips.
 */
data class RecentTransaction(
    val id: Long,
    val merchant: String,
    val date: LocalDate,
    val amount: Money,
    val accountName: String,
    val accountId: Long? = null,
    val direction: Direction = Direction.UNKNOWN,
    val categoryName: String? = null,
    val splits: List<SplitLine> = emptyList(),
    val transfer: TransferLink? = null,
)
