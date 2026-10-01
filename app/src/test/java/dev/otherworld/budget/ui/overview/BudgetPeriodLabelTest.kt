package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetPeriodLabelTest {
    @Test fun `monthly lines need no label`() = assertNull(budgetPeriodLabelRes("monthly"))

    @Test fun `weekly, quarterly and yearly lines say which period they cover`() {
        assertEquals(R.string.overview_period_weekly, budgetPeriodLabelRes("weekly"))
        assertEquals(R.string.overview_period_quarterly, budgetPeriodLabelRes("quarterly"))
        assertEquals(R.string.overview_period_yearly, budgetPeriodLabelRes("yearly"))
    }

    @Test fun `an unknown period gets no label rather than a guess`() = assertNull(budgetPeriodLabelRes("fortnightly"))
}
