package dev.otherworld.budget.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.local.AppDatabase
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.local.SnapshotKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class SnapshotStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SnapshotDao
    private val credentialStore = InMemoryCredentialStore()
    private var clockValue = Instant.parse("2026-09-27T10:00:00Z")

    private fun store() = SnapshotStore(dao, credentialStore, now = { clockValue })

    private val alice = Credentials(server = "https://a.example", loginName = "alice", appPassword = "x")
    private val bob = Credentials(server = "https://b.example", loginName = "bob", appPassword = "y")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.snapshots()
        credentialStore.save(alice)
    }

    @After fun tearDown() = db.close()

    @Test
    fun `read returns what write stored with fetchedAt from the clock`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"hi"}""")

        val cached = subject.read(SnapshotKind.ACCOUNTS) { it }!!
        assertEquals("""{"value":"hi"}""", cached.value)
        assertEquals(clockValue, cached.fetchedAt)
    }

    @Test
    fun `read ignores a row written for another server`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"alice's"}""")

        credentialStore.save(bob)
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
    }

    @Test
    fun `write is a no-op when signed out`() = runTest {
        credentialStore.clear()
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"hi"}""")

        credentialStore.save(alice)
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
    }

    @Test
    fun `clear removes every kind`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"a":1}""")
        subject.write(SnapshotKind.RECENT, """{"b":2}""")

        subject.clear()

        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
        assertNull(subject.read(SnapshotKind.RECENT) { it })
    }

    @Test
    fun `malformed json reads as null`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, "not json")

        assertNull(subject.read(SnapshotKind.ACCOUNTS) { raw -> raw.takeIf { it == "valid" } })
    }
}
