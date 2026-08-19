package dev.otherworld.budget.data.local

import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.domain.model.LineItem
import dev.otherworld.budget.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class DraftCodecTest {
    @Test fun `subtotal, tax and discount survive a persist round-trip`() {
        val draft = DraftTransaction(
            total = Money(BigDecimal("37.13"), "GBP"),
            subtotal = Money(BigDecimal("22.35"), "GBP"),
            tax = Money(BigDecimal("1.42"), "GBP"),
            discount = Money(BigDecimal("4.50"), "GBP"),
        )
        val back = DraftCodec.decode(DraftCodec.encode(draft))!!
        assertEquals(BigDecimal("22.35"), back.subtotal!!.amount)
        assertEquals(BigDecimal("1.42"), back.tax!!.amount)
        assertEquals(BigDecimal("4.50"), back.discount!!.amount)
    }

    @Test fun `a non-GBP currency survives a persist round-trip`() {
        val draft = DraftTransaction(total = Money(BigDecimal("9.90"), "EUR"))
        val back = DraftCodec.decode(DraftCodec.encode(draft))!!
        assertEquals("EUR", back.total!!.currency)
    }

    // FallbackCurrencyCache is deliberate that a currency is never guessed -- inventing one
    // (the old "GBP" default) could mis-label and, via createTransaction, mis-save an amount.
    // A persisted row that carries money but no currency is therefore malformed, not GBP.
    @Test fun `an amount with no persisted currency decodes to null rather than guessing GBP`() {
        assertNull(DraftCodec.decode("""{"total":"12.34"}"""))
    }

    @Test fun `a line item amount with no persisted currency decodes to null`() {
        assertNull(DraftCodec.decode("""{"lineItems":[{"description":"Milk","amount":"1.20"}]}"""))
    }

    // The "never throws on malformed input" contract, previously only pinned for SplitCodec.
    @Test fun `garbage input decodes to null instead of throwing`() {
        assertNull(DraftCodec.decode("not json at all"))
    }

    @Test fun `an unparseable date decodes to a null date instead of throwing`() {
        val back = DraftCodec.decode("""{"merchant":"Corner Shop","date":"31st of Never"}""")
        assertNotNull(back)
        assertEquals("Corner Shop", back!!.merchant)
        assertNull(back.date)
    }

    // A currency is only needed when there is money to label; a currency-less row with no
    // monetary fields is still a valid draft (a merchant/date the user is about to complete).
    @Test fun `a draft with no monetary fields decodes even without a currency`() {
        val back = DraftCodec.decode("""{"merchant":"Corner Shop"}""")
        assertNotNull(back)
        assertEquals("Corner Shop", back!!.merchant)
        assertNull(back.total)
    }

    @Test fun `an unpriced line item survives without a currency`() {
        val draft = DraftTransaction(
            merchant = "Corner Shop",
            lineItems = listOf(LineItem("Milk", amount = null)),
        )
        val back = DraftCodec.decode(DraftCodec.encode(draft))!!
        assertEquals(1, back.lineItems.size)
        assertEquals("Milk", back.lineItems.first().description)
        assertNull(back.lineItems.first().amount)
    }
}
