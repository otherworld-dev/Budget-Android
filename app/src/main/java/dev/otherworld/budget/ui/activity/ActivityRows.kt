package dev.otherworld.budget.ui.activity

import dev.otherworld.budget.domain.model.Direction
import dev.otherworld.budget.domain.model.RecentTransaction

/**
 * One row in the Activity list: either a transaction on its own, or -- when both halves of a
 * transfer are present in the same page of results -- a single collapsed row showing the
 * movement between accounts. See [buildActivityRows].
 */
sealed interface ActivityRow {
    val key: Long

    data class Single(val tx: RecentTransaction) : ActivityRow {
        override val key = tx.id
    }

    /** Both halves visible: one row, from -> to. */
    data class TransferPair(val from: RecentTransaction, val to: RecentTransaction) : ActivityRow {
        override val key = minOf(from.id, to.id)
    }
}

/**
 * Collapses transfer pairs in [transactions] into single [ActivityRow.TransferPair] rows,
 * leaving everything else as [ActivityRow.Single]. The list is walked in order; a row is only
 * ever paired once, so the first half encountered "consumes" its partner and a later row that
 * would also point at that same id is left alone rather than double-pairing.
 *
 * A row's own [dev.otherworld.budget.domain.model.TransferLink.linkedTransactionId] is checked
 * first, but pairing needs only *one* side's link to point at the other -- a row with no
 * transfer info of its own can still be found as the target of a later row's link, so an
 * unlinked row visited first is not committed to [ActivityRow.Single] until both directions have
 * been checked.
 *
 * The pair is emitted at the position of whichever half is listed first in [transactions].
 * [ActivityRow.TransferPair.from] is always the `DEBIT` half; if neither half is `DEBIT` (both
 * `CREDIT`, or both `UNKNOWN` as an older server sends), the half listed first becomes `from` so
 * the row is still deterministic.
 *
 * A transfer whose linked id isn't in [transactions] at all -- the partner is on another page,
 * or belongs to an account the caller can't see -- stays a plain [ActivityRow.Single]; the
 * screen reads its own [dev.otherworld.budget.domain.model.TransferLink] to label it (Task 8). A
 * transfer that links to *itself* (`linkedTransactionId == tx.id`) is treated the same way --
 * never as a pair with itself -- since a row can't be its own other half.
 *
 * Every id in [transactions] is emitted at most once, even if it appears more than once in the
 * input: the first occurrence wins and a later duplicate is dropped silently. This keeps
 * [ActivityRow.key] unique across the returned list, which the Activity screen's `LazyColumn`
 * relies on (Task 8) -- Compose's keyed `items` throws if two rows share a key.
 */
fun buildActivityRows(transactions: List<RecentTransaction>): List<ActivityRow> {
    val byId = transactions.associateBy { it.id }

    // The reverse direction: for a row with no (or no resolvable) link of its own, is there some
    // other row in the list whose link points at it? Last writer wins on a collision, which real
    // data never produces (transfers are 1:1).
    val pointedAtBy = transactions
        .mapNotNull { tx -> tx.transfer?.linkedTransactionId?.let { targetId -> targetId to tx.id } }
        .toMap()

    val consumed = mutableSetOf<Long>()
    val rows = mutableListOf<ActivityRow>()

    for (tx in transactions) {
        if (tx.id in consumed) continue     // already paired away, or a later duplicate of an id already emitted

        val partnerId = tx.transfer?.linkedTransactionId ?: pointedAtBy[tx.id]
        val partner = partnerId
            ?.takeIf { it != tx.id }                       // a transfer can never pair with itself
            ?.let { byId[it] }
            ?.takeIf { it.id !in consumed }

        consumed += tx.id                                  // claims this id -- first occurrence wins either way

        if (partner == null) {
            rows += ActivityRow.Single(tx)
            continue
        }

        consumed += partner.id
        val (from, to) = when {
            tx.direction == Direction.DEBIT -> tx to partner
            partner.direction == Direction.DEBIT -> partner to tx
            else -> tx to partner
        }
        rows += ActivityRow.TransferPair(from, to)
    }
    return rows
}

/**
 * Where a transfer went, for a row whose other half isn't in the list (both halves present
 * collapse into one [ActivityRow.TransferPair] instead). [To] and [From] name the other account;
 * [Unnamed] is a transfer into or out of an account the user can't see, whose name the server
 * deliberately leaves out.
 */
sealed interface TransferRoute {
    data class To(val account: String) : TransferRoute
    data class From(val account: String) : TransferRoute
    data object Unnamed : TransferRoute
}

/** Null for anything that isn't one half of a transfer. Money leaving (a debit) went *to* the other account. */
fun RecentTransaction.transferRoute(): TransferRoute? {
    val link = transfer ?: return null
    val account = link.linkedAccountName ?: return TransferRoute.Unnamed
    return if (direction == Direction.DEBIT) TransferRoute.To(account) else TransferRoute.From(account)
}
