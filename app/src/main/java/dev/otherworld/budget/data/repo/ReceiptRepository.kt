package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.local.DraftCodec
import dev.otherworld.budget.data.local.PendingReceiptDao
import dev.otherworld.budget.data.local.PendingReceiptEntity
import dev.otherworld.budget.data.local.PhotoStore
import dev.otherworld.budget.data.local.SplitCodec
import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.data.work.ReceiptNotifying
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PendingReceipt(
    val id: Long,
    /** Null when this row never had a photo -- see [dev.otherworld.budget.data.local.PendingReceiptEntity.photoPath]. */
    val photoPath: String?,
    val capturedAt: Long,
    val state: CaptureState,
    val draft: DraftTransaction?,
    val attempts: Int,
    val lastError: String?,
)

/**
 * A [ReceiptQueue.post] was refused because that same receipt is already being posted by
 * another caller in this process (the user's Save racing Settings' "Retry failed", a
 * double-tap, or [ReceiptQueue.retryFailedPosts] overlapping a live save). The idempotency key
 * means the server would dedupe the second POST rather than double-charge, so this is an
 * optimisation now, not the last line of defence: refusing avoids a wasted second photo upload
 * and a needless `409 request_in_flight`. The second caller is told no rather than started.
 */
class PostAlreadyInFlightException(id: Long) :
    IllegalStateException("Receipt $id is already being saved")

sealed interface ExtractOutcome {
    data object Idle : ExtractOutcome
    data class Extracted(val id: Long) : ExtractOutcome
    /** Backend answered but produced no usable draft; review still opens. */
    data class Failed(val id: Long, val error: BudgetApiError) : ExtractOutcome
    /** Transport failure; the item stays queued. */
    data object Retry : ExtractOutcome
}

/**
 * Everything the rest of the app may do to the capture queue.
 * ViewModels and workers depend on this, never on ReceiptRepository directly,
 * so they can be tested without Room.
 */
