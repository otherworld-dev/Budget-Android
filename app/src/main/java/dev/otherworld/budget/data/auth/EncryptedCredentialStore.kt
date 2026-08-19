package dev.otherworld.budget.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Backed by [EncryptedSharedPreferences], which requires the Android Keystore.
 * Robolectric cannot fake the Keystore reliably, so this class is exercised
 * only by the androidTest suite (see EncryptedCredentialStoreTest); unit
 * tests use [InMemoryCredentialStore] instead.
 */
class EncryptedCredentialStore(context: Context) : CredentialStore {

    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "budget_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Credentials? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return Credentials(server, login, password)
    }

    override fun save(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_SERVER, credentials.server)
            .putString(KEY_LOGIN, credentials.loginName)
            .putString(KEY_PASSWORD, credentials.appPassword)
            .apply()
    }

    override fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login_name"
        const val KEY_PASSWORD = "app_password"
    }
}
