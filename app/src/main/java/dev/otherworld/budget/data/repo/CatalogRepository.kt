package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.local.SnapshotCodec
import dev.otherworld.budget.data.local.SnapshotKind
import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.data.remote.Capabilities
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.Category
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accounts, categories and capabilities change rarely, so they are cached for the process
 * lifetime -- the review screen must not re-fetch them on every capture -- and persisted through
 * [SnapshotStore] so a cold start with no network still has *something* to show: the Review
 * screen offline dead end this exists to fix is a capture with no cached accounts to post
 * against, on a device that has fetched them before but killed the process since.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: BudgetApi,
    private val snapshots: SnapshotStore,
) {

    private val lock = Mutex()

    /**
     * The in-memory copy of one kind's value, plus whether it came from a real fetch this
     * process lifetime ([fresh] = true) or is merely a persisted-snapshot fallback ([fresh] =
     * false). The distinction is load-bearing: only a *fresh* value may short-circuit an
     * unforced call in [cached]. A fallback value must not pin memory for the rest of the
     * process -- the whole point of "put it in memory without marking it fresh" (see [cached]'s
     * KDoc) is that the *next* unforced call still tries the network, which is what lets a cold
     * start recover once connectivity returns rather than being stuck on stale data forever (see
     * [dev.otherworld.budget.ui.capture.CaptureViewModel.refresh]'s KDoc for the same concern one
     * layer up).
     */
    private class Slot<T> {
        var value: T? = null
        var fresh: Boolean = false
    }

    private val accounts = Slot<List<Account>>()
    private val categories = Slot<List<Category>>()
    private val capabilities = Slot<Capabilities>()

    /**
     * The session a fetch belongs to, bumped by every [invalidate] (and so every
     * [clearPersisted]). [cached] captures it before fetching and drops the answer -- neither kept
     * in memory nor persisted -- when it has moved on by the time the answer lands: with a 120 s
     * read timeout, the user that fetch was for can have signed out, and someone else signed in,
     * in the meantime. Guarded by [stateLock] together with the slots, so "is this still the same
     * session?" and "keep the answer" are one step. Deliberately not [lock]: sign-out must never
     * queue behind a fetch that may take two minutes to time out.
     */
    private val stateLock = Any()
    private var epoch = 0

    suspend fun accounts(forceRefresh: Boolean = false): Result<List<Account>> = lock.withLock {
        cached(
            slot = accounts,
            forceRefresh = forceRefresh,
            kind = SnapshotKind.ACCOUNTS,
            encode = SnapshotCodec::encodeAccounts,
            decode = SnapshotCodec::decodeAccounts,
            fetch = api::accounts,
        )
    }

    suspend fun categories(forceRefresh: Boolean = false): Result<List<Category>> = lock.withLock {
        cached(
            slot = categories,
            forceRefresh = forceRefresh,
            kind = SnapshotKind.CATEGORIES,
            encode = SnapshotCodec::encodeCategories,
            decode = SnapshotCodec::decodeCategories,
            fetch = api::categories,
        )
    }

    suspend fun capabilities(forceRefresh: Boolean = false): Result<Capabilities> = lock.withLock {
        cached(
            slot = capabilities,
            forceRefresh = forceRefresh,
            kind = SnapshotKind.CAPABILITIES,
            encode = SnapshotCodec::encodeCapabilities,
            decode = SnapshotCodec::decodeCapabilities,
            fetch = api::capabilities,
        )
    }

    /**
     * Shared cache/persist/fall-back shape for all three kinds:
     * 1. In memory, *fresh* (came from a fetch, not a fallback), and not forced -> return it.
     * 2. Otherwise fetch. On success, keep it in memory marked fresh, persist the snapshot, and
     *    return it.
     * 3. On failure, read the persisted snapshot. If there is one for the signed-in owner, put
     *    it in memory *without* marking it fresh (so step 1 will not short-circuit the next
     *    unforced call -- it must keep retrying the network) and return it as a success --
     *    last-known data beats nothing, which is what makes a *forced* refresh still fall back
     *    rather than surface the error. Deliberately does not re-[SnapshotStore.write] it: this
     *    value did not just come from the network, and doing so would bump its `fetchedAt` to
     *    now, corrupting [accountsFetchedAt]'s staleness reading with a fetch that never
     *    happened. Otherwise, the original failure stands.
     *
     * Steps 2 and 3 only touch memory while [epoch] is still the one captured before the fetch,
     * and the snapshot is written under the owner captured at the same moment (see
     * [SnapshotStore.write]); an answer that outlived its session is returned to its caller and
     * otherwise forgotten.
     */
    private suspend fun <T> cached(
        slot: Slot<T>,
        forceRefresh: Boolean,
        kind: SnapshotKind,
        encode: (T) -> String,
        decode: (String) -> T?,
        fetch: suspend () -> Result<T>,
    ): Result<T> {
        val (startEpoch, current, fresh) = synchronized(stateLock) { Triple(epoch, slot.value, slot.fresh) }
        if (current != null && fresh && !forceRefresh) return Result.success(current)
        val owner = snapshots.currentOwner()
        val result = fetch()
        return result.fold(
            onSuccess = {
                val kept = synchronized(stateLock) {
                    if (epoch != startEpoch) return@synchronized false
                    slot.value = it
                    slot.fresh = true
                    true
                }
                if (kept) snapshots.write(kind, encode(it), owner)
                result
            },
            onFailure = {
                val fallback = snapshots.read(kind, decode) ?: return result
                synchronized(stateLock) {
                    if (epoch == startEpoch) {
                        slot.value = fallback.value
                        slot.fresh = false
                    }
                }
                Result.success(fallback.value)
            },
        )
    }

    /**
     * True when the accounts held in memory are the persisted snapshot standing in for a failed
     * fetch rather than a network answer -- so a caller can serve them (Review and Quick Add
     * still need something to post against offline) while still telling the user the server
     * could not be reached, which Capture's offline notice exists to do.
     */
    fun accountsAreFallback(): Boolean = synchronized(stateLock) { accounts.value != null && !accounts.fresh }

    /** fetchedAt of the persisted accounts snapshot, for Overview's staleness line. */
    suspend fun accountsFetchedAt(): Instant? =
        snapshots.read(SnapshotKind.ACCOUNTS, SnapshotCodec::decodeAccounts)?.fetchedAt

    /**
     * Drops the in-memory values and starts a new [epoch], so a fetch still in flight cannot put
     * them back (non-suspending: [dev.otherworld.budget.data.auth.CredentialExpiry] calls it from
     * the request path). Deliberately leaves the persisted snapshots alone -- see
     * that class's KDoc for why an expiry does not need to touch them.
     */
    fun invalidate() {
        synchronized(stateLock) {
            epoch++
            accounts.value = null; accounts.fresh = false
            categories.value = null; categories.fresh = false
            capabilities.value = null; capabilities.fresh = false
        }
    }

    /**
     * [invalidate] plus the persisted snapshots -- sign-out and a server change on sign-in. Never
     * takes [lock]: see [epoch] for why it need not, and why it must not.
     */
    suspend fun clearPersisted() {
        invalidate()
        snapshots.clear()
    }
}
