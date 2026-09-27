package dev.otherworld.budget.data.repo

import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.domain.model.Account
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Robolectric, not pure JVM: [CatalogRepository] persists through a Room-backed [SnapshotStore]
 * (see [TestSnapshots.inMemory]), which needs a Context to open even for its in-memory driver.
 */
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryTest {

    private val alice = InMemoryCredentialStore(Credentials("https://cloud.example", "alice", "pw"))

    @Test
    fun `a second call does not hit the API`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api, TestSnapshots.inMemory(alice))

        repo.accounts()
        repo.accounts()

        assertEquals(1, api.accountCalls)
    }

    @Test
    fun `forceRefresh bypasses the cache and hits the API again`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api, TestSnapshots.inMemory(alice))

        repo.accounts()
        repo.accounts(forceRefresh = true)

        assertEquals(2, api.accountCalls)
    }

    @Test
    fun `invalidate clears the cache so the next call hits the API`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api, TestSnapshots.inMemory(alice))

        repo.accounts()
        repo.invalidate()
        repo.accounts()

        assertEquals(2, api.accountCalls)
    }

    @Test
    fun `a cold start while offline serves the persisted accounts`() = runTest {
        val snapshots = TestSnapshots.inMemory(alice)
        val repoA = CatalogRepository(FakeBudgetApi(), snapshots)
        val fetched = repoA.accounts().getOrThrow()

        // A fresh repository over the same persisted store -- an empty in-memory cache, as after
        // a process restart -- with the network unreachable this time.
        val repoB = CatalogRepository(FakeBudgetApi(nextError = BudgetApiError.Network(null)), snapshots)
        val result = repoB.accounts()

        assertTrue(result.isSuccess)
        assertEquals(fetched, result.getOrNull())
    }

    @Test
    fun `network recovering after a fallback is used on the very next unforced call`() = runTest {
        // A fallback value must not pin memory for the rest of the process: the whole point of
        // "put it in memory without marking it fresh" is that the next unforced call keeps
        // retrying the network, which is what lets a cold start recover once connectivity
        // returns instead of being stuck showing stale data for the process's entire life (the
        // exact bug CaptureViewModel.refresh()'s "re-run on every resume" KDoc exists to avoid
        // one layer up).
        val snapshots = TestSnapshots.inMemory(alice)
        CatalogRepository(FakeBudgetApi(), snapshots).accounts()   // seeds a persisted snapshot

        val api = FakeBudgetApi(nextError = BudgetApiError.Network(null))
        val repo = CatalogRepository(api, snapshots)
        repo.accounts()   // offline: served from the fallback snapshot, not marked fresh

        // Connectivity returns, and the server would now answer with a different list.
        val newAccounts = listOf(Account(99, "New Account", "GBP"))
        api.nextError = null
        api.accountsResult = newAccounts

        val result = repo.accounts()   // unforced

        assertTrue(result.isSuccess)
        assertEquals(newAccounts, result.getOrNull())
        // One failed call while offline, one successful call once connectivity returned -- proves
        // this hit the network again rather than being served straight out of memory.
        assertEquals(2, api.accountCalls)
    }

    @Test
    fun `offline with nothing persisted still fails`() = runTest {
        val snapshots = TestSnapshots.inMemory(alice)
        val repo = CatalogRepository(FakeBudgetApi(nextError = BudgetApiError.Network(null)), snapshots)

        val result = repo.accounts()

        assertTrue(result.isFailure)
    }

    @Test
    fun `a persisted snapshot from another server is not served`() = runTest {
        val credentials = InMemoryCredentialStore(Credentials("https://cloud.example", "alice", "pw"))
        val snapshots = TestSnapshots.inMemory(credentials)
        CatalogRepository(FakeBudgetApi(), snapshots).accounts()   // persists under alice's server

        credentials.save(Credentials("https://other.example", "bob", "pw"))
        val repo = CatalogRepository(FakeBudgetApi(nextError = BudgetApiError.Network(null)), snapshots)

        val result = repo.accounts()

        assertTrue(result.isFailure)
    }

    @Test
    fun `clearPersisted drops memory and disk`() = runTest {
        val api = FakeBudgetApi()
        val snapshots = TestSnapshots.inMemory(alice)
        val repo = CatalogRepository(api, snapshots)
        repo.accounts()   // populates memory and disk

        repo.clearPersisted()
        api.nextError = BudgetApiError.Network(null)   // now offline

        // Memory is gone: an unforced call has nothing to return but a real (failing) fetch --
        // a surviving in-memory value would wrongly succeed here.
        assertTrue(repo.accounts().isFailure)

        // And the disk copy is really gone, not just hidden behind this repo's own memory: a
        // second, cold repository over the same store gets nothing to fall back on either.
        val repoB = CatalogRepository(FakeBudgetApi(nextError = BudgetApiError.Network(null)), snapshots)
        assertTrue(repoB.accounts().isFailure)
    }

    @Test
    fun `a failed forced refresh returns the snapshot and leaves accountsFetchedAt unchanged`() = runTest {
        var clock = Instant.parse("2026-09-27T10:00:00Z")   // T0
        val snapshots = TestSnapshots.inMemory(alice, now = { clock })
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api, snapshots)
        repo.accounts()   // fetch at T0
        val fetchedAtT0 = repo.accountsFetchedAt()
        assertEquals(clock, fetchedAtT0)

        clock = Instant.parse("2026-09-27T11:00:00Z")   // T1
        api.nextError = BudgetApiError.Network(null)
        val result = repo.accounts(forceRefresh = true)   // forced, but offline

        assertTrue(result.isSuccess)   // still falls back to the snapshot
        // The snapshot was never re-written by the failed refresh, so its fetchedAt is still T0,
        // not bumped to T1 -- a fetch that never happened must not corrupt the staleness reading.
        assertEquals(fetchedAtT0, repo.accountsFetchedAt())
    }
}
