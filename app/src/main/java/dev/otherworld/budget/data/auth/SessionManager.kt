package dev.otherworld.budget.data.auth

import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.prefs.LastServerStore
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.BudgetService
import dev.otherworld.budget.data.remote.FallbackCurrencyCache
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.theme.ThemePalette
import dev.otherworld.budget.data.work.QueueScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface Session {
    /**
     * Completes a successful login: persists [credentials] and reconciles every piece of state
     * that belonged to the previous session -- see [SessionManager.signIn]. All credential
     * saves go through here so no caller can persist a login without that reconciliation.
     */
    suspend fun signIn(credentials: Credentials)
    suspend fun signOut(): Result<Unit>
}

@Singleton
class SessionManager @Inject constructor(
    private val store: CredentialStore,
    private val service: BudgetService,
    private val queue: ReceiptQueue,
    private val catalog: CatalogRepository,
    private val check: CheckRepository,
    private val scheduler: QueueScheduling,
    private val lastAccount: LastAccountStore,
    private val lastServer: LastServerStore,
    private val currencyCache: FallbackCurrencyCache,
    private val theme: ThemePalette,
    private val authExpiry: AuthExpiry,
) : Session {

    /**
     * The queue deliberately survives a credential expiry ("a revoked app password costs a
     * re-login, never a receipt" -- [CredentialExpiry]), which means the next sign-in may be
     * against a *different* server than the surviving rows were built for. [lastServer] --
     * plain prefs, so it too survives the credential wipe -- is the record of which server
     * that was. When it differs, everything keyed to the old server is reconciled: FAILED rows
     * are demoted to mandatory review with their foreign account/category ids scrubbed
     * ([ReceiptQueue.parkFailedForServerChange]), the remembered default account is forgotten,
     * and the catalog (memory *and* its persisted snapshots -- [CatalogRepository.clearPersisted])
     * the check screens' sections ([CheckRepository.reset]) and fallback-currency caches are
     * dropped so nothing from the old server is offered against the new one.
     *
     * NonCancellable: this runs in the onboarding screen's coroutine scope, and a rotation or
     * back-press mid-save must not leave credentials stored but the old server's FAILED rows
     * still armed for [ReceiptQueue.retryFailedPosts].
     */
    override suspend fun signIn(credentials: Credentials): Unit = withContext(NonCancellable) {
        val previousServer = lastServer.get()

        // Order is load-bearing: the park must complete BEFORE store.save makes the new server
        // visible to BaseUrlInterceptor (which resolves the base URL per request, at dispatch
        // time). parkFailedForServerChange acquires the retry-sweep lock, so a sweep already
        // in flight -- e.g. a scheduled PostWorker attempt, which survives a credential expiry
        // -- finishes entirely against the old credentials (post-expiry that means the cleared
        // store's placeholder host: every post fails proven-never-sent and the rows stay
        // FAILED, exactly what the park then collects). Only once the lock has been held and
        // released -- FAILED set empty -- do the new credentials get saved. With save-first,
        // an in-flight sweep's remaining posts resolved to the NEW server while carrying old
        // rows' account ids: the precise unattended cross-server write this exists to prevent.
        if (previousServer != null && previousServer != credentials.server) {
            queue.parkFailedForServerChange()
            lastAccount.clear()
            check.reset()             // before the snapshots go, so an in-flight refresh can't rewrite one
            catalog.clearPersisted()
            currencyCache.clear()
            // The old server's brand colour must not persist into the new session: revert to the
            // default now, so the new server's colour is fetched clean (onboarding calls refresh()
            // after this) instead of the previous server's colour lingering until it succeeds.
            theme.clear()
        }

        store.save(credentials)
        // A 401 still in flight was signed with the previous password; bumping the generation
        // stops it clearing the credentials just saved. See AuthExpiry.onCredentialsChanged.
        authExpiry.onCredentialsChanged()
        lastServer.set(credentials.server)
    }

    /**
     * Revokes the app password server-side, then wipes everything local.
     * The local wipe happens even if revocation fails -- an offline user, or one
     * whose server is gone, must still be able to sign out.
     *
     * Goes through [BudgetService] (Retrofit), not a hand-built OkHttp [okhttp3.Request] --
     * the shared client carries [dev.otherworld.budget.di.BaseUrlInterceptor], which
     * unconditionally prepends the stored server's path prefix to every request's path. A
     * request built with an already-absolute URL (e.g. "$server/ocs/...") would have that
     * prefix applied a second time, breaking any server deployed under a subpath (see
     * [dev.otherworld.budget.di.BaseUrlInterceptor]'s KDoc). Going through the same Retrofit
     * service every other endpoint uses guarantees the prefix is applied exactly once, the
     * same way it is everywhere else in this app.
     */
    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        val revocation = runCatching {
            val response = service.revokeAppPassword()
            // The body is never inspected -- only the status matters -- but Retrofit leaves an
            // unconverted raw ResponseBody open until the caller consumes or closes it, so both
            // the success and error body are closed explicitly here.
            response.body()?.close()
            response.errorBody()?.close()
            // A non-2xx (401/404/500/...) is a real revocation failure, not success -- the app
            // password may still be valid on the server, so this must not be swallowed.
            if (!response.isSuccessful) throw BudgetApiError.ServerError(response.code())
        }

        scheduler.cancelAll()
        queue.clearAll()          // deletes rows and their photos
        check.reset()             // the check screens' figures, and any refresh still in flight
        catalog.clearPersisted()
        lastAccount.clear()       // an account id belonging to the server we're leaving
        currencyCache.clear()     // ...and its currency; the next server states its own
        theme.clear()             // ...and its brand colour; signed out returns to the default
        store.clear()
        // The session these credentials belonged to is over: a 401 from a request still in
        // flight (signed with the just-revoked password) must not act on whatever session
        // comes next -- without this bump it could eject the user once, mid-next-login.
        authExpiry.onCredentialsChanged()

        revocation
    }
}
