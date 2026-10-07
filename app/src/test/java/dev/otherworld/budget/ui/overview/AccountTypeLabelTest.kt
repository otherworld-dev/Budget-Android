package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTypeLabelTest {

    @Test fun `every type the server defines has its own string`() {
        assertEquals(R.string.account_type_checking, accountTypeLabelRes("checking"))
        assertEquals(R.string.account_type_savings, accountTypeLabelRes("savings"))
        assertEquals(R.string.account_type_credit_card, accountTypeLabelRes("credit_card"))
        assertEquals(R.string.account_type_investment, accountTypeLabelRes("investment"))
        assertEquals(R.string.account_type_loan, accountTypeLabelRes("loan"))
        assertEquals(R.string.account_type_cash, accountTypeLabelRes("cash"))
        assertEquals(R.string.account_type_money_market, accountTypeLabelRes("money_market"))
        assertEquals(R.string.account_type_cryptocurrency, accountTypeLabelRes("cryptocurrency"))
        assertEquals(R.string.account_type_mortgage, accountTypeLabelRes("mortgage"))
        assertEquals(R.string.account_type_line_of_credit, accountTypeLabelRes("line_of_credit"))
    }

    @Test fun `a blank type is labelled Other`() {
        assertEquals(R.string.overview_accounts_other, accountTypeLabelRes(""))
        assertEquals(R.string.overview_accounts_other, accountTypeLabelRes("  "))
    }

    @Test fun `an unknown type has no string and is humanised instead`() {
        assertNull(accountTypeLabelRes("pension"))
        assertEquals("Pension", humaniseAccountType("pension"))
        assertEquals("Store card", humaniseAccountType("store_card"))
    }
}
