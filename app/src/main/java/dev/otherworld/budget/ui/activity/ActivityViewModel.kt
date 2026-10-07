package dev.otherworld.budget.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.R
import dev.otherworld.budget.core.StringResources
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.Section
import dev.otherworld.budget.domain.model.RecentTransaction
import dev.otherworld.budget.ui.common.WebLinks
import dev.otherworld.budget.ui.common.stalenessText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ActivityUiState(
    val rows: List<ActivityRow> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,          // mapped through StringResources
    val staleness: String? = null,
    val loaded: Boolean = false,        // false until cache or network has answered once
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val check: CheckRepository,
    private val credentials: CredentialStore,
    private val strings: StringResources,
    private val now: () -> Instant,
) : ViewModel() {

    private val _uiState = MutableStateFlow(toUiState(check.recent.value))
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    init {
        // check.recent is a singleton-scoped StateFlow that outlives this ViewModel, so every
        // emission -- including ones from a refresh triggered by another screen sharing the same
        // CheckRepository -- keeps this state current, not just the ones this ViewModel itself
        // asked for via onVisible()/refresh().
        viewModelScope.launch {
            check.recent.collect { section -> _uiState.value = toUiState(section) }
        }
    }

    /** Called on first composition and every resume -- a `force = false` refresh (spec §2.4). */
    fun onVisible() {
        recomputeStaleness()
        viewModelScope.launch { check.refresh(force = false) }
    }

    /** Pull-to-refresh: always hits the network. */
    fun refresh() {
        recomputeStaleness()
        viewModelScope.launch { check.refresh(force = true) }
    }

    /**
     * Reads the server at tap time, not once at construction: a tab's back stack is saved when
     * the user switches away, and restoring it can hand back this same ViewModel after a sign-out
     * and a sign-in to a different server. The nav host now drops those saved stacks on sign-out
     * and expiry, but a link must never open the wrong server's pages if one survives.
     */
    fun webUrlFor(id: Long): String? = credentials.load()?.server?.let { WebLinks.transaction(it, id) }

    /**
     * [check.recent] only emits when [CheckRepository] itself changes the section, which a
     * `force = false` refresh skips entirely while the data is still fresh (see
     * [CheckRepository.refresh]). Without this, staleness text computed from a wall clock would
     * only ever update on an emission that happens to coincide with it -- reopening the screen
     * after the five-minute window, with nothing else about the data having changed, would keep
     * showing "fresh" until *something* else forced a refresh. Calling this from both
     * [onVisible] and [refresh] re-reads the current section against `now()` every time the
     * screen is looked at, independent of whether a fetch actually happens.
     */
    private fun recomputeStaleness() {
        _uiState.value = toUiState(check.recent.value)
    }

    private fun toUiState(section: Section<List<RecentTransaction>>): ActivityUiState {
        val hasError = section.error != null
        return ActivityUiState(
            rows = section.data?.let(::buildActivityRows) ?: emptyList(),
            refreshing = section.refreshing,
            error = section.error?.let(::mapError),
            staleness = stalenessText(section.fetchedAt, hasError, now(), strings),
            loaded = section.fetchedAt != null || hasError,
        )
    }

    /** Never the raw [Throwable.message] -- always neutral, translated copy. */
    private fun mapError(e: BudgetApiError): String = when (e) {
        is BudgetApiError.Network -> strings.get(R.string.activity_error_offline)
        else -> strings.get(R.string.activity_error_generic)
    }
}
