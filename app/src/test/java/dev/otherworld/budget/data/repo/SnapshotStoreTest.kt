package dev.otherworld.budget.data.repo

import android.database.sqlite.SQLiteFullException
import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.local.SnapshotEntity
import dev.otherworld.budget.data.local.SnapshotKind
import kotlinx.coroutines.test.runTest
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

    private val credentialStore = InMemoryCredentialStore()
    private var clockValue = Instant.parse("2026-09-27T10:00:00Z")

    private fun store() = TestSnapshots.inMemory(credentialStore, now = { clockValue })

    private val alice = Credentials(server = "https://a.example", loginName = "alice", appPassword = "x")
    private val bob = Credentials(server = "https://b.example", loginName = "bob", appPassword = "y")

    @Before fun setUp() {
        credentialStore.save(alice)
    }

    @Test
    fun `read returns what write stored with fetchedAt from the clock`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"hi"}""", subject.currentOwner())

        val cached = subject.read(SnapshotKind.ACCOUNTS) { it }!!
        assertEquals("""{"value":"hi"}""", cached.value)
        assertEquals(clockValue, cached.fetchedAt)
    }

    @Test
    fun `read ignores a row written for another server`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"alice's"}""", subject.currentOwner())

        credentialStore.save(bob)
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
    }

    @Test
    fun `write is a no-op when signed out`() = runTest {
        credentialStore.clear()
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"hi"}""", subject.currentOwner())

        credentialStore.save(alice)
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
    }

    @Test
    fun `clear removes every kind`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, """{"a":1}""", subject.currentOwner())
        subject.write(SnapshotKind.RECENT, """{"b":2}""", subject.currentOwner())

        subject.clear()

        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
        assertNull(subject.read(SnapshotKind.RECENT) { it })
    }

    @Test
    fun `malformed json reads as null`() = runTest {
        val subject = store()
        subject.write(SnapshotKind.ACCOUNTS, "not json", subject.currentOwner())

        assertNull(subject.read(SnapshotKind.ACCOUNTS) { raw -> raw.takeIf { it == "valid" } })
    }

    @Test
    fun `a write for an owner who is no longer signed in is dropped`() = runTest {
        // The owner a fetch started under, not whoever is signed in when it lands: a late answer
        // from alice's session must not be stored as bob's.
        val subject = store()
        val aliceOwner = subject.currentOwner()

        credentialStore.save(bob)
        subject.write(SnapshotKind.ACCOUNTS, """{"value":"alice's"}""", aliceOwner)

        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
        credentialStore.save(alice)
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
    }

    @Test
    fun `a failing database reads as nothing and never throws`() = runTest {
        // A full disk (SQLiteFullException) must cost the cache, not crash whichever screen's
        // refresh happened to touch it -- Capture refreshes on every resume.
        val subject = SnapshotStore(ThrowingSnapshotDao(), credentialStore, now = { clockValue })

        subject.write(SnapshotKind.ACCOUNTS, """{"value":"hi"}""", subject.currentOwner())
        assertNull(subject.read(SnapshotKind.ACCOUNTS) { it })
        subject.clear()
    }

    private class ThrowingSnapshotDao : SnapshotDao {
        override suspend fun get(kind: String): SnapshotEntity? = throw SQLiteFullException("disk full")
        override suspend fun put(entity: SnapshotEntity) = throw SQLiteFullException("disk full")
        override suspend fun clear() = throw SQLiteFullException("disk full")
    }
}
