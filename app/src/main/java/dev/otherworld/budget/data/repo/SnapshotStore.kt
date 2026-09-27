package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.local.SnapshotEntity
import dev.otherworld.budget.data.local.SnapshotKind
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A decoded snapshot plus when it was fetched, for an "as of ..." label on a cached read. */
data class Cached<T>(val value: T, val fetchedAt: Instant)

/**
 * Owner-scoped cache of read-only check data (accounts, categories, capabilities, budget status,
 * upcoming bills, recent transactions), so the check screens have something to show offline.
 *
 * Scoped to whoever is signed in *right now* ([owner], read from [CredentialStore.load] at the
 * moment of the call): a fetch begun under one session can still complete and call [write] after
 * the user has signed out and a different one has signed in, and that write must not leak into
 * the new session's reads. [read] enforces this by comparing the stored row's owner against the
 * current one and returning null on any mismatch, rather than trusting whatever is on disk.
 */
@Singleton
class SnapshotStore @Inject constructor(
    private val dao: SnapshotDao,
    private val credentials: CredentialStore,
    private val now: () -> Instant,
) {
    private fun owner(): String? = credentials.load()?.let { "${it.server}|${it.loginName}" }

    /** Null when absent, malformed, or written by a different server/user than the one signed in now. */
    suspend fun <T> read(kind: SnapshotKind, decode: (String) -> T?): Cached<T>? {
        val owner = owner() ?: return null
        val row = dao.get(kind.name) ?: return null
        if (row.owner != owner) return null
        val value = decode(row.json) ?: return null
        return Cached(value, Instant.ofEpochMilli(row.fetchedAt))
    }

    /** Ignored (not written) when nobody is signed in -- there is no owner to tag the row with. */
    suspend fun write(kind: SnapshotKind, json: String) {
        val owner = owner() ?: return
        dao.put(SnapshotEntity(kind = kind.name, owner = owner, json = json, fetchedAt = now().toEpochMilli()))
    }

    suspend fun clear() = dao.clear()
}
