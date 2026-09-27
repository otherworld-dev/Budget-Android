package dev.otherworld.budget.data.repo

import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
