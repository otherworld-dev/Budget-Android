package dev.otherworld.budget.data.theme

import dev.otherworld.budget.data.prefs.ThemeColorStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory stand-in for the SharedPreferences store, so a "restart" is just a new controller. */
private class FakeThemeColorStore(initial: String? = null) : ThemeColorStore {
    private var value: String? = initial
    var writes = 0
        private set
    override fun get(): String? = value
    override fun set(hex: String) { value = hex; writes++ }
    override fun clear() { value = null }
}

/** Returns whatever colour it is told to, or null, standing in for the network fetch. */
private class FakeThemingApi(var color: String?) : ThemingApi {
    var calls = 0
        private set
    override suspend fun themeColor(): String? { calls++; return color }
}

class ThemeControllerTest {

    @Test
    fun `a stored colour is applied at construction, before any fetch`() = runTest {
        // The no-flash guarantee: a cold start re-reads the persisted colour synchronously, so the
        // first value the theme sees is the brand colour, not the default. Modelled as a fresh
        // controller over a pre-populated store.
        val api = FakeThemingApi(color = null)
        val controller = ThemeController(FakeThemeColorStore(initial = "#0082c9"), api)

        assertEquals("#0082c9", controller.seed.value)
        assertEquals(0, api.calls) // applied from prefs, no network involved
    }

    @Test
    fun `refresh persists and applies a usable colour`() = runTest {
        val store = FakeThemeColorStore()
        val controller = ThemeController(store, FakeThemingApi("#663399"))

        controller.refresh()

        assertEquals("#663399", controller.seed.value)
        assertEquals("#663399", store.get())
    }

    @Test
    fun `a malformed colour is neither stored nor applied - it falls back`() = runTest {
        val store = FakeThemeColorStore()
        val controller = ThemeController(store, FakeThemingApi("definitely-not-hex"))

        controller.refresh()

        assertNull(controller.seed.value)
        assertNull(store.get())
        assertEquals(0, store.writes)
    }

    @Test
    fun `a failed fetch never clears an already-adopted colour`() = runTest {
        // A transient network error must not flash the UI back to the default.
        val store = FakeThemeColorStore(initial = "#0082c9")
        val controller = ThemeController(store, FakeThemingApi(color = null))

        controller.refresh()

        assertEquals("#0082c9", controller.seed.value)
        assertEquals("#0082c9", store.get())
    }

    @Test
    fun `clear drops the stored and live colour, reverting to the default`() = runTest {
        val store = FakeThemeColorStore(initial = "#0082c9")
        val controller = ThemeController(store, FakeThemingApi(color = null))

        controller.clear()

        assertNull(controller.seed.value)
        assertNull(store.get())
    }

    @Test
    fun `server switch then re-fetch - cleared, then the new colour adopted`() = runTest {
        // Mirrors SessionManager: on a server change the old colour is cleared, then onboarding
        // refreshes and the new server's colour is applied. No old colour survives the switch.
        val store = FakeThemeColorStore(initial = "#0082c9")   // old server's colour
        val api = FakeThemingApi("#e91e63")                    // new server's colour
        val controller = ThemeController(store, api)

        controller.clear()                                     // SessionManager.signIn (server changed)
        assertNull(controller.seed.value)

        controller.refresh()                                   // onboarding adopts the new server's
        assertEquals("#e91e63", controller.seed.value)
    }
}
