package dev.otherworld.budget.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One [SnapshotKind]'s cached JSON payload (see [SnapshotCodec]), tagged with the server/user that
 * fetched it. [owner] is what lets [dev.otherworld.budget.data.repo.SnapshotStore] hide a row
 * written under a different signed-in session -- see its KDoc.
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val kind: String,
    val owner: String,
    val json: String,
    val fetchedAt: Long,
)
