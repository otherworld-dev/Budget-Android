package dev.otherworld.budget.ui.nav

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoActivityResumedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.otherworld.budget.MainActivity
import dev.otherworld.budget.data.auth.AuthExpiry
import dev.otherworld.budget.data.auth.Credentials
import dev.otherworld.budget.data.auth.EncryptedCredentialStore
import dev.otherworld.budget.data.local.PendingReceiptDao
import dev.otherworld.budget.data.local.PendingReceiptEntity
import dev.otherworld.budget.data.work.ReceiptNotifier
import dev.otherworld.budget.di.TestSupportEntryPoint
import dev.otherworld.budget.domain.model.CaptureState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [MainActivity] and [BudgetNavHost] together against the app's real Hilt graph.
 * There is no Hilt test runner in this project -- every other androidTest (e.g.
 * `ReviewScreenTest`) sidesteps Hilt by constructing its ViewModel directly and passing it in,
 * which `BudgetNavHost`'s hard-coded `hiltViewModel()` calls in every `composable {}` block do
 * not allow. So this test seeds/clears the real [EncryptedCredentialStore] -- the same class
 * Hilt binds `CredentialStore` to -- before each launch to drive the exact start-destination
 * branch each case needs, and stores an unreachable loopback address as the server so any real
 * network call this then triggers (capture's capabilities/accounts refresh, sign-out's
 * revocation) fails fast with "connection refused" rather than hanging or needing internet
 * access -- every one of those calls already has to tolerate failure, per `BudgetApi`'s own
 * "never throws" contract.
 *
 * Uses [createEmptyComposeRule] rather than `createAndroidComposeRule<MainActivity>()`: the
 * latter launches the Activity as part of applying the JUnit rule, before `@Before` runs, which
 * would launch it before credentials are seeded/cleared for each case.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val credentialStore = EncryptedCredentialStore(context)

    // TestSupportEntryPoint lives in app/src/main (not here) -- see its KDoc for why an
    // @EntryPoint declared in the androidTest source set can't work against the real BudgetApp.
    private val dao: PendingReceiptDao
        get() = EntryPointAccessors.fromApplication(context, TestSupportEntryPoint::class.java).pendingReceiptDao()

    private val authExpiry: AuthExpiry
        get() = EntryPointAccessors.fromApplication(context, TestSupportEntryPoint::class.java).authExpiry()

    @Before
    fun setUp() {
        credentialStore.clear()
        // App data persists across `adb install -r` between separate connectedAndroidTest runs
        // (it isn't a full uninstall/reinstall), so without this a row left behind by
        // tappingAwaitingReviewBannerOpensReviewScreen on one run would leak into every other
        // test's "Budget Companion" screen on the next.
        runBlocking { dao.clear() }

        // CaptureScreen requests CAMERA the moment it composes (LaunchedEffect(Unit)). This
        // emulator image does not pre-grant it for connectedAndroidTest, so without this the
        // real system permission dialog (GrantPermissionsActivity) steals the foreground window
        // from MainActivity and every semantics query fails with "No compose hierarchies found
        // in the app" -- not a MainActivity/BudgetNavHost defect, just an unrelated system
        // dialog covering the app's own window.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    @Test
    fun noCredentialsShowsOnboarding() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Connect to your Nextcloud").assertIsDisplayed()
        }
    }

    @Test
    fun credentialsPresentShowsCapture() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))

        ActivityScenario.launch(MainActivity::class.java).use {
            // The top bar title added in Task 16 so Capture has somewhere to put the Recent
            // and Settings entry points -- unlike the shutter/gallery controls, this text does
            // not depend on the (real, loopback, fast-failing) capabilities/accounts refresh
            // completing, so no async wait is needed here.
            composeRule.onNodeWithText("Budget Companion").assertIsDisplayed()
        }
    }

    /**
     * Quick Add's only entry point. The constraint it encodes is as much what is *not* here as
     * what is: no bottom bar and no drawer were added for it, so if this action ever leaves the
     * Capture top bar the screen becomes unreachable, and nothing else in the suite would notice.
     */
    @Test
    fun theCaptureTopBarReachesQuickAddAndComesBack() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Budget Companion").assertIsDisplayed()

            composeRule.onNodeWithContentDescription("Add transaction manually").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Add transaction").assertIsDisplayed()
            // Amount and Account are the two required fields; their presence is what makes this
            // the Quick Add screen rather than any other form.
            composeRule.onNodeWithText("Amount").assertIsDisplayed()

            // Cancel and the top bar's back arrow share onDone, so this exercises the exit path.
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Budget Companion").assertIsDisplayed()
        }
    }

    @Test
    fun signOutClearsBackStackSoBackDoesNotReturnToSettings() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))

        ActivityScenario.launch(MainActivity::class.java).use {
            // Capture -> Settings, via the top bar action added for Task 16's navigation wiring.
            composeRule.onNodeWithText("Settings").performClick()
            composeRule.waitForIdle()

            // Opens the confirmation dialog. Exactly one "Sign out" node exists at this point --
            // the screen's own Button -- so this click is unambiguous.
            composeRule.onNodeWithText("Sign out").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(
                "Sign out? Queued transactions that haven't been saved will be deleted.",
            ).assertIsDisplayed()

            // Two "Sign out" nodes exist now: the screen's own Button (still present, just
            // covered by the dialog) and the AlertDialog's confirm TextButton. The dialog is
            // composed after the Scaffold in SettingsScreen's body, so its button is the last
            // match in the merged semantics tree -- picking it by index avoids the "more than
            // one node found" failure a plain onNodeWithText("Sign out") would hit here.
            val signOutNodes = composeRule.onAllNodesWithText("Sign out")
            val dialogConfirmIndex = signOutNodes.fetchSemanticsNodes().size - 1
            signOutNodes[dialogConfirmIndex].performClick()

            // Sign-out makes a real (fast-failing, loopback) network call before it completes,
            // so this polls for the async result instead of relying on a single waitForIdle().
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("Connect to your Nextcloud").fetchSemanticsNodes().isNotEmpty()
            }

            // The whole back stack was cleared to Onboarding alone (popUpTo(0) { inclusive =
            // true }), so there is nothing left for Navigation-Compose's own BackHandler to pop
            // to: the system back press falls through to the Activity's default handling and
            // finishes the app entirely, rather than returning to Settings -- or Capture.
            // Espresso surfaces exactly that outcome as NoActivityResumedException instead of
            // letting pressBack() return normally, which is what this asserts: no other screen
            // was left resumed for Back to land on.
            assertThrows(NoActivityResumedException::class.java) { pressBack() }
        }
    }

    /**
     * Spec §5's second half: `Unauthorized` -> "Clear credentials, **return to onboarding**".
     * The signal is raised here through the app's real singleton [AuthExpiry] rather than by a
     * genuine 401, which would need a live server; everything after that -- clearing the
     * credentials, dropping the catalog cache, the navigation itself -- is the production path.
     */
    @Test
    fun anExpiredSessionReturnsToOnboardingWithoutWaitingForARestart() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Budget Companion").assertIsDisplayed()

            // On the main thread deliberately. In production the signal is raised from a
            // background request thread and the collector, running on Compose's
            // AndroidUiDispatcher, is resumed onto the main thread before it touches the
            // NavController. Under a Compose test rule that dispatcher is replaced by one that
            // resumes the continuation inline on whichever thread emitted, so raising it from
            // the instrumentation thread would run NavController.navigate off the main thread --
            // a property of the test harness, not of the app.
            instrumentation.runOnMainSync { authExpiry.onUnauthorized(authExpiry.currentGeneration) }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Connect to your Nextcloud").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Connect to your Nextcloud").assertIsDisplayed()

            // Same back-stack contract as sign-out: nothing authenticated is left behind for
            // Back to land on, so the press falls through to the Activity and finishes the app.
            assertThrows(NoActivityResumedException::class.java) { pressBack() }
        }
    }

    @Test
    fun tappingAwaitingReviewBannerOpensReviewScreen() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))
        // Inserted directly into the real Room DB (same singleton MainActivity's Hilt graph
        // reads from) rather than driven through extraction -- extraction needs a real server.
        // AWAITING_REVIEW is reached this way on OCR success *and* on failure/quota/
        // not-configured, so a bare row with no draftJson is a faithful stand-in for any of them.
        runBlocking {
            dao.insert(
                PendingReceiptEntity(
                    photoPath = "/tmp/awaiting-review.jpg",
                    capturedAt = System.currentTimeMillis(),
                    state = CaptureState.AWAITING_REVIEW,
                ),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // CaptureViewModel's observeAwaitingReview() collector needs a beat to pick up the
            // already-inserted row and update CaptureUiState -- a genuine async collection, not
            // guaranteed to have landed by the time a single waitForIdle() would return.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("1 transaction ready to review").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("1 transaction ready to review").performClick()
            composeRule.waitForIdle()

            // ReviewScreen has no top bar/title of its own, so its "Discard" button -- present
            // on no other screen in the app -- is what confirms navigation actually landed here,
            // not just that the banner was tappable.
            composeRule.onNodeWithText("Discard").assertIsDisplayed()
        }
    }

    /**
     * The journey the review notification exists for, cold-start end to end: tap -> Review ->
     * leave. Deferred as "no test for the ACTION_REVIEW start-destination branch" when the
     * branch was written; it would have caught the dead end it later grew, where `review/{id}`
     * is the NavHost's *start* destination and `popBackStack()` -- ReviewScreen's only exit --
     * either empties the back stack or is refused outright, leaving the user on a receipt that
     * no longer exists with no way forward.
     */
    @Test
    fun reviewNotificationColdStartOpensReviewAndCanLeaveIt() {
        credentialStore.save(Credentials("http://127.0.0.1:1", "tester", "app-password"))
        runBlocking {
            dao.insert(
                PendingReceiptEntity(
                    photoPath = "/tmp/notification-review.jpg",
                    capturedAt = System.currentTimeMillis(),
                    state = CaptureState.AWAITING_REVIEW,
                ),
            )
        }

        // Exactly the intent ReceiptNotifier's PendingIntent carries.
        val intent = Intent(context, MainActivity::class.java).setAction(ReceiptNotifier.ACTION_REVIEW)

        ActivityScenario.launch<MainActivity>(intent).use {
            // Start-destination resolution is a suspend Room query on Dispatchers.IO, so the
            // first frame is not guaranteed by the time launch() returns.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Discard").fetchSemanticsNodes().isNotEmpty()
            }

            // Discard reaches the same onDone callback Save does (ReviewViewModel sets `saved`
            // for both), so it exercises the exit path without needing a reachable server.
            composeRule.onNodeWithText("Discard").performClick()
            composeRule.waitForIdle()

            // Two "Discard" nodes now: the screen's button and the confirmation dialog's, the
            // latter composed last. (The dialog's title -- "Discard this transaction?" here,
            // since this row's photo file does not exist so ReviewViewModel resolves photoPath
            // to null -- is a different string and does not match onAllNodesWithText's
            // exact-text matching either way.)
            val discardNodes = composeRule.onAllNodesWithText("Discard")
            discardNodes[discardNodes.fetchSemanticsNodes().size - 1].performClick()

            // The assertion that matters: the app lands somewhere usable rather than on a blank
            // back stack or a stuck Review screen.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Budget Companion").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Budget Companion").assertIsDisplayed()
        }
    }
}
