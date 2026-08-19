package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.auth.CredentialStore
import okhttp3.Credentials as OkCredentials
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the three headers every Nextcloud OCS request needs. Without
 * OCS-APIRequest the server's SecurityMiddleware rejects the call on CSRF
 * grounds even for reads.
 */
class AuthInterceptor(private val store: CredentialStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = store.load() ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("Authorization", OkCredentials.basic(credentials.loginName, credentials.appPassword))
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}
