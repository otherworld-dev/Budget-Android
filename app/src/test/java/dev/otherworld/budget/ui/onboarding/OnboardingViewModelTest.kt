package dev.otherworld.budget.ui.onboarding

import app.cash.turbine.test
import dev.otherworld.budget.data.auth.*
import dev.otherworld.budget.data.theme.FakeThemePalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session fake that persists straight into the supplied store -- what SessionManager.signIn
 * does, minus the cross-server reconciliation these tests don't exercise.
 */
private class StoreBackedSession(private val store: CredentialStore) : Session {
    override suspend fun signIn(credentials: Credentials) {
        store.save(credentials)
    }

    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}

/** Like [FakeLoginFlow], but records whether and with what [start] was called. */
private class RecordingLoginFlow(
    private val start: Result<LoginFlowStart> = Result.success(
        LoginFlowStart("https://cloud.example.com/login/flow", "tok", "https://cloud.example.com/poll")
    ),
    private val poll: Result<Credentials> = Result.success(
        Credentials("https://cloud.example.com", "adam", "app-pw")
    ),
) : LoginFlow {
    var startCalls = 0
        private set
    var startedWith: String? = null
        private set

    override suspend fun start(serverUrl: String): Result<LoginFlowStart> {
        startCalls++
        startedWith = serverUrl
        return start
    }

    override suspend fun poll(start: LoginFlowStart): Result<Credentials> = poll
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        start: Result<LoginFlowStart> = Result.success(
            LoginFlowStart("https://cloud.example.com/login/flow", "tok", "https://cloud.example.com/poll")
        ),
        poll: Result<Credentials> = Result.success(
            Credentials("https://cloud.example.com", "adam", "app-pw")
        ),
        store: CredentialStore = InMemoryCredentialStore(),
    ) = OnboardingViewModel(FakeLoginFlow(start, poll), StoreBackedSession(store), FakeThemePalette()) to store

    @Test
    fun `rejects an invalid address before touching the network`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.onServerUrlChanged("http://cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.EDITING, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    // --- The cleartext consent gate ----------------------------------------------------------

    @Test
    fun `an http address moves to CONFIRM_HTTP without any network call`() = runTest(dispatcher) {
        // Nothing insecure by default: a plain-http (necessarily private-host) address must not
        // connect until the user has read the unencrypted-traffic warning and said yes. The gate
        // is the state machine, not the dialog -- so it is asserted here, without a UI.
        val loginFlow = RecordingLoginFlow()
        val vm = OnboardingViewModel(loginFlow, StoreBackedSession(InMemoryCredentialStore()), FakeThemePalette())

        vm.onServerUrlChanged("http://192.168.1.10")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.CONFIRM_HTTP, vm.uiState.value.status)
        assertEquals(0, loginFlow.startCalls)
    }

    @Test
    fun `confirming the http warning proceeds with the login flow`() = runTest(dispatcher) {
        val loginFlow = RecordingLoginFlow(
            poll = Result.success(Credentials("http://192.168.1.10", "adam", "app-pw")),
        )
        val store = InMemoryCredentialStore()
        val vm = OnboardingViewModel(loginFlow, StoreBackedSession(store), FakeThemePalette())

        vm.onServerUrlChanged("http://192.168.1.10")
        vm.onConnectClicked()
        advanceUntilIdle()
        vm.onHttpConfirmed()
        advanceUntilIdle()

        assertEquals(1, loginFlow.startCalls)
        assertEquals("http://192.168.1.10", loginFlow.startedWith)
        assertEquals(Status.DONE, vm.uiState.value.status)
        // The consent covers the poll response too: the server reporting the same http address
        // back is accepted, because the user themselves chose http this attempt.
        assertEquals("http://192.168.1.10", store.load()!!.server)
    }

    @Test
    fun `declining the http warning returns to editing with nothing sent`() = runTest(dispatcher) {
        val loginFlow = RecordingLoginFlow()
        val vm = OnboardingViewModel(loginFlow, StoreBackedSession(InMemoryCredentialStore()), FakeThemePalette())

        vm.onServerUrlChanged("http://192.168.1.10")
        vm.onConnectClicked()
        advanceUntilIdle()
        vm.onHttpDeclined()
        advanceUntilIdle()

        assertEquals(Status.EDITING, vm.uiState.value.status)
        assertEquals(0, loginFlow.startCalls)
    }

    @Test
    fun `an https address never sees the http confirmation step`() = runTest(dispatcher) {
        val loginFlow = RecordingLoginFlow()
        val vm = OnboardingViewModel(loginFlow, StoreBackedSession(InMemoryCredentialStore()), FakeThemePalette())

        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()

        // Synchronously past the gate: the very first observable status is STARTING (or later),
        // never CONFIRM_HTTP.
        assertTrue(vm.uiState.value.status != Status.CONFIRM_HTTP)
        advanceUntilIdle()
        assertEquals(1, loginFlow.startCalls)
    }

    @Test
    fun `a poll response that downgrades https to http fails the login instead of storing it`() =
        runTest(dispatcher) {
            // The bypass this closes: the poll response's `server` field is the server's own
            // configured address and used to be stored verbatim -- so an https sign-in against a
            // server whose overwrite.cli.url was left http would silently persist a cleartext
            // endpoint that every authenticated request (carrying the app password) then used.
            val (vm, store) = viewModel(
                poll = Result.success(Credentials("http://192.168.1.10", "adam", "app-pw")),
            )
            vm.onServerUrlChanged("cloud.example.com")   // https -- no consent given
            vm.onConnectClicked()
            advanceUntilIdle()

            assertEquals(Status.EDITING, vm.uiState.value.status)
            assertEquals(OnboardingViewModel.HTTP_DOWNGRADE_MESSAGE, vm.uiState.value.errorMessage)
            assertNull(store.load())
        }

    @Test
    fun `a poll response with an unusable server address fails the login`() = runTest(dispatcher) {
        val (vm, store) = viewModel(
            poll = Result.success(Credentials("not a url at all", "adam", "app-pw")),
        )
        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.EDITING, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(store.load())
    }

    @Test
    fun `emits the browser URL and waits`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.launchBrowser.test {
            vm.onServerUrlChanged("cloud.example.com")
            vm.onConnectClicked()
            advanceUntilIdle()
            assertEquals("https://cloud.example.com/login/flow", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stores credentials and finishes on a successful poll`() = runTest(dispatcher) {
        val (vm, store) = viewModel()
        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.DONE, vm.uiState.value.status)
        assertEquals("adam", store.load()!!.loginName)
    }

    @Test
    fun `a successful login triggers a theme-colour fetch`() = runTest(dispatcher) {
        // Adopting the server's Nextcloud theme colour is kicked off the moment login succeeds --
        // fire-and-forget on a scope that outlives this soon-to-be-popped ViewModel.
        val theme = FakeThemePalette()
        val vm = OnboardingViewModel(
            FakeLoginFlow(
                Result.success(
                    LoginFlowStart("https://cloud.example.com/login/flow", "tok", "https://cloud.example.com/poll")
                ),
                Result.success(Credentials("https://cloud.example.com", "adam", "app-pw")),
            ),
            StoreBackedSession(InMemoryCredentialStore()),
            theme,
        )

        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.DONE, vm.uiState.value.status)
        assertEquals(1, theme.backgroundRefreshCalls)
    }

    @Test
    fun `a failed login does not fetch a theme colour`() = runTest(dispatcher) {
        val theme = FakeThemePalette()
        val vm = OnboardingViewModel(
            FakeLoginFlow(
                Result.success(
                    LoginFlowStart("https://cloud.example.com/login/flow", "tok", "https://cloud.example.com/poll")
                ),
                Result.failure(RuntimeException("no")),
            ),
            StoreBackedSession(InMemoryCredentialStore()),
            theme,
        )

        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(0, theme.backgroundRefreshCalls)
    }

    @Test
    fun `surfaces a start failure without storing anything`() = runTest(dispatcher) {
        val (vm, store) = viewModel(start = Result.failure(RuntimeException("boom")))
        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()

        assertEquals(Status.EDITING, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(store.load())
    }

    /**
     * Replaces an earlier "cancelling returns to editing" test that only asserted the
     * post-cancel status. Under StandardTestDispatcher, deleting `job?.cancel()` from
     * onCancelled() -- leaving only the status update -- produced the exact same observable
     * status: onCancelled() ran before the launched coroutine got to execute at all, so
     * advanceUntilIdle() would just let start() succeed and poll() fail, landing on EDITING
     * either way. That test never actually exercised cancellation.
     *
     * This version uses a poll() that genuinely suspends (on a CompletableDeferred, not a
     * pre-baked Result), so the coroutine is verifiably alive and mid-poll at the moment
     * onCancelled() runs. It then resolves the deferred *after* cancelling: if job.cancel()
     * were removed, the coroutine would still be suspended there, waking up to a success and
     * driving the state to DONE with saved credentials -- exactly what the final assertions
     * rule out.
     */
    @Test
    fun `cancelling stops the poll so a login completing afterwards is not applied`() = runTest(dispatcher) {
        val pollGate = CompletableDeferred<Result<Credentials>>()
        val store = InMemoryCredentialStore()
        val gatedLoginFlow = object : LoginFlow {
            override suspend fun start(serverUrl: String) = Result.success(
                LoginFlowStart("https://cloud.example.com/login/flow", "tok", "https://cloud.example.com/poll")
            )
            override suspend fun poll(start: LoginFlowStart) = pollGate.await()
        }
        val vm = OnboardingViewModel(gatedLoginFlow, StoreBackedSession(store), FakeThemePalette())

        vm.onServerUrlChanged("cloud.example.com")
        vm.onConnectClicked()
        advanceUntilIdle()
        assertEquals(Status.WAITING_FOR_BROWSER, vm.uiState.value.status)

        vm.onCancelled()
        advanceUntilIdle()
        assertEquals(Status.EDITING, vm.uiState.value.status)

        // The poll was genuinely abandoned, not just hidden by the UI: completing it now must
        // have no effect, because a cancelled coroutine never resumes from pollGate.await().
        pollGate.complete(Result.success(Credentials("https://cloud.example.com", "adam", "app-pw")))
        advanceUntilIdle()

        assertEquals(Status.EDITING, vm.uiState.value.status)
        assertNull(store.load())
    }
}
