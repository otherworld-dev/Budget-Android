package dev.otherworld.budget.data.local

import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.SplitPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class SplitCodecTest {
    @Test fun `a two-part split survives a persist round-trip`() {
        val parts = listOf(
            SplitPart(Money(BigDecimal("6.00"), "GBP"), 14, "A"),
            SplitPart(Money(BigDecimal("4.00"), "GBP"), null, "B"),
        )

        assertEquals(parts, SplitCodec.decode(SplitCodec.encode(parts)))
    }

    @Test fun `garbage input decodes to null instead of throwing`() {
        assertNull(SplitCodec.decode("garbage"))
    }
}
