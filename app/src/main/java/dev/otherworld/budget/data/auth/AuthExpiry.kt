package dev.otherworld.budget.data.auth

import dev.otherworld.budget.data.remote.FallbackCurrencyCache
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The app's single "our stored app password is no longer accepted" signal, required by the
 * design's error model (§5: `Unauthorized` -> clear credentials, return to onboarding).
 *
 * Kept separate from [Session] rather than being another method on [SessionManager] because
 * [SessionManager] depends on [dev.otherworld.budget.data.repo.ReceiptQueue] and
 * [CatalogRepository]; anything that must *raise* this signal sits underneath those, so putting
 * it on [SessionManager] would close a dependency cycle in the Hilt graph.
 */
interface AuthExpiry {
    /**
     * Generation of the currently stored credentials. Callers capture it *before* dispatching a
     * request and hand it back to [onUnauthorized], which ties a 401 to the credentials that
     * actually signed the failing request. Bumped by [onCredentialsChanged] on every save and
     * by each acted-upon expiry.
     */
    val currentGeneration: Int

    /**
     * Announce that fresh credentials were just stored. A 401 still in flight from before this
     * call was signed with the *old* password; bumping the generation is what stops it wiping
     * the new one -- the exact failure being a slow request racing a successful re-login: the
     * shared client's read timeout is 120s, so a request dispatched before expiry can land its
     * 401 well after the user has signed back in.
     */
    fun onCredentialsChanged()

    /**
     * Raise the expiry for a 401 answered to a request signed at [requestGeneration]. No-ops
     * unless that is still the current generation -- a stale 401 from a superseded session
     * (or a second 401 from the same dying one) must not clear anything.
     */
    fun onUnauthorized(requestGeneration: Int)

    /**
     * Emitted once per expiry, for whoever can act on the "return to onboarding" half of the
     * rule -- clearing credentials is not visible to a user already looking at a screen.
     *
     * Deliberately has **no replay**: a subscriber sees expiries that happen while it is
     * subscribed and nothing else. A replayed one would be re-delivered on every rotation and
     * every return to the foreground, which would eject a user who had since signed back in,
     * and would restart an in-progress login from scratch. An expiry raised while nothing is
     * subscribed needs no signal anyway -- the credentials are already gone, so the next launch
     * resolves its start destination to Onboarding by itself.
     */
    val expirations: SharedFlow<Unit>
}

/**
 * Clears the stored credentials, the catalog cache, the check sections and the fallback-currency
 * cache, then announces the expiry.
 *
 * Deliberately *not* a sign-out: the queue rows and their photos are the user's un-saved work
 * and survive, so a revoked app password costs a re-login, never a receipt.
 *
 * The catalog cache has to go with the credentials. It is process-lifetime by design
 * ([CatalogRepository]), so leaving it populated let Capture keep rendering accounts and an
 * OCR-available banner from a session that no longer exists -- a healthy-looking screen over a
 * queue that could not drain, for as long as the process lived. It is fetched from a
 * [Provider] because [CatalogRepository] is built on [dev.otherworld.budget.data.remote.BudgetApi],
 * which is built on this class: the indirection is what keeps that cycle out of the Hilt graph,
 * and by the time [onUnauthorized] runs the singleton it resolves is the same instance every
 * screen reads from. [FallbackCurrencyCache] is per-server state for the same reason the
 * catalog is, and goes with it.
 *
 * [catalog]'s `invalidate()` only drops the in-memory values, never the persisted snapshots --
 * deliberately, since this runs from a non-suspending failure handler on the request path and
 * must not block it. It does not need to: [store]`.clear()` above already removes the
 * credentials, and [dev.otherworld.budget.data.repo.SnapshotStore] scopes every read by whoever
 * is signed in *now*, so the persisted rows become unreadable the instant those credentials are
 * gone, with no separate deletion required. They stay on disk, orphaned, until the next sign-in
 * resolves them: [SessionManager.signIn] clears them outright when the server differs, or leaves
 * them -- legitimately the same owner's -- when it is the same server and user.
 *
 * [check]'s sections are reset here too, for the in-memory half of the same problem: the snapshots
 * are owner-scoped but [CheckRepository]'s flows are not, and [SessionManager.signIn] cannot tell
 * a different user on the same server from the same one returning. Left populated, the next user
 * would be shown the previous one's balances -- and kept on them, since a non-forced refresh skips
 * every section still inside its freshness window. From a [Provider] for the same cycle reason as
 * [catalog]; [CheckRepository.reset] is non-suspending, so it is safe on the request path.
 */
@Singleton
class CredentialExpiry @Inject constructor(
    private val store: CredentialStore,
    private val catalog: Provider<CatalogRepository>,
    private val check: Provider<CheckRepository>,
    private val currencyCache: FallbackCurrencyCache,
) : AuthExpiry {

    private val generation = AtomicInteger(0)

    override val currentGeneration: Int
        get() = generation.get()

    override fun onCredentialsChanged() {
        generation.incrementAndGet()
    }

    // extraBufferCapacity = 1 + DROP_OLDEST so tryEmit always succeeds: onUnauthorized is called
    // from a non-suspending failure handler on the request path and must never block it, nor
    // depend on how promptly the UI happens to be collecting.
    private val _expirations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val expirations: SharedFlow<Unit> = _expirations.asSharedFlow()

    override fun onUnauthorized(requestGeneration: Int) {
        // compareAndSet makes the check-and-advance atomic: exactly one 401 per generation
        // acts. A 401 from a request signed with an already-superseded password -- or a second
        // 401 from the same dying session -- fails the CAS and changes nothing.
        if (!generation.compareAndSet(requestGeneration, requestGeneration + 1)) return
        store.clear()
        catalog.get().invalidate()
        check.get().reset()
        currencyCache.clear()
        _expirations.tryEmit(Unit)
    }
}
