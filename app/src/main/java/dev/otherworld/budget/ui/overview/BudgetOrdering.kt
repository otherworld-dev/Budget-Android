package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import java.math.BigDecimal
import java.math.MathContext

/**
 * Where a [BudgetLine] ranks by how close it is to (or past) running out. A plain ratio can't
 * represent "no budget was set" without falling back to a [Double] infinity, so that edge case
 * gets its own rank instead: [NegativeInfinity] sorts before every [Ratio] and [PositiveInfinity]
 * sorts after every [Ratio], exactly where -/+ infinity would fall in a real division, but
 * without ever constructing a floating-point value.
 */
private sealed interface RiskRank : Comparable<RiskRank> {
    data object NegativeInfinity : RiskRank
    data class Ratio(val value: BigDecimal) : RiskRank
    data object PositiveInfinity : RiskRank

    override fun compareTo(other: RiskRank): Int = when {
        this is NegativeInfinity -> if (other is NegativeInfinity) 0 else -1
        other is NegativeInfinity -> 1
        this is PositiveInfinity -> if (other is PositiveInfinity) 0 else 1
        other is PositiveInfinity -> -1
        else -> (this as Ratio).value.compareTo((other as Ratio).value)
    }
}

/**
 * `remaining / budgeted`, computed as exact [BigDecimal] division ([MathContext.DECIMAL64]) --
 * never re-derived from `budgeted - spent` (see [BudgetLine.remaining]). A `budgeted` of zero or
 * less can't be divided by, so it ranks as [RiskRank.NegativeInfinity] when [BudgetLine.remaining]
 * is already negative (there is no budget left and spending has gone past it), or
 * [RiskRank.PositiveInfinity] otherwise (no budget set, but nothing overspent either).
 */
private fun BudgetLine.riskRank(): RiskRank = when {
    budgeted.amount.signum() <= 0 ->
        if (remaining.amount.signum() < 0) RiskRank.NegativeInfinity else RiskRank.PositiveInfinity
    else -> RiskRank.Ratio(remaining.amount.divide(budgeted.amount, MathContext.DECIMAL64))
}

/**
 * Expense lines with a budget, closest to running out first (lowest `remaining / budgeted`
 * ratio; see [riskRank] for the zero-budget edge case). Income lines, and expense lines with
 * neither a budgeted amount nor any spending, are excluded -- there is nothing to be "at risk"
 * of. Ties are broken by [BudgetLine.name], case-insensitively, since there's no other ordering
 * signal for two categories at the same risk.
 */
fun BudgetStatus.byRisk(): List<BudgetLine> = lines
    .filter { it.type == "expense" && (it.budgeted.amount.signum() > 0 || it.spent.amount.signum() > 0) }
    .sortedWith(riskOrder)

private val riskOrder = compareBy<BudgetLine> { it.riskRank() }.thenBy { it.name.lowercase() }

/**
 * The [count] riskiest lines from [byRisk], for a compact Overview card -- leaving out any line
 * whose subcategories are listed too. The server mirrors the web Budget page, where a parent's
 * budgeted and spent already include its children's, so a parent beside its own children would
 * show the same money twice.
 */
fun BudgetStatus.atRisk(count: Int = 3): List<BudgetLine> {
    val ranked = byRisk()
    val parents = ranked.mapNotNullTo(HashSet()) { it.parentId }
    return ranked.filter { it.categoryId !in parents }.take(count)
}

/** One row of the Budget detail list: a line and how deep it sits under its parents (0 = top). */
data class BudgetTreeRow(val line: BudgetLine, val depth: Int)

/**
 * [byRisk]'s lines arranged as the web Budget page's tree: top-level lines closest to running out
 * first, each followed by its subcategories (by the same order) one level deeper. A line whose
 * parent isn't in the list -- `parent_id` names the Budget page's parent, which has no line of
 * its own when it has no budget -- is shown at the top level. Each line appears exactly once,
 * even if the parent links were ever to form a cycle.
 */
fun BudgetStatus.byRiskTree(): List<BudgetTreeRow> {
    val ranked = byRisk()
    val ids = ranked.mapTo(HashSet()) { it.categoryId }
    val children = ranked.filter { it.parentId in ids }.groupBy { it.parentId }
    val rows = mutableListOf<BudgetTreeRow>()
    val placed = HashSet<Long>()

    fun place(line: BudgetLine, depth: Int) {
        if (!placed.add(line.categoryId)) return
        rows += BudgetTreeRow(line, depth)
        children[line.categoryId].orEmpty().forEach { place(it, depth + 1) }
    }

    ranked.filter { it.parentId !in ids }.forEach { place(it, 0) }
    // Only lines caught in a parent cycle are left unplaced; list them rather than drop them.
    ranked.forEach { place(it, 0) }
    return rows
}
