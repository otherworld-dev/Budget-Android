package dev.otherworld.budget.data.theme

import dev.otherworld.budget.data.prefs.ThemeColorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The clear/refresh half of theme adoption, injected where session lifecycle happens
 * ([dev.otherworld.budget.data.auth.SessionManager] clears; onboarding and app start refresh).
 */
interface ThemePalette {
    /**
     * Forgets the adopted colour -- both the persisted hex and the live [ThemeController.seed] --
     * so the UI reverts to the default Nextcloud blue. Called on sign-out and on a server switch,
     * the same points that clear the last-server, last-account and fallback-currency state.
     */
    fun clear()

    /**
     * Fetches the signed-in server's theming colour and, if it is usable, persists and applies it.
     * Safe to call when logged out or offline: the fetch simply returns `null` and nothing changes.
     * Never clears an existing colour on failure -- a transient network error must not flash the UI
     * back to the default.
     */
    suspend fun refresh()

    /**
     * Fire-and-forget [refresh] on an application-lifetime scope, for callers that must not block
     * on it and whose own scope may not outlive the fetch -- the onboarding ViewModel (cleared the
     * moment login navigates away) and app startup. The refresh applies reactively via [ThemeController.seed]
     * whenever it lands.
     */
    fun refreshInBackground()
}

/**
 * Single source of truth for the runtime theme seed. Holds it as a [StateFlow] the theme layer
 * observes, so an adopted colour applies reactively without a restart, and seeds that flow from
 * the plain-prefs [ThemeColorStore] at construction so a cold start paints the stored brand colour
 * at the first composition rather than flashing the default first (see [ThemeColorStore]'s KDoc).
 *
 * `null` seed == the default scheme: that is the state when logged out, before the first fetch,
 * when a fetch fails, and when the server has no theming.
 */
@Singleton
class ThemeController @Inject constructor(
    private val store: ThemeColorStore,
    private val api: ThemingApi,
) : ThemePalette {

    // Initialised from persisted prefs so the very first value the theme reads is the stored
    // colour, not the default -- this is what avoids the blue->brand flash on a cold start.
    private val _seed = MutableStateFlow(store.get())

    /** The hex seed to theme from, or `null` for the default scheme. */
    val seed: StateFlow<String?> = _seed.asStateFlow()

    // Outlives any Activity or ViewModel: a refresh triggered by a login navigating away from
    // onboarding must still complete. SupervisorJob so one failed refresh never poisons the next.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun refresh() {
        val hex = api.themeColor() ?: return
        // Validate before persisting: a malformed colour must never be stored or applied, it
        // falls back silently. Storing the raw string (not a parsed form) keeps the wire value.
        if (ThemeColors.isUsable(hex)) {
            store.set(hex)
            _seed.value = hex
        }
    }

    override fun refreshInBackground() {
        scope.launch { refresh() }
    }

    override fun clear() {
        store.clear()
        _seed.value = null
    }
}
