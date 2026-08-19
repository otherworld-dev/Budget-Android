package dev.otherworld.budget.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.local.AppDatabase
import dev.otherworld.budget.data.local.DraftCodec
import dev.otherworld.budget.data.local.PendingReceiptEntity
import dev.otherworld.budget.data.local.PhotoStore
import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.CreateTransactionRequest
import dev.otherworld.budget.data.remote.CreatedTransaction
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.domain.model.CaptureState
import dev.otherworld.budget.domain.model.DraftTransaction
import dev.otherworld.budget.domain.model.LineItem
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.SplitPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.LocalDate

// See PendingReceiptDaoTest for why sdk is pinned per-class rather than globally
// (Robolectric 4.14.1 tops out at API 35; this project targets API 36), and
// RobolectricTestApplication's KDoc for why application is overridden too.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeBudgetApi
    private lateinit var photos: PhotoStore
    private lateinit var scheduler: RecordingQueueScheduler
    private lateinit var notifier: RecordingRepoNotifier
    private lateinit var repo: ReceiptRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        api = FakeBudgetApi()
        photos = PhotoStore(context)
        scheduler = RecordingQueueScheduler()
        notifier = RecordingRepoNotifier()
        repo = repository()
    }

    @After fun tearDown() = db.close()

    /** One construction site, so a repository-constructor change touches one line. */
    private fun repository(api: BudgetApi = this.api) =
        ReceiptRepository(api, db.pendingReceipts(), photos, scheduler, notifier)

    private fun newPhoto() = photos.newPhotoFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `enqueue stores the photo in CAPTURED`() = runTest {
        repo.enqueue(newPhoto())
        assertEquals(1, db.pendingReceipts().countInState(CaptureState.CAPTURED))
    }

    @Test
    fun `extractNext moves the item to AWAITING_REVIEW with a draft`() = runTest {
        val id = repo.enqueue(newPhoto())
        val outcome = repo.extractNext()

        assertEquals(ExtractOutcome.Extracted(id), outcome)
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, stored.state)
        assertEquals("Tesco", DraftCodec.decode(stored.draftJson!!)!!.merchant)
    }

    @Test
    fun `extractNext is idle when the queue is empty`() = runTest {
        assertEquals(ExtractOutcome.Idle, repo.extractNext())
    }

    @Test
    fun `a network failure leaves the item in CAPTURED for retry`() = runTest {
        val id = repo.enqueue(newPhoto())
        api.nextError = BudgetApiError.Network(null)

        assertEquals(ExtractOutcome.Retry, repo.extractNext())
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.CAPTURED, stored.state)
        assertEquals(1, stored.attempts)
    }

    @Test
    fun `extraction failure still opens review so the capture is not wasted`() = runTest {
        val id = repo.enqueue(newPhoto())
        api.nextError = BudgetApiError.ExtractionFailed

        val outcome = repo.extractNext()

        assertTrue(outcome is ExtractOutcome.Failed)
        assertEquals(CaptureState.AWAITING_REVIEW, db.pendingReceipts().byId(id)!!.state)
    }

    @Test
    fun `OCR not configured also opens review for manual entry`() = runTest {
        val id = repo.enqueue(newPhoto())
        api.ocrAvailable = false

        repo.extractNext()

        assertEquals(CaptureState.AWAITING_REVIEW, db.pendingReceipts().byId(id)!!.state)
    }

    @Test
    fun `a successful post removes the row and deletes the photo`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))

        assertTrue(result.isSuccess)
        assertEquals(0, db.pendingReceipts().count())
        assertFalse(photo.exists())
    }

    @Test
    fun `a post that provably never left the device marks FAILED and keeps the photo for retry`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        // A refused TCP connection: the request body was never written, so the server cannot
        // have created this transaction. This is the only shape of failure that may be
        // auto-retried without risking a duplicate.
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        assertTrue(result.isFailure)
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
        assertTrue(photo.exists())
        // Spec section 7 puts PostWorker on this edge -- the retry must not wait for the user
        // to find the button in Settings.
        assertTrue(scheduler.postScheduled)
    }

    @Test
    fun `an ambiguous 5xx now auto-retries, because the idempotency key makes a replay safe`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        // The old hazard: nginx returns 504 *after* Nextcloud committed the transaction, and a
        // naive re-post charges the user twice. That is exactly what the idempotency key now
        // prevents -- a replay under the same key joins the committed transaction -- so a 5xx is
        // safe to retry unattended instead of being parked on the user to check Recent.
        api.nextError = BudgetApiError.ServerError(504)

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        assertTrue(result.isFailure)
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.FAILED, stored.state)
        assertTrue(photo.exists())
        // FAILED, so the sweep picks it up, and the background retry is scheduled straight away.
        assertTrue(scheduler.postScheduled)
    }

    @Test
    fun `a read timeout also auto-retries now the replay carries the key`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        // The request was written; only the answer went missing -- the server may have committed
        // it. Once that was a reason to park the row; with the key it is just another transient
        // failure to replay.
        api.nextError = BudgetApiError.Network(SocketTimeoutException("timeout"))

        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
        assertTrue(scheduler.postScheduled)
    }

    @Test
    fun `a rejected app password needs re-auth, so it is parked, not auto-retried`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        // Unlike a transient failure, a 401 will keep failing until the user re-authenticates
        // (BudgetApiRetrofit has already cleared the credentials and sent them to Onboarding), so
        // an unattended retry is pointless: the row waits in review, not the sweep pool.
        api.nextError = BudgetApiError.Unauthorized

        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, stored.state)
        assertEquals(0, db.pendingReceipts().countInState(CaptureState.FAILED))
        assertFalse(scheduler.postScheduled)
    }

    @Test
    fun `request_in_flight auto-retries -- the winner is committing under this key`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.RequestInFlight

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        assertTrue(result.isFailure)
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
        assertTrue(scheduler.postScheduled)
    }

    @Test
    fun `an idempotency key conflict parks the row for review and rotates its burned key`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        val originalKey = db.pendingReceipts().byId(id)!!.idempotencyKey
        // The server holds a *different* purchase under this key -- so unlike every other failure,
        // re-sending the same key can only hit the same wall. The row must leave the auto-retry
        // pool, take a fresh key so a manual re-save can get through, and go back to the user.
        api.nextError = BudgetApiError.IdempotencyKeyConflict

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        assertTrue(result.isFailure)
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, stored.state)
        assertEquals(ReceiptRepository.KEY_CONFLICT_MESSAGE, stored.lastError)
        assertNotEquals(originalKey, stored.idempotencyKey)
        assertTrue(stored.idempotencyKey.isNotBlank())
        assertFalse(scheduler.postScheduled)
        assertEquals(0, db.pendingReceipts().countInState(CaptureState.FAILED))
    }

    @Test
    fun `post sends the row's idempotency key, unchanged across a retry`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        val key = db.pendingReceipts().byId(id)!!.idempotencyKey
        assertTrue(key.isNotBlank())

        // First attempt fails transiently -> FAILED; the sweep then re-posts it successfully.
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        api.nextError = null
        assertTrue(repo.retryFailedPosts())

        // The client's whole obligation for duplicate-safety: the *same* key on both attempts, so
        // the server (whose reserve-before-write dedup is proven on its side) collapses them to one
        // transaction. The fake mirrors that -- one `created` entry despite two posts.
        assertEquals(listOf(key, key), api.idempotencyKeys)
        assertEquals(1, api.created.size)
        assertEquals(0, db.pendingReceipts().count())
    }

    @Test
    fun `enqueue and enqueueWithoutPhoto assign distinct, non-empty idempotency keys`() = runTest {
        val a = db.pendingReceipts().byId(repo.enqueue(newPhoto()))!!.idempotencyKey
        val b = db.pendingReceipts().byId(
            repo.enqueueWithoutPhoto(DraftTransaction(total = Money(BigDecimal("1.00"), "GBP")))
        )!!.idempotencyKey

        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertNotEquals(a, b)
    }

    @Test
    fun `a failed post keeps the user's account and category choice for retry`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))

        repo.post(id, CreateTransactionRequest(
            accountId = 2, categoryId = 15, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        // Requirement: post() persists the user's account/category choice onto the row
        // *before* the network call, so a retry of the FAILED row reuses what the user
        // picked rather than losing it. The extraction draft never contains these.
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(2L, stored.accountId)
        assertEquals(15L, stored.categoryId)
    }

    @Test
    fun `a transient failure keeps the choices on the FAILED row, so a retry is pre-filled`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.ServerError(504)

        repo.post(id, CreateTransactionRequest(
            accountId = 2, categoryId = 15, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("1.00"), "GBP"), photo = photo,
        ))

        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.FAILED, stored.state)
        assertEquals(2L, stored.accountId)
        assertEquals(15L, stored.categoryId)
    }

    @Test
    fun `a post that parks back in review preserves the stored draft's subtotal and tax`() = runTest {
        // A separate-tax receipt: items 3.40 + 18.95 reconcile with the total (23.77) only
        // together with tax 1.42 -- exactly the shape ReviewViewModel.load() needs to offer
        // per-item splitting on re-open. Seeded the same way a real row would have it: written by
        // extraction/enqueueWithoutPhoto, not synthesised inline by post().
        val draft = DraftTransaction(
            merchant = "Tesco",
            date = LocalDate.of(2026, 3, 12),
            total = Money(BigDecimal("23.77"), "GBP"),
            suggestedCategoryId = 14,
            lineItems = listOf(
                LineItem("Coffee beans", Money(BigDecimal("3.40"), "GBP")),
                LineItem("Cheese", Money(BigDecimal("18.95"), "GBP")),
            ),
            subtotal = Money(BigDecimal("22.35"), "GBP"),
            tax = Money(BigDecimal("1.42"), "GBP"),
        )
        val id = repo.enqueueWithoutPhoto(draft)
        // Unauthorized needs a human, so post() parks the row back in AWAITING_REVIEW (not
        // FAILED) -- the one branch that rewrites draftJson from the field-by-field `edited`
        // this fix targets.
        api.nextError = BudgetApiError.Unauthorized

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("23.77"), "GBP"), photo = null,
        ))

        assertTrue(result.isFailure)
        val stored = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, stored.state)
        val decoded = DraftCodec.decode(stored.draftJson!!)!!
        // The bug: post() rebuilt the draft field-by-field and never copied subtotal/tax across,
        // so they silently reset to null here -- which on re-open makes SplitPlan.rows() see no
        // tax at all, the items no longer reconcile, and the receipt wrongly reports as
        // unsplittable ("the items don't add up to the total").
        assertEquals(Money(BigDecimal("1.42"), "GBP"), decoded.tax)
        assertEquals(Money(BigDecimal("22.35"), "GBP"), decoded.subtotal)
        // Line items must also survive -- the field the old code *did* carry across, so this
        // guards against a fix that regresses it while adding subtotal/tax.
        assertEquals(draft.lineItems, decoded.lineItems)
        // And it's still that overall a stored draft's un-edited fields are preserved, not lost.
        assertEquals("Tesco", decoded.merchant)
    }

    // --- Photo-less rows (Quick Add) -------------------------------------------------------

    @Test
    fun `enqueueWithoutPhoto parks a photo-less row straight in AWAITING_REVIEW with its draft`() = runTest {
        // AWAITING_REVIEW, not CAPTURED: there is nothing to extract, and a CAPTURED row would be
        // picked up by extractNext() with no image to send.
        val id = repo.enqueueWithoutPhoto(
            DraftTransaction(merchant = "Parking", date = LocalDate.of(2026, 3, 12),
                total = Money(BigDecimal("4.20"), "GBP"), suggestedCategoryId = 15)
        )

        val stored = db.pendingReceipts().byId(id)!!
        assertNull(stored.photoPath)
        assertEquals(CaptureState.AWAITING_REVIEW, stored.state)
        assertEquals(0, db.pendingReceipts().countInState(CaptureState.CAPTURED))
        // Persisted up front so a process death between enqueue and post still leaves the user's
        // typing recoverable through Review.
        val draft = DraftCodec.decode(stored.draftJson!!)!!
        assertEquals("Parking", draft.merchant)
        assertEquals(BigDecimal("4.20"), draft.total!!.amount)
        assertEquals(15L, draft.suggestedCategoryId)
    }

    @Test
    fun `a photo-less row posts through the same path and is removed on success`() = runTest {
        val id = repo.enqueueWithoutPhoto(DraftTransaction(total = Money(BigDecimal("4.20"), "GBP")))

        val result = repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
            merchant = "Unknown", total = Money(BigDecimal("4.20"), "GBP"), photo = null,
        ))

        assertTrue(result.isSuccess)
        assertEquals(0, db.pendingReceipts().count())
        assertNull(api.created.single().photo)
    }

    @Test
    fun `a photo-less row that fails is retried by the sweep like any other`() = runTest {
        // The reason Quick Add goes through the queue at all: offline support and the unattended
        // retry come for free, with no second code path to get them wrong.
        val id = repo.enqueueWithoutPhoto(DraftTransaction(
            merchant = "Parking", date = LocalDate.of(2026, 3, 12),
            total = Money(BigDecimal("4.20"), "GBP"),
        ))
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 2, categoryId = 15, date = LocalDate.of(2026, 3, 12),
            merchant = "Parking", total = Money(BigDecimal("4.20"), "GBP"), photo = null,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        api.nextError = null
        assertTrue(repo.retryFailedPosts())

        assertEquals(0, db.pendingReceipts().count())
        val resent = api.created.single()
        assertEquals(2L, resent.accountId)
        assertEquals(15L, resent.categoryId)
        assertNull(resent.photo)
    }

    @Test
    fun `discarding, clearing and pruning tolerate a row that never had a photo`() = runTest {
        // Every one of these used to dereference photoPath unconditionally.
        repo.discard(repo.enqueueWithoutPhoto(DraftTransaction()))
        assertEquals(0, db.pendingReceipts().count())

        repo.enqueueWithoutPhoto(DraftTransaction())
        repo.clearAll()
        assertEquals(0, db.pendingReceipts().count())

        repo.enqueueWithoutPhoto(DraftTransaction())
        repo.enqueueWithoutPhoto(DraftTransaction())
        repo.prune(maxItems = 1, maxAgeMillis = 30L * 24 * 60 * 60 * 1000)
        // Prune surfaces the over-bound row rather than deleting it -- see the prune tests.
        assertEquals(2, db.pendingReceipts().count())
        assertEquals(listOf(1), notifier.staleCounts)
    }

    @Test
    fun `discard removes the row and the photo`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.discard(id)
        assertEquals(0, db.pendingReceipts().count())
        assertFalse(photo.exists())
    }

    @Test
    fun `prune surfaces rows past the bounds instead of deleting them, and sweeps only old orphaned photos`() = runTest {
        // Spec section 7: "the oldest items are surfaced for manual resolution rather than
        // silently dropped." An earlier prune deleted over-age/over-bound rows and their photos
        // outright -- financial records the user never resolved, destroyed by a daily worker.
        // Acute in the current no-server-routes state, where every capture legitimately waits.
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000
        val maxAge = 10 * oneDay

        suspend fun seed(capturedAt: Long, backdatePhotoBy: Long? = null): Pair<Long, File> {
            val photo = newPhoto()
            backdatePhotoBy?.let { photo.setLastModified(now - it) }
            val id = db.pendingReceipts().insert(
                PendingReceiptEntity(photoPath = photo.absolutePath, capturedAt = capturedAt, state = CaptureState.CAPTURED)
            )
            return id to photo
        }

        // A: well past the age bound -- and its photo file genuinely old too, so this also
        // proves a *referenced* old file survives the orphan sweep.
        val (idA, photoA) = seed(capturedAt = now - 100 * oneDay, backdatePhotoBy = 100 * oneDay)
        // B..E: within the age bound; with maxItems = 2, B and C are over the count bound.
        val (idB, photoB) = seed(capturedAt = now - 4 * oneDay)
        val (idC, photoC) = seed(capturedAt = now - 3 * oneDay)
        val (idD, photoD) = seed(capturedAt = now - 2 * oneDay)
        val (idE, photoE) = seed(capturedAt = now - 1 * oneDay)

        // Two files with no row at all: one old (a crash between newPhotoFile() and enqueue,
        // long abandoned) and one fresh (could be mid-enqueue right now).
        val oldOrphan = newPhoto().apply { setLastModified(now - 100 * oneDay) }
        val freshOrphan = newPhoto()

        repo.prune(maxItems = 2, maxAgeMillis = maxAge)

        // Every row and every referenced photo survives -- A (over age) and B, C (over count)
        // included. Nothing with user data behind it is deleted.
        assertEquals(5, db.pendingReceipts().count())
        for (id in listOf(idA, idB, idC, idD, idE)) assertNotNull(db.pendingReceipts().byId(id))
        for (photo in listOf(photoA, photoB, photoC, photoD, photoE)) assertTrue(photo.exists())

        // The stale ones (A by age; B and C as the oldest surplus) are surfaced, once, as one
        // notification the user can act on.
        assertEquals(listOf(3), notifier.staleCounts)

        // Only the certainly-abandoned file goes: orphaned AND old. The fresh orphan is kept --
        // it may belong to an enqueue that has not written its row yet.
        assertFalse(oldOrphan.exists())
        assertTrue(freshOrphan.exists())
    }

    @Test
    fun `prune raises no notification while everything is within bounds`() = runTest {
        repo.enqueue(newPhoto())

        repo.prune(maxItems = 100, maxAgeMillis = 30L * 24 * 60 * 60 * 1000)

        assertTrue(notifier.staleCounts.isEmpty())
        assertEquals(1, db.pendingReceipts().count())
    }

    @Test
    fun `retryFailedPosts re-posts FAILED rows using the row's own account and category, and reports success`() = runTest {
        val photo1 = newPhoto()
        val id1 = repo.enqueue(photo1)
        repo.extractNext()   // draft.suggestedCategoryId = 14 (FakeBudgetApi)

        val photo2 = newPhoto()
        val id2 = repo.enqueue(photo2)
        repo.extractNext()

        // Only a proven never-sent failure reaches FAILED at all now, which is precisely what
        // makes an unattended re-post of these rows safe.
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        // Choose categories that deliberately differ from the draft's suggestedCategoryId (14),
        // so a retry using the draft instead of the stored row would be caught.
        repo.post(id1, CreateTransactionRequest(
            accountId = 1, categoryId = 99, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo1,
        ))
        repo.post(id2, CreateTransactionRequest(
            accountId = 2, categoryId = 77, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo2,
        ))
        assertEquals(2, db.pendingReceipts().countInState(CaptureState.FAILED))

        api.nextError = null
        val allSucceeded = repo.retryFailedPosts()

        assertTrue(allSucceeded)
        assertEquals(0, db.pendingReceipts().count())
        assertTrue(api.created.any { it.accountId == 1L && it.categoryId == 99L })
        assertTrue(api.created.any { it.accountId == 2L && it.categoryId == 77L })
    }

    @Test
    fun `the retry sweep drops a photo whose file has vanished instead of sending a dead File`() = runTest {
        // The unattended sweep has to resolve "is there a photo to send" exactly as
        // ReviewViewModel.load() does, or the two halves of the queue disagree about the same
        // row. Handing the API a File that no longer exists throws FileNotFoundException at
        // OkHttp's body-write time; the error mapper turns that into Network(...), which is
        // retryable -- so the row would sit in FAILED and be re-sent every sweep, forever, for a
        // request that can never be built because the file is gone for good. Dropping the vanished
        // file is what breaks that loop.
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        // FAILED deliberately keeps the photo for the retry, so the row still records a path --
        // this is the case where the *file* has gone from under it (external cleanup, a restore
        // onto a device without the app's files), not one that never had one.
        assertTrue(photo.delete())
        assertNotNull(db.pendingReceipts().byId(id)!!.photoPath)

        api.nextError = null
        assertTrue(repo.retryFailedPosts())

        // Posted, and posted without the attachment: the amount the user already reviewed is
        // worth more than an image that is gone either way.
        assertEquals(0, db.pendingReceipts().count())
        assertNull(api.created.single().photo)
    }

    @Test
    fun `retryFailedPosts returns false if a retry still fails`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))

        // api.nextError is still set, so the retry fails too.
        val allSucceeded = repo.retryFailedPosts()

        assertFalse(allSucceeded)
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
    }

    @Test
    fun `a retry that hits a transient failure stays FAILED for the next sweep`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
        val key = db.pendingReceipts().byId(id)!!.idempotencyKey

        // The server comes back, but answers this sweep with a gateway timeout. Once that would
        // have parked the row on the user; now, because the same key protects the replay, it just
        // stays FAILED for the next sweep to try again.
        api.nextError = BudgetApiError.ServerError(504)
        assertFalse(repo.retryFailedPosts())
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        // The next sweep succeeds. Every attempt carried the row's one key, so the server records a
        // single transaction no matter how many times it was re-sent.
        api.nextError = null
        assertTrue(repo.retryFailedPosts())
        assertEquals(0, db.pendingReceipts().count())
        assertEquals(1, api.created.size)
        assertTrue(api.idempotencyKeys.all { it == key })
    }

    @Test
    fun `a queued split re-sends its splits under the same key on the sweep`() = runTest {
        val id = repo.enqueueWithoutPhoto(DraftTransaction(total = Money(BigDecimal("10.00"), "GBP")))
        val parts = listOf(
            SplitPart(Money(BigDecimal("6.00"), "GBP"), 14, "A"),
            SplitPart(Money(BigDecimal("4.00"), "GBP"), 15, "B"),
        )
        api.nextError = BudgetApiError.Network(ConnectException("refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = null, date = LocalDate.of(2026, 8, 1),
            merchant = "X", total = Money(BigDecimal("10.00"), "GBP"), photo = null, splits = parts,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        api.nextError = null
        assertTrue(repo.retryFailedPosts())
        assertEquals(parts, api.created.single().splits)   // rebuilt from splitsJson, not lost
        assertEquals(1, api.created.size)
    }

    @Test
    fun `parking for a server change demotes FAILED rows to review and scrubs the foreign ids`() = runTest {
        // The cross-server hazard: FAILED rows are re-posted unattended, but their stored
        // account/category ids belong to the server they were built against. After a sign-in
        // to a different server, they must go back through mandatory review with nothing
        // foreign pre-selected.
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()   // draft.suggestedCategoryId = 14 (FakeBudgetApi)
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 2, categoryId = 15, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        repo.parkFailedForServerChange()

        val parked = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, parked.state)
        assertNull(parked.accountId)
        assertNull(parked.categoryId)
        // The draft's own suggestion is just as foreign -- it is what pre-selects the category
        // picker on re-review -- while the user's amount/merchant/date are kept.
        val draft = DraftCodec.decode(parked.draftJson!!)!!
        assertNull(draft.suggestedCategoryId)
        assertEquals("Tesco", draft.merchant)
        assertEquals(BigDecimal("24.31"), draft.total!!.amount)
        assertEquals(ReceiptRepository.SERVER_CHANGED_MESSAGE, parked.lastError)
        // Out of the unattended pool: a sweep now finds nothing to send.
        api.nextError = null
        assertTrue(repo.retryFailedPosts())
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun `parking waits out a live sweep and leaves it nothing to re-send`() = runTest {
        // The other half of the sign-in ordering fix (SessionManagerTest pins park-before-save):
        // parkFailedForServerChange must take the same sweep lock retryFailedPosts holds, so a
        // sweep already dispatching when a cross-server sign-in lands (a scheduled PostWorker
        // attempt survives expiry) finishes first -- against the old credentials -- and the
        // park then empties the FAILED set before the lock is ever free again. The first sweep
        // that could see the new server finds nothing to send.
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)
        api.nextError = null

        var createCalls = 0
        val networkGate = CompletableDeferred<Result<CreatedTransaction>>()
        val gatedApi = object : BudgetApi by api {
            override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String): Result<CreatedTransaction> {
                createCalls++
                return networkGate.await()
            }
        }
        val gatedRepo = repository(gatedApi)

        val sweep = launch { gatedRepo.retryFailedPosts() }
        var iterations = 0
        while (db.pendingReceipts().byId(id)?.state != CaptureState.POSTING && iterations < 1_000) {
            yield()
            iterations++
        }
        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)?.state)

        // Sign-in lands mid-sweep: the park must block on the sweep lock, not interleave.
        var parked = false
        val park = launch {
            gatedRepo.parkFailedForServerChange()
            parked = true
        }
        repeat(100) { yield() }
        assertFalse(parked)

        // The in-flight attempt fails proven-never-sent, so the row returns to FAILED and the
        // sweep completes, releasing the lock -- and only then does the park run.
        networkGate.complete(Result.failure(BudgetApiError.Network(ConnectException("refused"))))
        sweep.join()
        park.join()
        assertTrue(parked)

        val row = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, row.state)
        assertEquals(ReceiptRepository.SERVER_CHANGED_MESSAGE, row.lastError)

        // The next sweep -- the first one that could be aimed at the new server -- must issue
        // no request at all: the FAILED set was emptied while the lock was still contended.
        assertEquals(1, createCalls)
        assertTrue(gatedRepo.retryFailedPosts())
        assertEquals(1, createCalls)
    }

    @Test
    fun `parking for a server change leaves rows in other states alone`() = runTest {
        val capturedId = repo.enqueue(newPhoto())
        val awaitingId = repo.enqueueWithoutPhoto(DraftTransaction(total = Money(BigDecimal("4.20"), "GBP")))

        repo.parkFailedForServerChange()

        assertEquals(CaptureState.CAPTURED, db.pendingReceipts().byId(capturedId)!!.state)
        val awaiting = db.pendingReceipts().byId(awaitingId)!!
        assertEquals(CaptureState.AWAITING_REVIEW, awaiting.state)
        assertNull(awaiting.lastError)   // no server-change warning stamped on it
    }

    @Test
    fun `clearAll deletes every row and every photo`() = runTest {
        val photo1 = newPhoto()
        repo.enqueue(photo1)
        val photo2 = newPhoto()
        repo.enqueue(photo2)

        repo.clearAll()

        assertEquals(0, db.pendingReceipts().count())
        assertFalse(photo1.exists())
        assertFalse(photo2.exists())
    }

    @Test
    fun `oldestAwaitingReview returns the oldest, not the newest, and null when none`() = runTest {
        assertNull(repo.oldestAwaitingReview())

        val photo1 = newPhoto()
        val olderId = db.pendingReceipts().insert(
            PendingReceiptEntity(photoPath = photo1.absolutePath, capturedAt = 1_000L, state = CaptureState.CAPTURED)
        )
        val photo2 = newPhoto()
        db.pendingReceipts().insert(
            PendingReceiptEntity(photoPath = photo2.absolutePath, capturedAt = 2_000L, state = CaptureState.CAPTURED)
        )

        repo.extractNext()   // processes the older row first (FIFO)
        repo.extractNext()   // processes the newer row

        assertEquals(olderId, repo.oldestAwaitingReview()?.id)
    }

    @Test
    fun `reconcileInterrupted moves a stranded POSTING row to AWAITING_REVIEW, preserving choices`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()

        // Simulate post() having persisted POSTING plus the user's choices, then the process
        // dying before the network call resolved either way.
        val strandedEntity = db.pendingReceipts().byId(id)!!.copy(
            state = CaptureState.POSTING,
            accountId = 2,
            categoryId = 15,
            attempts = 3,
        )
        db.pendingReceipts().update(strandedEntity)

        repo.reconcileInterrupted()

        val reconciled = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, reconciled.state)
        assertEquals(2L, reconciled.accountId)
        assertEquals(15L, reconciled.categoryId)
        assertEquals("Tesco", DraftCodec.decode(reconciled.draftJson!!)!!.merchant)
        assertEquals(3, reconciled.attempts)   // no attempt actually completed
        assertEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, reconciled.lastError)
    }

    @Test
    fun `reconcileInterrupted does not reset a post whose network call is still in flight`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()

        // A gate the test controls explicitly, rather than FakeBudgetApi's latencyMs: under
        // runTest, a delay() in a background coroutine can get skipped by the scheduler's own
        // idle-detection before this test's poll loop ever observes the intermediate state (this
        // was tried and is exactly what happened -- the row was already gone by the first check).
        // Suspending on a CompletableDeferred isn't a delay the virtual clock can fast-forward,
        // so post() stays genuinely in flight until the test itself completes it below.
        val networkGate = CompletableDeferred<Result<CreatedTransaction>>()
        val gatedApi = object : BudgetApi by api {
            override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String) = networkGate.await()
        }
        val gatedRepo = repository(gatedApi)

        val postJob = launch {
            gatedRepo.post(id, CreateTransactionRequest(
                accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
                merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
            ))
        }

        // Poll rather than trust a single check right after launch(): post()'s dao.update runs
        // on Room's own executor, a real thread the virtual-time scheduler doesn't track, so
        // there's no guarantee the row has landed as POSTING the instant launch() returns.
        var iterations = 0
        while (db.pendingReceipts().byId(id)?.state != CaptureState.POSTING && iterations < 1_000) {
            yield()
            iterations++
        }
        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)?.state)

        // Race it: post() is now suspended on networkGate, exactly the window in which
        // reconcileInterrupted() must not treat this row as crash-stranded.
        gatedRepo.reconcileInterrupted()

        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)!!.state)

        networkGate.complete(Result.success(CreatedTransaction(9002L, null)))   // let post() finish
        postJob.join()

        // post() completed normally: the row is gone, proving the fix didn't just block
        // reconciliation forever -- it only closed the race.
        assertEquals(0, db.pendingReceipts().count())
        assertFalse(photo.exists())
    }

    @Test
    fun `a post cancelled mid-flight parks the row for review and the cancellation reaches the caller`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()

        // Same gate as the test above: post() suspends here until the test resolves it, giving
        // a deterministic window to cancel the coroutine while the row is genuinely POSTING.
        // networkGate.await() propagates cancellation exactly as the production BudgetApi does
        // since BudgetApiRetrofit.call() stopped swallowing CancellationException; a companion
        // test against the real Retrofit stack lives in BudgetApiRetrofitTest.
        val networkGate = CompletableDeferred<Result<CreatedTransaction>>()
        val gatedApi = object : BudgetApi by api {
            override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String) = networkGate.await()
        }
        val gatedRepo = repository(gatedApi)

        var postReturned = false
        val postJob = launch {
            gatedRepo.post(id, CreateTransactionRequest(
                accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
                merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
            ))
            postReturned = true
        }

        var iterations = 0
        while (db.pendingReceipts().byId(id)?.state != CaptureState.POSTING && iterations < 1_000) {
            yield()
            iterations++
        }
        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)?.state)

        // Cancel while post() is suspended inside networkGate.await() -- the user backing out
        // of a Quick Add save, QueueScheduler.cancelAll(), or WorkManager stopping a PostWorker
        // on constraint loss/timeout.
        postJob.cancelAndJoin()

        // Half one: the cancellation genuinely propagated. post() must never return a
        // fabricated Result to a cancelled caller -- the line after it must not have run.
        assertTrue(postJob.isCancelled)
        assertFalse(postReturned)

        // Half two: the row was parked for mandatory review immediately -- not left inert in
        // POSTING until the next launch's reconcileInterrupted() -- carrying the warning that
        // the server may already hold the transaction.
        val parked = db.pendingReceipts().byId(id)!!
        assertEquals(CaptureState.AWAITING_REVIEW, parked.state)
        assertEquals(ReceiptRepository.INTERRUPTED_POST_MESSAGE, parked.lastError)

        // And the in-flight marker did not leak: a later post of the same row is accepted
        // rather than refused as already-in-flight.
        networkGate.complete(Result.success(CreatedTransaction(9002L, null)))
        val retry = gatedRepo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertTrue(retry.isSuccess)
    }

    @Test
    fun `a second post of the same receipt is refused while the first is still in flight`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()

        // Same gating technique as the reconcileInterrupted tests: post() stays genuinely
        // suspended mid-network-call until this test releases it, which is the only window in
        // which a second post of the same row can exist.
        val networkGate = CompletableDeferred<Result<CreatedTransaction>>()
        val gatedApi = object : BudgetApi by api {
            override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String) = networkGate.await()
        }
        val gatedRepo = repository(gatedApi)
        val request = CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        )

        val first = launch { gatedRepo.post(id, request) }

        var iterations = 0
        while (db.pendingReceipts().byId(id)?.state != CaptureState.POSTING && iterations < 1_000) {
            yield()
            iterations++
        }
        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)?.state)

        // The choke point itself says no -- no second network call is started, so the API's
        // lack of an idempotency key cannot turn a double tap (or Settings' "Retry failed"
        // racing the user's Save) into two transactions.
        val second = gatedRepo.post(id, request)

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is PostAlreadyInFlightException)
        assertEquals(CaptureState.POSTING, db.pendingReceipts().byId(id)?.state)

        networkGate.complete(Result.success(CreatedTransaction(9002L, null)))
        first.join()
        assertEquals(0, db.pendingReceipts().count())
    }

    @Test
    fun `a second retry sweep is refused while one is already running`() = runTest {
        val photo = newPhoto()
        val id = repo.enqueue(photo)
        repo.extractNext()
        api.nextError = BudgetApiError.Network(ConnectException("Connection refused"))
        repo.post(id, CreateTransactionRequest(
            accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
            merchant = "Tesco", total = Money(BigDecimal("24.31"), "GBP"), photo = photo,
        ))
        assertEquals(CaptureState.FAILED, db.pendingReceipts().byId(id)!!.state)

        val networkGate = CompletableDeferred<Result<CreatedTransaction>>()
        val gatedApi = object : BudgetApi by api {
            override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String) = networkGate.await()
        }
        val gatedRepo = repository(gatedApi)

        val sweep = launch { gatedRepo.retryFailedPosts() }

        var iterations = 0
        while (db.pendingReceipts().byId(id)?.state != CaptureState.POSTING && iterations < 1_000) {
            yield()
            iterations++
        }

        // Two sweeps each snapshot the FAILED rows before the other changes them, so the second
        // would re-post a row the first had already handled -- a duplicate the in-flight guard
        // cannot catch, because by then the first sweep's post has finished.
        assertFalse(gatedRepo.retryFailedPosts())

        networkGate.complete(Result.success(CreatedTransaction(9002L, null)))
        sweep.join()
        assertEquals(0, db.pendingReceipts().count())
    }
}

/** Hand-rolled fake -- records the stale-queue notifications prune raises. */
class RecordingRepoNotifier : dev.otherworld.budget.data.work.ReceiptNotifying {
    val staleCounts = mutableListOf<Int>()
    override fun notifyReadyForReview(count: Int) = Unit
    override fun notifyStaleQueue(count: Int) { staleCounts += count }
    override fun clear() = Unit
    override fun clearStaleQueue() = Unit
}

/** Hand-rolled fake -- this project uses no mocking library. Records what was scheduled. */
class RecordingQueueScheduler : QueueScheduling {
    var postScheduled = false
        private set

    override fun scheduleExtraction() = Unit
    override fun schedulePost() { postScheduled = true }
    override fun schedulePrune() = Unit
    override fun cancelAll() = Unit
}
