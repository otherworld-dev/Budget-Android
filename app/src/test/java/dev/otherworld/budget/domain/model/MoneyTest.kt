package dev.otherworld.budget.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `parses a plain decimal string from the server`() {
        assertEquals(BigDecimal("24.31"), Money.parse("24.31", "GBP")!!.amount)
    }

    @Test
    fun `parses comma as a decimal separator`() {
        assertEquals(BigDecimal("24.31"), Money.parse("24,31", "EUR")!!.amount)
    }

    @Test
    fun `strips currency symbols and whitespace the user may type`() {
        assertEquals(BigDecimal("24.31"), Money.parse(" £24.31 ", "GBP")!!.amount)
    }

    @Test
    fun `strips thousands separators`() {
        assertEquals(BigDecimal("1234.56"), Money.parse("1,234.56", "GBP")!!.amount)
    }

    @Test
    fun `returns null for unparseable input rather than throwing`() {
        assertNull(Money.parse("", "GBP"))
        assertNull(Money.parse("abc", "GBP"))
        assertNull(Money.parse("12.34.56", "GBP"))
    }

    @Test
    fun `preserves a leading minus sign for a refund`() {
        assertEquals(BigDecimal("-5.00"), Money.parse("-5.00", "GBP")!!.amount)
    }

    @Test
    fun `preserves sign through thousands separators`() {
        assertEquals(BigDecimal("-1234.56"), Money.parse("-1,234.56", "GBP")!!.amount)
    }

    @Test
    fun `returns null for a minus sign that is not a leading sign`() {
        assertNull(Money.parse("5-00", "GBP"))
        assertNull(Money.parse("5.00-", "GBP"))
    }

    @Test
    fun `returns null for a bare minus sign`() {
        assertNull(Money.parse("-", "GBP"))
    }

    @Test
    fun `does not lose precision through a float`() {
        // 0.1 + 0.2 must be exactly 0.30, which a Double cannot represent.
        val sum = Money.parse("0.10", "GBP")!!.amount + Money.parse("0.20", "GBP")!!.amount
        assertEquals(BigDecimal("0.30"), sum)
    }
}
