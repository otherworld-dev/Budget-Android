package dev.otherworld.budget.ui.nav

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.otherworld.budget.ui.capture.CaptureScreen
import dev.otherworld.budget.ui.onboarding.OnboardingScreen
import dev.otherworld.budget.ui.quickadd.QuickAddScreen
import dev.otherworld.budget.ui.recent.RecentScreen
import dev.otherworld.budget.ui.review.ReviewScreen
import dev.otherworld.budget.ui.settings.SettingsScreen
import dev.otherworld.budget.ui.welcome.WelcomeScreen

/**
 * Ties the six screens together. [startDestination] is decided once, outside this composable
 * (see [dev.otherworld.budget.MainActivity]), because it depends on I/O -- whether credentials
 * exist and, when launched from the review notification, which receipt is oldest awaiting
 * review -- that this composable itself has no business performing.
 *
 * [navController] defaults to a freshly remembered one, but [dev.otherworld.budget.MainActivity]
 * passes its own so it can also `navigate()` from [dev.otherworld.budget.MainActivity.onNewIntent]
 * -- a warm-start tap on the review notification, which happens outside this composable entirely
 * and has no other way to reach the same back stack.
 */
@Composable
fun BudgetNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
    // Where the one-time welcome hands off to: Onboarding on a genuine first run (no credentials),
    // Capture for an install that predates the welcome and is already signed in. Resolved once in
    // MainActivity alongside the start destination.
    postWelcomeDestination: String = Routes.ONBOARDING,
) {
    val nav = navController

    NavHost(navController = nav, startDestination = startDestination) {

        composable(Routes.WELCOME) {
            // First run only -- MainActivity routes here as the start destination while
            // WelcomeStore.seen() is false. "Get started" marks it seen (in the ViewModel) and
            // moves on to Onboarding; popUpTo removes Welcome so Back can't return to it, and since
            // it is marked seen it is never a destination again.
            WelcomeScreen(onGetStarted = {
                nav.navigate(postWelcomeDestination) { popUpTo(Routes.WELCOME) { inclusive = true } }
            })
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                nav.navigate(Routes.CAPTURE) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }

        composable(Routes.CAPTURE) {
            // CaptureScreen has no onReceiptReady callback: extraction runs on a background
            // worker, never synchronously with the shutter press, so there is no receipt id
            // available at that moment to navigate on. onOpenReview is the reliable substitute --
            // wired from CaptureViewModel's live observeAwaitingReview() state, not a one-shot
            // notification tap, so it works even if POST_NOTIFICATIONS was declined.
            // launchSingleTop on all three top-bar destinations. On Quick Add it is load-bearing,
            // not tidiness: two taps on "+" push two QUICK_ADD entries, and after the user fills
            // the top one and saves, onDone's popBackStack() reveals the second -- an *identical
            // blank Add-transaction form*. Nothing in this app confirms a successful save, so a
            // blank form is indistinguishable from "it didn't work", and re-typing the amount
            // creates a genuine second transaction (a fresh ViewModel, so handedToQueue and the
            // Save re-entrancy guard both see a clean slate; the queue's five protections all key
            // off a row id that does not exist yet).
            //
            // Recent and Settings get it too, by the same rule rather than for the same stakes:
            // neither can mean anything twice on one stack, and a duplicate only costs the user a
            // second Back press to leave one screen. Applying it uniformly also means the next
            // screen added here inherits the safe default instead of the accident.
            //
            // Review gets it for the Quick Add reason, not the tidiness one. It was originally
            // left off here ("different ids are different destinations"), but the reachable
            // double-push is two taps on the AwaitingReviewBanner -- the *same* id twice, the
            // exact two-taps-push-two-entries mechanism that closed the Quick Add path above.
            // The lower duplicate is the dangerous one: its ViewModel loaded before anything
            // happened on the top copy, so when a save up there ends ambiguously (row parked
            // with the check-Recent warning) and the pop reveals the stale entry, it shows the
            // same pre-filled form with Save armed and no warning -- and a "the save didn't
            // work" read away from a duplicate transaction. singleTop re-uses the top entry
            // when the destination repeats; navigation between *different* receipts still
            // stacks nothing here, since Review is only entered from this banner (oldest id)
            // or the notification path, both of which resolve one id at a time.
            CaptureScreen(
                onOpenQuickAdd = { nav.navigate(Routes.QUICK_ADD) { launchSingleTop = true } },
                onOpenRecent = { nav.navigate(Routes.RECENT) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenReview = { id -> nav.navigate(Routes.review(id)) { launchSingleTop = true } },
            )
        }

        composable(Routes.QUICK_ADD) {
            // A plain popBackStack() is enough here, unlike Review's onDone: Quick Add is only
            // ever reached from Capture, never as a start destination (no notification or share
            // intent lands on it), so there is always something to pop back to.
            QuickAddScreen(onDone = { nav.popBackStack() })
        }

        composable(
            route = Routes.REVIEW_PATTERN,
            arguments = listOf(navArgument("receiptId") { type = NavType.LongType }),
        ) {
            // receiptId reaches the ViewModel through SavedStateHandle, so it
            // survives process death with the user's unsaved edits.
            ReviewScreen(
                viewModel = hiltViewModel(),
                // A cold start from the review notification makes review/{id} the *start*
                // destination, and popping the start destination either empties the back stack
                // (blank screen) or is refused outright -- leaving the user on a saved receipt's
                // stale fields with no way out, since LaunchedEffect(saved) never fires again.
                // popBackStack() reports which happened, so falling back to Capture turns the
                // notification -> Review -> Save journey into a complete one. popUpTo(0) because
                // the review entry must not be reachable by Back afterwards: the row it renders
                // has been posted or discarded and no longer exists.
                onDone = {
                    if (!nav.popBackStack()) {
                        nav.navigate(Routes.CAPTURE) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }

        composable(Routes.RECENT) { RecentScreen(onBack = { nav.popBackStack() }) }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSignedOut = {
                    // Clears the whole back stack, not just up to Settings: after sign-out,
                    // pressing Back must never return into an authenticated screen.
                    nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}
