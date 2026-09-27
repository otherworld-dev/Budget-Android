package dev.otherworld.budget.ui.common

import dev.otherworld.budget.R
import dev.otherworld.budget.core.StringResources
import dev.otherworld.budget.data.repo.CheckRepository
import java.time.Duration
import java.time.Instant

/**
 * "Updated 4 min ago" text for a check-screen section (spec §2.5). Null means the line shouldn't
 * show at all: either nothing has loaded yet ([fetchedAt] null), or the data is younger than
 * [CheckRepository.STALE_AFTER] and the last refresh didn't fail. [hasError] forces the line to
 * show even for otherwise-fresh data, since a failed refresh means the figures on screen might
 * already be behind what the server has, however recent the last *successful* fetch was.
 */
fun stalenessText(fetchedAt: Instant?, hasError: Boolean, now: Instant, strings: StringResources): String? {
    fetchedAt ?: return null
    val age = Duration.between(fetchedAt, now)
    if (!hasError && age < CheckRepository.STALE_AFTER) return null

    val minutes = age.toMinutes()
    return when {
        minutes < 1 -> strings.get(R.string.activity_updated_just_now)
        minutes < 60 -> strings.get(R.string.activity_updated_minutes_ago, minutes)
        else -> strings.get(R.string.activity_updated_hours_ago, age.toHours())
    }
}
