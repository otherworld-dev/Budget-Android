package dev.otherworld.budget.domain.model

import java.time.LocalDate

data class DraftTransaction(
    val merchant: String? = null,
    val date: LocalDate? = null,
    val total: Money? = null,
    val suggestedCategoryId: Long? = null,
    val lineItems: List<LineItem> = emptyList(),
    /** Persisted for completeness/forward use -- only [tax] currently feeds SplitPlan. */
    val subtotal: Money? = null,
    val tax: Money? = null,
    /** Total loyalty/coupon/multibuy saving on the receipt (always positive), or null when none. */
    val discount: Money? = null,
)
