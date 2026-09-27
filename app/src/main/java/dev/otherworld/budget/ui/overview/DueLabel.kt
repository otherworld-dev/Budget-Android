package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.domain.model.UpcomingBill
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How soon [UpcomingBill.nextDueDate] falls, relative to "today" -- the wording the Overview
 * screen shows against each bill (spec §1.3/§2: "Overdue" / "Today" / "Tomorrow" / "in N days").
 */
sealed interface DueLabel {
    data object Overdue : DueLabel
    data object Today : DueLabel
    data object Tomorrow : DueLabel
    data class InDays(val days: Int) : DueLabel
}

/**
 * [UpcomingBill.overdue] is the server's own verdict and wins outright -- it may know things a
 * bare date comparison can't (e.g. a due date pushed out after a failed autopay). But a due date
 * that has already passed is always [DueLabel.Overdue] even when that flag is false, since the
 * alternative would be calling a bill from last week "in -3 days".
 */
fun dueLabel(bill: UpcomingBill, today: LocalDate): DueLabel = when {
    bill.overdue || bill.nextDueDate.isBefore(today) -> DueLabel.Overdue
    bill.nextDueDate.isEqual(today) -> DueLabel.Today
    bill.nextDueDate.isEqual(today.plusDays(1)) -> DueLabel.Tomorrow
    else -> DueLabel.InDays(ChronoUnit.DAYS.between(today, bill.nextDueDate).toInt())
}
