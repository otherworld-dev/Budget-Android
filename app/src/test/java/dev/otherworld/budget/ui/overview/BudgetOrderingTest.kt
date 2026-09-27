package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BudgetOrderingTest {
    private fun money(v: String) = Money(BigDecimal(v), "GBP")

    private fun line(name: String, type: String = "expense", budgeted: String, spent: String, remaining: String) =
        BudgetLine(
            categoryId = name.hashCode().toLong(),
            name = name,
            parentId = null,
            type = type,
            period = "monthly",
            budgeted = money(budgeted),
            carried = money("0.00"),
            spent = money(spent),
            remaining = money(remaining),
            shared = false,
        )

    private fun status(vararg lines: BudgetLine) = BudgetStatus(
        month = "2026-09",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 30),
        currency = "GBP",
        budgeted = money("0.00"),
        spent = money("0.00"),
        remaining = money("0.00"),
        lines = lines.toList(),
    )

    @Test fun `overspent comes first`() {
        val overspent = line("Groceries", budgeted = "100.00", spent = "120.00", remaining = "-20.00")
        val onTrack = line("Fuel", budgeted = "100.00", spent = "50.00", remaining = "50.00")
        assertEquals(listOf(overspent, onTrack), status(onTrack, overspent).byRisk())
    }

    @Test fun `lower remaining ratio before higher`() {
        val worse = line("A", budgeted = "100.00", spent = "90.00", remaining = "10.00") // ratio 0.10
        val better = line("B", budgeted = "100.00", spent = "20.00", remaining = "80.00") // ratio 0.80
        assertEquals(listOf(worse, better), status(better, worse).byRisk())
    }

    @Test fun `income lines are excluded`() {
        // Budgeted and spent are both positive so this would pass the amount filter -- exclusion
        // must come from the type check, not from having no budget or spend.
        val income = line("Salary", type = "income", budgeted = "2000.00", spent = "1800.00", remaining = "200.00")
        val expense = line("Fuel", budgeted = "100.00", spent = "50.00", remaining = "50.00")
        assertEquals(listOf(expense), status(income, expense).byRisk())
    }

    @Test fun `zero-budget line with spending sorts first`() {
        // A zero (or negative) budgeted amount ranks as -infinity when remaining is negative --
        // ahead of even a heavily overspent line with a real, finite ratio.
        val zeroBudget = line("Uncategorised", budgeted = "0.00", spent = "50.00", remaining = "-50.00")
        val overspent = line("Groceries", budgeted = "100.00", spent = "150.00", remaining = "-50.00") // ratio -0.5
        assertEquals(listOf(zeroBudget, overspent), status(overspent, zeroBudget).byRisk())
    }

    @Test fun `atRisk takes three`() {
        val lines = (1..5).map { i ->
            line("Cat$i", budgeted = "100.00", spent = "${i * 10}.00", remaining = "${100 - i * 10}.00")
        }
        val budget = status(*lines.toTypedArray())
        assertEquals(budget.byRisk().take(3), budget.atRisk())
    }
}
