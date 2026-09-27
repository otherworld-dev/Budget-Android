package dev.otherworld.budget.data.local

/**
 * One cached slice of read-only check data (spec §1). Each kind is its own row in [SnapshotEntity]
 * -- there is no reason to invalidate all of them together just because one refreshed.
 */
enum class SnapshotKind { ACCOUNTS, CATEGORIES, CAPABILITIES, BUDGET_STATUS, UPCOMING_BILLS, RECENT }
