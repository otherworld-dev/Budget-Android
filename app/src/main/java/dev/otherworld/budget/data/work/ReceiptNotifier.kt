package dev.otherworld.budget.data.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otherworld.budget.MainActivity
import dev.otherworld.budget.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction over [ReceiptNotifier] so workers can be exercised with a hand-rolled fake
 * ([dev.otherworld.budget.data.work.ExtractWorkerTest]'s `RecordingNotifier`) instead of
 * touching Android's real notification manager -- this project uses no mocking library.
 */
interface ReceiptNotifying {
    fun notifyReadyForReview(count: Int)

    /**
     * Raised by the daily prune pass when rows have sat unresolved past the queue's bounds
     * (30 days / 100 items -- see [dev.otherworld.budget.data.repo.ReceiptQueue.prune]).
     * The queue never deletes user data on its own; this notification is the "surfaced for
     * manual resolution" half of that promise.
     */
    fun notifyStaleQueue(count: Int)

    /** Retires the ready-for-review notification (see [notifyReadyForReview]). */
    fun clear()

    /** Retires the stale-queue notification once the user has resolved the stale rows. */
    fun clearStaleQueue()
}

@Singleton
class ReceiptNotifier @Inject constructor(@ApplicationContext private val context: Context) : ReceiptNotifying {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_receipts),
                NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    // POST_NOTIFICATIONS (API 33+) may be denied; the notify() call below is wrapped in
    // runCatching and no-ops on the resulting SecurityException, which lint cannot see.
    @SuppressLint("MissingPermission")
    override fun notifyReadyForReview(count: Int) {
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_REVIEW)
                // Pairs with android:launchMode="singleTop" in the manifest: together these are
                // what actually deliver this intent to a running MainActivity via onNewIntent
                // (CLEAR_TOP brings it forward if another screen is on top of it) instead of
                // stacking a second instance of the app on top of the first.
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(R.plurals.receipts_ready, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_receipt)
            .setContentTitle(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID, notification) }   // no-op if permission denied
    }

    @SuppressLint("MissingPermission") // see notifyReadyForReview: notify() no-ops on denial
    override fun notifyStaleQueue(count: Int) {
        // A plain launch intent, not ACTION_REVIEW: stale rows can be CAPTURED (not yet
        // reviewable), and Capture's banners are the surface that shows every queue state.
        val intent = PendingIntent.getActivity(
            context, STALE_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(R.plurals.stale_queue, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_receipt)
            .setContentTitle(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            // The daily prune re-raises this while anything is stale; updating the count in
            // place is right, but re-sounding/re-heads-upping every day for the same backlog
            // is nagging, not informing.
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(STALE_ID, notification) }   // no-op if permission denied
    }

    override fun clear() = manager.cancel(ID)

    override fun clearStaleQueue() = manager.cancel(STALE_ID)

    companion object {
        const val ACTION_REVIEW = "dev.otherworld.budget.REVIEW"
        private const val CHANNEL = "receipts"
        private const val ID = 1
        private const val STALE_ID = 2
        private const val STALE_REQUEST_CODE = 1
    }
}
