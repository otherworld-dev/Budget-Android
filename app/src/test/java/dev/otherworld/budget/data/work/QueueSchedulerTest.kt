package dev.otherworld.budget.data.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.otherworld.budget.RobolectricTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// See ExtractWorkerTest for why sdk and application are pinned per-class. RobolectricTestApplication
// is a no-op Application (it is not a Configuration.Provider), so WorkManager is not auto-initialised
// and WorkManagerTestInitHelper can install the test instance cleanly.
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class QueueSchedulerTest {

    private lateinit var context: Context
    private lateinit var wm: WorkManager
    private lateinit var scheduler: QueueScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
        scheduler = QueueScheduler(context)
    }

    @Test fun `scheduleExtraction enqueues one uniquely-named extraction`() {
        scheduler.scheduleExtraction()
        assertEquals(1, wm.getWorkInfosForUniqueWork(ExtractWorker.NAME).get().size)
    }

    @Test fun `schedulePost enqueues one uniquely-named post`() {
        scheduler.schedulePost()
        assertEquals(1, wm.getWorkInfosForUniqueWork(PostWorker.NAME).get().size)
    }

    @Test fun `schedulePrune enqueues the periodic prune`() {
        scheduler.schedulePrune()
        assertEquals(1, wm.getWorkInfosForUniqueWork(PruneWorker.NAME).get().size)
    }

    // The KEEP policy is load-bearing: a second capture while extraction is already running must
    // not cancel and restart the in-flight drain. If it were REPLACE, the second enqueue would
    // mint a new work id; KEEP preserves the original request.
    @Test fun `a second scheduleExtraction keeps the in-flight drain rather than replacing it`() {
        scheduler.scheduleExtraction()
        val firstId = wm.getWorkInfosForUniqueWork(ExtractWorker.NAME).get().single().id

        scheduler.scheduleExtraction()
        val infos = wm.getWorkInfosForUniqueWork(ExtractWorker.NAME).get()

        assertEquals(1, infos.size)
        assertEquals(firstId, infos.single().id)
    }

    @Test fun `cancelAll cancels the queue work`() {
        scheduler.scheduleExtraction()
        scheduler.schedulePost()

        scheduler.cancelAll()

        assertEquals(
            WorkInfo.State.CANCELLED,
            wm.getWorkInfosForUniqueWork(ExtractWorker.NAME).get().single().state,
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            wm.getWorkInfosForUniqueWork(PostWorker.NAME).get().single().state,
        )
    }
}
