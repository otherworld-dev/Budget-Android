package dev.otherworld.budget.data.auth

import dev.otherworld.budget.data.remote.BudgetApiError
import dev.otherworld.budget.data.remote.ErrorMapper
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

data class LoginFlowStart(val loginUrl: String, val pollToken: String, val pollEndpoint: String)

/**
 * Obtains Nextcloud credentials via Login Flow v2: the app opens [LoginFlowStart.loginUrl]
 * in a browser, the user authorises there, and [poll] waits for the server to hand back
 * an app password. The user never types their Nextcloud password into this app.
 */
interface LoginFlow {
    suspend fun start(serverUrl: String): Result<LoginFlowStart>
    suspend fun poll(start: LoginFlowStart): Result<Credentials>
}

/**
 * Login Flow v2 hits `/index.php/login/v2` and its poll endpoint directly — these are not
 * OCS routes, so unlike the rest of this app's requests they carry no `OCS-APIRequest`
 * header and no `Authorization` header (there are no credentials yet to authorise with).
 */
class LoginFlowClient(
    private val client: OkHttpClient,
    private val pollIntervalMs: Long = 3_000,
) : LoginFlow {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class StartDto(val poll: PollDto, val login: String)
    @Serializable private data class PollDto(val token: String, val endpoint: String)
    @Serializable private data class CredentialsDto(
        val server: String,
        @SerialName("loginName") val loginName: String,
        @SerialName("appPassword") val appPassword: String,
    )

    // try/catch rather than runCatching: a CancellationException must propagate as
    // cancellation (see ErrorMapper.fromThrowable), and runCatching would capture the
    // rethrow straight back into the returned Result.
    override suspend fun start(serverUrl: String): Result<LoginFlowStart> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/index.php/login/v2")
                .header("User-Agent", USER_AGENT)
                .post(ByteArray(0).toRequestBody())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BudgetApiError.ServerError(response.code)
                val dto = json.decodeFromString<StartDto>(response.body!!.string())
                Result.success(LoginFlowStart(dto.login, dto.poll.token, dto.poll.endpoint))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(ErrorMapper.fromThrowable(e))
        }
    }

    override suspend fun poll(start: LoginFlowStart): Result<Credentials> = poll(start, timeout = 5.minutes)

    /**
     * Polls until the user finishes authorising in the browser. Nextcloud answers
     * 404 while the flow is still pending, so 404 is "keep waiting", not an error.
     * Any other non-2xx response stops immediately as a real failure.
     *
     * Per-attempt exceptions are triaged three ways: a [BudgetApiError] (e.g. the
     * non-404 status above) stops immediately; an [IOException] is treated as
     * transient — [start] already proved the server reachable before the browser
     * ever opened, so a dropped connection here is more likely the phone's wifi
     * blipping mid-authorisation than a real outage — and is retried, with the most
     * recent one kept so a deadline expiry can report it as the cause; anything else
     * (e.g. a malformed 200 body) is a bug, not a transient condition, so it is
     * mapped and returned immediately rather than retried for the full timeout.
     */
    suspend fun poll(start: LoginFlowStart, timeout: Duration): Result<Credentials> =
        withContext(Dispatchers.IO) {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            var lastNetworkError: IOException? = null
            while (deadline.hasNotPassedNow()) {
                val attempt = runCatching {
                    val request = Request.Builder()
                        .url(start.pollEndpoint)
                        .header("User-Agent", USER_AGENT)
                        .post("token=${start.pollToken}".toRequestBody(FORM))
                        .build()
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                val dto = json.decodeFromString<CredentialsDto>(response.body!!.string())
                                Credentials(dto.server.trimEnd('/'), dto.loginName, dto.appPassword)
                            }
                            response.code == 404 -> null // still pending
                            else -> throw BudgetApiError.ServerError(response.code)
                        }
                    }
                }
                attempt.getOrNull()?.let { return@withContext Result.success(it) }

                when (val error = attempt.exceptionOrNull()) {
                    null -> Unit // still pending (404); keep polling
                    is BudgetApiError -> return@withContext Result.failure(error)
                    is IOException -> lastNetworkError = error // transient; keep polling
                    // Cancellation is not a poll outcome to report -- rethrow so the caller's
                    // scope observes it (same rule as ErrorMapper.fromThrowable, made explicit
                    // here because runCatching above has already captured the exception).
                    is CancellationException -> throw error
                    else -> return@withContext Result.failure(ErrorMapper.fromThrowable(error))
                }
                delay(pollIntervalMs)
            }
            Result.failure(BudgetApiError.Network(lastNetworkError))
        }

    private companion object {
        const val USER_AGENT = "Budget Companion"
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
