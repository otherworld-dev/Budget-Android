package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.UpcomingBill
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DueLabelTest {
    private val today = LocalDate.of(2026, 9, 27)

    private fun bill(nextDueDate: LocalDate, overdue: Boolean) = UpcomingBill(
        id = 1,
        name = "Council Tax",
        amount = Money(BigDecimal("150.00"), "GBP"),
        nextDueDate = nextDueDate,
        overdue = overdue,
        frequency = "monthly",
        accountName = "Current Account",
        isTransfer = false,
        autoPay = true,
        shared = false,
    )

    @Test fun `past date is overdue even when the server flag is false`() {
        val b = bill(today.minusDays(3), overdue = false)
        assertEquals(DueLabel.Overdue, dueLabel(b, today))
    }

    @Test fun `server overdue flag wins`() {
        // A future due date that the server still calls overdue (e.g. a payment already known
        // to have failed) must show Overdue, not "in 5 days".
        val b = bill(today.plusDays(5), overdue = true)
        assertEquals(DueLabel.Overdue, dueLabel(b, today))
    }

    @Test fun today() {
        val b = bill(today, overdue = false)
        assertEquals(DueLabel.Today, dueLabel(b, today))
    }

    @Test fun tomorrow() {
        val b = bill(today.plusDays(1), overdue = false)
        assertEquals(DueLabel.Tomorrow, dueLabel(b, today))
    }

    @Test fun `five days`() {
        val b = bill(today.plusDays(5), overdue = false)
        assertEquals(DueLabel.InDays(5), dueLabel(b, today))
    }
}
