package dev.otherworld.budget.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.14.1 (pinned in the catalog) tops out at API 35; this project's
// targetSdk is 36. Pinned per-class rather than globally (see robolectric.org/4.16
// release notes for API 36 support, which requires JDK 21 -- unavailable in this
// build environment) so a future test class that actually needs API 36 behaviour
// fails loudly instead of being silently downgraded by a blanket config.
// application: swaps out the real, @HiltAndroidApp BudgetApp -- see RobolectricTestApplication's
// KDoc for why letting Robolectric bootstrap the real one is actively harmful here.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class PendingReceiptDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingReceiptDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pendingReceipts()
    }

    @After fun tearDown() = db.close()

    private fun receipt(state: CaptureState, capturedAt: Long = 1_000L) =
        PendingReceiptEntity(
            photoPath = "/tmp/${state.name}-$capturedAt.jpg",
            capturedAt = capturedAt, state = state,
        )

    @Test
    fun `inserts and observes`() = runTest {
        dao.insert(receipt(CaptureState.CAPTURED))
        dao.observeAll().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nextInState returns the oldest first so the queue is FIFO`() = runTest {
        dao.insert(receipt(CaptureState.CAPTURED, capturedAt = 2_000))
        dao.insert(receipt(CaptureState.CAPTURED, capturedAt = 1_000))
        assertEquals(1_000L, dao.nextInState(CaptureState.CAPTURED)!!.capturedAt)
    }

    @Test
    fun `nextInState ignores other states`() = runTest {
        dao.insert(receipt(CaptureState.POSTING))
        assertNull(dao.nextInState(CaptureState.CAPTURED))
    }

    @Test
    fun `state transitions persist`() = runTest {
        val id = dao.insert(receipt(CaptureState.CAPTURED))
        val stored = dao.nextInState(CaptureState.CAPTURED)!!
        dao.update(stored.copy(state = CaptureState.AWAITING_REVIEW, draftJson = """{"merchant":"Tesco"}"""))

        assertNull(dao.nextInState(CaptureState.CAPTURED))
        assertEquals(id, dao.nextInState(CaptureState.AWAITING_REVIEW)!!.id)
        assertEquals(1, dao.countInState(CaptureState.AWAITING_REVIEW))
    }

    @Test
    fun `observeAwaitingReview emits only reviewable items`() = runTest {
        dao.insert(receipt(CaptureState.CAPTURED))
        dao.insert(receipt(CaptureState.AWAITING_REVIEW, capturedAt = 3_000))
        dao.observeAwaitingReview().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

}
