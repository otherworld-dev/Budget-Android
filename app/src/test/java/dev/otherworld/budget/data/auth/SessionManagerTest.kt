package dev.otherworld.budget.data.auth

import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.remote.TestApiFactory
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ExtractOutcome
import dev.otherworld.budget.data.prefs.LastServerStore
import dev.otherworld.budget.data.repo.PendingReceipt
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.theme.FakeThemePalette
import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.ui.review.FakeLastAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Exercises [SessionManager] against a real [MockWebServer], the same way
 * [dev.otherworld.budget.data.remote.BudgetApiRetrofitTest] exercises [BudgetApiRetrofit] --
 * unlike that class, [SessionManager] is the *only* caller in this app that used to build its
 * own absolute-URL [okhttp3.Request] rather than going through [BudgetService]/Retrofit, which
 * is exactly what let a server deployed under a subpath silently double the path prefix (see
 * [SessionManager.signOut]'s KDoc). These tests assert the recorded request path directly, since
 * that is the bug a mocked [Session] or a fake [dev.otherworld.budget.data.remote.BudgetService]
 * can never catch.
 */
class SessionManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var queue: RecordingQueue
    private lateinit var scheduler: RecordingScheduler
    private lateinit var lastAccount: FakeLastAccount

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        queue = RecordingQueue()
        scheduler = RecordingScheduler()
        lastAccount = FakeLastAccount(7L)
    }

    @After fun tearDown() = server.shutdown()

    private val lastServer = FakeLastServer()
    private val theme = FakeThemePalette()
    private val currencyCache = dev.otherworld.budget.data.remote.FallbackCurrencyCache()
    private val authExpiry = CredentialExpiry(
        InMemoryCredentialStore(),
        javax.inject.Provider { CatalogRepository(FakeBudgetApi()) },
        currencyCache,
    )

    private fun sessionManager(serverUrl: String): SessionManager {
        store = InMemoryCredentialStore().apply { save(Credentials(serverUrl, "adam", "pw")) }
        return SessionManager(
            store = store,
            service = TestApiFactory.service(store),
            queue = queue,
            catalog = CatalogRepository(FakeBudgetApi()),
            scheduler = scheduler,
            lastAccount = lastAccount,
            lastServer = lastServer,
            currencyCache = currencyCache,
            theme = theme,
            authExpiry = authExpiry,
        )
    }

    @Test
    fun `revocation on a subpath-deployed server hits the prefix exactly once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val manager = sessionManager(server.url("/nextcloud/").toString().trimEnd('/'))

        val result = manager.signOut()

        assertTrue(result.isSuccess)
        assertEquals("/nextcloud/ocs/v2.php/core/apppassword", server.takeRequest().path)
    }

    @Test
    fun `revocation on a root-deployed server hits the bare OCS path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))

        val result = manager.signOut()

        assertTrue(result.isSuccess)
        assertEquals("/ocs/v2.php/core/apppassword", server.takeRequest().path)
    }

    @Test
    fun `a non-2xx revocation response is a failure but local state is still fully cleared`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))

        val result = manager.signOut()

        assertTrue(result.isFailure)
        assertNull(store.load())
        assertTrue(queue.clearAllCalled)
        assertTrue(scheduler.cancelAllCalled)
    }

    @Test
    fun `sign out forgets the last-used account, which belongs to the server being left`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))

        manager.signOut()

        // Left behind, this id would silently pre-select an account on a *different* server the
        // next time someone signed in and opened Review.
        assertTrue(lastAccount.cleared)
        assertNull(lastAccount.get())
    }

    @Test
    fun `sign out drops the fallback currency along with the rest of the session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        currencyCache.value = "GBP"
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))

        manager.signOut()

        assertNull(currencyCache.value)
    }

    @Test
    fun `sign out clears the adopted theme colour so the app returns to the default`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))

        manager.signOut()

        // Left behind, the previous server's brand colour would keep theming a logged-out app.
        assertTrue(theme.cleared)
    }

    // --- signIn: reconciling a queue that outlived its server -------------------------------

    @Test
    fun `first-ever sign-in stores credentials and records the server, touching nothing else`() = runTest {
        val manager = sessionManager("https://old.example")
        store.clear()   // simulate the pre-login state

        manager.signIn(Credentials("https://cloud.example", "adam", "pw"))

        assertEquals("https://cloud.example", store.load()!!.server)
        assertEquals("https://cloud.example", lastServer.get())
        assertFalse(queue.parkedForServerChange)
        assertFalse(lastAccount.cleared)
        // No previous server, so nothing to un-theme -- the incoming server's colour is fetched fresh.
        assertFalse(theme.cleared)
    }

    @Test
    fun `signing back in to the same server leaves the queue alone`() = runTest {
        val manager = sessionManager("https://cloud.example")
        lastServer.set("https://cloud.example")
        store.clear()   // e.g. a credential expiry wiped the password but not the queue

        manager.signIn(Credentials("https://cloud.example", "adam", "new-pw"))

        assertEquals("new-pw", store.load()!!.appPassword)
        assertFalse(queue.parkedForServerChange)
        assertFalse(lastAccount.cleared)
        // Same server -> the adopted colour is still valid and must not be dropped (no default flash).
        assertFalse(theme.cleared)
    }

    @Test
    fun `a server change parks the queue before the new credentials become visible`() = runTest {
        // Ordering is the fix for the sweep race: BaseUrlInterceptor resolves the base URL per
        // request at dispatch time, and a retryFailedPosts sweep already holding the sweep lock
        // keeps dispatching until it finishes. parkFailedForServerChange waits on that same
        // lock -- so as long as it completes BEFORE store.save, an in-flight sweep runs to the
        // end against the OLD credentials (post-expiry: the cleared store), and the FAILED set
        // is empty by the time the new server exists to post to. Saving first opened a window,
        // one sweep long, in which old-server rows were posted to the new host with foreign
        // account ids -- the exact unattended cross-server write C3 exists to prevent.
        val manager = sessionManager("https://old.example")
        lastServer.set("https://old.example")
        store.clear()   // post-expiry: the store stays empty until signIn saves

        var credentialsVisibleAtPark: Credentials? =
            Credentials("sentinel-not-null", "x", "x")   // overwritten by the observer below
        queue.onPark = { credentialsVisibleAtPark = store.load() }

        manager.signIn(Credentials("https://work.example", "adam", "pw"))

        // At the instant the park ran, the new credentials had not been saved: nothing an
        // in-flight or subsequent sweep dispatches while old rows still exist can carry the
        // new server's base URL. (The repository-level half -- the park genuinely waiting out
        // a live sweep and leaving it nothing to send -- is pinned in ReceiptRepositoryTest.)
        assertNull(credentialsVisibleAtPark)
        assertTrue(queue.parkedForServerChange)
        assertEquals("https://work.example", store.load()!!.server)
    }

    @Test
    fun `sign out bumps the credential generation so a late 401 cannot end the next session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val manager = sessionManager(server.url("/").toString().trimEnd('/'))
        val staleGeneration = authExpiry.currentGeneration   // a request in flight right now

        manager.signOut()

        // A 401 answered to that request after sign-out must no-op -- without the bump it
        // could clear the *next* session's credentials mid-login, once.
        assertTrue(authExpiry.currentGeneration > staleGeneration)
    }

    @Test
    fun `signing in to a different server parks FAILED rows and forgets per-server state`() = runTest {
        // The cross-server hazard: FAILED rows are auto-retried unattended, but their stored
        // account ids belong to the old server -- and small integer ids exist on virtually
        // every instance, so an unattended post against the new one would land in an arbitrary
        // account there. The queue survives expiry by design; this is the balancing half.
        val manager = sessionManager("https://old.example")
        lastServer.set("https://old.example")
        store.clear()
        currencyCache.value = "GBP"

        manager.signIn(Credentials("https://work.example", "adam", "pw"))

        assertTrue(queue.parkedForServerChange)
        assertTrue(lastAccount.cleared)
        assertNull(currencyCache.value)
        // The old server's brand colour is dropped too; onboarding then fetches the new one clean.
        assertTrue(theme.cleared)
        assertEquals("https://work.example", lastServer.get())
        assertEquals("https://work.example", store.load()!!.server)
    }
}

