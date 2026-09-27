package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.local.SnapshotCodec
import dev.otherworld.budget.data.local.SnapshotKind
import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.BudgetStatus
import dev.otherworld.budget.domain.model.RecentTransaction
import dev.otherworld.budget.domain.model.UpcomingBill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The read-only check side's data: balances, budget left this month, bills due soon and recent
 * activity, each as its own [Section] so one failing route never blanks the others.
 *
 * Cache-first: the first [refresh] seeds every flow from its [SnapshotStore] row before touching
 * the network, so Overview opens on the last-known figures even offline, then refetches whatever
 * is older than [STALE_AFTER].
 */
@Singleton
class CheckRepository @Inject constructor(
    private val api: BudgetApi,
    private val catalog: CatalogRepository,
    private val snapshots: SnapshotStore,
    private val now: () -> Instant,
) {

    private val _balances = MutableStateFlow(Section<List<Account>>())
    private val _budget = MutableStateFlow(Section<BudgetStatus>())
    private val _bills = MutableStateFlow(Section<List<UpcomingBill>>())
    private val _recent = MutableStateFlow(Section<List<RecentTransaction>>())

    val balances: StateFlow<Section<List<Account>>> = _balances.asStateFlow()
    val budget: StateFlow<Section<BudgetStatus>> = _budget.asStateFlow()
    val bills: StateFlow<Section<List<UpcomingBill>>> = _bills.asStateFlow()
    val recent: StateFlow<Section<List<RecentTransaction>>> = _recent.asStateFlow()

    /**
     * Guards [generation], [seededGeneration] and every flow write together. [reset] is called
     * from [dev.otherworld.budget.data.auth.SessionManager] while a refresh may be mid-flight on
     * another thread, so "is this result still for the current session?" and "apply it" must be
     * one atomic step -- a plain check-then-update could let a stale result land just after the
     * reset cleared the flows.
     */
    private val stateLock = Any()
    private var generation = 0
    private var seededGeneration = -1

    /** Keeps two refreshes from fetching the same kinds at once; the loser then finds them fresh. */
    private val refreshLock = Mutex()

    /**
     * Loads cached sections (once per generation -- that is, once per session: [reset] starts a
     * new one), then refetches kinds older than STALE_AFTER (all when force).
     */
    suspend fun refresh(force: Boolean = false) {
        val gen = synchronized(stateLock) { generation }
        // Captured with the generation, for the same reason: the snapshot a fetch writes belongs
        // to whoever was signed in when it started (see SnapshotStore.write).
        val owner = snapshots.currentOwner()
        seed(gen)
        refreshLock.withLock {
            if (!isCurrent(gen)) return
            // A failure here means no fetch *and* no persisted snapshot (the catalog falls back to
            // one when it can): assume the check is available and let each section fail on its own.
            val checkAvailable =
                catalog.capabilities(forceRefresh = force).getOrNull()?.checkAvailable ?: true
            coroutineScope {
                launch { refreshBalances(gen, owner, force) }
                launch {
                    refreshSection(gen, owner, force, _recent, SnapshotKind.RECENT, SnapshotCodec::encodeRecent) {
                        api.recentTransactions()
                    }
                }
                if (checkAvailable) {
                    launch {
                        refreshSection(gen, owner, force, _budget, SnapshotKind.BUDGET_STATUS, SnapshotCodec::encodeBudget, gated = true) {
                            api.budgetStatus()
                        }
                    }
                    launch {
                        refreshSection(gen, owner, force, _bills, SnapshotKind.UPCOMING_BILLS, SnapshotCodec::encodeBills, gated = true) {
                            api.upcomingBills(BILL_DAYS)
                        }
                    }
                } else {
                    // An older server has neither route, so don't even ask.
                    apply(gen, _budget) { it.copy(refreshing = false, error = null, unsupported = true) }
                    apply(gen, _bills) { it.copy(refreshing = false, error = null, unsupported = true) }
                }
            }
        }
    }

    /**
     * Resets every flow to Section() -- called by SessionManager with CatalogRepository.clearPersisted().
     * Bumping [generation] is what stops a refresh already in flight from repopulating the flows
     * (or writing its snapshot) for a session that has ended; the next [refresh] seeds afresh.
     */
    fun reset() {
        synchronized(stateLock) {
            generation++
            _balances.value = Section()
            _budget.value = Section()
            _bills.value = Section()
            _recent.value = Section()
        }
    }

    /**
     * Once per generation: fills each still-empty flow from its snapshot. Accounts read the
     * catalog's own ACCOUNTS row -- there is one accounts cache, not two.
     */
    private suspend fun seed(gen: Int) {
        if (synchronized(stateLock) { seededGeneration == gen }) return
        seedOne(gen, _balances, snapshots.read(SnapshotKind.ACCOUNTS, SnapshotCodec::decodeAccounts))
        seedOne(gen, _budget, snapshots.read(SnapshotKind.BUDGET_STATUS, SnapshotCodec::decodeBudget))
        seedOne(gen, _bills, snapshots.read(SnapshotKind.UPCOMING_BILLS, SnapshotCodec::decodeBills))
        seedOne(gen, _recent, snapshots.read(SnapshotKind.RECENT, SnapshotCodec::decodeRecent))
        synchronized(stateLock) { if (generation == gen) seededGeneration = gen }
    }

    private fun <T> seedOne(gen: Int, flow: MutableStateFlow<Section<T>>, cached: Cached<T>?) {
        cached ?: return
        apply(gen, flow) { if (it.data == null) it.copy(data = cached.value, fetchedAt = cached.fetchedAt) else it }
    }

    /**
     * Balances go through the catalog, which on a network failure answers *success* with its
     * persisted snapshot. So [Section.fetchedAt] always comes from [CatalogRepository.accountsFetchedAt]
     * -- which only moves on a real fetch -- never [now], and a call that didn't move it is
     * reported as a [BudgetApiError.Network] so the section reads as stale rather than current.
     */
    private suspend fun refreshBalances(gen: Int, owner: String?, force: Boolean) {
        if (!force && isFresh(_balances.value.fetchedAt)) return
        if (!apply(gen, _balances) { it.copy(refreshing = true) }) return
        val (before, result, after) = clearingOnCancel(gen, _balances) {
            Triple(catalog.accountsFetchedAt(), catalog.accounts(forceRefresh = true), catalog.accountsFetchedAt())
        }
        if (ownerChanged(gen, owner, _balances)) return
        result.fold(
            onSuccess = { accounts ->
                val error = if (after == null || after == before) BudgetApiError.Network(null) else null
                apply(gen, _balances) { Section(data = accounts, fetchedAt = after, error = error) }
            },
            onFailure = { e -> apply(gen, _balances) { it.copy(refreshing = false, error = e.asApiError()) } },
        )
    }

    /**
     * One section's fetch. [gated] marks a check-only route (budget, bills): a 404 or 501 there
     * is a part-upgraded server that can't serve it, shown as unsupported rather than an error.
     * The snapshot is written only after the result was applied under the still-current
     * generation, so a result [reset] discarded never reaches disk either, and it is written
     * under [owner], the one signed in when the refresh began.
     */
    private suspend fun <T> refreshSection(
        gen: Int,
        owner: String?,
        force: Boolean,
        flow: MutableStateFlow<Section<T>>,
        kind: SnapshotKind,
        encode: (T) -> String,
        gated: Boolean = false,
        fetch: suspend () -> Result<T>,
    ) {
        val current = flow.value
        // An unsupported section is always retried: the server may have been upgraded since.
        if (!force && !current.unsupported && isFresh(current.fetchedAt)) return
        if (!apply(gen, flow) { it.copy(refreshing = true) }) return
        val result = clearingOnCancel(gen, flow) { fetch() }
        if (ownerChanged(gen, owner, flow)) return
        result.fold(
            onSuccess = { value ->
                if (apply(gen, flow) { Section(data = value, fetchedAt = now()) }) {
                    snapshots.write(kind, encode(value), owner)
                }
            },
            onFailure = { e ->
                val error = e.asApiError()
                if (gated && error is BudgetApiError.ServerError && error.code in UNSUPPORTED_CODES) {
                    apply(gen, flow) { it.copy(refreshing = false, error = null, unsupported = true) }
                } else {
                    apply(gen, flow) { it.copy(refreshing = false, error = error) }
                }
            },
        )
    }

    /**
     * Runs a section's network phase, dropping its `refreshing` flag if the refresh is cancelled
     * mid-flight. This is a singleton that outlives the screen whose scope launched the refresh:
     * a flag left set would spin until the next *forced* refresh, since a non-forced one returns
     * at the freshness check without touching it.
     */
    private suspend fun <T, R> clearingOnCancel(
        gen: Int,
        flow: MutableStateFlow<Section<T>>,
        block: suspend () -> R,
    ): R = try {
        block()
    } catch (e: CancellationException) {
        apply(gen, flow) { it.copy(refreshing = false) }
        throw e
    }

    /** Applies [transform] only if no [reset] has happened since [gen] was captured. */
    private fun <T> apply(
        gen: Int,
        flow: MutableStateFlow<Section<T>>,
        transform: (Section<T>) -> Section<T>,
    ): Boolean = synchronized(stateLock) {
        if (gen != generation) return false
        flow.update(transform)
        true
    }

    /**
     * True, after dropping [flow]'s `refreshing` flag, when someone other than [owner] is signed in
     * now: the answer in hand is the previous user's and must not be shown to, or stored for, the
     * next one. [reset] normally catches this first -- SessionManager and CredentialExpiry both
     * call it -- but the owner check means a path that ever forgets to still cannot leak.
     */
    private fun <T> ownerChanged(gen: Int, owner: String?, flow: MutableStateFlow<Section<T>>): Boolean {
        if (snapshots.currentOwner() == owner) return false
        apply(gen, flow) { it.copy(refreshing = false) }
        return true
    }

    private fun isCurrent(gen: Int) = synchronized(stateLock) { gen == generation }

    private fun isFresh(fetchedAt: Instant?) = fetchedAt != null && fetchedAt.isAfter(now().minus(STALE_AFTER))

    // BudgetApi's contract is Result<T> failing only with BudgetApiError; this is belt and braces.
    private fun Throwable.asApiError() = this as? BudgetApiError ?: BudgetApiError.Network(this)

    companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(5)
        const val BILL_DAYS = 14
        private val UNSUPPORTED_CODES = setOf(404, 501)
    }
}
