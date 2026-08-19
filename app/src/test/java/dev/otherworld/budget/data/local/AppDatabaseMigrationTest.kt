package dev.otherworld.budget.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 1 -> 2: `photoPath` becomes nullable so Quick Add can enqueue a photo-less row.
 *
 * Written against a real, file-backed database rather than Room's `MigrationTestHelper`: that
 * helper resolves the exported schema JSON out of the *instrumentation* context's assets, which
 * means either wiring `schemas/` into the androidTest assets and running this on a device, or
 * shipping the schema files inside the production APK so a Robolectric run can see them. Neither
 * is worth it here, because the assertion that actually matters is not "the JSON says the right
 * thing" -- it is that Room can open the migrated file at all. Room validates the live table
 * against the compiled v2 entity on every open after a migration and aborts with
 * "Migration didn't properly handle" if a column's affinity, nullability or the primary key
 * differ, so [migrationPreservesQueuedReceipts] opening the database *is* the schema check, run
 * against the same generated code the app ships.
 */
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    @After fun tearDown() {
        db?.close()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    /**
     * Creates the v1 database exactly as Room would have left it -- the v1 `createSql` and
     * identity hash are copied verbatim from `schemas/…AppDatabase/1.json`, which is the shipped
     * record of what a v1 install on disk actually looks like.
     */
    private fun createV1(seed: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_receipts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoPath` TEXT NOT NULL, " +
                    "`capturedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `draftJson` TEXT, " +
                    "`attempts` INTEGER NOT NULL, `lastError` TEXT, `accountId` INTEGER, " +
                    "`categoryId` INTEGER)"
            )
            database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, 'b34f64c989a4432e29abb1f1c8066e65')"
            )
            database.seed()
            database.version = 1
        }
    }

    /**
     * Creates the v2 database exactly as Room would have left it -- the v2 `createSql` and identity
     * hash are copied verbatim from `schemas/…AppDatabase/2.json`. Used to test MIGRATION_2_3 in
     * isolation from a v2-on-disk starting point.
     */
    private fun createV2(seed: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_receipts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoPath` TEXT, " +
                    "`capturedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `draftJson` TEXT, " +
                    "`attempts` INTEGER NOT NULL, `lastError` TEXT, `accountId` INTEGER, " +
                    "`categoryId` INTEGER)"
            )
            database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '5100c7875e938a7f3d996c308c73ed1d')"
            )
            database.seed()
            database.version = 2
        }
    }

    /**
     * Creates the v3 database exactly as Room would have left it -- the v3 `createSql` and identity
     * hash are copied verbatim from `schemas/…AppDatabase/3.json`. Used to test MIGRATION_3_4 in
     * isolation from a v3-on-disk starting point.
     */
    private fun createV3(seed: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_receipts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoPath` TEXT, " +
                    "`capturedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `draftJson` TEXT, " +
                    "`attempts` INTEGER NOT NULL, `lastError` TEXT, `accountId` INTEGER, " +
                    "`categoryId` INTEGER, `idempotencyKey` TEXT NOT NULL)"
            )
            database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '93ae3010b99cfd44cf0d44ee8e0259d0')"
            )
            database.seed()
            database.version = 3
        }
    }

    // The database version is now 4, so opening any older file runs the whole chain (1->2->3->4,
    // 2->3->4, or 3->4). All three migrations are registered; opening is the schema check for the
    // last one applied.
    private fun open(): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, dbName)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .allowMainThreadQueries()
        .build()
        .also { db = it }

    @Test
    fun `migrationPreservesQueuedReceipts`() = runTest {
        // A dev install mid-flight: one row already reviewed and carrying the user's account and
        // category choice. fallbackToDestructiveMigration() would drop this row and orphan its
        // photo file, which is the quiet data loss the real migration exists to avoid.
        createV1 {
            execSQL(
                "INSERT INTO pending_receipts " +
                    "(photoPath, capturedAt, state, draftJson, attempts, lastError, accountId, categoryId) " +
                    "VALUES ('/data/receipts/a.jpg', 1700000000000, 'AWAITING_REVIEW', " +
                    "'{\"merchant\":\"Tesco\"}', 2, 'boom', 7, 14)"
            )
        }

        // Opening it is the assertion: Room runs MIGRATION_1_2 and then validates the resulting
        // table against the compiled v2 entity, throwing if anything about it is wrong.
        val dao = open().pendingReceipts()

        val row = dao.nextInState(CaptureState.AWAITING_REVIEW)!!
        assertEquals("/data/receipts/a.jpg", row.photoPath)
        assertEquals(1_700_000_000_000L, row.capturedAt)
        assertEquals("""{"merchant":"Tesco"}""", row.draftJson)
        assertEquals(2, row.attempts)
        assertEquals("boom", row.lastError)
        assertEquals(7L, row.accountId)
        assertEquals(14L, row.categoryId)
    }

    @Test
    fun `a migrated database accepts a photo-less row`() = runTest {
        // The point of the migration. On v1 this INSERT fails outright: photoPath was NOT NULL.
        createV1 {}
        val dao = open().pendingReceipts()

        val id = dao.insert(
            PendingReceiptEntity(
                photoPath = null,
                capturedAt = 1_700_000_000_001,
                state = CaptureState.AWAITING_REVIEW,
            )
        )

        assertNull(dao.byId(id)!!.photoPath)
    }

    @Test
    fun `the autoincrement counter survives when rows are copied across`() = runTest {
        createV1 {
            execSQL(
                "INSERT INTO pending_receipts (id, photoPath, capturedAt, state, attempts) " +
                    "VALUES (41, '/data/receipts/a.jpg', 1700000000000, 'CAPTURED', 0)"
            )
        }
        val dao = open().pendingReceipts()

        val id = dao.insert(
            PendingReceiptEntity(photoPath = null, capturedAt = 1, state = CaptureState.AWAITING_REVIEW)
        )

        assertEquals(42L, id)
    }

    @Test
    fun `the autoincrement counter survives an upgrade with an empty queue`() = runTest {
        // The case that actually bites, and the one the copy above passes by accident: a
        // successful post deletes its row, so the overwhelmingly normal upgrade finds the queue
        // *empty*. A recreate-and-copy migration then carries no rows, seeds no sqlite_sequence
        // entry for the new table, and DROP TABLE has taken the old one with it -- so the next
        // receipt is issued id 1, an id already handed out before the upgrade. A review
        // notification's PendingIntent built pre-upgrade for receipt 1 would open Review on a
        // different receipt 1.
        createV1 {
            execSQL(
                "INSERT INTO pending_receipts (id, photoPath, capturedAt, state, attempts) " +
                    "VALUES (41, '/data/receipts/a.jpg', 1700000000000, 'CAPTURED', 0)"
            )
            execSQL("DELETE FROM pending_receipts")
        }
        val dao = open().pendingReceipts()

        val id = dao.insert(
            PendingReceiptEntity(photoPath = null, capturedAt = 1, state = CaptureState.AWAITING_REVIEW)
        )

        assertEquals(42L, id)
    }

    @Test
    fun `migration 2 to 3 backfills a distinct non-empty idempotency key onto every existing row`() = runTest {
        // A dev install carrying two queued receipts across the v2->v3 upgrade. Each must come out
        // with its own key: a shared or blank key would make the server treat the two rows as the
        // same purchase and refuse to record the second. Opening the file also *is* the schema
        // check -- Room validates the migrated table against the compiled v3 entity.
        createV2 {
            execSQL(
                "INSERT INTO pending_receipts (photoPath, capturedAt, state, attempts) " +
                    "VALUES ('/data/receipts/a.jpg', 1700000000000, 'FAILED', 1)"
            )
            execSQL(
                "INSERT INTO pending_receipts (photoPath, capturedAt, state, attempts) " +
                    "VALUES ('/data/receipts/b.jpg', 1700000000001, 'FAILED', 1)"
            )
        }

        val dao = open().pendingReceipts()

        val keys = dao.observeAll().first().map { it.idempotencyKey }
        assertEquals(2, keys.size)
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals(2, keys.toSet().size)                 // distinct
        assertTrue(keys.all { it.length <= 64 })           // within the server's cap
    }

    @Test
    fun `migration 3 to 4 adds a null splitsJson to every existing row`() = runTest {
        // A dev install carrying a queued receipt across the v3->v4 upgrade, from before splits
        // existed at all -- it must come out with a null splitsJson (not lost, not defaulted to
        // something a decode would choke on). Opening the file also *is* the schema check -- Room
        // validates the migrated table against the compiled v4 entity.
        createV3 {
            execSQL(
                "INSERT INTO pending_receipts (photoPath, capturedAt, state, attempts, idempotencyKey) " +
                    "VALUES ('/data/receipts/a.jpg', 1700000000000, 'FAILED', 1, 'abc123')"
            )
        }

        val dao = open().pendingReceipts()

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertNull(rows.single().splitsJson)
    }
}
