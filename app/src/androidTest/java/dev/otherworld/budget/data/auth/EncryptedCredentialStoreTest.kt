package dev.otherworld.budget.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCredentialStoreTest {

    private lateinit var store: EncryptedCredentialStore

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = EncryptedCredentialStore(context).also { it.clear() }
    }

    @Test fun roundTripsCredentials() {
        store.save(Credentials("https://cloud.example.com", "adam", "secret"))
        assertEquals("adam", store.load()!!.loginName)
    }

    @Test fun clearRemovesEverything() {
        store.save(Credentials("https://cloud.example.com", "adam", "secret"))
        store.clear()
        assertNull(store.load())
    }
}
