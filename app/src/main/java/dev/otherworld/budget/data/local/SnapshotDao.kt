package dev.otherworld.budget.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SnapshotDao {

    @Query("SELECT * FROM snapshots WHERE kind = :kind")
    suspend fun get(kind: String): SnapshotEntity?

    @Upsert
    suspend fun put(entity: SnapshotEntity)

    @Query("DELETE FROM snapshots")
    suspend fun clear()
}
