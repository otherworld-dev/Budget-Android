package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.auth.CredentialExpiry
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.CheckRepository
import dev.otherworld.budget.data.repo.TestSnapshots
import dev.otherworld.budget.di.BaseUrlInterceptor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import javax.inject.Provider

/**
 * Builds the same Retrofit stack [dev.otherworld.budget.di.NetworkModule] wires
 * through Hilt, but without Hilt, so unit tests can exercise [BudgetApiRetrofit]
 * against a [okhttp3.mockwebserver.MockWebServer] directly.
 */
object TestApiFactory {
    /**
     * Wired with the real [CredentialExpiry] over the same [store], not a fake, so a test can
     * observe the production consequences of a 401 -- the credentials being cleared, the
     * [CatalogRepository] cache being dropped -- rather than merely that a collaborator was
     * called. [catalog] defaults to a throwaway repository -- over its own in-memory snapshot
     * store, scoped to the same [store] -- for the tests that only care about the credentials.
     */
    fun create(
        store: CredentialStore,
        catalog: CatalogRepository = CatalogRepository(FakeBudgetApi(), TestSnapshots.inMemory(store)),
    ): BudgetApi {
        // One shared cache between the api and its expiry, as in the Hilt graph.
        val currencyCache = FallbackCurrencyCache()
        // Throwaway, like the default catalog: nothing here reads check data, but an expiry resets it.
        val check by lazy { CheckRepository(FakeBudgetApi(), catalog, TestSnapshots.fake(store), now = { Instant.now() }) }
        return BudgetApiRetrofit(
            service(store),
            CredentialExpiry(store, Provider { catalog }, Provider { check }, currencyCache),
            currencyCache,
        )
    }

    /** The raw [BudgetService], for tests exercising a single endpoint (e.g. SessionManagerTest). */
    fun service(store: CredentialStore): BudgetService {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor(store))
            .addInterceptor(AuthInterceptor(store))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://placeholder.invalid/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(BudgetService::class.java)
    }
}
