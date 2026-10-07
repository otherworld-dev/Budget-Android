package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetPeriodLabelTest {
    @Test fun `monthly lines have no period so far`() = assertNull(periodToDateLabelRes("monthly"))

    @Test fun `a weekly line is the month's share like any other, so it gets no label`() =
        assertNull(periodToDateLabelRes("weekly"))

    @Test fun `quarterly and yearly lines say which period the so-far figures cover`() {
        assertEquals(R.string.overview_budget_this_quarter, periodToDateLabelRes("quarterly"))
        assertEquals(R.string.overview_budget_this_year, periodToDateLabelRes("yearly"))
    }

    @Test fun `an unknown period gets no label rather than a guess`() = assertNull(periodToDateLabelRes("fortnightly"))
}
