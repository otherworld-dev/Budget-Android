package dev.otherworld.budget.ui.recent

import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun store() = InMemoryCredentialStore().apply {
        save(Credentials("https://cloud.example.com", "adam", "pw"))
    }

    @Test
    fun `loads transactions on init`() = runTest(dispatcher) {
        val vm = RecentViewModel(FakeBudgetApi(), store()); advanceUntilIdle()
        assertEquals(4, vm.uiState.value.transactions.size)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `surfaces a failure without clearing what is already shown`() = runTest(dispatcher) {
        val api = FakeBudgetApi()
        val vm = RecentViewModel(api, store()); advanceUntilIdle()

        api.nextError = BudgetApiError.Network(null)
        vm.refresh(); advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertEquals(4, vm.uiState.value.transactions.size)
    }

    @Test
    fun `builds a deep link into the Budget web UI`() = runTest(dispatcher) {
        val vm = RecentViewModel(FakeBudgetApi(), store()); advanceUntilIdle()
        assertEquals(
            "https://cloud.example.com/apps/budget/#transactions?id=9001",
            vm.webUrlFor(9001),
        )
    }

    @Test
    fun `returns no URL when signed out`() = runTest(dispatcher) {
        val vm = RecentViewModel(FakeBudgetApi(), InMemoryCredentialStore()); advanceUntilIdle()
        assertNull(vm.webUrlFor(9001))
    }
}
