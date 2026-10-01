package dev.otherworld.budget.data.remote

import dev.otherworld.budget.RobolectricTestApplication
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.InMemoryCredentialStore
import dev.otherworld.budget.data.remote.fake.FakeBudgetApi
import dev.otherworld.budget.data.repo.CatalogRepository
import dev.otherworld.budget.data.repo.TestSnapshots
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.otherworld.budget.domain.model.Direction
import dev.otherworld.budget.domain.model.Money
import dev.otherworld.budget.domain.model.TransferLink
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Robolectric, not pure JVM: [TestApiFactory.create]'s default [CatalogRepository] now persists
 * through a Room-backed [dev.otherworld.budget.data.repo.SnapshotStore] (see
 * [TestSnapshots.inMemory]), which needs a Context to open even for its in-memory driver.
 */
@Config(sdk = [35], application = RobolectricTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class BudgetApiRetrofitTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var api: BudgetApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = InMemoryCredentialStore().apply {
            save(Credentials(server.url("/").toString().trimEnd('/'), "adam", "pw"))
        }
        api = TestApiFactory.create(store)   // helper defined alongside NetworkModule
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(data: String) = MockResponse().setBody(
        """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""
    )

    private fun txnRequest() = CreateTransactionRequest(
        accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
        merchant = "Tesco",
        total = dev.otherworld.budget.domain.model.Money(BigDecimal("1.00"), "GBP"),
        photo = null,
    )

    @Test
    fun `accounts hits the OCS path and maps to domain`() = runTest {
        server.enqueue(ok("""[{"id":1,"name":"Current Account","currency":"GBP"}]"""))

        val accounts = api.accounts().getOrThrow()

        assertEquals("Current Account", accounts.single().name)
        assertEquals("/ocs/v2.php/apps/budget/api/v1/accounts", server.takeRequest().path)
    }

    @Test
    fun `accounts parse balance, base-currency balance, closed and shared`() = runTest {
        server.enqueue(ok("""
            [{"id":1,"name":"Savings","currency":"GBP","type":"savings",
              "balance":"1450.00","balance_in_base_currency":"1690.12","base_currency":"EUR",
              "closed":true,"shared":true}]
        """.trimIndent()))

        val account = api.accounts().getOrThrow().single()

        assertEquals(Money(BigDecimal("1450.00"), "GBP"), account.balance)
        assertEquals(Money(BigDecimal("1690.12"), "EUR"), account.balanceInBase)
        assertTrue(account.closed)
        assertTrue(account.shared)
        assertEquals("savings", account.type)
    }

    @Test
    fun `accounts from an older server parse with no balance`() = runTest {
        server.enqueue(ok("""[{"id":1,"name":"A","currency":"GBP"}]"""))

        val account = api.accounts().getOrThrow().single()

        assertNull(account.balance)
        assertFalse(account.closed)
    }

    @Test
    fun `extract posts multipart and maps the draft`() = runTest {
        server.enqueue(ok("""
            {"merchant":"Tesco","date":"2026-03-12","total":"24.31","currency":"GBP",
             "suggested_category_id":14,
             "line_items":[{"description":"Milk 2L","amount":"1.20"}]}
        """.trimIndent()))

        val photo = File.createTempFile("receipt", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val draft = api.extract(photo).getOrThrow()

        assertEquals("Tesco", draft.merchant)
        assertEquals(BigDecimal("24.31"), draft.total!!.amount)
        assertEquals(1, draft.lineItems.size)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `a 412 with ocr_not_configured surfaces as OcrNotConfigured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(412).setBody(
            """{"ocs":{"meta":{"status":"failure","statuscode":412},"data":{"error_code":"ocr_not_configured"}}}"""
        ))
        val error = api.extract(File.createTempFile("rcpt", ".jpg")).exceptionOrNull()
        assertTrue(error is BudgetApiError.OcrNotConfigured)
    }

    @Test
    fun `a 401 surfaces as Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(api.accounts().exceptionOrNull() is BudgetApiError.Unauthorized)
    }

    @Test
    fun `a 401 clears the stored credentials so the next launch re-onboards`() = runTest {
        // Spec section 5: Unauthorized -> "Clear credentials, return to onboarding". Before this,
        // Unauthorized was produced and consumed by nothing, so a revoked app password left every
        // request failing for ever with no route back to a working app. MainActivity's
        // start-destination resolution sends a launch with no credentials to Onboarding, so
        // clearing them here is what closes that loop.
        server.enqueue(MockResponse().setResponseCode(401))

        api.accounts()

        assertNull(store.load())
    }

    @Test
    fun `a 401 also drops the catalog cache so no screen renders a dead session`() = runTest {
        // The cache is process-lifetime by design, so without this Capture keeps rendering the
        // accounts and OCR banner it fetched before the password was revoked -- a healthy screen
        // over a queue that cannot drain.
        val cached = FakeBudgetApi()
        val catalog = CatalogRepository(cached, TestSnapshots.fake())
        val api = TestApiFactory.create(store, catalog)
        catalog.accounts()                          // populates the cache

        server.enqueue(MockResponse().setResponseCode(401))
        api.accounts()                              // the 401 that ends the session

        catalog.accounts()
        assertEquals(2, cached.accountCalls)        // re-fetched, so the cache really was dropped
    }

    @Test
    fun `an ordinary server error leaves the credentials alone`() = runTest {
        // The counterpart of the test above: only an authentication failure may sign the user
        // out. A 500 (or an offline device) must never cost them their session.
        server.enqueue(MockResponse().setResponseCode(500))

        api.accounts()

        assertNotNull(store.load())
    }

    @Test
    fun `a 403 does not sign the user out`() = runTest {
        // A 403 is a proxy, a WAF, an account without Budget access, or a route on the wrong
        // Nextcloud controller base class -- none of which a fresh login fixes, so treating it
        // as Unauthorized signed the user out again on every following request.
        server.enqueue(MockResponse().setResponseCode(403))

        val error = api.accounts().exceptionOrNull()

        assertEquals(403, (error as BudgetApiError.ServerError).code)
        assertNotNull(store.load())
    }

    @Test
    fun `createTransaction sends every field, the idempotency header, and returns the new id`() = runTest {
        server.enqueue(ok("""{"id":9002,"idempotency_key":"idem-abc"}"""))
        val photo = File.createTempFile("receipt", ".jpg").apply { writeBytes(byteArrayOf(1)) }

        val created = api.createTransaction(
            CreateTransactionRequest(
                accountId = 1, categoryId = 14, date = LocalDate.of(2026, 3, 12),
                merchant = "Tesco",
                total = dev.otherworld.budget.domain.model.Money(BigDecimal("24.31"), "GBP"),
                photo = photo,
            ),
            idempotencyKey = "idem-abc",
        ).getOrThrow()

        assertEquals(9002L, created.id)
        val request = server.takeRequest()
        // The key rides as a header, not a body part, so it is identical whether or not a photo is
        // attached (docs/server-api-contract.md).
        assertEquals("idem-abc", request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("Tesco"))
        assertTrue(body.contains("24.31"))
        assertTrue(body.contains("2026-03-12"))
        assertFalse(body.contains("idempotency_key"))   // header, never a part
    }

    @Test
    fun `createTransaction omits the photo part entirely when there is none`() = runTest {
        // A Quick Add manual entry. The part must be absent, not empty: an empty `photo` arrives
        // server-side as a zero-byte upload, which is a corrupt attachment rather than no
        // attachment. `photo` is optional on POST transactions for exactly this
        // (docs/server-api-contract.md).
        server.enqueue(ok("""{"id":9003}"""))

        val created = api.createTransaction(
            CreateTransactionRequest(
                accountId = 1, categoryId = null, date = LocalDate.of(2026, 3, 12),
                merchant = "Parking",
                total = dev.otherworld.budget.domain.model.Money(BigDecimal("4.20"), "GBP"),
                photo = null,
            ),
            idempotencyKey = "idem-xyz",
        ).getOrThrow()

        assertEquals(9003L, created.id)
        val request = server.takeRequest()
        assertEquals("idem-xyz", request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("""name="amount""""))
        assertFalse(body.contains("""name="photo""""))
    }

    @Test
    fun `an absent idempotency echo is accepted -- an older server asserts nothing`() = runTest {
        server.enqueue(ok("""{"id":9004}"""))
        val created = api.createTransaction(txnRequest(), idempotencyKey = "idem-1").getOrThrow()
        assertEquals(9004L, created.id)
    }

    @Test
    fun `a mismatched idempotency echo is refused rather than trusted`() = runTest {
        // The one silent-failure the echo exists to catch: the server accepted (or already held) a
        // *different* transaction under our key, so this id is not provably ours. Fail, so the row
        // parks for review, instead of recording a possibly-wrong outcome.
        server.enqueue(ok("""{"id":9005,"idempotency_key":"some-other-key"}"""))
        val result = api.createTransaction(txnRequest(), idempotencyKey = "idem-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `createTransaction surfaces splits_error while still succeeding`() = runTest {
        server.enqueue(ok("""{"id":9002,"splits_error":"Split amounts (3.00) must equal transaction amount (23.77)"}"""))
        val created = api.createTransaction(txnRequest(), idempotencyKey = "k").getOrThrow()
        assertEquals(9002L, created.id)
        assertEquals("Split amounts (3.00) must equal transaction amount (23.77)", created.splitsError)
    }

    @Test
    fun `createTransaction sends splits as a json part, tax omitting category_id`() = runTest {
        server.enqueue(ok("""{"id":9002}"""))
        api.createTransaction(
            txnRequest().copy(
                categoryId = null,
                splits = listOf(
                    dev.otherworld.budget.domain.model.SplitPart(Money(BigDecimal("3.40"), "GBP"), 12, "Flat White"),
                    dev.otherworld.budget.domain.model.SplitPart(Money(BigDecimal("1.42"), "GBP"), null, "Tax"),
                ),
            ),
            idempotencyKey = "k",
        ).getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("""name="splits""""))
        assertTrue(body.contains(""""amount":"3.40""""))
        assertTrue(body.contains(""""category_id":12"""))
        // The tax part carries no category_id key at all.
        assertTrue(body.contains(""""amount":"1.42""""))
        assertFalse(Regex(""""amount":"1\.42","category_id"""").containsMatchIn(body))
    }

    @Test
    fun `createTransaction omits the splits part when there are none`() = runTest {
        server.enqueue(ok("""{"id":9002}"""))
        api.createTransaction(txnRequest(), idempotencyKey = "k").getOrThrow()
        assertFalse(server.takeRequest().body.readUtf8().contains("""name="splits""""))
    }

    @Test
    fun `cancelling an in-flight call propagates the cancellation instead of fabricating a result`() {
        // Against the real Retrofit/OkHttp stack, not a fake: the fakes in the repository tests
        // propagate cancellation by construction (they suspend on a Deferred), but production
        // used to swallow it -- runCatching in call() turned the CancellationException a
        // cancelled Retrofit call resumes with into Result.failure(ServerError(0)), a
        // definitive-looking outcome for a request that was merely torn down. This pins the
        // production semantics the queue's unknown-outcome handling now relies on.
        // NO_RESPONSE rather than a headers delay: the server holds the connection open until
        // the client closes it, so the cancelled call is what releases the dispatcher -- and
        // tearDown's shutdown() isn't left waiting out a sleeping response thread.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        runBlocking {
            var returned: Result<*>? = null
            val call = launch { returned = api.accounts() }
            delay(250)   // real time: let the request reach the wire
            call.cancelAndJoin()

            assertTrue(call.isCancelled)
            assertNull(returned)            // call() never handed a Result to a cancelled caller
        }
    }

    @Test
    fun `recent parses direction, splits and transfer link`() = runTest {
        server.enqueue(ok("""
            [{"id":1,"merchant":"Tesco","date":"2026-03-12","amount":"24.31","currency":"GBP","account_name":"Current",
              "account_id":1,"type":"credit","category_name":"Groceries",
              "splits":[{"amount":"3.40","category_name":"Groceries","description":"Milk"},
                        {"amount":"-31.20","category_name":null}]},
             {"id":2,"merchant":"Transfer to Savings","date":"2026-03-11","amount":"50.00","currency":"GBP",
              "account_name":"Current","account_id":1,"type":"debit",
              "linked_transaction_id":77,"linked_account_name":"Savings"}]
        """.trimIndent()))

        val rows = api.recentTransactions().getOrThrow()

        val credit = rows[0]
        assertEquals(Direction.CREDIT, credit.direction)
        assertEquals(2, credit.splits.size)
        assertEquals(BigDecimal("-31.20"), credit.splits[1].amount.amount)

        val debit = rows[1]
        assertEquals(Direction.DEBIT, debit.direction)
        assertEquals(TransferLink(77, "Savings"), debit.transfer)
    }

    @Test
    fun `recent from an older server has UNKNOWN direction and no splits`() = runTest {
        server.enqueue(ok("""
            [{"id":1,"merchant":"Tesco","date":"2026-03-12","amount":"24.31","currency":"GBP","account_name":"Current"}]
        """.trimIndent()))

        val row = api.recentTransactions().getOrThrow().single()

        assertEquals(Direction.UNKNOWN, row.direction)
        assertTrue(row.splits.isEmpty())
        assertNull(row.transfer)
    }

    @Test
    fun `a malformed recent row is skipped rather than failing the whole list`() = runTest {
        server.enqueue(ok("""
            [{"id":1,"merchant":"Tesco","date":"2026-03-12","amount":"24.31","currency":"GBP","account_name":"Current"},
             {"id":2,"merchant":"Broken","date":"not-a-date","amount":"1.00","currency":"GBP","account_name":"Current"}]
        """.trimIndent()))

        assertEquals(1, api.recentTransactions().getOrThrow().size)
    }

    @Test
    fun `extract maps subtotal, tax and discount when the receipt prints them`() = runTest {
        server.enqueue(ok("""
            {"merchant":"Tesco","date":"2026-08-01","total":"23.77","currency":"GBP",
             "suggested_category_id":14,"line_items":[{"description":"Flat White","amount":"3.40"}],
             "warnings":[],"subtotal":"22.35","tax":"1.42","discount":"4.50"}
        """.trimIndent()))
        val draft = api.extract(File.createTempFile("rcpt", ".jpg").apply { writeBytes(byteArrayOf(1)) }).getOrThrow()
        assertEquals(BigDecimal("22.35"), draft.subtotal!!.amount)
        assertEquals(BigDecimal("1.42"), draft.tax!!.amount)
        assertEquals(BigDecimal("4.50"), draft.discount!!.amount)
        assertEquals("GBP", draft.discount!!.currency)
    }

    @Test
    fun `extract leaves subtotal and tax null on a tax-inclusive receipt`() = runTest {
        server.enqueue(ok("""{"merchant":"X","date":"2026-08-01","total":"9.75","currency":"GBP","line_items":[],"warnings":[]}"""))
        val draft = api.extract(File.createTempFile("rcpt", ".jpg").apply { writeBytes(byteArrayOf(1)) }).getOrThrow()
        assertNull(draft.subtotal); assertNull(draft.tax); assertNull(draft.discount)
    }

    @Test
    fun `capabilities maps splits_available, and its absence is false`() = runTest {
        server.enqueue(ok("""{"ocr_available":true,"currency":"GBP","version":"2.41.0","splits_available":true}"""))
        assertTrue(api.capabilities().getOrThrow().splitsAvailable)

        server.enqueue(ok("""{"ocr_available":true,"currency":"GBP","version":"2.41.0"}"""))
        assertFalse(api.capabilities().getOrThrow().splitsAvailable)
    }

    @Test
    fun `extract resolves the currency from capabilities when the draft omits one`() = runTest {
        // The draft response itself carries no "currency" field, and nothing has called
        // capabilities() yet on this fresh BudgetApiRetrofit -- fallbackCurrency starts
        // null, never a hardcoded literal like "GBP". extract() must fetch capabilities()
        // to resolve it rather than guess.
        server.enqueue(ok("""
            {"merchant":"Rewe","date":"2026-04-01","total":"9.99","line_items":[]}
        """.trimIndent()))
        server.enqueue(ok("""{"ocr_available":true,"currency":"EUR","version":"2.41.0"}"""))

        val photo = File.createTempFile("receipt", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        val draft = api.extract(photo).getOrThrow()

        assertEquals("EUR", draft.total!!.currency)
        assertEquals("/ocs/v2.php/apps/budget/api/v1/ocr/extract", server.takeRequest().path)
        assertEquals("/ocs/v2.php/apps/budget/api/v1/capabilities", server.takeRequest().path)
    }

    private fun budgetStatusJson() = """
        {"month":"2026-09","start_date":"2026-09-01","end_date":"2026-09-30","currency":"GBP",
         "totals":{"budgeted":"1450.00","spent":"912.40","remaining":"537.60"},
         "categories":[{"category_id":12,"name":"Groceries","parent_id":null,"type":"expense","period":"monthly",
                        "budgeted":"400.00","carried":"0.00","spent":"431.20","remaining":"-31.20","shared":false}]}
    """.trimIndent()

    @Test
    fun `budget status parses totals and lines including a negative remaining`() = runTest {
        server.enqueue(ok(budgetStatusJson()))

        val status = api.budgetStatus().getOrThrow()

        assertEquals(BigDecimal("537.60"), status.remaining.amount)
        assertEquals(BigDecimal("-31.20"), status.lines[0].remaining.amount)
        assertEquals(LocalDate.of(2026, 9, 1), status.startDate)
    }

    @Test
    fun `budget status sends month only when given`() = runTest {
        server.enqueue(ok(budgetStatusJson()))
        api.budgetStatus()
        assertFalse(server.takeRequest().path!!.contains("month="))

        server.enqueue(ok(budgetStatusJson()))
        api.budgetStatus("2026-08")
        assertTrue(server.takeRequest().path!!.contains("month=2026-08"))
    }

    @Test
    fun `upcoming bills parse and send days`() = runTest {
        server.enqueue(ok("""
            {"days":14,"bills":[{"id":5,"name":"Rent","amount":"850.00","amount_type":"fixed","currency":"GBP",
             "frequency":"monthly","next_due_date":"2026-09-01","overdue":true,"account_id":1,
             "account_name":"Current","category_id":9,"is_transfer":false,"auto_pay":true,"shared":false}]}
        """.trimIndent()))

        val bills = api.upcomingBills(14).getOrThrow()

        assertTrue(server.takeRequest().path!!.contains("days=14"))
        val bill = bills.single()
        assertEquals(5L, bill.id)
        assertEquals(BigDecimal("850.00"), bill.amount.amount)
        assertEquals(LocalDate.of(2026, 9, 1), bill.nextDueDate)
        assertTrue(bill.overdue)
        assertTrue(bill.autoPay)
        assertEquals("Current", bill.accountName)
        assertFalse(bill.shared)
    }

    @Test
    fun `a bill whose amount type isn't fixed is an estimate`() = runTest {
        // The server only works out a variable bill's real amount when it's paid; until then
        // `amount` is the stored figure, so the app must not present it as exact.
        fun bill(id: Int, amountType: String?) = """
            {"id":$id,"name":"B$id","amount":"40.00",${amountType?.let { "\"amount_type\":\"$it\"," } ?: ""}
             "currency":"GBP","frequency":"monthly","next_due_date":"2026-10-02"}
        """.trimIndent()
        server.enqueue(ok("""{"days":14,"bills":[${bill(1, "variable")},${bill(2, "fixed")},${bill(3, null)}]}"""))

        val bills = api.upcomingBills(14).getOrThrow()

        assertEquals(listOf(true, false, false), bills.map { it.estimated })
    }

    @Test
    fun `capabilities read check_available, absent means false`() = runTest {
        server.enqueue(ok("""{"ocr_available":true,"currency":"GBP","version":"2.41.0","check_available":true}"""))
        assertTrue(api.capabilities().getOrThrow().checkAvailable)

        server.enqueue(ok("""{"ocr_available":true,"currency":"GBP","version":"2.41.0"}"""))
        assertFalse(api.capabilities().getOrThrow().checkAvailable)
    }
}
