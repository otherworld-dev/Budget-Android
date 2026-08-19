package dev.otherworld.budget.domain.model

import java.math.BigDecimal

/**
 * Decides whether a receipt can be split by item, and builds the fixed rows when it can. The rules
 * the server enforces -- the parts sum exactly to the transaction amount, at least two of them, no
 * zero part -- are checked here first, so the client only ever offers, and only ever sends, a set
 * the server will accept.
 *
 * Four shapes reconcile, and they are checked in this order; the first that matches wins:
 *
 *  1. `items            == total`  -- tax-inclusive prices (the common UK case). Any VAT the receipt
 *     printed is already in the item prices, so it is *not* added again as a row.
 *  2. `items + tax      == total`  -- tax printed separately, added as its own uncategorised part.
 *  3. `items - discount == total`  -- a loyalty/coupon/multibuy saving, sent as a negative part.
 *  4. `items - discount + tax == total` -- both.
 *
 * The savings row is negative, described "Savings", uncategorised; the tax row is positive,
 * "Tax", uncategorised. Sums are exact [BigDecimal] arithmetic compared with `compareTo` (never a
 * float, and equivalent to comparing integer minor units). A [tax] or [discount] that is null or
 * non-positive is treated as absent -- a mis-read negative "tax" or a zero discount is never a
 * reconciling line. Zero-amount item lines are dropped (the server rejects a zero part); a line
 * with an unreadable amount makes the receipt unsplittable.
 *
 * When none of the four match, the receipt was probably misread: [rows] returns null and the caller
 * shows "these items don't add up to the total" rather than offering a split the server would 400.
 */
object SplitPlan {
    fun rows(lineItems: List<LineItem>, tax: Money?, discount: Money?, total: Money): List<SplitRow>? {
        if (lineItems.isEmpty()) return null

        val itemRows = buildList {
            for (item in lineItems) {
                val amount = item.amount ?: return null          // unreadable -> can't reconcile
                if (amount.amount.signum() != 0) {               // a zero line is dropped, not sent
                    // A negative line (a saving the receipt printed as its own line, not in the
                    // discount field) still closes the reconciliation, but must be sent as a
                    // negative, uncategorised part -- never a categorised item.
                    add(SplitRow(amount, item.description, categorisable = amount.amount.signum() > 0))
                }
            }
        }
        if (itemRows.isEmpty()) return null

        val itemsSum = itemRows.fold(BigDecimal.ZERO) { acc, row -> acc.add(row.amount.amount) }
        val t = total.amount
        val taxAmt = tax?.amount?.takeIf { it.signum() > 0 }
        val discAmt = discount?.amount?.takeIf { it.signum() > 0 }
        val savingsRow = discAmt?.let { SplitRow(Money(it.negate(), total.currency), "Savings", categorisable = false) }
        val taxRow = taxAmt?.let { SplitRow(Money(it, total.currency), "Tax", categorisable = false) }

        val rows = when {
            itemsSum.compareTo(t) == 0 -> itemRows
            taxAmt != null && itemsSum.add(taxAmt).compareTo(t) == 0 -> itemRows + taxRow!!
            discAmt != null && itemsSum.subtract(discAmt).compareTo(t) == 0 -> itemRows + savingsRow!!
            taxAmt != null && discAmt != null &&
                itemsSum.subtract(discAmt).add(taxAmt).compareTo(t) == 0 -> itemRows + savingsRow!! + taxRow!!
            else -> return null
        }
        return rows.takeIf { it.size >= 2 }
    }
}
