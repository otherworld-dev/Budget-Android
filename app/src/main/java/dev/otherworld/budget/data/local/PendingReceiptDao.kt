package dev.otherworld.budget.data.local

import androidx.room.*
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReceiptDao {

    @Query("SELECT * FROM pending_receipts ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<PendingReceiptEntity>>

    @Query("SELECT * FROM pending_receipts WHERE state = :state ORDER BY capturedAt ASC")
    fun observeInState(state: CaptureState): Flow<List<PendingReceiptEntity>>

    fun observeAwaitingReview(): Flow<List<PendingReceiptEntity>> =
        observeInState(CaptureState.AWAITING_REVIEW)

    @Query("SELECT * FROM pending_receipts WHERE state = :state ORDER BY capturedAt ASC LIMIT 1")
    suspend fun nextInState(state: CaptureState): PendingReceiptEntity?

    @Query("SELECT * FROM pending_receipts WHERE id = :id")
    suspend fun byId(id: Long): PendingReceiptEntity?

    @Query("SELECT COUNT(*) FROM pending_receipts WHERE state = :state")
    suspend fun countInState(state: CaptureState): Int

    @Query("SELECT COUNT(*) FROM pending_receipts")
    suspend fun count(): Int

    @Insert suspend fun insert(receipt: PendingReceiptEntity): Long
    @Update suspend fun update(receipt: PendingReceiptEntity)

    @Query("DELETE FROM pending_receipts WHERE id = :id")
    suspend fun delete(id: Long)

    // Deliberately no bulk delete-by-age: rows hold unresolved financial records, and the one
    // caller such a query ever had (the old prune pass) silently destroyed 30-day-old rows.
    // Ageing rows are surfaced for manual resolution instead -- see ReceiptRepository.prune.

    @Query("DELETE FROM pending_receipts")
    suspend fun clear()
}