interface ReceiptQueue {
    fun observeQueue(): Flow<List<PendingReceipt>>
    fun observeAwaitingReview(): Flow<List<PendingReceipt>>
    suspend fun byId(id: Long): PendingReceipt?
    suspend fun oldestAwaitingReview(): PendingReceipt?
    suspend fun enqueue(photo: File): Long
    /**
     * Enqueues a row with no photo, already in
     * [dev.otherworld.budget.domain.model.CaptureState.AWAITING_REVIEW] and carrying [draft] --
     * the manual-entry counterpart of [enqueue], used by Quick Add.
     *
     * Quick Add exists to record a transaction that has no receipt to photograph, so nothing here
     * needs extracting; the user has already supplied everything a draft would have contained.
     * Starting the row in AWAITING_REVIEW rather than CAPTURED is what keeps it away from
     * [extractNext] (which has no image to send) while leaving it on the one path that matters:
     * the caller posts it via [post] immediately, so a manual entry inherits every duplicate-post
     * protection a captured receipt gets -- the in-flight guard, the never-sent/unknown failure
     * split, [reconcileInterrupted], and the retry sweep -- instead of taking a second, unguarded
     * route to `POST transactions`. It also means a manual entry works offline for free: a post
     * that cannot be made leaves a real queue row behind, which the Capture banner surfaces and
     * Review re-opens pre-filled.
     *
     * [draft] is persisted up front rather than left to [post] so that a process death between
     * this call and the post still leaves the user's typing recoverable.
     */
    suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long
    suspend fun extractNext(): ExtractOutcome
    /**
     * Posts one reviewed receipt, tagged with the row's idempotency key so a retry the server has
     * already seen replays that transaction instead of duplicating it. Fails with
     * [PostAlreadyInFlightException] if a post of the same id is already in flight in this process
     * -- callers never have to guard that themselves.
     *
     * A failure lands the row in one of three states, decided by
     * [dev.otherworld.budget.data.remote.BudgetApiError.isRetryable]: FAILED (auto-retried by
     * [retryFailedPosts]) for a transient failure -- now including read timeouts and 5xx, which the
     * key makes safe to replay; AWAITING_REVIEW with [ReceiptRepository.INTERRUPTED_POST_MESSAGE]
     * for a failure that needs a human, such as [dev.otherworld.budget.data.remote.BudgetApiError.Unauthorized];
     * and AWAITING_REVIEW with [ReceiptRepository.KEY_CONFLICT_MESSAGE] and a freshly rotated key on
     * [dev.otherworld.budget.data.remote.BudgetApiError.IdempotencyKeyConflict], the one failure a
     * replay of the same key cannot fix.
     *
     * A post whose coroutine is cancelled mid-flight is parked in AWAITING_REVIEW with
     * [ReceiptRepository.INTERRUPTED_POST_MESSAGE] (under NonCancellable, so the parking always
     * completes) and the cancellation is rethrown to the caller: an interruption with no server
     * answer at all keeps mandatory review, and the key still protects the eventual re-save.
     */
    suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction>
    /**
     * Re-posts every FAILED row. Safe to call automatically because every attempt carries the row's
     * idempotency key, so re-sending a request the server may already have applied replays that one
     * transaction rather than creating a second -- see [post]. Returns true if every retry
     * succeeded; a sweep that is refused because another sweep is already running returns false.
     */
    suspend fun retryFailedPosts(): Boolean      // true if every retry succeeded
    /**
     * Recovers rows stranded in POSTING -- e.g. the process died between persisting the
     * user's choices and hearing back from the server. Moves them to AWAITING_REVIEW, not
     * FAILED. The idempotency key would make an unattended re-send duplicate-safe, but a crash
     * mid-post means no server answer of any kind came back, so mandatory review stays the
     * conservative default (a resend then still replays, never duplicates) -- and review is the
     * natural place a user resumes after an interruption anyway. Draft, accountId and categoryId
     * are preserved so re-review is pre-filled; attempts is left untouched because no attempt
     * actually completed.
     */
    suspend fun reconcileInterrupted()
    /**
     * Called after signing in to a *different* server than the one the queue's FAILED rows
     * were built against. A FAILED row is one queued for unattended re-post -- but that
     * judgement was made for the old server: the rows' stored accountId/categoryId are foreign
     * ids there, and small integer ids exist on virtually every Nextcloud instance, so an
     * unattended re-post ([retryFailedPosts] via PostWorker, which survives a credential
     * expiry) would silently write transactions -- and upload receipt photos -- into an
     * arbitrary account of a server the user never reviewed them for. Rows are demoted to
     * AWAITING_REVIEW with the foreign ids scrubbed and
     * [ReceiptRepository.SERVER_CHANGED_MESSAGE] stored, so mandatory review re-applies before
     * anything is sent. Other states are untouched: CAPTURED/AWAITING_REVIEW rows were already
     * on user-mediated paths, and POSTING rows belong to a live post.
     */
    suspend fun parkFailedForServerChange()
    suspend fun discard(id: Long)
    suspend fun clearAll()
    /**
     * Applies the queue bounds (spec §7: [maxItems] rows / [maxAgeMillis] old). Never deletes a
     * row or a referenced photo: rows past the bounds are counted and surfaced through
     * [dev.otherworld.budget.data.work.ReceiptNotifying.notifyStaleQueue] for the user to
     * review or discard; only orphaned photo files are swept. See [ReceiptRepository.prune].
     */
    suspend fun prune(maxItems: Int = MAX_ITEMS, maxAgeMillis: Long = MAX_AGE_MILLIS)

