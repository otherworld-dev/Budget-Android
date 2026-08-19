package dev.otherworld.budget.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SplitPlanTest {
    private fun gbp(v: String) = Money(BigDecimal(v), "GBP")
    private fun item(desc: String, v: String?) = LineItem(desc, v?.let(::gbp))

    // helper: rows(items, tax, discount, total)
    private fun rows(items: List<LineItem>, tax: String? = null, discount: String? = null, total: String) =
        SplitPlan.rows(items, tax?.let(::gbp), discount?.let(::gbp), gbp(total))

    @Test fun `shape 1 -- items already equal the total (tax-inclusive) split on items alone`() {
        // A real Blue Banana receipt: the item prices sum to the total, VAT embedded, and the OCR
        // still returns a positive VAT line. Adding it would overshoot; split the items alone.
        val r = rows(listOf(item("A", "35.00"), item("B", "44.94")), tax = "13.32", total = "79.94")!!
        assertEquals(2, r.size)
        assertTrue(r.all { it.categorisable })   // no tax row
    }

    @Test fun `shape 2 -- items plus a separate tax reconcile with a tax row`() {
        val r = rows(listOf(item("A", "3.40"), item("B", "18.95")), tax = "1.42", total = "23.77")!!
        assertEquals(3, r.size)
        val taxRow = r.last()
        assertEquals("Tax", taxRow.description)
        assertEquals(false, taxRow.categorisable)
        assertEquals(BigDecimal("1.42"), taxRow.amount.amount)
    }

    @Test fun `shape 3 -- a loyalty saving reconciles with a negative Savings row`() {
        // items 41.63, savings 4.50, paid 37.13.
        val r = rows(listOf(item("A", "20.00"), item("B", "21.63")), discount = "4.50", total = "37.13")!!
        assertEquals(3, r.size)
        val savings = r.last()
        assertEquals("Savings", savings.description)
        assertEquals(false, savings.categorisable)
        assertEquals(-1, savings.amount.amount.signum())              // negative part
        assertEquals(BigDecimal("-4.50"), savings.amount.amount)
        // The whole set sums to the total.
        assertEquals(0, r.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount.amount) }.compareTo(BigDecimal("37.13")))
    }

    @Test fun `shape 4 -- both a saving and a separate tax reconcile`() {
        // items 40.00, - discount 5.00, + tax 2.00 = 37.00.
        val r = rows(listOf(item("A", "18.00"), item("B", "22.00")), tax = "2.00", discount = "5.00", total = "37.00")!!
        assertEquals(4, r.size)
        assertTrue(r.any { it.description == "Savings" && it.amount.amount.signum() < 0 })
        assertTrue(r.any { it.description == "Tax" && it.amount.amount.signum() > 0 })
        assertEquals(0, r.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount.amount) }.compareTo(BigDecimal("37.00")))
    }

    @Test fun `a tax-inclusive receipt with no printed tax reconciles on items alone`() {
        val r = rows(listOf(item("A", "4.00"), item("B", "5.75")), total = "9.75")!!
        assertEquals(2, r.size)
        assertTrue(r.all { it.categorisable })
    }

    @Test fun `a receipt that matches none of the four shapes is not splittable`() {
        // items 47.43, total 40.63, and no tax/discount that closes the 6.80 gap.
        assertNull(rows(listOf(item("A", "29.43"), item("B", "18.00")), total = "40.63"))
    }

    @Test fun `a negative tax -- a discount the OCR mis-read into the tax field -- is ignored`() {
        assertNull(rows(listOf(item("A", "29.43"), item("B", "18.00")), tax = "-12.80", total = "40.63"))
    }

    @Test fun `a zero discount is treated as absent`() {
        // items == total already (shape 1); a 0.00 discount must not knock it out.
        val r = rows(listOf(item("A", "4.00"), item("B", "5.75")), discount = "0.00", total = "9.75")!!
        assertEquals(2, r.size)
    }

    @Test fun `a zero-amount item line is dropped, not sent as a zero part`() {
        // A 0.00 line (a free item / a mis-read) is excluded; the rest still reconcile.
        val r = rows(listOf(item("A", "9.75"), item("Free", "0.00"), item("B", "0.00").let { it }), total = "9.75")
        // Only one non-zero item -> fewer than two parts -> not splittable.
        assertNull(r)
        // But two non-zero items plus a dropped zero line do split.
        val r2 = rows(listOf(item("A", "4.00"), item("Free", "0.00"), item("B", "5.75")), total = "9.75")!!
        assertEquals(2, r2.size)
        assertTrue(r2.none { it.amount.amount.signum() == 0 })
    }

    @Test fun `a null line-item amount blocks splitting`() {
        assertNull(rows(listOf(item("A", "3.40"), item("B", null)), total = "3.40"))
    }

    @Test fun `fewer than two parts is not splittable`() {
        assertNull(rows(listOf(item("A", "9.75")), total = "9.75"))
    }

    @Test fun `no line items is not splittable`() {
        assertNull(rows(emptyList(), total = "9.75"))
    }

    @Test fun `scale differences do not defeat reconciliation`() {
        assertEquals(2, rows(listOf(item("A", "3.4"), item("B", "6.35")), total = "9.75")!!.size)
    }

    @Test fun `a single item plus a positive tax is two parts and splits`() {
        val r = rows(listOf(item("A", "10.00")), tax = "2.00", total = "12.00")!!
        assertEquals(2, r.size)
        assertTrue(r.last().description == "Tax")
    }

    @Test fun `a negative line item -- a discount printed as its own line -- is kept but uncategorised`() {
        // Some receipts print a multibuy/loyalty saving as its own negative line rather than in the
        // discount field: items 10.00 + (-2.00) == 8.00 (shape 1). The negative line must be kept
        // (it closes the reconciliation) but sent as a negative, *uncategorised* part -- never a
        // categorised item, which would file a saving under a spending category.
        val r = rows(listOf(item("Milk", "10.00"), item("Multibuy saving", "-2.00")), total = "8.00")!!
        assertEquals(2, r.size)
        val saving = r.single { it.amount.amount.signum() < 0 }
        assertEquals(false, saving.categorisable)                     // posted uncategorised
        assertEquals(BigDecimal("-2.00"), saving.amount.amount)
        val milk = r.single { it.amount.amount.signum() > 0 }
        assertTrue(milk.categorisable)                                // the real item stays categorisable
        assertEquals(0, r.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount.amount) }.compareTo(BigDecimal("8.00")))
    }
}
