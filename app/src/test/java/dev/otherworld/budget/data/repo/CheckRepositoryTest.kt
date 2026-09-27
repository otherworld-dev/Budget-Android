package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.local.SnapshotCodec
import dev.otherworld.budget.data.local.SnapshotKind
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.remote.fake.FakeCheckData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Plain JVM: the store sits on [TestSnapshots.fake]'s hand-rolled DAO, and the one clock drives
 * both [CheckRepository] and the snapshots' `fetchedAt`, so staleness moves only when a test moves
 * [clock].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckRepositoryTest {

    private val dispatcher = StandardTestDispatcher()

    private val t0 = Instant.parse("2026-09-27T10:00:00Z")
    private var clock = t0

    private val api = FakeBudgetApi()
    private val snapshots = TestSnapshots.fake(now = { clock })
    private val repo = CheckRepository(api, CatalogRepository(api, snapshots), snapshots, now = { clock })

    @Test
    fun `first refresh emits cached data before the network answers`() = runTest(dispatcher) {
        val cached = FakeCheckData.recent.take(1)
        snapshots.write(SnapshotKind.RECENT, SnapshotCodec.encodeRecent(cached))
        clock = t0 + Duration.ofHours(1)          // stale, so the refresh refetches it
        api.latencyMs = 1000

        launch { repo.refresh() }
        runCurrent()

        assertEquals(cached, repo.recent.value.data)
        assertEquals(t0, repo.recent.value.fetchedAt)
        assertEquals(0, api.recentCalls)   // still waiting on capabilities: nothing fetched yet

        advanceUntilIdle()
        assertEquals(FakeCheckData.recent, repo.recent.value.data)
    }

    @Test
    fun `a successful refresh stores each section with fetchedAt`() = runTest(dispatcher) {
        repo.refresh()

        with(repo.balances.value) {
            assertEquals(FakeCheckData.accounts, data); assertEquals(t0, fetchedAt)
            assertNull(error); assertFalse(refreshing)
        }
        with(repo.budget.value) {
            assertEquals(FakeCheckData.budget, data); assertEquals(t0, fetchedAt)
            assertNull(error); assertFalse(refreshing); assertFalse(unsupported)
        }
        with(repo.bills.value) {
            assertEquals(FakeCheckData.bills, data); assertEquals(t0, fetchedAt)
            assertNull(error); assertFalse(refreshing); assertFalse(unsupported)
        }
        with(repo.recent.value) {
            assertEquals(FakeCheckData.recent, data); assertEquals(t0, fetchedAt)
            assertNull(error); assertFalse(refreshing)
        }
        assertEquals(FakeCheckData.budget, snapshots.read(SnapshotKind.BUDGET_STATUS, SnapshotCodec::decodeBudget)?.value)
        assertEquals(FakeCheckData.bills, snapshots.read(SnapshotKind.UPCOMING_BILLS, SnapshotCodec::decodeBills)?.value)
        assertEquals(FakeCheckData.recent, snapshots.read(SnapshotKind.RECENT, SnapshotCodec::decodeRecent)?.value)
    }

    @Test
    fun `a failed section keeps its cached data and sets error while others succeed`() = runTest(dispatcher) {
        val cachedBills = FakeCheckData.bills.take(1)
        snapshots.write(SnapshotKind.UPCOMING_BILLS, SnapshotCodec.encodeBills(cachedBills))
        clock = t0 + Duration.ofHours(1)
        api.unsupportedCheckRoutes = false
        api.failBills = true

        repo.refresh()

        with(repo.bills.value) {
            assertEquals(cachedBills, data)
            assertEquals(t0, fetchedAt)                        // the snapshot's, not this refresh's
            assertTrue(error is BudgetApiError.Network)
            assertFalse(refreshing); assertFalse(unsupported)
        }
        with(repo.budget.value) {
            assertEquals(FakeCheckData.budget, data); assertNull(error)
        }
        assertEquals(FakeCheckData.recent, repo.recent.value.data)
        assertNull(repo.recent.value.error)
    }

    @Test
    fun `checkAvailable false marks budget and bills unsupported and does not call them`() = runTest(dispatcher) {
        api.checkAvailable = false

        repo.refresh()

        assertTrue(repo.budget.value.unsupported)
        assertTrue(repo.bills.value.unsupported)
        assertNull(repo.budget.value.error)
        assertFalse(repo.budget.value.refreshing)
        assertEquals(0, api.budgetCalls)
        assertEquals(0, api.billsCalls)
        // Both of these routes predate check_available, so an old server still serves them.
        assertEquals(FakeCheckData.accounts, repo.balances.value.data)
        assertEquals(FakeCheckData.recent, repo.recent.value.data)
    }

    @Test
    fun `a 404 on budget status marks it unsupported, not errored`() = runTest(dispatcher) {
        api.unsupportedCheckRoutes = true

        repo.refresh()

        with(repo.budget.value) {
            assertTrue(unsupported); assertNull(error); assertFalse(refreshing)
        }
        with(repo.bills.value) {
            assertTrue(unsupported); assertNull(error)
        }
        assertEquals(1, api.budgetCalls)
    }

    @Test
    fun `a non-forced refresh within five minutes makes no calls`() = runTest(dispatcher) {
        repo.refresh()
        clock = t0 + Duration.ofMinutes(4)

        repo.refresh()

        assertEquals(1, api.accountCalls)
        assertEquals(1, api.budgetCalls)
        assertEquals(1, api.billsCalls)
        assertEquals(1, api.recentCalls)
    }

    @Test
    fun `a non-forced refresh after five minutes calls again`() = runTest(dispatcher) {
        repo.refresh()
        clock = t0 + Duration.ofMinutes(6)

        repo.refresh()

        assertEquals(2, api.accountCalls)
        assertEquals(2, api.budgetCalls)
        assertEquals(2, api.billsCalls)
        assertEquals(2, api.recentCalls)
        assertEquals(clock, repo.recent.value.fetchedAt)
    }

    @Test
    fun `a forced refresh always calls`() = runTest(dispatcher) {
        repo.refresh()

        repo.refresh(force = true)

        assertEquals(2, api.accountCalls)
        assertEquals(2, api.budgetCalls)
        assertEquals(2, api.billsCalls)
        assertEquals(2, api.recentCalls)
    }

    @Test
    fun `balances keep the catalog's fetchedAt and flag an error when it falls back to its snapshot`() = runTest(dispatcher) {
        repo.refresh()
        clock = t0 + Duration.ofMinutes(10)
        api.nextError = BudgetApiError.Network(null)

        repo.refresh()

        with(repo.balances.value) {
            // The catalog answers success with its persisted snapshot; stamping that "now" would
            // hide that it is ten minutes old.
            assertEquals(FakeCheckData.accounts, data)
            assertEquals(t0, fetchedAt)
            assertTrue(error is BudgetApiError.Network)
            assertFalse(refreshing)
        }
    }

    @Test
    fun `a cancelled refresh does not leave sections refreshing`() = runTest(dispatcher) {
        api.latencyMs = 1000

        val job = launch { repo.refresh() }
        advanceTimeBy(1500)                        // all four section fetches in flight
        assertTrue(repo.recent.value.refreshing)
        assertTrue(repo.balances.value.refreshing)
        job.cancel()
        advanceUntilIdle()

        // A singleton outlives the screen that cancelled it: a flag left set here would spin until
        // the next forced refresh, since a non-forced one returns at the freshness check.
        assertFalse(repo.balances.value.refreshing)
        assertFalse(repo.budget.value.refreshing)
        assertFalse(repo.bills.value.refreshing)
        assertFalse(repo.recent.value.refreshing)
    }

    @Test
    fun `a result landing after reset is discarded`() = runTest(dispatcher) {
        api.latencyMs = 1000

        launch { repo.refresh() }
        // Capabilities answers at 1000ms; the four section fetches are then in flight until 2000ms.
        advanceTimeBy(1500)
        assertTrue(repo.recent.value.refreshing)
        repo.reset()
        advanceUntilIdle()

        assertNull(repo.recent.value.data)
        assertNull(repo.budget.value.data)
        assertNull(repo.bills.value.data)
        assertNull(repo.balances.value.data)
        assertFalse(repo.recent.value.refreshing)
        // Neither applied nor written: the next session must not seed from it either.
        assertNull(snapshots.read(SnapshotKind.RECENT, SnapshotCodec::decodeRecent))
        assertNull(snapshots.read(SnapshotKind.BUDGET_STATUS, SnapshotCodec::decodeBudget))
        assertNull(snapshots.read(SnapshotKind.UPCOMING_BILLS, SnapshotCodec::decodeBills))
    }
}
