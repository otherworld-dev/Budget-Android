package dev.otherworld.budget.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens [url] with `ACTION_VIEW`, guarding the call that Activity's own screen guarded inline
 * before this was lifted out for Overview to share (Task 9): not every Android device has a
 * browser (kiosk builds, stripped e-ink readers -- this app's own reference device is one), and
 * an unguarded `ACTION_VIEW` there throws [ActivityNotFoundException] and takes the app down.
 * Returns whether it succeeded so the caller can show its own failure message instead.
 */
fun Context.openInBrowser(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    true
} catch (e: ActivityNotFoundException) {
    false
}
