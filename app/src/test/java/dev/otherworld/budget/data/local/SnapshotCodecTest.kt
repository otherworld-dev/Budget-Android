package dev.otherworld.budget.data.local

import dev.otherworld.budget.data.remote.fake.FakeCheckData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Round-trips every [SnapshotKind]'s payload through [SnapshotCodec] using [FakeCheckData], the
 * same fixture the fake API answers with -- so a codec bug here is exactly the shape of data the
 * cache will actually see. `decode*` must never throw, malformed input included.
 */
class SnapshotCodecTest {

    @Test fun `accounts round-trip, including a negative balance and a null balance`() {
        val back = SnapshotCodec.decodeAccounts(SnapshotCodec.encodeAccounts(FakeCheckData.accounts))!!
        assertEquals(FakeCheckData.accounts.size, back.size)
        assertEquals(FakeCheckData.accounts, back)
        // FakeCheckData.accounts[1] ("Joint Account") carries a negative balance.
        assertEquals(BigDecimal("-45.10"), back[1].balance!!.amount)
    }

    @Test fun `categories round-trip`() {
        val categories = listOf(
            dev.otherworld.budget.domain.model.Category(14, "Groceries", null),
            dev.otherworld.budget.domain.model.Category(15, "Household", 14),
        )
        val back = SnapshotCodec.decodeCategories(SnapshotCodec.encodeCategories(categories))!!
        assertEquals(categories, back)
    }

    @Test fun `capabilities round-trip`() {
        val capabilities = dev.otherworld.budget.data.remote.Capabilities(
            ocrAvailable = true, currency = "GBP", version = "2.41.0",
            splitsAvailable = true, checkAvailable = true,
        )
        val back = SnapshotCodec.decodeCapabilities(SnapshotCodec.encodeCapabilities(capabilities))!!
        assertEquals(capabilities, back)
    }

    @Test fun `budget status round-trips, including a negative remaining line`() {
        val back = SnapshotCodec.decodeBudget(SnapshotCodec.encodeBudget(FakeCheckData.budget))!!
        assertEquals(FakeCheckData.budget, back)
        // The Groceries line is overspent -- remaining is negative and must survive as sent.
        assertEquals(BigDecimal("-31.20"), back.lines.first { it.categoryId == 14L }.remaining.amount)
    }

    @Test fun `upcoming bills round-trip`() {
        val back = SnapshotCodec.decodeBills(SnapshotCodec.encodeBills(FakeCheckData.bills))!!
        assertEquals(FakeCheckData.bills, back)
    }

    @Test fun `recent transactions round-trip, including splits and a transfer pair`() {
        val back = SnapshotCodec.decodeRecent(SnapshotCodec.encodeRecent(FakeCheckData.recent))!!
        assertEquals(FakeCheckData.recent, back)

        val split = back.first { it.id == 9001L }
        assertEquals(2, split.splits.size)
        assertEquals("Groceries", split.splits[0].categoryName)

        val transferOut = back.first { it.id == 9003L }
        assertEquals(9004L, transferOut.transfer!!.linkedTransactionId)
        assertEquals("Joint Account", transferOut.transfer!!.linkedAccountName)
    }

    @Test fun `malformed json decodes to null rather than throwing`() {
        assertNull(SnapshotCodec.decodeBudget("not json"))
        assertNull(SnapshotCodec.decodeAccounts("not json"))
        assertNull(SnapshotCodec.decodeCategories("not json"))
        assertNull(SnapshotCodec.decodeCapabilities("not json"))
        assertNull(SnapshotCodec.decodeBills("not json"))
        assertNull(SnapshotCodec.decodeRecent("not json"))
    }
}
