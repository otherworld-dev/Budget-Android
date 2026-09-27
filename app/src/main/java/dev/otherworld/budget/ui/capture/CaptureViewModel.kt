package dev.otherworld.budget.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface CaptureMessage {
    /** Rendered with the fixed neutral copy. Never links anywhere. */
    data object OcrUnavailable : CaptureMessage
    /** The server answered, and it genuinely has no accounts. Fixed copy, no setup flow. */
    data object NoAccounts : CaptureMessage
    /**
     * The server could not be asked at all. Distinct from [NoAccounts]: an unreachable server
     * says nothing about whether accounts exist, and telling an offline user to "add an account
     * in Budget on Nextcloud" sends them looking for a problem they do not have. Carries a retry
     * affordance; capture itself stays enabled, since an offline photo simply queues (spec §7).
     */
    data object Offline : CaptureMessage
    /** A shutter press or an image copy failed. The app's primary action must never no-op silently. */
    data object CaptureFailed : CaptureMessage
    data class Queued(val count: Int) : CaptureMessage
}

data class CaptureUiState(
    val loading: Boolean = true,
    val ocrAvailable: Boolean = false,
    val hasAccounts: Boolean = false,
    val pendingCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val oldestAwaitingReviewId: Long? = null,
    val message: CaptureMessage? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val queue: ReceiptQueue,
    private val scheduler: QueueScheduling,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            queue.observeQueue().collect { pending ->
                _uiState.update {
                    // AWAITING_REVIEW rows have their own banner, and they are not "queued" in
                    // any sense the user would recognise -- they are waiting on the user, not on
                    // the server. Counting them here made one receipt appear in both banners.
                    it.copy(pendingCount = pending.count { row -> row.state != CaptureState.AWAITING_REVIEW })
                }
            }
        }
        // The only reliable route into Review: POST_NOTIFICATIONS is opportunistic and often
        // declined, and AWAITING_REVIEW is reached on OCR success *and* on failure/quota/
        // not-configured (see ReceiptRepository.extractNext) -- exactly the cases where the user
        // most needs a way in. This must reflect real queue state, not just the notification
        // having fired, so Capture always has a working door into Review regardless of
        // notification permission or delivery.
        viewModelScope.launch {
            queue.observeAwaitingReview().collect { awaiting ->
                _uiState.update {
                    it.copy(
                        awaitingReviewCount = awaiting.size,
                        // observeAwaitingReview() is ordered oldest-first (capturedAt ASC), same
                        // as ReceiptQueue.oldestAwaitingReview()'s one-shot query -- so the first
                        // element here is already the oldest.
                        oldestAwaitingReviewId = awaiting.firstOrNull()?.id,
                    )
                }
            }
        }
    }

    /**
     * Re-run on every resume (see CaptureScreen), not only from `init`: Capture is the start
     * destination and is never popped, so a ViewModel created while offline would otherwise
     * keep showing its first, degraded answer for the entire life of the process -- including
     * after the user had gone to Settings, connected, and come back.
     *
     * A *failed* catalog fetch and an empty account list are different answers and are reported
     * differently; `null` from [Result.getOrNull] is exactly the distinction the old code threw
     * away with `.orEmpty()`.
     *
     * So is a fetch that failed but was answered from the catalog's persisted snapshot
     * ([CatalogRepository.accountsAreFallback]): the accounts are real enough to capture against,
     * so [CaptureUiState.hasAccounts] follows them, but the server was still unreachable and the
     * notice says so. A cached empty list is not proof the server has no accounts either, so that
     * reads as offline too.
     */
    fun refresh() = viewModelScope.launch {
        val ocr = catalog.capabilities().getOrNull()?.ocrAvailable ?: false
        val accounts = catalog.accounts().getOrNull()
        val offline = accounts == null || catalog.accountsAreFallback()
        _uiState.update {
            it.copy(
                loading = false,
                ocrAvailable = ocr,
                hasAccounts = !accounts.isNullOrEmpty(),
                message = when {
                    offline -> CaptureMessage.Offline
                    accounts.isEmpty() -> CaptureMessage.NoAccounts
                    !ocr -> CaptureMessage.OcrUnavailable
                    else -> null
                },
            )
        }
    }

    /**
     * Enqueues regardless of [CaptureUiState.ocrAvailable] -- with no OCR the item
     * still reaches Review, where the user types the amount by hand.
     */
    fun onPhotoCaptured(photo: File) = viewModelScope.launch {
        // A capture that works clears a stale "couldn't use that photo" message; the steady-state
        // messages (Offline/NoAccounts/OcrUnavailable) are re-derived by the next refresh(),
        // which runs on every resume.
        _uiState.update { if (it.message == CaptureMessage.CaptureFailed) it.copy(message = null) else it }
        queue.enqueue(photo)
        scheduler.scheduleExtraction()
    }

    /**
     * Called when a shutter press or an image copy failed. Every path from "the user asked for a
     * photo" to "a row exists in the queue" must end in either a queued receipt or this -- the
     * app has exactly one primary action, and it silently doing nothing is indistinguishable
     * from it having worked.
     */
    fun onCaptureFailed() {
        _uiState.update { it.copy(message = CaptureMessage.CaptureFailed) }
    }
}
