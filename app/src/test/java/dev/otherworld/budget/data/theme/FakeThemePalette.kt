package dev.otherworld.budget.data.theme

/**
 * Hand-rolled test fake, same pattern as FakeLastServer / RecordingQueue in SessionManagerTest.
 * Records what the session lifecycle did to the theme without doing any network work.
 */
class FakeThemePalette : ThemePalette {
    var cleared = false
        private set
    var refreshCalls = 0
        private set
    var backgroundRefreshCalls = 0
        private set

    override fun clear() {
        cleared = true
    }

    override suspend fun refresh() {
        refreshCalls++
    }

    override fun refreshInBackground() {
        backgroundRefreshCalls++
    }
}
