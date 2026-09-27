package dev.otherworld.budget.ui.overview

import dev.otherworld.budget.R
import dev.otherworld.budget.core.FakeStringResources
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.TestSnapshots
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * [CheckRepository] built the same way [dev.otherworld.budget.ui.activity.ActivityViewModelTest]
 * does -- a hand-rolled snapshot store and one mutable clock shared with the ViewModel's own
 * `now`, so "five minutes pass" means the same thing to both. `today` is a second, independent
 * clock (spec's "today" for due labels), fixed at 2026-09-27 to match FakeCheckData's bills.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val t0 = Instant.parse("2026-09-27T10:00:00Z")
    private var clock = t0
    private val today = LocalDate.of(2026, 9, 27)

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
        OverviewViewModel(check, credentials, strings, now = { clock }, today = { today })

    private fun money(v: String) = Money(BigDecimal(v), "GBP")

    @Test
    fun `closed accounts are hidden and accounts group by type`() = runTest(dispatcher) {
        api.accountsResult = listOf(
            Account(1, "Current", "GBP", type = "checking", balance = money("10.00")),
            Account(2, "Old ISA", "GBP", type = "savings", balance = money("0.00"), closed = true),
            Account(3, "Cash", "GBP", type = "", balance = money("5.00")),
            Account(4, "Savings", "GBP", type = "savings", balance = money("20.00")),
        )
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        val groups = viewModel.uiState.value.balances.data!!
        // Order of first appearance among the accounts actually shown -- closed account 2 is
        // dropped before grouping, so its "savings" doesn't count as an earlier appearance than
        // account 3's empty type: "checking" (account 1), then the empty-type group
        // (account 3, labelled overview_accounts_other), then "savings" (account 4).
        assertEquals(
            listOf("checking", strings.get(R.string.overview_accounts_other), "savings"),
            groups.map { it.type },
        )
        assertEquals(listOf(1L), groups[0].accounts.map { it.id })
        assertEquals(listOf(3L), groups[1].accounts.map { it.id })
        assertEquals(listOf(4L), groups[2].accounts.map { it.id })
    }

    @Test
    fun `web links follow a server change made after the ViewModel was built`() = runTest(dispatcher) {
        // Same reason as ActivityViewModelTest's: a restored tab back stack can outlive a sign-in
        // to a different server, so the server must be read at tap time.
        val credentials = store()
        val viewModel = vm(credentials)

        credentials.save(Credentials("https://other.example.org", "bob", "pw"))

        assertEquals("https://other.example.org/apps/budget/#accounts", viewModel.accountsUrl())
        assertEquals("https://other.example.org/apps/budget/#budget", viewModel.budgetUrl())
        assertEquals("https://other.example.org/apps/budget/#bills", viewModel.billsUrl())
    }

    @Test
    fun `atRisk puts Groceries (overspent) first`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertEquals("Groceries", viewModel.uiState.value.atRisk.first().name)
    }

    @Test
    fun `bills carry due labels from the injected today`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        val rows = viewModel.uiState.value.bills.data!!
        assertEquals(DueLabel.Overdue, rows.first { it.bill.name == "Council Tax" }.label)
        assertEquals(DueLabel.InDays(5), rows.first { it.bill.name == "Phone" }.label)
    }

    @Test
    fun `checkAvailable false gives the old-server state`() = runTest(dispatcher) {
        api.checkAvailable = false
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertTrue(viewModel.uiState.value.oldServer)
        assertTrue(viewModel.uiState.value.budget.unsupported)
        assertTrue(viewModel.uiState.value.bills.unsupported)
    }

    @Test
    fun `a 404 on budget alone marks only budget unsupported, not oldServer`() = runTest(dispatcher) {
        api.unsupportedBudgetOnly = true
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertTrue(viewModel.uiState.value.budget.unsupported)
        assertFalse(viewModel.uiState.value.bills.unsupported)
        assertFalse(viewModel.uiState.value.oldServer)
        // Neither data nor an error string -- the screen must recognise `unsupported` on its own
        // to explain this section, not fall through a data/error/refreshing check and render
        // nothing (review fix round 1, finding 1).
        assertNull(viewModel.uiState.value.budget.data)
        assertNull(viewModel.uiState.value.budget.error)
    }

    @Test
    fun `a section error without data surfaces an error string, with data it becomes staleness`() = runTest(dispatcher) {
        // No prior data at all: the failure has nothing to fall back on, so it must show up as
        // an error string.
        api.failBills = true
        val viewModel = vm()
        viewModel.onVisible(); advanceUntilIdle()

        assertNull(viewModel.uiState.value.bills.data)
        assertEquals(strings.get(R.string.overview_error_offline), viewModel.uiState.value.bills.error)

        // Once bills has loaded successfully, the exact same kind of failure keeps the cached
        // rows and reports itself as staleness instead of an error string.
        api.failBills = false
        viewModel.refresh(); advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bills.data)
        assertNull(viewModel.uiState.value.bills.error)

        api.failBills = true
        viewModel.refresh(); advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bills.data)
        assertNull(viewModel.uiState.value.bills.error)
        assertNotNull(viewModel.uiState.value.bills.staleness)
    }
}
