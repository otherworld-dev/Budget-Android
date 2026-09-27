package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.remote.fake.FakeCheckData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeBudgetApiTest {

    @Test
    fun `returns accounts by default`() = runTest {
        val result = FakeBudgetApi().accounts()
        assertEquals(3, result.getOrThrow().size)
    }

    @Test
    fun `can be forced to report OCR unavailable`() = runTest {
        val api = FakeBudgetApi().apply { ocrAvailable = false }
        assertEquals(false, api.capabilities().getOrThrow().ocrAvailable)
    }

    @Test
    fun `extract surfaces the not-configured error when OCR is off`() = runTest {
        val api = FakeBudgetApi().apply { ocrAvailable = false }
        val error = api.extract(File("receipt.jpg")).exceptionOrNull()
        assertTrue(error is BudgetApiError.OcrNotConfigured)
    }

    @Test
    fun `extract can be forced to report quota exhaustion`() = runTest {
        val api = FakeBudgetApi().apply { nextError = BudgetApiError.OcrQuotaExhausted }
        assertTrue(api.extract(File("receipt.jpg")).exceptionOrNull() is BudgetApiError.OcrQuotaExhausted)
    }

    @Test
    fun `recentTransactions with a negative limit succeeds with an empty list`() = runTest {
        val result = FakeBudgetApi().recentTransactions(-1)
        assertEquals(emptyList<Any>(), result.getOrThrow())
    }

    @Test
    fun `recentTransactions honours a positive limit`() = runTest {
        val result = FakeBudgetApi().recentTransactions(1)
        assertEquals(1, result.getOrThrow().size)
    }

    private fun request(merchant: String = "Tesco") = CreateTransactionRequest(
        accountId = 1, categoryId = 14,
        date = java.time.LocalDate.of(2026, 3, 12),
        merchant = merchant,
        total = dev.otherworld.budget.domain.model.Money(java.math.BigDecimal("24.31"), "GBP"),
        photo = File("receipt.jpg"),
    )

    @Test
    fun `createTransaction records what it was given`() = runTest {
        val api = FakeBudgetApi()
        api.createTransaction(request(), "key-1").getOrThrow()
        assertEquals("Tesco", api.created.single().merchant)
        assertEquals(listOf("key-1"), api.idempotencyKeys)
    }

    @Test
    fun `replaying the same key returns the same id and creates nothing new`() = runTest {
        // The server's reserve-before-write dedup, mirrored: a retry of the same request under the
        // same key joins the transaction the first call created rather than making a second.
        val api = FakeBudgetApi()
        val first = api.createTransaction(request(), "key-1").getOrThrow()
        val replay = api.createTransaction(request(), "key-1").getOrThrow()

        assertEquals(first.id, replay.id)
        assertEquals(1, api.created.size)
        assertEquals(listOf("key-1", "key-1"), api.idempotencyKeys)
    }

    @Test
    fun `distinct keys create distinct transactions`() = runTest {
        val api = FakeBudgetApi()
        val a = api.createTransaction(request("A"), "key-a").getOrThrow()
        val b = api.createTransaction(request("B"), "key-b").getOrThrow()

        org.junit.Assert.assertNotEquals(a.id, b.id)
        assertEquals(2, api.created.size)
    }

    @Test fun `createTransaction records the splits it was given`() = runTest {
        val api = FakeBudgetApi()
        val parts = listOf(dev.otherworld.budget.domain.model.SplitPart(
            dev.otherworld.budget.domain.model.Money(java.math.BigDecimal("1.00"), "GBP"), 14, "X"))
        api.createTransaction(request().copy(splits = parts), "k").getOrThrow()
        assertEquals(parts, api.created.single().splits)
    }

    @Test
    fun `budget status and bills return the fake data and count calls`() = runTest {
        val api = FakeBudgetApi()
        assertEquals(FakeCheckData.budget, api.budgetStatus().getOrThrow())
        assertEquals(FakeCheckData.bills, api.upcomingBills().getOrThrow())
        assertEquals(1, api.budgetCalls)
        assertEquals(1, api.billsCalls)
    }

    @Test
    fun `unsupportedCheckRoutes answers 404 for budget and bills but not accounts`() = runTest {
        val api = FakeBudgetApi().apply { unsupportedCheckRoutes = true }

        assertEquals(BudgetApiError.ServerError(404), api.budgetStatus().exceptionOrNull())
        assertEquals(BudgetApiError.ServerError(404), api.upcomingBills().exceptionOrNull())
        assertTrue(api.accounts().isSuccess)
    }

    @Test
    fun `capabilities report checkAvailable`() = runTest {
        val api = FakeBudgetApi()
        assertTrue(api.capabilities().getOrThrow().checkAvailable)

        api.checkAvailable = false
        assertFalse(api.capabilities().getOrThrow().checkAvailable)
    }

    @Test
    fun `recent includes a transfer pair and a split row`() = runTest {
        val rows = FakeBudgetApi().recentTransactions().getOrThrow()

        val transferOut = rows.first { it.id == 9003L }
        val transferIn = rows.first { it.id == 9004L }
        assertEquals(9004L, transferOut.transfer?.linkedTransactionId)
        assertEquals(9003L, transferIn.transfer?.linkedTransactionId)

        val split = rows.first { it.id == 9001L }
        assertEquals(2, split.splits.size)
    }

    @Test
    fun `transactionSplits returns the splits of the matching recent row, empty when none`() = runTest {
        val api = FakeBudgetApi()
        assertEquals(2, api.transactionSplits(9001).getOrThrow().size)
        assertEquals(emptyList<Any>(), api.transactionSplits(9000).getOrThrow())
    }
}
