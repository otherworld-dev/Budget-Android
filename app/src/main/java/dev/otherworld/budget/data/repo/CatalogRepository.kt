package dev.otherworld.budget.data.repo

import dev.otherworld.budget.data.remote.BudgetApi
import dev.otherworld.budget.data.remote.Capabilities
import dev.otherworld.budget.domain.model.Account
import dev.otherworld.budget.domain.model.Category
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accounts, categories and capabilities change rarely, so they are cached for the
 * process lifetime. The review screen must not re-fetch them on every capture.
 */
@Singleton
class CatalogRepository @Inject constructor(private val api: BudgetApi) {

    private val lock = Mutex()
    private var accounts: List<Account>? = null
    private var categories: List<Category>? = null
    private var capabilities: Capabilities? = null

    suspend fun accounts(forceRefresh: Boolean = false): Result<List<Account>> = lock.withLock {
        accounts.takeUnless { forceRefresh }?.let { return Result.success(it) }
        api.accounts().onSuccess { accounts = it }
    }

    suspend fun categories(forceRefresh: Boolean = false): Result<List<Category>> = lock.withLock {
        categories.takeUnless { forceRefresh }?.let { return Result.success(it) }
        api.categories().onSuccess { categories = it }
    }

    suspend fun capabilities(forceRefresh: Boolean = false): Result<Capabilities> = lock.withLock {
        capabilities.takeUnless { forceRefresh }?.let { return Result.success(it) }
        api.capabilities().onSuccess { capabilities = it }
    }

    /** Drops all cached values so the next call of any kind re-fetches. */
    fun invalidate() {
        accounts = null
        categories = null
        capabilities = null
    }
}
