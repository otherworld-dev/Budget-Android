package dev.otherworld.budget.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.R
import dev.otherworld.budget.core.StringResources
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.Section
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.BudgetLine
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.UpcomingBill
import dev.otherworld.budget.ui.common.WebLinks
import dev.otherworld.budget.ui.common.stalenessText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** One check-screen section, mapped for display -- see [Section] for the raw shape this comes from. */
data class SectionUi<T>(
    val data: T? = null,
    val refreshing: Boolean = false,
    // Only set when data == null; with data present, a failed refresh shows as staleness instead
    // (see stalenessText's own `hasError` parameter) rather than blanking what's already on screen.
    val error: String? = null,
    val staleness: String? = null,
    val unsupported: Boolean = false,
)

/** [Account.type] in the order each type first appeared in the server's own list (closed accounts dropped). */
data class AccountGroup(val type: String, val accounts: List<Account>)

/** One bill plus the due wording ([dueLabel]) it earns against the injected `today`. */
data class BillRow(val bill: UpcomingBill, val label: DueLabel)

data class OverviewUiState(
    // True only when *both* budget and bills are unsupported -- an older server missing
    // check_available entirely. Balances/recent predate that flag and still work on such a
    // server, but the spec asks for one explanatory state here, not a partial screen.
    val oldServer: Boolean = false,
    val balances: SectionUi<List<AccountGroup>> = SectionUi(),
    val budget: SectionUi<BudgetStatus> = SectionUi(),
    val atRisk: List<BudgetLine> = emptyList(),
    val bills: SectionUi<List<BillRow>> = SectionUi(),
)

/**
 * Backs both [OverviewScreen] and [BudgetScreen] -- the latter is a push from the former's own
 * "See all" link, sharing this same instance (scoped to the Overview back-stack entry) rather
 * than re-fetching, so the detail list is exactly what Overview just showed a summary of.
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val check: CheckRepository,
    private val credentials: CredentialStore,
    private val strings: StringResources,
    private val now: () -> Instant,
    private val today: () -> LocalDate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        buildState(check.balances.value, check.budget.value, check.bills.value),
    )
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        // Each of the three flows is singleton-scoped and outlives this ViewModel, so a refresh
        // triggered by another screen sharing the same CheckRepository (e.g. BudgetScreen pulling
        // to refresh) still keeps this state current.
        viewModelScope.launch {
            combine(check.balances, check.budget, check.bills) { balances, budget, bills ->
                buildState(balances, budget, bills)
            }.collect { _uiState.value = it }
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

    // The server is read at tap time -- same reasoning as ActivityViewModel.webUrlFor.
    fun accountsUrl(): String? = credentials.load()?.server?.let { WebLinks.accounts(it) }
    fun budgetUrl(): String? = credentials.load()?.server?.let { WebLinks.budget(it) }
    fun billsUrl(): String? = credentials.load()?.server?.let { WebLinks.bills(it) }

    /** Same reasoning as [dev.otherworld.budget.ui.activity.ActivityViewModel.recomputeStaleness]. */
    private fun recomputeStaleness() {
        _uiState.value = buildState(check.balances.value, check.budget.value, check.bills.value)
    }

    private fun buildState(
        balances: Section<List<Account>>,
        budget: Section<BudgetStatus>,
        bills: Section<List<UpcomingBill>>,
    ): OverviewUiState = OverviewUiState(
        oldServer = budget.unsupported && bills.unsupported,
        balances = toSectionUi(balances, ::groupByType),
        budget = toSectionUi(budget) { it },
        atRisk = budget.data?.atRisk() ?: emptyList(),
        bills = toSectionUi(bills) { list -> list.map { BillRow(it, dueLabel(it, today())) } },
    )

    private fun <T, R> toSectionUi(section: Section<T>, transform: (T) -> R): SectionUi<R> {
        val hasError = section.error != null
        return SectionUi(
            data = section.data?.let(transform),
            refreshing = section.refreshing,
            error = if (section.data == null) section.error?.let(::mapError) else null,
            staleness = stalenessText(section.fetchedAt, hasError, now(), strings),
            unsupported = section.unsupported,
        )
    }

    /**
     * Never the raw [Throwable.message] -- always neutral, translated copy. Overview's own
     * strings, not Activity's -- "loading your activity" would be a wrong-screen error on a
     * screen with no activity list at all (caught in review: this used to reuse Activity's).
     */
    private fun mapError(e: BudgetApiError): String = when (e) {
        is BudgetApiError.Network -> strings.get(R.string.overview_error_offline)
        else -> strings.get(R.string.overview_error_generic)
    }

    /**
     * Closed accounts dropped, the rest grouped by [Account.type] in the order each type first
     * appears in the server's own list -- never re-sorted, since that order is itself meaningful
     * (spec §2.2). An empty type (an older server, or a genuinely untyped account) gets its own
     * group labelled [R.string.overview_accounts_other] rather than a blank heading.
     */
    private fun groupByType(accounts: List<Account>): List<AccountGroup> {
        val groups = LinkedHashMap<String, MutableList<Account>>()
        for (account in accounts) {
            if (account.closed) continue
            val label = account.type.ifBlank { strings.get(R.string.overview_accounts_other) }
            groups.getOrPut(label) { mutableListOf() }.add(account)
        }
        return groups.map { (type, accounts) -> AccountGroup(type, accounts) }
    }
}
