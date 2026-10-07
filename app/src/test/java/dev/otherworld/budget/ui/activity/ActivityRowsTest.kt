package dev.otherworld.budget.ui.activity

import dev.otherworld.budget.domain.model.Direction
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.RecentTransaction
import dev.otherworld.budget.domain.model.TransferLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ActivityRowsTest {
    private fun tx(id: Long, direction: Direction = Direction.UNKNOWN, transfer: TransferLink? = null) =
        RecentTransaction(
            id = id,
            merchant = "Merchant $id",
            date = LocalDate.of(2026, 3, 1),
            amount = Money(BigDecimal("10.00"), "GBP"),
            accountName = "Account",
            direction = direction,
            transfer = transfer,
        )

    @Test fun `a debit and credit pair collapse into one row from the debit side`() {
        val debit = tx(1, Direction.DEBIT, TransferLink(2, "Joint Account"))
        val credit = tx(2, Direction.CREDIT, TransferLink(1, "Current Account"))
        val rows = buildActivityRows(listOf(debit, credit))
        assertEquals(1, rows.size)
        val pair = rows.single() as ActivityRow.TransferPair
        assertEquals(debit, pair.from)
        assertEquals(credit, pair.to)
    }

    @Test fun `the pair takes the position of the half listed first`() {
        // Only the credit half carries a link (one-directional); the debit half links back to
        // nothing. A plain single sits between the two positions the pair could otherwise land on.
        val credit = tx(2, Direction.CREDIT, TransferLink(1, null))
        val single = tx(3)
        val debit = tx(1, Direction.DEBIT)
        val rows = buildActivityRows(listOf(credit, single, debit))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is ActivityRow.TransferPair)
        assertEquals(single, (rows[1] as ActivityRow.Single).tx)
    }

    @Test fun `credit half listed first still makes the debit half the source`() {
        // The credit half (listed first) carries no link at all -- only the later debit half
        // points back at it. Pairing must still be found from the debit's one-sided link.
        val credit = tx(2, Direction.CREDIT)
        val debit = tx(1, Direction.DEBIT, TransferLink(2, "Current Account"))
        val rows = buildActivityRows(listOf(credit, debit))
        assertEquals(1, rows.size)
        val pair = rows.single() as ActivityRow.TransferPair
        assertEquals(debit, pair.from)
        assertEquals(credit, pair.to)
    }

    @Test fun `a transfer whose partner is missing stays a single row`() {
        val lonely = tx(1, Direction.DEBIT, TransferLink(999, "Somewhere else"))
        val rows = buildActivityRows(listOf(lonely))
        assertEquals(listOf(ActivityRow.Single(lonely)), rows)
    }

    @Test fun `a linked id pointing at an absent row stays single`() {
        // A dangling link must not accidentally latch onto some unrelated row in the list.
        val dangling = tx(1, Direction.DEBIT, TransferLink(555, "Somewhere else"))
        val other = tx(2, Direction.CREDIT)
        val rows = buildActivityRows(listOf(dangling, other))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ActivityRow.Single })
    }

    @Test fun `a pair with no debit half uses the first half as source`() {
        val first = tx(1, Direction.UNKNOWN, TransferLink(2, null))
        val second = tx(2, Direction.UNKNOWN, TransferLink(1, null))
        val rows = buildActivityRows(listOf(first, second))
        assertEquals(1, rows.size)
        val pair = rows.single() as ActivityRow.TransferPair
        assertEquals(first, pair.from)
        assertEquals(second, pair.to)
    }

    @Test fun `non-transfer rows keep their order`() {
        val a = tx(1)
        val b = tx(2)
        val c = tx(3)
        val rows = buildActivityRows(listOf(a, b, c))
        assertEquals(listOf(ActivityRow.Single(a), ActivityRow.Single(b), ActivityRow.Single(c)), rows)
    }

    @Test fun `a transfer linked to itself stays a single row`() {
        val selfLinked = tx(1, Direction.DEBIT, TransferLink(1, "Somewhere else"))
        val rows = buildActivityRows(listOf(selfLinked))
        assertEquals(listOf(ActivityRow.Single(selfLinked)), rows)
    }

    @Test fun `a duplicated id is emitted once`() {
        // Two rows share id 1 but differ in content, so the assertion actually proves which one
        // survives (the first), rather than merely that some row with id 1 remains.
        val first = tx(1).copy(merchant = "First")
        val duplicate = tx(1).copy(merchant = "Second")
        val other = tx(2)
        val rows = buildActivityRows(listOf(first, duplicate, other))
        assertEquals(listOf(ActivityRow.Single(first), ActivityRow.Single(other)), rows)
    }
    @Test fun `a lone outgoing transfer names the account it went to`() {
        val out = tx(1, Direction.DEBIT, TransferLink(9, "Savings"))
        assertEquals(TransferRoute.To("Savings"), out.transferRoute())
    }

    @Test fun `a lone incoming transfer names the account it came from`() {
        val incoming = tx(1, Direction.CREDIT, TransferLink(9, "Current"))
        assertEquals(TransferRoute.From("Current"), incoming.transferRoute())
    }

    @Test fun `a transfer to an account the user can't see stays unnamed`() {
        val hidden = tx(1, Direction.DEBIT, TransferLink(9, null))
        assertEquals(TransferRoute.Unnamed, hidden.transferRoute())
    }

    @Test fun `an ordinary transaction has no transfer route`() {
        assertEquals(null, tx(1, Direction.DEBIT).transferRoute())
    }
}
