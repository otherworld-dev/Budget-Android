package dev.otherworld.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.otherworld.budget.domain.model.CaptureState

class CaptureStateConverter {
    @TypeConverter fun toDb(state: CaptureState): String = state.name
    @TypeConverter fun fromDb(raw: String): CaptureState = CaptureState.valueOf(raw)
}

@Database(entities = [PendingReceiptEntity::class], version = 4, exportSchema = true)
@TypeConverters(CaptureStateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingReceipts(): PendingReceiptDao

    companion object {
        /**
         * Makes `photoPath` nullable so Quick Add can enqueue a photo-less row through the same
         * queue as a captured receipt (see [PendingReceiptEntity.photoPath]).
         *
         * The app has never shipped, so no user's data is at stake -- but a developer's install
         * carrying real queued receipts is, and `fallbackToDestructiveMigration()` would silently
         * delete a pending row *and* orphan its photo file, which is exactly the class of quiet
         * data loss this queue is built to avoid. A real migration also keeps the schema JSON
         * honest: Room validates the migrated table against the compiled v2 entity on the next
         * open, so a mistake here fails loudly at startup instead of at some later write.
         *
         * SQLite has no `ALTER TABLE … DROP NOT NULL`, so this is the standard recreate-and-copy:
         * build the v2 table under a temporary name, copy every row across, drop the old table and
         * rename. The `CREATE TABLE` text is Room's own generated v2 SQL (see
         * `schemas/…AppDatabase/2.json`) rather than a hand-written approximation, because Room's
         * post-migration validation compares column affinity, nullability and the primary key, and
         * an "equivalent-looking" statement that differs in any of those aborts the open.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_receipts_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`photoPath` TEXT, " +
                        "`capturedAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`draftJson` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "`accountId` INTEGER, " +
                        "`categoryId` INTEGER)"
                )
                db.execSQL(
                    "INSERT INTO `pending_receipts_new` " +
                        "(`id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId`) " +
                        "SELECT `id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId` " +
                        "FROM `pending_receipts`"
                )
                // Carry the AUTOINCREMENT high-water mark across by hand. DROP TABLE deletes the
                // old table's `sqlite_sequence` row, and the INSERT..SELECT above only seeds the
                // new table's mark from the rows it actually copied -- so on the *normal* upgrade,
                // where the queue happens to be empty (a successful post deletes its row), the
                // mark would be lost entirely and the next receipt would be issued id 1 again.
                // Ids are handed straight back to callers: a review notification's PendingIntent
                // built before the upgrade for receipt 1 would then open Review on a completely
                // different receipt 1. Runs before the DROP, while the old row still exists; the
                // subsequent RENAME re-points this entry at the final table name.
                db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'pending_receipts_new'")
                db.execSQL(
                    "INSERT INTO sqlite_sequence (name, seq) " +
                        "SELECT 'pending_receipts_new', seq FROM sqlite_sequence WHERE name = 'pending_receipts'"
                )
                db.execSQL("DROP TABLE `pending_receipts`")
                db.execSQL("ALTER TABLE `pending_receipts_new` RENAME TO `pending_receipts`")
            }
        }

        /**
         * Adds `idempotencyKey` (`TEXT NOT NULL`): the per-row key the app now sends on every
         * `POST transactions` so the server dedupes retries (see [PendingReceiptEntity.idempotencyKey]).
         *
         * Same recreate-and-copy shape as [MIGRATION_1_2], for the same reasons: SQLite's
         * `ALTER TABLE ADD COLUMN` cannot add a `NOT NULL` column without a *constant* default, and
         * a constant default would both leave every carried-over row sharing one blank key (so the
         * server would refuse to dedupe them apart) and risk a Room default-value validation
         * mismatch against the entity, which declares none. Building the new table and supplying the
         * value in `INSERT..SELECT` sidesteps both: `lower(hex(randomblob(16)))` backfills a distinct
         * 32-char key onto each existing row, so a dev install's queued receipts keep working dedup.
         * The `CREATE TABLE` text is Room's generated v3 SQL (`schemas/…AppDatabase/3.json`); the
         * `sqlite_sequence` carry-over is [MIGRATION_1_2]'s, for the same autoincrement reason.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_receipts_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`photoPath` TEXT, " +
                        "`capturedAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`draftJson` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "`accountId` INTEGER, " +
                        "`categoryId` INTEGER, " +
                        "`idempotencyKey` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `pending_receipts_new` " +
                        "(`id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId`, `idempotencyKey`) " +
                        "SELECT `id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId`, lower(hex(randomblob(16))) " +
                        "FROM `pending_receipts`"
                )
                db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'pending_receipts_new'")
                db.execSQL(
                    "INSERT INTO sqlite_sequence (name, seq) " +
                        "SELECT 'pending_receipts_new', seq FROM sqlite_sequence WHERE name = 'pending_receipts'"
                )
                db.execSQL("DROP TABLE `pending_receipts`")
                db.execSQL("ALTER TABLE `pending_receipts_new` RENAME TO `pending_receipts`")
            }
        }

        /**
         * Adds `splitsJson` (`TEXT`, nullable): the confirmed per-category splits of a transaction,
         * persisted on the queue row so a retry of a FAILED post rebuilds and re-sends them under
         * the row's unchanged idempotency key instead of losing them (see
         * [PendingReceiptEntity.splitsJson] and [SplitCodec]).
         *
         * Same recreate-and-copy shape as [MIGRATION_2_3], but simpler: `splitsJson` is nullable, so
         * SQLite's `ALTER TABLE ADD COLUMN` would in principle work here -- the recreate is kept only
         * for consistency with the other migrations and because every existing row correctly copies
         * across as NULL (no splits were ever recorded before this column existed). The `CREATE
         * TABLE` text is Room's generated v4 SQL (`schemas/…AppDatabase/4.json`); the
         * `sqlite_sequence` carry-over is [MIGRATION_1_2]'s, for the same autoincrement reason.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_receipts_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`photoPath` TEXT, `capturedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`draftJson` TEXT, `attempts` INTEGER NOT NULL, `lastError` TEXT, " +
                        "`accountId` INTEGER, `categoryId` INTEGER, `idempotencyKey` TEXT NOT NULL, " +
                        "`splitsJson` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `pending_receipts_new` " +
                        "(`id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId`, `idempotencyKey`, `splitsJson`) " +
                        "SELECT `id`, `photoPath`, `capturedAt`, `state`, `draftJson`, `attempts`, `lastError`, `accountId`, `categoryId`, `idempotencyKey`, NULL " +
                        "FROM `pending_receipts`"
                )
                db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'pending_receipts_new'")
                db.execSQL(
                    "INSERT INTO sqlite_sequence (name, seq) " +
                        "SELECT 'pending_receipts_new', seq FROM sqlite_sequence WHERE name = 'pending_receipts'"
                )
                db.execSQL("DROP TABLE `pending_receipts`")
                db.execSQL("ALTER TABLE `pending_receipts_new` RENAME TO `pending_receipts`")
            }
        }
    }
}
