package dev.otherworld.budget.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.domain.model.RecentTransaction
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentUiState(
    val loading: Boolean = true,
    val transactions: List<RecentTransaction> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val api: BudgetApi,
    private val credentials: CredentialStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentUiState())
    val uiState: StateFlow<RecentUiState> = _uiState.asStateFlow()

    /**
     * Resolved once. [CredentialStore] is Keystore-backed AES-GCM decryption, and [webUrlFor] is
     * called from the list's item scope -- once per visible row, again on every recomposition --
     * so reading it there decrypted the credentials dozens of times per scroll. The server cannot
     * change while this ViewModel is alive: signing out navigates away and destroys it.
     */
    private val serverUrl: String? = credentials.load()?.server

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _uiState.update { it.copy(loading = true, error = null) }
        api.recentTransactions()
            // A refresh failure must not blank a list the user is reading.
            .onSuccess { rows -> _uiState.update { it.copy(loading = false, transactions = rows) } }
            .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
    }

    fun webUrlFor(id: Long): String? =
        serverUrl?.let { "$it/apps/budget/#transactions?id=$id" }
}
