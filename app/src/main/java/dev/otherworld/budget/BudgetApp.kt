package dev.otherworld.budget

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.theme.ThemePalette
import dev.otherworld.budget.data.work.QueueScheduling
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class BudgetApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var receiptQueue: ReceiptQueue
    @Inject lateinit var scheduler: QueueScheduling
    @Inject lateinit var theme: ThemePalette

    /**
     * Application-scoped: outlives any single Activity, so startup cleanup finishes even if
     * the user backs out of MainActivity immediately. SupervisorJob + the try/catch below mean
     * a failure here is logged, never crashes the app, and never blocks [onCreate].
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Recovers rows stranded in POSTING by process death so they surface in Recent
        // for review instead of vanishing. See ReceiptQueue.reconcileInterrupted KDoc.
        appScope.launch {
            try {
                receiptQueue.reconcileInterrupted()
            } catch (e: Exception) {
                Log.w(TAG, "reconcileInterrupted failed at startup", e)
            }
        }

        // ExistingPeriodicWorkPolicy.KEEP makes repeated calls across app starts harmless.
        scheduler.schedulePrune()

        // Adopt the signed-in server's Nextcloud theme colour on every start. A no-op when logged
        // out (the fetch returns null); the stored colour, if any, is already applied at the first
        // composition, so this just keeps it fresh. Independent of the Budget routes on purpose.
        theme.refreshInBackground()
    }

    companion object {
        private const val TAG = "BudgetApp"
    }
}