/** Hand-rolled in-memory fake, mirroring SharedPreferencesLastServerStore. */
class FakeLastServer : LastServerStore {
    private var value: String? = null
    override fun get(): String? = value
    override fun set(server: String) { value = server }
}

/**
 * Hand-rolled fake -- same pattern as FakeSettingsQueue in SettingsViewModelTest. Only
 * [clearAll] is exercised here.
 */
class RecordingQueue : ReceiptQueue {
    var clearAllCalled = false
    var parkedForServerChange = false

    /** Lets a test observe surrounding state at the exact moment the park runs. */
    var onPark: (() -> Unit)? = null
    override fun observeQueue(): Flow<List<PendingReceipt>> = TODO()
    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> = TODO()
    override suspend fun byId(id: Long): PendingReceipt? = TODO()
    override suspend fun oldestAwaitingReview(): PendingReceipt? = TODO()
    override suspend fun enqueue(photo: File): Long = TODO()
    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = TODO()
    override suspend fun extractNext(): ExtractOutcome = TODO()
    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> = TODO()
    override suspend fun retryFailedPosts(): Boolean = TODO()
    override suspend fun reconcileInterrupted(): Unit = TODO()
    override suspend fun parkFailedForServerChange() {
        parkedForServerChange = true
        onPark?.invoke()
    }
    override suspend fun discard(id: Long): Unit = TODO()
    override suspend fun clearAll() { clearAllCalled = true }
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long): Unit = TODO()
}

/** Hand-rolled fake. Only [cancelAll] is exercised here. */
class RecordingScheduler : QueueScheduling {
    var cancelAllCalled = false
    override fun scheduleExtraction(): Unit = TODO()
    override fun schedulePost(): Unit = TODO()
    override fun schedulePrune(): Unit = TODO()
    override fun cancelAll() { cancelAllCalled = true }
}
