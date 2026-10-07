package dev.otherworld.budget.data.repo

import android.util.Log
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.local.SnapshotEntity
import dev.otherworld.budget.data.local.SnapshotKind
import kotlinx.coroutines.CancellationException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A decoded snapshot plus when it was fetched, for an "as of ..." label on a cached read. */
data class Cached<T>(val value: T, val fetchedAt: Instant)

/**
 * Owner-scoped cache of read-only check data (accounts, categories, capabilities, budget status,
 * upcoming bills, recent transactions), so the check screens have something to show offline.
 *
 * Every row is tagged with an owner (server + login name). A fetch begun under one session can
 * complete after the user has signed out and someone else has signed in, so the owner a row is
 * written under must be the one captured when the fetch *started* ([currentOwner], passed back to
 * [write]) -- not whoever is signed in when it lands, which would file the old session's answer
 * under the new one. [write] drops the row when that owner is no longer the signed-in one, and
 * [read] compares the stored owner against the current one and returns null on any mismatch,
 * rather than trusting whatever is on disk.
 *
 * Best-effort throughout: this is a cache, and a Room failure (a full disk's
 * `SQLiteFullException`, say) must cost the cached copy, not crash the screen whose refresh
 * touched it -- Capture refreshes on every resume, so a throwing read there is a crash loop.
 * [read] answers null and [write]/[clear] do nothing; cancellation still propagates.
 */
@Singleton
class SnapshotStore @Inject constructor(
    private val dao: SnapshotDao,
    private val credentials: CredentialStore,
    private val now: () -> Instant,
) {
    /**
     * Whoever is signed in right now, as rows are tagged; null when signed out. Callers capture it
     * before a fetch and hand it to [write] afterwards.
     */
    fun currentOwner(): String? = credentials.load()?.let { "${it.server}|${it.loginName}" }

    /** Null when absent, malformed, unreadable, or written by a different server/user than the one signed in now. */
    suspend fun <T> read(kind: SnapshotKind, decode: (String) -> T?): Cached<T>? = bestEffort(null) {
        val owner = currentOwner() ?: return@bestEffort null
        val row = dao.get(kind.name) ?: return@bestEffort null
        if (row.owner != owner) return@bestEffort null
        val value = decode(row.json) ?: return@bestEffort null
        Cached(value, Instant.ofEpochMilli(row.fetchedAt))
    }

    /**
     * Stores [json] under [owner], the value [currentOwner] had when the fetch that produced it
     * began. Ignored when [owner] is null (nobody was signed in) or is no longer the signed-in
     * one (the session ended while the fetch was in flight).
     */
    suspend fun write(kind: SnapshotKind, json: String, owner: String?) = bestEffort(Unit) {
        if (owner == null || currentOwner() != owner) return@bestEffort
        dao.put(SnapshotEntity(kind = kind.name, owner = owner, json = json, fetchedAt = now().toEpochMilli()))
    }

    /**
     * Best-effort like the rest. A clear that fails leaves rows behind, but they stay owner-tagged,
     * so the next session still cannot read another owner's.
     */
    suspend fun clear() = bestEffort(Unit) { dao.clear() }

    private inline fun <R> bestEffort(fallback: R, block: () -> R): R = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "snapshot I/O failed; carrying on without the cache", e)
        fallback
    }

    private companion object {
        const val TAG = "SnapshotStore"
    }
}
