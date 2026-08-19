package dev.otherworld.budget.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.auth.Session
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val server: String? = null,
    val loginName: String? = null,
    /**
     * Rows waiting on the app or the server -- CAPTURED, POSTING and FAILED. Deliberately the
     * *same* partition Capture's queued banner counts
     * ([dev.otherworld.budget.ui.capture.CaptureViewModel]'s pendingCount), so one row is never
     * described as two different things on two screens: "queued" means not-awaiting-review,
     * everywhere. [failed] is the subset of these with a manual retry affordance, and the
     * screen renders it as a parenthetical of queued rather than a third bucket.
     */
    val queued: Int = 0,
    /** Rows waiting on the user -- AWAITING_REVIEW, the same set Capture's tappable banner counts. */
    val awaitingReview: Int = 0,
    val failed: Int = 0,
    val signingOut: Boolean = false,
    val signedOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: CredentialStore,
    private val session: Session,
    private val queue: ReceiptQueue,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val credentials = store.load()
        _uiState.update { it.copy(server = credentials?.server, loginName = credentials?.loginName) }
        viewModelScope.launch {
            queue.observeQueue().collect { rows ->
                _uiState.update { state ->
                    state.copy(
                        // See SettingsUiState: the same two-way partition Capture's banners
                        // use (queued = everything not awaiting review; ready-to-review = the
                        // rest), with FAILED broken out as a subset because only it gets the
                        // manual retry affordance. Settings used to count AWAITING_REVIEW rows
                        // in "waiting", so the same row read as "ready to review" on Capture
                        // and "waiting" here -- one item, two contradictory descriptions.
                        queued = rows.count { it.state != CaptureState.AWAITING_REVIEW },
                        awaitingReview = rows.count { it.state == CaptureState.AWAITING_REVIEW },
                        failed = rows.count { it.state == CaptureState.FAILED },
                    )
                }
            }
        }
    }

    /**
     * [Session.signOut] always wipes local credentials, queue rows, and photos, whether or
     * not server-side revocation succeeded -- see [dev.otherworld.budget.data.auth.SessionManager].
     * signedOut is therefore set unconditionally once the call resolves; its Result is not
     * otherwise surfaced.
     */
    fun onSignOutClicked() = viewModelScope.launch {
        _uiState.update { it.copy(signingOut = true) }
        session.signOut()
        _uiState.update { it.copy(signingOut = false, signedOut = true) }
    }

    /**
     * Re-posts every FAILED row; the queue counts update via [ReceiptQueue.observeQueue].
     * Needs no re-entrancy guard of its own: [ReceiptQueue.retryFailedPosts] admits one sweep
     * at a time and [ReceiptQueue.post] refuses a second concurrent post of the same receipt,
     * so a double tap cannot produce a duplicate transaction.
     */
    fun onRetryFailedClicked() = viewModelScope.launch {
        queue.retryFailedPosts()
    }
}
