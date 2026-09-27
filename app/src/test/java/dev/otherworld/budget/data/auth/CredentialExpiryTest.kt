package dev.otherworld.budget.data.auth

import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.remote.FallbackCurrencyCache
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.TestSnapshots
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import javax.inject.Provider

/**
 * Robolectric, not pure JVM: [CatalogRepository] now persists through a Room-backed
 * [dev.otherworld.budget.data.repo.SnapshotStore] (see [TestSnapshots.inMemory]), which needs a
 * Context to open even for its in-memory driver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class CredentialExpiryTest {

    private val api = FakeBudgetApi()
    private val store = InMemoryCredentialStore(Credentials("https://cloud.example", "adam", "pw"))
    private val snapshots = TestSnapshots.inMemory(store)
    private val catalog = CatalogRepository(api, snapshots)
    private val check = CheckRepository(api, catalog, snapshots, now = { Instant.now() })
    private val currencyCache = FallbackCurrencyCache()
    private val expiry = CredentialExpiry(store, Provider { catalog }, Provider { check }, currencyCache)

    @Test
    fun `an expiry clears the credentials`() {
        expiry.onUnauthorized(expiry.currentGeneration)
        assertNull(store.load())
    }

    @Test
    fun `an expiry invalidates the catalog cache`() = runTest {
        // CatalogRepository caches for the life of the process, so a revoked app password used
        // to leave Capture rendering accounts and an "OCR available" banner from a session that
        // no longer existed, while every queued receipt failed behind it.
        catalog.accounts()
        assertEquals(1, api.accountCalls)

        expiry.onUnauthorized(expiry.currentGeneration)
        catalog.accounts()

        assertEquals(2, api.accountCalls)
    }

    @Test
    fun `an expiry resets the check sections`() = runTest {
        // The next sign-in may be a different user on the same server, which signIn cannot tell
        // apart from the same one returning -- so the previous user's figures must go now.
        check.refresh()
        assertNotNull(check.balances.value.data)
        assertNotNull(check.recent.value.data)

        expiry.onUnauthorized(expiry.currentGeneration)

        assertNull(check.balances.value.data)
        assertNull(check.budget.value.data)
        assertNull(check.bills.value.data)
        assertNull(check.recent.value.data)
        assertNull(check.recent.value.fetchedAt)   // so the next refresh can't skip it as fresh
    }

    @Test
    fun `an expiry clears the fallback currency`() {
        // Per-server state, exactly like the catalog: left populated, the next server's drafts
        // would be labelled with the previous server's currency for the life of the process.
        currencyCache.value = "GBP"

        expiry.onUnauthorized(expiry.currentGeneration)

        assertNull(currencyCache.value)
    }

    @Test
    fun `an expiry is announced to a live observer`() = runTest {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            expiry.expirations.collect { seen += it }
        }
        runCurrent()

        expiry.onUnauthorized(expiry.currentGeneration)
        runCurrent()

        assertEquals(1, seen.size)
    }

    @Test
    fun `an expiry raised before anyone subscribed is not replayed to them`() = runTest {
        // A replayed expiry would be re-delivered on every rotation and every return to the
        // foreground, ejecting a user who had already signed back in and cancelling a Login Flow
        // in progress. There is nothing to miss: the credentials are gone, so a launch that
        // starts after an expiry resolves its own start destination to Onboarding.
        expiry.onUnauthorized(expiry.currentGeneration)

        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            expiry.expirations.collect { seen += it }
        }
        runCurrent()

        assertEquals(0, seen.size)
    }

    @Test
    fun `a stale 401 from before a re-login does not wipe the fresh credentials`() = runTest {
        // The race: a request signed with the old (revoked) password can sit in flight for up
        // to the client's 120s read timeout. If its 401 lands after the user has completed a
        // fresh login, acting on it would clear the brand-new credentials and eject the user
        // straight back to Onboarding. The generation captured at request time no longer
        // matches, so the expiry no-ops.
        val staleGeneration = expiry.currentGeneration   // captured when the request went out

        store.save(Credentials("https://cloud.example", "adam", "fresh-pw"))
        expiry.onCredentialsChanged()                    // SessionManager.signIn does this

        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            expiry.expirations.collect { seen += it }
        }
        runCurrent()

        expiry.onUnauthorized(staleGeneration)           // the old request's 401 finally lands
        runCurrent()

        assertNotNull(store.load())
        assertEquals("fresh-pw", store.load()!!.appPassword)
        assertEquals(0, seen.size)                       // and nobody is told to re-onboard
    }

    @Test
    fun `only the first 401 of a generation acts`() = runTest {
        // Several requests from the same dying session can all 401; the first one ends the
        // session, the rest must not re-fire the signal (which would re-navigate a user who is
        // already mid-login on Onboarding).
        val generation = expiry.currentGeneration

        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            expiry.expirations.collect { seen += it }
        }
        runCurrent()

        expiry.onUnauthorized(generation)
        runCurrent()
        expiry.onUnauthorized(generation)
        runCurrent()

        assertEquals(1, seen.size)
    }
}
