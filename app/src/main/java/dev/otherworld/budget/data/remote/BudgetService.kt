package dev.otherworld.budget.data.remote

import dev.otherworld.budget.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

private const val BASE = "ocs/v2.php/apps/budget/api/v1"

interface BudgetService {
    @GET("$BASE/capabilities")
    suspend fun capabilities(): Response<OcsResponse<CapabilitiesDto>>

    /**
     * Nextcloud *core*'s capabilities, which carry the instance/user theming colour
     * (`ocs.data.capabilities.theming.color`). Not under [BASE]: this is a core OCS route, not a
     * Budget-app one, so it answers 200 on any modern server even while the Budget routes 404.
     * Rides the same authenticated client and [dev.otherworld.budget.di.BaseUrlInterceptor] base
     * URL as every other call, so it hits the logged-in server with the three OCS headers.
     */
    @GET("ocs/v2.php/cloud/capabilities")
    suspend fun coreCapabilities(): Response<OcsResponse<CoreCapabilitiesDto>>

    @GET("$BASE/accounts")
    suspend fun accounts(): Response<OcsResponse<List<AccountDto>>>

    @GET("$BASE/categories")
    suspend fun categories(): Response<OcsResponse<List<CategoryDto>>>

    @GET("$BASE/transactions/recent")
    suspend fun recent(@Query("limit") limit: Int): Response<OcsResponse<List<RecentDto>>>

    /** `month` omitted (null) lets the server pick the user's current budget month. */
    @GET("$BASE/budget/status")
    suspend fun budgetStatus(@Query("month") month: String?): Response<OcsResponse<BudgetStatusDto>>

    @GET("$BASE/bills/upcoming")
    suspend fun upcomingBills(@Query("days") days: Int): Response<OcsResponse<UpcomingBillsDto>>

    @GET("$BASE/transactions/{id}/splits")
    suspend fun transactionSplits(@Path("id") id: Long): Response<OcsResponse<SplitsDto>>

    @Multipart
    @POST("$BASE/ocr/extract")
    suspend fun extract(@Part image: MultipartBody.Part): Response<OcsResponse<DraftDto>>

    @Multipart
    @POST("$BASE/transactions")
    suspend fun createTransaction(
        /**
         * The per-row idempotency key. Sent as a header (rather than a multipart field) so it rides
         * every POST identically regardless of body encoding, and because the server reads the
         * header when the field is absent -- see `docs/server-api-contract.md`. The header name is
         * fixed server-side; the value is capped at 64 characters.
         */
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part("account_id") accountId: RequestBody,
        @Part("category_id") categoryId: RequestBody?,
        @Part("date") date: RequestBody,
        @Part("merchant") merchant: RequestBody,
        @Part("amount") amount: RequestBody,
        /**
         * A JSON array string, e.g. `[{"amount":"3.40","category_id":12,"description":"Flat
         * White"},{"amount":"1.42","description":"Tax"}]`. Omitted (null) for a single-category
         * save, exactly like [photo] -- not sent as an empty array.
         */
        @Part("splits") splits: RequestBody?,
        /**
         * Nullable, and null means the part is left out of the body altogether -- Retrofit's
         * handler for a raw [MultipartBody.Part] simply skips a null value, it does not send an
         * empty part. That is what a Quick Add manual entry posts (there is no photo), and the
         * contract makes `photo` optional to match.
         */
        @Part photo: MultipartBody.Part?,
    ): Response<OcsResponse<CreatedDto>>

    /**
     * Revokes the app password used to sign in, e.g. on sign-out -- see [dev.otherworld.budget.data.auth.SessionManager].
     * Not under [BASE]: this is a Nextcloud core OCS route, not a Budget-app one. The response
     * body is never inspected -- only [Response.isSuccessful] matters -- so it is left as a raw
     * [ResponseBody] rather than risk a converter-factory mismatch on a shape this app doesn't
     * otherwise model.
     */
    @DELETE("ocs/v2.php/core/apppassword")
    suspend fun revokeAppPassword(): Response<ResponseBody>
}
