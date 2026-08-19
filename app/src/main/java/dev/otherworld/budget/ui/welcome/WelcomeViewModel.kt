package dev.otherworld.budget.ui.welcome

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.prefs.WelcomeStore
import javax.inject.Inject

/**
 * Backs the one-time [WelcomeScreen]. Its only job is to record that the welcome has been shown, so
 * that first-run gating in [dev.otherworld.budget.MainActivity] never routes here again.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val welcome: WelcomeStore,
) : ViewModel() {

    /** Marks the welcome as shown. Called as the user leaves the screen ("Get started"). */
    fun markSeen() = welcome.markSeen()
}
