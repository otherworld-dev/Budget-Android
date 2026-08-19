package dev.otherworld.budget.di

import android.content.Context
import dev.otherworld.budget.data.auth.*
import dev.otherworld.budget.data.remote.*
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.repo.ReceiptRepository
import dev.otherworld.budget.data.theme.ThemeController
import dev.otherworld.budget.data.theme.ThemePalette
import dev.otherworld.budget.data.theme.ThemingApi
import dev.otherworld.budget.data.theme.ThemingApiRetrofit
import dev.otherworld.budget.data.work.QueueScheduler
import dev.otherworld.budget.data.work.QueueScheduling
import dev.otherworld.budget.data.work.ReceiptNotifier
import dev.otherworld.budget.data.work.ReceiptNotifying
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the plain [OkHttpClient] used for Nextcloud Login Flow v2. Login Flow requests
 * carry no `OCS-APIRequest` and no `Authorization` header -- there are no credentials yet
 * to authorise with, and the server being logged into may not even be the one already
 * stored. Giving it a dedicated, uninterceptored client makes it impossible for
 * [BaseUrlInterceptor] or [AuthInterceptor] to touch a login request by construction,
 * rather than relying on them merely no-op'ing when [CredentialStore] happens to be
 * empty (which is false on a re-login: signing into server B while already signed in to
 * server A would otherwise silently redirect the request to A with a stale Authorization
 * header attached).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LoginFlowHttpClient

/**
 * The server address is unknown until the user logs in, so Retrofit is built with
 * a placeholder base URL and this interceptor rewrites the scheme/host/port/prefix
 * on every call from the stored credentials.
 */
class BaseUrlInterceptor(private val store: CredentialStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val server = store.load()?.server ?: return chain.proceed(chain.request())
        val base = server.toHttpUrlOrNull() ?: return chain.proceed(chain.request())
        val rewritten = chain.request().url.newBuilder()
            .scheme(base.scheme).host(base.host).port(base.port)
            .encodedPath(base.encodedPath.trimEnd('/') + chain.request().url.encodedPath)
            .build()
        return chain.proceed(chain.request().newBuilder().url(rewritten).build())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json() = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun okHttp(store: CredentialStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BaseUrlInterceptor(store))
        .addInterceptor(AuthInterceptor(store))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // extraction can be slow on a small server
        .build()

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://placeholder.invalid/")   // always rewritten by BaseUrlInterceptor
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun budgetService(retrofit: Retrofit): BudgetService = retrofit.create(BudgetService::class.java)

    @Provides @Singleton @LoginFlowHttpClient
    fun loginFlowHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun loginFlowClient(@LoginFlowHttpClient client: OkHttpClient) = LoginFlowClient(client)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds @Singleton abstract fun budgetApi(impl: BudgetApiRetrofit): BudgetApi
    @Binds @Singleton abstract fun receiptQueue(impl: ReceiptRepository): ReceiptQueue
    @Binds @Singleton abstract fun receiptNotifying(impl: ReceiptNotifier): ReceiptNotifying
    @Binds @Singleton abstract fun queueScheduling(impl: QueueScheduler): QueueScheduling
    @Binds @Singleton abstract fun loginFlow(impl: LoginFlowClient): LoginFlow
    @Binds @Singleton abstract fun session(impl: SessionManager): Session
    @Binds @Singleton abstract fun authExpiry(impl: CredentialExpiry): AuthExpiry
    @Binds @Singleton abstract fun themingApi(impl: ThemingApiRetrofit): ThemingApi
    @Binds @Singleton abstract fun themePalette(impl: ThemeController): ThemePalette

    companion object {
        @Provides @Singleton
        fun credentialStore(@ApplicationContext context: Context): CredentialStore =
            EncryptedCredentialStore(context)
    }
}
