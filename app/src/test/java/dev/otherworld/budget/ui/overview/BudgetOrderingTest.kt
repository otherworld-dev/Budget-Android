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

    /** A line with an explicit id and parent, for the parent/child tests. */
    private fun node(id: Long, name: String, parentId: Long?, spent: String) = BudgetLine(
        categoryId = id,
        name = name,
        parentId = parentId,
        type = "expense",
        period = "monthly",
        budgeted = money("100.00"),
        carried = money("0.00"),
        spent = money(spent),
        remaining = money(BigDecimal("100.00").subtract(BigDecimal(spent)).toPlainString()),
        shared = false,
    )

    @Test fun `atRisk leaves out a parent whose subcategories are listed`() {
        // The server's parent line already includes its children's figures, so listing it beside
        // them would show the same money twice.
        val parent = node(1, "Home", parentId = null, spent = "99.00")
        val child = node(2, "Water", parentId = 1, spent = "50.00")
        val other = node(3, "Fuel", parentId = null, spent = "10.00")
        assertEquals(listOf(child, other), status(parent, child, other).atRisk())
    }

    @Test fun `atRisk keeps a parent with no listed subcategories`() {
        val parent = node(1, "Home", parentId = null, spent = "90.00")
        assertEquals(listOf(parent), status(parent).atRisk())
    }

    @Test fun `budget tree nests subcategories under their parent, each level by risk`() {
        val home = node(1, "Home", parentId = null, spent = "10.00")
        val fuel = node(2, "Fuel", parentId = null, spent = "90.00")
        val water = node(3, "Water", parentId = 1, spent = "20.00")
        val power = node(4, "Power", parentId = 1, spent = "80.00")
        assertEquals(
            listOf(BudgetTreeRow(fuel, 0), BudgetTreeRow(home, 0), BudgetTreeRow(power, 1), BudgetTreeRow(water, 1)),
            status(home, fuel, water, power).byRiskTree(),
        )
    }

    @Test fun `three levels nest with increasing depth`() {
        val bank = node(1, "Bank", parentId = null, spent = "0.00")
        val card = node(2, "Card", parentId = 1, spent = "0.00")
        val spotify = node(3, "Spotify", parentId = 2, spent = "0.00")
        assertEquals(
            listOf(BudgetTreeRow(bank, 0), BudgetTreeRow(card, 1), BudgetTreeRow(spotify, 2)),
            status(spotify, card, bank).byRiskTree(),
        )
    }

    @Test fun `a subcategory whose parent has no line of its own is a root`() {
        // parent_id names the Budget page's parent, which can be missing from the list when it has
        // no budget of its own.
        val orphan = node(5, "Snacks", parentId = 99, spent = "10.00")
        assertEquals(listOf(BudgetTreeRow(orphan, 0)), status(orphan).byRiskTree())
    }

    @Test fun `a parent cycle does not loop forever`() {
        val a = node(1, "A", parentId = 2, spent = "10.00")
        val b = node(2, "B", parentId = 1, spent = "20.00")
        val rows = status(a, b).byRiskTree()
        assertEquals(setOf(a, b), rows.map { it.line }.toSet())
        assertEquals(2, rows.size)
    }

    @Test fun `a month with no expense budgets has nothing to show`() {
        // A user who hasn't set budgets up still gets a status back (income lines, zero totals);
        // the card must say so rather than show "£0.00 left of £0.00".
        val income = line("Salary", type = "income", budgeted = "2000.00", spent = "0.00", remaining = "2000.00")
        assertEquals(false, status(income).hasBudgets())
        assertEquals(false, status().hasBudgets())
        assertEquals(true, status(line("Fuel", budgeted = "80.00", spent = "0.00", remaining = "80.00")).hasBudgets())
    }

    @Test fun `atRisk takes three`() {
        val lines = (1..5).map { i ->
            line("Cat$i", budgeted = "100.00", spent = "${i * 10}.00", remaining = "${100 - i * 10}.00")
        }
        val budget = status(*lines.toTypedArray())
        assertEquals(budget.byRisk().take(3), budget.atRisk())
    }
}
