package dev.otherworld.budget.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

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

    // --- Server figures --------------------------------------------------------------------

    @Test
    fun `a server figure keeps every decimal place its currency has`() {
        // The server writes money in the currency's own places: 8 for BTC, 3 for JOD.
        assertEquals(BigDecimal("0.02200000"), Money.fromServer("0.02200000", "BTC")!!.amount)
        assertEquals(BigDecimal("12.500"), Money.fromServer("12.500", "JOD")!!.amount)
        assertEquals(BigDecimal("-31.20"), Money.fromServer("-31.20", "GBP")!!.amount)
    }

    @Test
    fun `a server figure is never read as grouped thousands`() {
        // Typed, "1.234" is ambiguous; from the server it can only be one and a bit.
        assertEquals(BigDecimal("1.234"), Money.fromServer("1.234", "JOD")!!.amount)
    }

    @Test
    fun `a server figure that isn't a plain decimal is refused`() {
        assertNull(Money.fromServer("", "GBP"))
        assertNull(Money.fromServer("abc", "GBP"))
        assertNull(Money.fromServer("1,234.56", "GBP"))
        assertNull(Money.fromServer("£5.00", "GBP"))
    }

    // --- Formatting --------------------------------------------------------------------------

    @Test
    fun `a currency Java doesn't know is shown with its own code, not the phone's`() {
        // BTC isn't ISO 4217, so Currency.getInstance refuses it. That used to fall back to the
        // locale's currency, which showed 0.022 BTC as "£0.02".
        assertEquals("0.022 BTC", Money(BigDecimal("0.02200000"), "BTC").format(Locale.UK))
    }

    @Test
    fun `a currency Java doesn't know still shows at least two decimal places`() {
        assertEquals("1.50 ETH", Money(BigDecimal("1.50000000"), "ETH").format(Locale.UK))
    }
}
