package dev.otherworld.budget.ui.overview

import androidx.annotation.StringRes
import dev.otherworld.budget.R

/**
 * The "£400.00 of £1,200.00 this year" line for a budget line's [dev.otherworld.budget.domain.model.PeriodToDate],
 * or null for a period that has none. The line's own figures are the month's share whatever its
 * period, as on the web Budget page, so a weekly line needs nothing extra; only a quarterly or
 * yearly one carries its whole period so far. An unknown period gets no label rather than a guess.
 */
@StringRes
fun periodToDateLabelRes(period: String): Int? = when (period) {
    "quarterly" -> R.string.overview_budget_this_quarter
    "yearly" -> R.string.overview_budget_this_year
    else -> null
}
