package dev.otherworld.budget.ui.overview

import androidx.annotation.StringRes
import dev.otherworld.budget.R

/**
 * The label for a budget line whose period isn't the month, or null for a monthly line. The
 * server mirrors the web Budget page, so a weekly, quarterly or yearly line's figures cover its
 * own period (the week containing the 15th, the calendar quarter, the calendar year), not the
 * budget month the rest of the card shows. Without the label those figures read as monthly. An
 * unknown period gets no label rather than a guess.
 */
@StringRes
fun budgetPeriodLabelRes(period: String): Int? = when (period) {
    "weekly" -> R.string.overview_period_weekly
    "quarterly" -> R.string.overview_period_quarterly
    "yearly" -> R.string.overview_period_yearly
    else -> null
}
