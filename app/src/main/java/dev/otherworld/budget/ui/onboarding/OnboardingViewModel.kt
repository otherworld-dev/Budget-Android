package dev.otherworld.budget.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.auth.*
import dev.otherworld.budget.data.theme.ThemePalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Status {
    EDITING,

    /**
     * The address normalised to plain `http://` (necessarily a private/LAN host --
     * [ServerUrlNormaliser] rejects http for anything else) and the app is waiting for the user
     * to confirm they understand what that means before a single byte is sent. Nothing insecure
     * happens by default: no network call is made in this state, and the only ways out are an
     * explicit [OnboardingViewModel.onHttpConfirmed] (proceed) or
     * [OnboardingViewModel.onHttpDeclined] (back to [EDITING]). The screen renders this as a
     * plain-words dialog: password, receipts and amounts all travel unencrypted on the local
     * network.
     */
    CONFIRM_HTTP,

    STARTING,
    WAITING_FOR_BROWSER,
    DONE,
}

data class OnboardingUiState(
    val serverUrl: String = "",
    val status: Status = Status.EDITING,
    val errorMessage: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val loginFlow: LoginFlow,
    private val session: Session,
    private val theme: ThemePalette,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _launchBrowser = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val launchBrowser: SharedFlow<String> = _launchBrowser.asSharedFlow()

    private var job: Job? = null

    /**
     * The normalised `http://` address the CONFIRM_HTTP dialog is asking about. Held here, not
     * in [OnboardingUiState]: it is the pending *input* to a decision, not something the screen
     * renders, and keeping it out of state means a confirm can only ever act on the exact URL
     * the dialog was raised for.
     */
    private var pendingHttpUrl: String? = null

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    fun onConnectClicked() {
        val normalised = ServerUrlNormaliser.normalise(_uiState.value.serverUrl)
            .getOrElse { error ->
                _uiState.update { it.copy(status = Status.EDITING, errorMessage = error.message) }
                return
            }

        // Plain http (only ever a private host; the normaliser rejects the rest) does not
        // connect until the user has explicitly said yes to unencrypted traffic. The state
        // machine gate lives here so it is testable without a UI; the dialog itself is dumb.
        if (normalised.startsWith("http://")) {
            pendingHttpUrl = normalised
            _uiState.update { it.copy(status = Status.CONFIRM_HTTP, errorMessage = null) }
            return
        }

        connect(normalised, httpConsented = false)
    }

    /** The user read the cleartext warning and chose to continue. */
    fun onHttpConfirmed() {
        val url = pendingHttpUrl
        pendingHttpUrl = null
        if (url == null) {
            _uiState.update { it.copy(status = Status.EDITING) }
            return
        }
        connect(url, httpConsented = true)
    }

    /** The user read the cleartext warning and backed out. Nothing was sent. */
    fun onHttpDeclined() {
        pendingHttpUrl = null
        _uiState.update { it.copy(status = Status.EDITING) }
    }

    private fun connect(normalised: String, httpConsented: Boolean) {
        _uiState.update { it.copy(status = Status.STARTING, errorMessage = null) }

        job = viewModelScope.launch {
            val start = loginFlow.start(normalised).getOrElse { error ->
                _uiState.update { it.copy(status = Status.EDITING, errorMessage = friendly(error)) }
                return@launch
            }

            _uiState.update { it.copy(status = Status.WAITING_FOR_BROWSER) }
            _launchBrowser.emit(start.loginUrl)

            loginFlow.poll(start)
                .onSuccess { credentials ->
                    // The poll response's `server` field is the server's own idea of its
                    // address (overwrite.cli.url), not what the user typed, and it is the URL
                    // every authenticated request will actually be sent to -- so it goes
                    // through the same policy the typed address did. Without this, a server
                    // whose admin left an http URL configured would silently downgrade an
                    // https sign-in to a stored cleartext endpoint carrying the app password.
                    validatedServer(credentials.server, httpConsented)
                        .onSuccess { server ->
                            // Through Session, not CredentialStore directly: signing in also
                            // reconciles state left over from a previous session (a different
                            // server's queued FAILED rows, its cached catalog/currency) --
                            // see SessionManager.signIn.
                            session.signIn(credentials.copy(server = server))
                            // Adopt the new server's Nextcloud theme colour. Fire-and-forget on a
                            // scope that outlives this ViewModel (login is about to pop onboarding
                            // and clear it), independent of the Budget routes -- it hits a core OCS
                            // route that answers 200 even when the Budget routes 404. It applies
                            // reactively when it lands; nothing here waits on it.
                            theme.refreshInBackground()
                            _uiState.update { it.copy(status = Status.DONE) }
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(status = Status.EDITING, errorMessage = error.message)
                            }
                        }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(status = Status.EDITING, errorMessage = friendly(error)) }
                }
        }
    }

    /**
     * Re-validates the server URL the Login Flow poll handed back. `http://` is accepted only
     * when the user themselves entered an http address *and* passed the [Status.CONFIRM_HTTP]
     * gate this attempt ([httpConsented]); anything else -- an https sign-in coming back http,
     * a public-host http URL, an unparseable value -- fails the login rather than storing an
     * endpoint the user never agreed to send credentials to.
     */
    private fun validatedServer(reported: String, httpConsented: Boolean): Result<String> {
        if (reported.startsWith("http://") && !httpConsented) {
            return Result.failure(IllegalArgumentException(HTTP_DOWNGRADE_MESSAGE))
        }
        val normalised = ServerUrlNormaliser.normalise(reported).getOrElse {
            return Result.failure(IllegalArgumentException(
                "Your Nextcloud reported a server address this app can't use ($reported), " +
                    "so sign-in was stopped. Check the server's configured address."
            ))
        }
        // Belt to the check above: unreachable today (the normaliser never *introduces* an
        // http scheme -- a scheme-less input gets https), but this line is what keeps that
        // true if the normaliser ever changes.
        if (normalised.startsWith("http://") && !httpConsented) {
            return Result.failure(IllegalArgumentException(HTTP_DOWNGRADE_MESSAGE))
        }
        return Result.success(normalised)
    }

    fun onCancelled() {
        job?.cancel()
        _uiState.update { it.copy(status = Status.EDITING) }
    }

    /**
     * The device has no browser at all -- neither a Custom Tabs provider nor anything answering
     * ACTION_VIEW. Login Flow v2 cannot be completed without one, so the poll is stopped rather
     * than left spinning behind a "Waiting for your browser…" that will never resolve.
     */
    fun onBrowserUnavailable() {
        job?.cancel()
        _uiState.update {
            it.copy(
                status = Status.EDITING,
                errorMessage = "No browser is available to finish signing in.",
            )
        }
    }

    private fun friendly(error: Throwable) =
        error.message ?: "Couldn't reach that server. Check the address and try again."

    companion object {
        const val HTTP_DOWNGRADE_MESSAGE =
            "Your Nextcloud reported an unencrypted (http://) address, so sign-in was stopped " +
                "to keep your password protected. Check the server's configured address, or " +
                "enter the http:// address yourself if you trust this network."
    }
}
