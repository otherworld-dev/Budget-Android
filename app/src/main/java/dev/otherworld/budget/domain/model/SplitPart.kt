package dev.otherworld.budget.domain.model

/** One category allocation of a split transaction, ready to send. */
data class SplitPart(val amount: Money, val categoryId: Long?, val description: String?)

/**
 * A fixed row of a splittable receipt. An *item* is [categorisable] -- the user assigns it a
 * category. The tax line and a savings/discount line are not: tax has no meaningful category, and
 * where a whole-basket discount belongs is not something to guess, so both post uncategorised. A
 * savings row's [amount] is negative.
 */
data class SplitRow(val amount: Money, val description: String, val categorisable: Boolean)
