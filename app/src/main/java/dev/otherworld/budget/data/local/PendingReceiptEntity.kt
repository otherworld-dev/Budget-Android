package dev.otherworld.budget.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.otherworld.budget.domain.model.CaptureState

@Entity(tableName = "pending_receipts")
data class PendingReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * Null for a row that never had a photo -- a Quick Add manual entry, which goes through the
     * same queue and the same [dev.otherworld.budget.data.repo.ReceiptRepository.post] as a
     * captured receipt precisely so it inherits that path's duplicate-transaction protections.
     * Null therefore means "no photo was ever taken", never "the photo has been deleted": a row
     * whose post succeeded is deleted along with its file, so a surviving row's non-null path is
     * still expected to point at a real file.
     */
    val photoPath: String?,
    val capturedAt: Long,
    val state: CaptureState,
    val draftJson: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    // Captured at post time, not at extraction time: a retry of a FAILED post must
    // reuse what the *user* chose, which the extraction draft never contained.
    val accountId: Long? = null,
    val categoryId: Long? = null,
    /**
     * The row's idempotency key: a UUID minted once when the row is enqueued and replayed on every
     * `POST transactions` attempt for it, so the server dedupes a retry of one row into a single
     * transaction (`docs/server-api-contract.md`). Stable for the row's life -- that stability is
     * the whole point -- except when a post is rejected with
     * [dev.otherworld.budget.data.remote.BudgetApiError.IdempotencyKeyConflict], where
     * [dev.otherworld.budget.data.repo.ReceiptRepository.post] rotates it onto a fresh key.
     *
     * The default is empty only so test fixtures that build a row by hand need not supply one;
     * [dev.otherworld.budget.data.repo.ReceiptRepository] always sets a real UUID on the rows it
     * creates, and the v2->v3 migration backfills a unique key onto every pre-existing row, so a
     * production row's key is never blank.
     */
    val idempotencyKey: String = "",
    val splitsJson: String? = null,
)
