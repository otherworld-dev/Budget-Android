package dev.otherworld.budget.data.auth

data class Credentials(val server: String, val loginName: String, val appPassword: String) {
    // The generated toString() would print appPassword; redact it so a Credentials caught in a
    // log line, exception message, or crash report never leaks the Nextcloud app password.
    override fun toString(): String =
        "Credentials(server=$server, loginName=$loginName, appPassword=***)"
}

interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials)
    fun clear()
}

/** Used by Compose previews and unit tests; never registered in the release graph. */
class InMemoryCredentialStore(private var value: Credentials? = null) : CredentialStore {
    override fun load() = value
    override fun save(credentials: Credentials) { value = credentials }
    override fun clear() { value = null }
}
