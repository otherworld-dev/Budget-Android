package dev.otherworld.budget.ui.activity

import dev.otherworld.budget.R
import dev.otherworld.budget.core.FakeStringResources
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.TestSnapshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * [CheckRepository] built the same way [dev.otherworld.budget.data.repo.CheckRepositoryTest] does
 * -- a hand-rolled snapshot store and one mutable clock shared with the ViewModel's own `now`, so
 * "five minutes pass" means the same thing to both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val t0 = Instant.parse("2026-09-27T10:00:00Z")
    private var clock = t0

    private val api = FakeBudgetApi()
    private val snapshots = TestSnapshots.fake(now = { clock })
    private val check = CheckRepository(api, CatalogRepository(api, snapshots), snapshots, now = { clock })
    private val strings = FakeStringResources()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun store() = InMemoryCredentialStore().apply {
        save(Credentials("https://cloud.example.com", "adam", "pw"))
    }

    private fun vm(credentials: InMemoryCredentialStore = store()) =
        ActivityViewModel(check, credentials, strings, now = { clock })

    @Test
    fun `rows collapse the fake transfer pair`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.rows.size)
        assertTrue(viewModel.uiState.value.loaded)
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun `a refresh failure keeps rows and sets a mapped error string`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        api.nextError = BudgetApiError.Network(null)
        viewModel.refresh(); advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.rows.size)
        assertEquals(strings.get(R.string.activity_error_offline), viewModel.uiState.value.error)
    }

    @Test
    fun `builds a deep link into the Budget web UI`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertEquals(
            "https://cloud.example.com/apps/budget/#transactions?id=9001",
            viewModel.webUrlFor(9001),
        )
    }

    @Test
    fun `the deep link follows a server change made after the ViewModel was built`() = runTest(dispatcher) {
        // Tab back stacks are saved across sign-out, so this ViewModel can come back via
        // restoreState after the user has signed in to a different server: a server resolved once
        // at construction would open the old server's web pages.
        val credentials = store()
        val viewModel = vm(credentials)

        credentials.save(Credentials("https://other.example.org", "bob", "pw"))

        assertEquals(
            "https://other.example.org/apps/budget/#transactions?id=9001",
            viewModel.webUrlFor(9001),
        )
    }

    @Test
    fun `returns no URL when signed out`() = runTest(dispatcher) {
        val viewModel = vm(InMemoryCredentialStore())
        viewModel.onVisible(); advanceUntilIdle()

        assertNull(viewModel.webUrlFor(9001))
    }

    @Test
    fun `staleness appears after five minutes`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()
        assertNull(viewModel.uiState.value.staleness)

        // onVisible() recomputes the staleness line synchronously against the current clock, so
        // the "stale" moment is observable even before the resulting network refresh (also
        // triggered below) lands and moves fetchedAt forward again.
        clock = t0 + Duration.ofMinutes(6)
        viewModel.onVisible()

        assertNotNull(viewModel.uiState.value.staleness)
    }

    @Test
    fun `onVisible within five minutes makes no network call`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()
        val callsAfterFirstLoad = api.recentCalls

        clock = t0 + Duration.ofMinutes(4)
        viewModel.onVisible(); advanceUntilIdle()

        assertEquals(callsAfterFirstLoad, api.recentCalls)
    }
}
