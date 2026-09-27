package dev.otherworld.budget.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.local.AppDatabase
import dev.otherworld.budget.data.local.FakeSnapshotDao
import java.time.Instant

/**
 * Builds a [SnapshotStore] over a fresh in-memory Room database, the same setup
 * [SnapshotStoreTest] used to build inline. Exercising the real DAO -- not a hand-rolled fake --
 * is what proves the JSON round-trips through actual SQLite, which is exactly what
 * [dev.otherworld.budget.data.local.SnapshotCodec] and the owner-scoping in [SnapshotStore] need
 * covered.
 *
 * Takes the [CredentialStore] rather than building one internally so a test can keep a reference
 * to it and change the signed-in owner mid-test (e.g. simulating a sign-in to a different
 * server) while every [SnapshotStore] built from the same call keeps reading the one database.
 *
 * Needs a Robolectric context -- [ApplicationProvider.getApplicationContext] only resolves under
 * `RobolectricTestRunner` -- so any test calling this must be `@RunWith(RobolectricTestRunner::class)`.
 */
object TestSnapshots {
    fun inMemory(credentials: CredentialStore, now: () -> Instant = { Instant.now() }): SnapshotStore {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        return SnapshotStore(db.snapshots(), credentials, now)
    }

    /**
     * A [SnapshotStore] over a hand-rolled [FakeSnapshotDao] instead of Room -- for the many
     * `CatalogRepository(api, ...)` call sites (view-model tests, mostly) that need *a* working
     * store to satisfy the constructor but have no interest in persistence themselves, and so
     * have no reason to need a Robolectric context.
     */
    fun fake(): SnapshotStore = SnapshotStore(
        FakeSnapshotDao(),
        InMemoryCredentialStore(Credentials("https://cloud.example", "adam", "pw")),
        now = { Instant.now() },
    )
}
