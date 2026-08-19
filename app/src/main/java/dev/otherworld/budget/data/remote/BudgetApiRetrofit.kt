package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.auth.AuthExpiry
import dev.otherworld.budget.data.remote.dto.AccountDto
import dev.otherworld.budget.data.remote.dto.CategoryDto
import dev.otherworld.budget.data.remote.dto.SplitWireDto
import dev.otherworld.budget.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import javax.inject.Inject

class BudgetApiRetrofit @Inject constructor(
    private val service: BudgetService,
    private val authExpiry: AuthExpiry,
    /**
     * Currency used when a payload omits its own, resolved from [capabilities] the first time
     * it's needed. A shared singleton rather than a private field so session teardown (expiry,
     * sign-out, server switch) can clear it -- see [FallbackCurrencyCache].
     */
    private val fallbackCurrency: FallbackCurrencyCache,
) : BudgetApi {

    /**
     * Encodes the `splits` multipart part. `explicitNulls = false` omits a null `category_id` or
     * `description` from the JSON entirely rather than writing `"category_id":null` -- that
     * omission is how the tax line is sent with no category.
     */
    private val splitsJson = Json { explicitNulls = false }

    /**
     * Every authenticated request in the app funnels through here, which makes this the one
     * place a [BudgetApiError.Unauthorized] can be observed for *any* endpoint -- so the
     * design's "401 -> clear credentials, return to onboarding" rule is raised here rather
     * than re-implemented in each repository and ViewModel that happens to call an endpoint
     * (where the next one added would simply forget). Sign-out revocation and the Login Flow
     * do not pass through here, and must not: they have their own notion of a rejected
     * request. See [AuthExpiry] for what the signal does and, importantly, does not clear.
     *
     * [CancellationException] is rethrown, never wrapped into a failed [Result]. A `runCatching`
     * here used to swallow it into `ServerError(0)`, which made a torn-down request look like a
     * definitive server answer: the caller's coroutine kept running as if the call had completed,
     * and [dev.otherworld.budget.data.repo.ReceiptRepository.post] recorded a completed attempt
     * for a request that was merely abandoned. BudgetApi's contract is that real failures arrive
     * as a failed Result and cancellation arrives as cancellation.
     */
    private suspend fun <D, T> call(
        request: suspend () -> Response<OcsResponse<D>>,
        map: suspend (D) -> T,
    ): Result<T> {
        // Captured before the request is dispatched: a 401 may only end the session whose
        // credentials actually signed this request. The shared client's read timeout is 120s,
        // so a request signed with a revoked password can land its 401 *after* the user has
        // completed a fresh login -- without the generation check that stale 401 would wipe
        // the brand-new credentials and eject them straight back to Onboarding.
        val generationAtRequest = authExpiry.currentGeneration
        return try {
            val response = request()
            if (!response.isSuccessful) {
                throw ErrorMapper.fromHttp(response.code(), response.errorBody()?.string())
            }
            Result.success(map(response.body()!!.ocs.data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(ErrorMapper.fromThrowable(e))
        }.also { result ->
            if (result.exceptionOrNull() is BudgetApiError.Unauthorized) {
                authExpiry.onUnauthorized(generationAtRequest)
            }
        }
    }

    override suspend fun capabilities() = call({ service.capabilities() }) { dto ->
        fallbackCurrency.value = dto.currency
        Capabilities(dto.ocrAvailable, dto.currency, dto.version, splitsAvailable = dto.splitsAvailable)
    }

    override suspend fun accounts() = call({ service.accounts() }) { it.map(AccountDto::toDomain) }

    override suspend fun categories() = call({ service.categories() }) { it.map(CategoryDto::toDomain) }

    override suspend fun recentTransactions(limit: Int) =
        call({ service.recent(limit) }) { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun extract(photo: File) = call({
        service.extract(MultipartBody.Part.createFormData("image", photo.name, photo.asRequestBody(JPEG)))
    }) { dto ->
        // Only resolve the fallback (one extra request, once per session -- capabilities()
        // caches into fallbackCurrency on success) when the draft actually needs it; a
        // draft that already states its own currency never pays for the extra round trip.
        val currency = dto.currency ?: fallbackCurrency.value ?: capabilities().getOrElse { throw it }.currency
        dto.toDomain(currency)
    }

    override suspend fun createTransaction(request: CreateTransactionRequest, idempotencyKey: String) = call({
        service.createTransaction(
            idempotencyKey = idempotencyKey,
            accountId = request.accountId.toString().text(),
            categoryId = request.categoryId?.toString()?.text(),
            date = request.date.toString().text(),
            merchant = request.merchant.text(),
            amount = request.total.amount.toPlainString().text(),
            // Omitted entirely when there are no splits (an ordinary, single-category save) rather
            // than sent as an empty array -- exactly like `photo` below.
            splits = request.splits?.let { parts ->
                splitsJson.encodeToString(parts.map { SplitWireDto(it.amount.amount.toPlainString(), it.categoryId, it.description) }).text()
            },
            // Omitted entirely when there is no photo (a Quick Add manual entry) rather than sent
            // as an empty part: an empty `photo` would arrive server-side as a zero-byte upload,
            // which is a corrupt attachment, not an absent one.
            photo = request.photo?.let { file ->
                MultipartBody.Part.createFormData("photo", file.name, file.asRequestBody(JPEG))
            },
        )
    }) { dto ->
        // The server echoes the key it accepted. If it echoes a *different* one, our key did not
        // route to this transaction -- dedup is not doing what we rely on -- so this id is not
        // trustworthy as ours; fail (the row parks for review) rather than record a possibly-wrong
        // outcome. A silent id mismatch is exactly the failure the echo exists to catch. An absent
        // echo (older server) asserts nothing and passes through.
        if (dto.idempotencyKey != null && dto.idempotencyKey != idempotencyKey) {
            throw BudgetApiError.ServerError(0)
        }
        CreatedTransaction(dto.id, dto.splitsError)
    }

    private fun String.text(): RequestBody = toRequestBody(PLAIN)

    private companion object {
        val JPEG = "image/jpeg".toMediaType()
        val PLAIN = "text/plain".toMediaType()
    }
}
