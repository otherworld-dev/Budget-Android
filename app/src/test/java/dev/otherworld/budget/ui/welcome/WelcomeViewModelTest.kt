package dev.otherworld.budget.ui.welcome

import dev.otherworld.budget.data.prefs.WelcomeStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeViewModelTest {

    @Test
    fun `markSeen records that the welcome has been shown`() {
        val store = FakeWelcomeStore()
        assertFalse(store.seen())

        WelcomeViewModel(store).markSeen()

        assertTrue(store.seen())
    }
}

private class FakeWelcomeStore : WelcomeStore {
    private var seen = false
    override fun seen(): Boolean = seen
    override fun markSeen() { seen = true }
}