    companion object {
        /**
         * Spec §7's queue bounds. Named here -- not inlined into [prune]'s defaults -- because
         * two sides must agree on what "stale" means: the prune pass that raises the
         * notification, and the observer that retires it once nothing is stale any more
         * (see MainActivity). A drift between the two would strand or prematurely kill it.
         */
        const val MAX_ITEMS = 100
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

@Singleton
class ReceiptRepository @Inject constructor(
    private val api: BudgetApi,
    private val dao: PendingReceiptDao,
    private val photos: PhotoStore,
    private val scheduler: QueueScheduling,
    private val notifier: ReceiptNotifying,
) : ReceiptQueue {

    /**
     * Guards the POSTING <-> {AWAITING_REVIEW, FAILED, deleted} transitions so
     * [reconcileInterrupted] can never race a live [post]. A row's DB state alone cannot tell
     * reconcileInterrupted() whether POSTING means "the process died mid-post" (safe to reset)
     * or "post() is still waiting on the network, right now, in this process" (unsafe to reset:
     * the live post is about to write the row's final state itself, so resetting it underneath
     * means two writers racing on one row and the user acting on a row prematurely made reviewable
     * -- the idempotency key keeps that from becoming a duplicate charge, but it is still state
     * thrash and a wasted re-upload worth preventing). [inFlightPosts] carries that extra bit of
     * information that the DB alone doesn't have. The mutex ensures a row's DB
     * state and its inFlightPosts membership always change together, so reconcileInterrupted()
     * never observes one without the other. The network call itself stays outside the lock so
     * concurrent posts of different receipts are not serialised on each other's round trip.
     *
     * A row can end up POSTING but absent from [inFlightPosts] -- i.e. eligible for
     * reconcileInterrupted() to recover -- only when the process died outright mid-post. The
     * other teardown, an *in-process* interruption of the API call (cancellation from the user
     * leaving the screen, [QueueScheduler].cancelAll(), WorkManager stopping a worker on
     * constraint loss/timeout, or a thrown exception from a non-production [BudgetApi]), no
     * longer strands the row: [post]'s catch block parks it in AWAITING_REVIEW with
     * [INTERRUPTED_POST_MESSAGE] under NonCancellable and then rethrows. Both teardowns get the
     * same conservative treatment -- mandatory review with the check-Recent warning -- because
     * neither tells us whether the server actually received the request; only a definitive
     * [Result] from the API (success or a genuine [BudgetApiError] failure) is trusted to
     * record a final outcome.
     */
    private val postingMutex = Mutex()
    private val inFlightPosts = mutableSetOf<Long>()

    /**
     * Admits one [retryFailedPosts] sweep at a time. Two overlapping sweeps each snapshot the
     * FAILED rows *before* the other starts changing them, so the second would re-post a row
     * the first had already dealt with -- and [inFlightPosts] cannot catch that, because by then
     * the first sweep's post has finished and the id is no longer in flight. Non-blocking
     * (`tryLock`): a refused sweep has nothing useful to wait for, since the running one is
     * already doing exactly the work it would do.
     */
    private val retrySweepMutex = Mutex()

    override fun observeQueue(): Flow<List<PendingReceipt>> = dao.observeAll().map { it.map(::toDomain) }

    override fun observeAwaitingReview(): Flow<List<PendingReceipt>> =
        dao.observeAwaitingReview().map { it.map(::toDomain) }

    override suspend fun byId(id: Long): PendingReceipt? = dao.byId(id)?.let(::toDomain)

    override suspend fun oldestAwaitingReview(): PendingReceipt? =
        dao.nextInState(CaptureState.AWAITING_REVIEW)?.let(::toDomain)

    override suspend fun enqueue(photo: File): Long = dao.insert(
        PendingReceiptEntity(
            photoPath = photo.absolutePath,
            capturedAt = System.currentTimeMillis(),
            state = CaptureState.CAPTURED,
            idempotencyKey = newIdempotencyKey(),
        )
    )

    override suspend fun enqueueWithoutPhoto(draft: DraftTransaction): Long = dao.insert(
        PendingReceiptEntity(
            photoPath = null,
            capturedAt = System.currentTimeMillis(),
            state = CaptureState.AWAITING_REVIEW,
            draftJson = DraftCodec.encode(draft),
            idempotencyKey = newIdempotencyKey(),
        )
    )

    /**
     * A fresh idempotency key for a new row (or a burned one being rotated -- see [post]). A UUID
     * is 36 chars, inside the server's 64-char cap, and unique per row so two receipts are never
     * deduped into one. Minted here, once, and thereafter replayed unchanged on every post attempt
     * for the row -- that stability is what lets a retry join, rather than duplicate, the
     * transaction the first attempt may have created.
     */
    private fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    override suspend fun extractNext(): ExtractOutcome {
        val entity = dao.nextInState(CaptureState.CAPTURED) ?: return ExtractOutcome.Idle
        // CAPTURED is only ever reached by enqueue(), which always has a file, so a null path
        // here is not a state this app can produce -- but extraction is the one place a null
        // would otherwise crash a background worker, and a worker that throws retries forever.
        // Move it on to review (where it is now editable and postable without a photo) rather
        // than sending a request that cannot be built.
        val photoPath = entity.photoPath ?: run {
            dao.update(entity.copy(state = CaptureState.AWAITING_REVIEW))
            return ExtractOutcome.Extracted(entity.id)
        }
        val result = api.extract(File(photoPath))

        result.getOrNull()?.let { draft ->
            dao.update(entity.copy(
                state = CaptureState.AWAITING_REVIEW,
                draftJson = DraftCodec.encode(draft),
                lastError = null,
            ))
            return ExtractOutcome.Extracted(entity.id)
        }

        val error = result.exceptionOrNull() as? BudgetApiError ?: BudgetApiError.ServerError(0)
        return when (error) {
            // The server answered definitively. Open review with an empty draft so the
            // capture is not wasted -- the user types the amount by hand.
            is BudgetApiError.ExtractionFailed,
            is BudgetApiError.OcrNotConfigured,
            is BudgetApiError.OcrQuotaExhausted -> {
                dao.update(entity.copy(
                    state = CaptureState.AWAITING_REVIEW,
                    draftJson = DraftCodec.encode(DraftTransaction()),
                    lastError = error.message,
                ))
                ExtractOutcome.Failed(entity.id, error)
            }
            // Transport or auth problem. Leave it queued and count the attempt -- the photo is
            // never thrown away for something that may be temporary. A revoked app password
            // (Unauthorized) does not stall here forever: BudgetApiRetrofit has already raised
            // AuthExpiry, which clears the credentials and returns the UI to Onboarding, so this
            // row is extracted once the user is signed in again.
            else -> {
                dao.update(entity.copy(attempts = entity.attempts + 1, lastError = error.message))
                ExtractOutcome.Retry
            }
        }
    }

    override suspend fun post(id: Long, request: CreateTransactionRequest): Result<CreatedTransaction> {
        val entity = dao.byId(id) ?: return Result.failure(IllegalStateException("No receipt $id"))

        // Persist the user's edits and their account/category choice onto the row
        // *before* the network call. A retry of a failed post reuses what the user
        // chose here -- the extraction draft never contained accountId/categoryId.
        //
        // Built from the stored draft, copying over only what the user actually edited on this
        // screen -- the same pattern parkFailedForServerChange() uses -- rather than rebuilt
        // field-by-field. A field-by-field rebuild silently drops whatever DraftTransaction
        // carries that this screen never touches (lineItems, subtotal, tax): the request has no
        // opinion on those, so they must survive from what extraction (or a prior edit) put
        // there, not default to null and overwrite it below.
        val stored = entity.draftJson?.let(DraftCodec::decode)
        val edited = (stored ?: DraftTransaction()).copy(
            merchant = request.merchant,
            date = request.date,
            total = request.total,
            suggestedCategoryId = request.categoryId,
        )
        val posting = entity.copy(
            state = CaptureState.POSTING,
            draftJson = DraftCodec.encode(edited),
            accountId = request.accountId,
            categoryId = request.categoryId,
            splitsJson = request.splits?.let(SplitCodec::encode),
        )

        // See postingMutex's KDoc: the row becomes POSTING and is marked in-flight atomically,
        // so reconcileInterrupted() can never see one without the other.
        //
        // The same lock is where a second, concurrent post of this id is refused. The server would
        // now dedupe two live POSTs of one row (both carry the row's idempotency key), so this is no
        // longer the sole line against a double charge -- but refusing the second here still avoids
        // a wasted second photo upload and a needless `409 request_in_flight` round trip, and does
        // it as one invariant at the choke point rather than a re-entrancy guard every call site
        // (Review's Save, Settings' "Retry failed", PostWorker) must remember.
        val started = postingMutex.withLock {
            if (id in inFlightPosts) {
                false
            } else {
                dao.update(posting)
                inFlightPosts += id
                true
            }
        }
        if (!started) return Result.failure(PostAlreadyInFlightException(id))

        val result: Result<CreatedTransaction> = try {
            // The row's idempotency key rides every attempt for this row unchanged, so a retry the
            // server has already seen replays that transaction instead of creating a second one.
            api.createTransaction(request, entity.idempotencyKey)
        } catch (e: Throwable) {
            // A thrown exception here is not a definitive API failure: BudgetApi's contract is
            // that real failures arrive as a failed Result and cancellation arrives as
            // CancellationException (BudgetApiRetrofit.call rethrows it; see also
            // ErrorMapper.fromThrowable). So this catch means the call was torn down mid-flight
            // -- the user backed out of a save, QueueScheduler.cancelAll() ran, WorkManager
            // stopped this worker on constraint loss/timeout -- and the server's state is
            // unknown: the request may or may not have been delivered first. The row is
            // therefore parked in AWAITING_REVIEW carrying INTERRUPTED_POST_MESSAGE -- the same
            // conservative unknown-outcome treatment an ambiguous failed Result gets below --
            // rather than left inert in POSTING until the next launch's reconcileInterrupted().
            // Parked, it surfaces immediately in Capture's ready-to-review banner, and mandatory
            // review plus the check-Recent warning remain the duplicate defence. attempts is
            // left untouched, like reconcileInterrupted(): no attempt verifiably completed.
            //
            // Must run under NonCancellable: an already-cancelled coroutine cannot suspend
            // normally (to wait for the mutex, or for Room) without it. If this cleanup were
            // skipped, `id` would leak in inFlightPosts for the rest of the process's life and
            // the row would strand in POSTING with no recovery path.
            //
            // The exception is rethrown afterwards: a cancelled caller must observe
            // cancellation, not a fabricated outcome.
            withContext(NonCancellable) {
                postingMutex.withLock {
                    inFlightPosts -= id
                    dao.update(posting.copy(
                        state = CaptureState.AWAITING_REVIEW,
                        lastError = INTERRUPTED_POST_MESSAGE,
                    ))
                }
            }
            throw e
        }

        // The call returned a definitive outcome (success or a genuine BudgetApiError failure),
        // so clearing the in-flight marker and writing the row's final state happen together,
        // under NonCancellable so a cancellation racing the call's return can't split them: a
        // completed network call must never be left un-recorded (a successful post whose row is
        // never deleted would resubmit as a real duplicate the next time the user reviews it).
        var autoRetryable = false
        withContext(NonCancellable) {
            postingMutex.withLock {
                inFlightPosts -= id
                result.onSuccess {
                    dao.delete(id)
                }.onFailure { error ->
                    // Where a failed post lands. The idempotency key makes any replay
                    // duplicate-safe, so the old "did the request reach the server?" split is gone;
                    // the question is now just whether an unattended retry will help
                    // (BudgetApiError.isRetryable, the one place the rule lives). Three outcomes:
                    //
                    //  - IdempotencyKeyConflict: the server holds a *different* purchase under this
                    //    key. Re-sending the same key can only hit the same wall, so the key is
                    //    rotated and the row goes back to mandatory review -- never auto-retried.
                    //  - retryable (any Network failure, a 5xx, request_in_flight): FAILED, which
                    //    retryFailedPosts() re-sends unattended. This now includes the read
                    //    timeouts and proxy 504s the old design had to park on the user -- the key
                    //    turns "go check Recent yourself" into silent recovery.
                    //  - anything else needing a human (Unauthorized, a non-409 4xx): back to
                    //    AWAITING_REVIEW with the check-Recent warning.
                    //
                    // attempts is incremented in every branch: unlike a crash-stranded row, an
                    // attempt genuinely completed here. Build from `posting`, not `entity`, or the
                    // accountId/categoryId/draftJson just persisted above would be lost.
                    val budgetError = error as? BudgetApiError
                    when {
                        budgetError is BudgetApiError.IdempotencyKeyConflict ->
                            dao.update(posting.copy(
                                state = CaptureState.AWAITING_REVIEW,
                                attempts = posting.attempts + 1,
                                idempotencyKey = newIdempotencyKey(),
                                lastError = KEY_CONFLICT_MESSAGE,
                            ))
                        budgetError?.isRetryable() == true -> {
                            autoRetryable = true
                            dao.update(posting.copy(
                                state = CaptureState.FAILED,
                                attempts = posting.attempts + 1,
                                lastError = error.message,
                            ))
                        }
                        else ->
                            dao.update(posting.copy(
                                state = CaptureState.AWAITING_REVIEW,
                                attempts = posting.attempts + 1,
                                lastError = INTERRUPTED_POST_MESSAGE,
                            ))
                    }
                }
            }
        }
        // Nothing to delete for a manual entry, which never had a file.
        result.onSuccess { entity.photoPath?.let(photos::delete) }

        // Spec §7 puts PostWorker on this edge: network constraints plus exponential backoff,
        // so a receipt that could not be sent is not left waiting for the user to open Settings.
        // Only ever scheduled for the FAILED branch above -- an unattended retry is safe exactly
        // when, and only when, FAILED means "provably never sent".
        if (autoRetryable) scheduler.schedulePost()

        return result
    }

    /**
     * See the KDoc on [ReceiptQueue.reconcileInterrupted]. Not invoked automatically here:
     * [ReceiptRepository]'s constructor is synchronous and has no CoroutineScope of its own,
     * and firing an unstructured coroutine from an init block would have no completion signal
     * for callers (including tests, which construct this class directly rather than through
     * Hilt) and no defined error handling. Call it explicitly from wherever the app already
     * owns startup sequencing -- that is WorkManager/app-start wiring, out of this task's
     * "testable without WorkManager" boundary.
     */
    override suspend fun reconcileInterrupted() {
        postingMutex.withLock {
            // Rows currently marked in-flight belong to a post() awaiting a response in this
            // same process, not one stranded by a crash -- see postingMutex's KDoc.
            dao.observeInState(CaptureState.POSTING).first()
                .filterNot { it.id in inFlightPosts }
                .forEach { entity ->
                    dao.update(entity.copy(
                        state = CaptureState.AWAITING_REVIEW,
                        lastError = INTERRUPTED_POST_MESSAGE,
                    ))
                }
        }
    }

    /**
     * See [ReceiptQueue.parkFailedForServerChange]. Takes [retrySweepMutex] then [postingMutex]
     * -- the same order [retryFailedPosts] (which holds the sweep lock while its posts take the
     * posting lock) establishes, so the two cannot deadlock -- which guarantees no sweep is
     * mid-snapshot while rows are being demoted out from under it, and no demoted row is
     * concurrently being flipped to POSTING.
     */
    override suspend fun parkFailedForServerChange() {
        retrySweepMutex.withLock {
            postingMutex.withLock {
                dao.observeInState(CaptureState.FAILED).first().forEach { entity ->
                    // The draft's suggestedCategoryId is scrubbed along with the row's own
                    // accountId/categoryId: it is just as foreign to the new server, and it is
                    // what pre-selects the category picker when Review re-opens this row.
                    val draft = entity.draftJson?.let(DraftCodec::decode)
                    dao.update(entity.copy(
                        state = CaptureState.AWAITING_REVIEW,
                        accountId = null,
                        categoryId = null,
                        draftJson = draft?.copy(suggestedCategoryId = null)
                            ?.let(DraftCodec::encode) ?: entity.draftJson,
                        lastError = SERVER_CHANGED_MESSAGE,
                    ))
                }
            }
        }
    }

    override suspend fun discard(id: Long) {
        dao.byId(id)?.photoPath?.let(photos::delete)
        dao.delete(id)
    }

    /**
     * Re-posts every FAILED row from its stored draft and choices. Returns false if any retry
     * failed, or if a sweep was already running (see [retrySweepMutex]).
     *
     * Only FAILED rows are touched, and post() only writes FAILED for a transient failure. Each
     * re-post carries the row's idempotency key, so one the server already applied replays that
     * transaction rather than creating a second -- that is the whole basis on which this may run
     * unattended, from [dev.otherworld.budget.data.work.PostWorker] or a Settings tap.
     */
    override suspend fun retryFailedPosts(): Boolean {
        if (!retrySweepMutex.tryLock()) return false
        try {
            val failed = dao.observeInState(CaptureState.FAILED).first()
            var allSucceeded = true
            for (entity in failed) {
                val draft = entity.draftJson?.let(DraftCodec::decode)
                val total = draft?.total
                val accountId = entity.accountId
                if (draft == null || total == null || accountId == null) {
                    allSucceeded = false
                    continue
                }
                val result = post(entity.id, CreateTransactionRequest(
                    accountId = accountId,
                    categoryId = entity.categoryId,
                    date = draft.date ?: LocalDate.now(),
                    merchant = draft.merchant ?: "Unknown",
                    total = total,
                    // Null for a manual entry, and null for a captured row whose file has since
                    // gone -- the same resolution ReviewViewModel.load() makes, deliberately in
                    // step with it. Passing a File that does not exist throws
                    // FileNotFoundException at OkHttp's body-write time, which the error mapper
                    // turns into Network(...) -- and isRetryable() is true for that, so the row
                    // would sit in FAILED and be re-sent on every sweep, forever, for a request
                    // that can never be built because the file is gone for good. Dropping the
                    // vanished file (posting without the attachment) is what breaks that loop; the
                    // sweep and the screen have to agree on what "there is a photo to send" means,
                    // or the unattended half contradicts the half the user can see.
                    photo = entity.photoPath?.let(::File)?.takeIf { it.exists() },
                    splits = entity.splitsJson?.let(SplitCodec::decode),
                ))
                if (result.isFailure) allSucceeded = false
            }
            return allSucceeded
        } finally {
            retrySweepMutex.unlock()
        }
    }

    /** Deletes every row and its photo. Used on sign-out. */
    override suspend fun clearAll() {
        dao.observeAll().first().forEach { row -> row.photoPath?.let(photos::delete) }
        dao.clear()
    }

    /**
     * Enforces spec §7's queue bounds ([maxAgeMillis] old / [maxItems] rows) by *surfacing*,
     * never by deleting. Every row past either bound holds a financial record the user has not
     * resolved -- a captured receipt photo, or a typed-in transaction -- and this app's current
     * reality (no server routes yet) means rows can legitimately wait far past any bound while
     * Capture's own banner promises "they'll be saved when it's back". An earlier version
     * deleted such rows and their photos outright, silently, from a daily worker with no
     * network constraint: the one component quietly destroying the data everything else is
     * built to protect. Now the oldest items raise a notification ("N transactions have been
     * waiting over 30 days -- review or discard them") and the user decides; discard and
     * sign-out remain the only paths that delete a row.
     *
     * The only thing swept automatically is photo *files no row references* (orphans left by a
     * crash between file creation and enqueue, and only once they are older than the age bound
     * -- see [PhotoStore.pruneOrphans]).
     */
    override suspend fun prune(maxItems: Int, maxAgeMillis: Long) {
        val rows = dao.observeAll().first()   // newest first
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val overBound = rows.drop(maxItems).map { it.id }.toSet()
        val stale = rows.count { it.capturedAt < cutoff || it.id in overBound }
        if (stale > 0) notifier.notifyStaleQueue(stale)

        photos.pruneOrphans(
            referencedPaths = rows.mapNotNull { it.photoPath }.toSet(),
            maxAgeMillis = maxAgeMillis,
        )
    }

    private fun toDomain(e: PendingReceiptEntity) = PendingReceipt(
        id = e.id, photoPath = e.photoPath, capturedAt = e.capturedAt, state = e.state,
        draft = e.draftJson?.let(DraftCodec::decode), attempts = e.attempts, lastError = e.lastError,
    )

    companion object {
        const val INTERRUPTED_POST_MESSAGE =
            "Interrupted before we knew if it saved -- check Recent before saving again."
        const val SERVER_CHANGED_MESSAGE =
            "Saved while connected to a different server -- re-check the account before saving."
        const val KEY_CONFLICT_MESSAGE =
            "This receipt may already be saved under a different entry -- check Recent before saving again."
    }
}
