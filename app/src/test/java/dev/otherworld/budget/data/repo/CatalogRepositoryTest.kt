package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM test: CatalogRepository touches no Android API, so no Robolectric needed. */
class CatalogRepositoryTest {

    @Test
    fun `a second call does not hit the API`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api)

        repo.accounts()
        repo.accounts()

        assertEquals(1, api.accountCalls)
    }

    @Test
    fun `forceRefresh bypasses the cache and hits the API again`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api)

        repo.accounts()
        repo.accounts(forceRefresh = true)

        assertEquals(2, api.accountCalls)
    }

    @Test
    fun `invalidate clears the cache so the next call hits the API`() = runTest {
        val api = FakeBudgetApi()
        val repo = CatalogRepository(api)

        repo.accounts()
        repo.invalidate()
        repo.accounts()

        assertEquals(2, api.accountCalls)
    }
}
