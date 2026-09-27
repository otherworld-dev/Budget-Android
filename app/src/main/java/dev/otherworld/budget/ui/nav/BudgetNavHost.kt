package dev.otherworld.budget.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.otherworld.budget.R
import dev.otherworld.budget.ui.capture.CaptureScreen
import dev.otherworld.budget.ui.onboarding.OnboardingScreen
import dev.otherworld.budget.ui.quickadd.QuickAddScreen
import dev.otherworld.budget.ui.review.ReviewScreen
import dev.otherworld.budget.ui.settings.SettingsScreen
import dev.otherworld.budget.ui.welcome.WelcomeScreen

/**
 * Ties the app's nine screens together. [startDestination] is decided once, outside this
 * composable (see [dev.otherworld.budget.MainActivity]), because it depends on I/O -- whether
 * credentials exist and, when launched from the review notification, which receipt is oldest
 * awaiting review -- that this composable itself has no business performing.
 *
 * [navController] defaults to a freshly remembered one, but [dev.otherworld.budget.MainActivity]
 * passes its own so it can also `navigate()` from [dev.otherworld.budget.MainActivity.onNewIntent]
 * -- a warm-start tap on the review notification, which happens outside this composable entirely
 * and has no other way to reach the same back stack.
 *
 * Wrapped in a [Scaffold] so [BudgetBottomBar] can sit below the [NavHost] rather than inside any
 * one screen: it is shared chrome for [TOP_LEVEL_ROUTES] only (Capture, Overview, Activity), not
 * a property of any single composable, and reading the current route from
 * `currentBackStackEntryAsState()` here is what lets it disappear on every other destination --
 * Welcome, Onboarding, Quick Add, Review, Settings and Budget -- without each of those screens
 * having to know the bar exists at all.
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
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in TOP_LEVEL_ROUTES) {
                BudgetBottomBar(currentRoute = currentRoute, onSelect = nav::navigateToTab)
            }
        },
    ) { innerPadding ->
        // Only the bottom inset: each screen already draws its own top bar (and Scaffold), so
        // applying the full `innerPadding` here would double up their top insets too.
        //
        // `consumeWindowInsets` matters just as much as the `padding` above it. `padding` only
        // reserves layout space for whatever this Scaffold decided `bottomPadding` should be
        // (the bottom bar's height when it's showing, or -- since nothing else consumed it --
        // the raw navigation-bar inset when it's hidden); it does not touch the `WindowInsets`
        // every screen below still reads. Every screen here ends up under this same NavHost, and
        // several read those insets themselves: CaptureScreen/QuickAddScreen/SettingsScreen each
        // have their own Scaffold with the Material 3 default `contentWindowInsets` (safeDrawing),
        // and WelcomeScreen/ReviewScreen call `Modifier.safeDrawingPadding()` directly -- all of
        // which, left alone, would reserve the *same* navigation-bar inset a second time on top
        // of the padding already applied here (shrinking CaptureScreen's camera preview and
        // lifting its shutter button being the one this was caught from). Declaring that amount
        // consumed tells every one of those descendants it has already been accounted for, so
        // none of them reserve it twice -- on every route, not just the three with a bottom bar,
        // since `bottomPadding` already equals the raw inset on the others (see above).
        val bottomInset = innerPadding.calculateBottomPadding()
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier
                .padding(bottom = bottomInset)
                .consumeWindowInsets(PaddingValues(bottom = bottomInset)),
        ) {
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
                // launchSingleTop on both top-bar destinations. On Quick Add it is load-bearing,
                // not tidiness: two taps on "+" push two QUICK_ADD entries, and after the user fills
                // the top one and saves, onDone's popBackStack() reveals the second -- an *identical
                // blank Add-transaction form*. Nothing in this app confirms a successful save, so a
                // blank form is indistinguishable from "it didn't work", and re-typing the amount
                // creates a genuine second transaction (a fresh ViewModel, so handedToQueue and the
                // Save re-entrancy guard both see a clean slate; the queue's five protections all key
                // off a row id that does not exist yet).
                //
                // Settings gets it too, by the same rule rather than for the same stakes: it can't
                // mean anything twice on one stack, and a duplicate only costs the user a second
                // Back press to leave it. Applying it uniformly also means the next screen added
                // here inherits the safe default instead of the accident. (Recent's old entry point
                // here is gone as of Task 7 -- reaching Activity is now the bottom bar's job, via
                // navigateToTab below, which carries its own launchSingleTop for the same reason.)
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

            // Placeholders only: Task 8 (Overview) and Task 9 (Activity) replace these with the
            // real screens, including the Settings icon in their own top bars -- not added here,
            // since there is no real top bar yet to put it in. Routes.BUDGET is Overview's own
            // detail push (see TOP_LEVEL_ROUTES's KDoc for why it isn't a fourth tab); Task 8
            // wires the push, so for now it is unreachable but still needs to compile and hold a
            // route.
            composable(Routes.OVERVIEW) { PlaceholderScreen(R.string.overview_placeholder) }
            composable(Routes.ACTIVITY) { PlaceholderScreen(R.string.activity_placeholder) }
            composable(Routes.BUDGET) { PlaceholderScreen(R.string.budget_placeholder) }

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
}

/**
 * Switches tabs the standard bottom-navigation way, but popping up to [Routes.CAPTURE] rather
 * than the usual recipe's `navController.graph.findStartDestination()` -- deliberately, because
 * that recipe assumes the graph's start destination is always the hub screen still sitting at the
 * bottom of the back stack, which is not true here. [BudgetNavHost]'s `startDestination` can be
 * `review/{receiptId}` on a cold start from the review notification, and `graph.findStartDestination()`
 * keeps naming that same destination for this NavHost's entire lifetime -- it is fixed at graph
 * construction, not derived from whatever is actually on the stack. Once `ReviewScreen`'s `onDone`
 * falls back to `nav.navigate(Routes.CAPTURE) { popUpTo(0) { inclusive = true } }` (its own KDoc
 * above), the stack is `[Capture]` but the start destination the graph reports is still the review
 * route -- which is no longer in the stack at all. `popUpTo` targeting a route that isn't in the
 * back stack is a silent no-op, not an error, so every tab tap would just push a new entry on top
 * forever ([Capture, Overview, Activity, Capture, ...]), with Back walking every tab ever visited
 * one at a time instead of leaving, and `restoreState` never finding anything saved to restore.
 *
 * [Routes.CAPTURE] itself has no such problem: it is both the post-onboarding landing screen
 * ([OnboardingScreen]'s `onDone` above) and Review's own fallback target, so by the time any tab
 * is reachable to tap at all, Capture is already sitting at the bottom of the stack underneath it
 * -- unlike the graph's nominal start destination, which the review cold-start path leaves behind.
 *
 * `launchSingleTop` here is kept for the same reason [BudgetNavHost]'s long comment on
 * `composable(Routes.CAPTURE)` gives for Quick Add, Settings and Review: none of the three tabs
 * can mean anything twice on the back stack, so re-tapping the tab that is already on top must
 * reuse that entry rather than push a duplicate. `saveState`/`restoreState` is the extra half of
 * the pattern: it is what makes each tab keep its own back stack and scroll position when the
 * user switches away and back, the standard Material 3 bottom-navigation behaviour.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.CAPTURE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A centred placeholder for the routes Task 8/9 have not replaced with real screens yet. */
@Composable
private fun PlaceholderScreen(@StringRes textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(textRes))
    }
}
