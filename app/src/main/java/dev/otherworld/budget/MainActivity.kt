package dev.otherworld.budget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.otherworld.budget.data.auth.AuthExpiry
import dev.otherworld.budget.data.auth.CredentialStore
import dev.otherworld.budget.data.local.PhotoStore
import dev.otherworld.budget.data.prefs.WelcomeStore
import dev.otherworld.budget.data.repo.ReceiptQueue
import dev.otherworld.budget.data.theme.ThemeController
import dev.otherworld.budget.data.work.ReceiptNotifier
import dev.otherworld.budget.data.work.ReceiptNotifying
import dev.otherworld.budget.ui.capture.CaptureViewModel
import dev.otherworld.budget.ui.nav.BudgetNavHost
import dev.otherworld.budget.ui.nav.Routes
import dev.otherworld.budget.ui.theme.BudgetReceiptsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var photoStore: PhotoStore
    @Inject lateinit var credentials: CredentialStore

    // Only ever observed here -- MainActivity is the one place that both outlives every screen
    // and can reach the back stack. Never raised from the UI: see BudgetApiRetrofit.call().
    @Inject lateinit var authExpiry: AuthExpiry

    // Read-only here -- for oldestAwaitingReview() when deciding the start destination, and
    // for the awaiting-review observer that retires the notification. Enqueuing still goes
    // exclusively through captureViewModel.onPhotoCaptured below, so this does not
    // reintroduce the duplicated enqueue-and-schedule logic Task 12 removed.
    @Inject lateinit var queue: ReceiptQueue

    // Only ever *cleared* here, when the awaiting-review set empties; posting stays with the
    // workers that know what finished.
    @Inject lateinit var notifier: ReceiptNotifying

    // The runtime brand colour. Its seed StateFlow is initialised from plain prefs at construction,
    // so the value read at the first composition below is already the stored colour -- the app
    // paints the user's brand colour immediately on a cold start, with no blue->brand flash, and a
    // network refresh updates it in place. Null seed == the default Nextcloud blue.
    @Inject lateinit var theme: ThemeController

    // First-run gate: while seen() is false the start destination is the one-time welcome screen.
    @Inject lateinit var welcomeStore: WelcomeStore

    // Routes shared images through the same path a camera capture takes -- see
    // handleSharedImages -- so "what happens when a photo is ready" (enqueue, schedule
    // extraction, and whatever CaptureViewModel does with it in future) lives in one place.
    private val captureViewModel: CaptureViewModel by viewModels()

    // Must be registered before the activity is STARTED, so this is a property, not something
    // created lazily inside handleSharedImages.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Set once, via SideEffect, the first time BudgetNavHost composes below. onNewIntent needs
    // this to navigate a *running* Activity to Review -- it fires outside the Compose tree
    // entirely (a plain Activity callback), so there is no other way for it to reach the same
    // back stack BudgetNavHost owns. Read/written only from the main thread (SideEffect and
    // onNewIntent both run there), so a plain nullable var needs no synchronisation.
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only on a genuinely fresh launch (savedInstanceState == null). Every recreation --
        // rotation, dark-mode toggle, locale change, split-screen resize -- re-runs onCreate
        // with the *same* intent: getIntent() still returns the ACTION_SEND that started the
        // task (and onNewIntent's setIntent below keeps warm-delivered shares visible to later
        // recreations too, per AOSP's relaunch path, which preserves the last-used intent).
        // Without this guard each rotation re-copied the shared image into a fresh UUID file
        // and enqueued another queue row: N recreations -> N+1 identical "receipts", each
        // extracting and presenting as a legitimate distinct item in review -- a duplicate-
        // transaction path needing no user error beyond rotating the phone. The URI read grant
        // survives recreation (grants release when the Activity finishes, not on relaunch), so
        // the re-copy always succeeded. Warm deliveries still arrive via onNewIntent.
        if (savedInstanceState == null) handleSharedImages(intent)

        // The "ready to review" notification's claim is only true while something actually
        // awaits review. Clear it the moment the set empties (saved or discarded, from any
        // screen), so a later tap on a stale shade entry cannot promise a review that no
        // longer exists. Runs for the Activity's whole lifetime; the emptying transition can
        // only happen from UI paths, so an Activity-scoped observer is always there to see it.
        lifecycleScope.launch {
            queue.observeAwaitingReview()
                .map { it.isEmpty() }
                .distinctUntilChanged()
                .collect { empty -> if (empty) notifier.clear() }
        }

        // Same retirement pattern for the stale-queue notification the daily prune raises:
        // once nothing is past the queue bounds any more (the user reviewed or discarded the
        // old rows), the shade entry's claim is false and it goes. Uses the same MAX_ITEMS/
        // MAX_AGE_MILLIS the prune pass counts with -- one definition of "stale" on both
        // sides. Staleness only *clears* through queue changes (rows resolved), which all
        // happen via UI paths, so an Activity-scoped observer is always alive to see it; time
        // alone can only make rows staler, never fresher.
        lifecycleScope.launch {
            queue.observeQueue()
                .map { rows ->
                    rows.size <= ReceiptQueue.MAX_ITEMS &&
                        rows.none { it.capturedAt < System.currentTimeMillis() - ReceiptQueue.MAX_AGE_MILLIS }
                }
                .distinctUntilChanged()
                .collect { withinBounds -> if (withinBounds) notifier.clearStaleQueue() }
        }

        // The first call to setContent waits on this coroutine -- see resolveDestination's KDoc
        // for why that work does not belong on the main thread.
        lifecycleScope.launch {
            // First run shows the welcome screen; every run after resolves normally. seen() is a
            // cheap boolean pref read, unlike resolveDestination's Keystore-backed credential load.
            // `resolved` is computed either way so the welcome's "Get started" lands on the right
            // place -- Onboarding for a genuine first run (no credentials), Capture for an existing
            // install that simply predates this screen.
            val resolved = resolveDestination(intent?.action)
            val start = if (welcomeStore.seen()) resolved else Routes.WELCOME
            setContent {
                val seed by theme.seed.collectAsState()
                BudgetReceiptsTheme(seedHex = seed) {
                    val nav = rememberNavController()
                    SideEffect { navController = nav }

                    // The other half of spec §5's "Unauthorized | 401 | Clear credentials,
                    // return to onboarding". CredentialExpiry does the clearing, but it sits in
                    // the data layer with no way to reach a back stack, so until this existed a
                    // password revoked mid-session showed the user nothing at all: Capture kept
                    // rendering its cached accounts while every queued receipt failed, until the
                    // next cold start.
                    //
                    // Collected here inside the composition rather than against the navController
                    // field above, which is null until the first composition -- an expiry racing
                    // startup would have had nothing to navigate. Scoped to `nav`, so a rotation
                    // simply re-subscribes; expirations has no replay, so re-subscribing cannot
                    // re-deliver an old expiry to a user who has since signed back in.
                    LaunchedEffect(nav) {
                        authExpiry.expirations.collect {
                            // Onboarding is where an expiry sends you, so an expiry raised while
                            // it is already open has nothing to do -- and navigating anyway would
                            // pop and rebuild the entry, cancelling a Login Flow the user is in
                            // the middle of (they must leave for a browser to complete it).
                            if (nav.currentDestination?.route == Routes.ONBOARDING) return@collect
                            // Same clear-everything navigation sign-out uses: after an expiry,
                            // Back must not return into a screen that needs credentials.
                            nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                        }
                    }

                    BudgetNavHost(startDestination = start, navController = nav, postWelcomeDestination = resolved)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedImages(intent)

        // A plain re-launch (e.g. tapping the launcher icon while the app is already open, or
        // returning from another share-target call) should leave whatever screen is already
        // showing -- only a genuine review-notification tap navigates, and only when it has a
        // real review to open. resolveDestination falls back to CAPTURE when nothing awaits
        // review and to ONBOARDING when there are no credentials; navigating to either from
        // here only degrades the stack the user is on (a second Capture entry that makes Back
        // look broken, or -- worse -- a second Onboarding stacked over a login they are in the
        // middle of, whose poll then completes invisibly underneath). A stale notification
        // with nothing behind it simply does nothing.
        //
        // launchSingleTop so tapping the notification while that same review/{id} is already
        // on top re-uses the entry instead of stacking a second copy -- a stale lower entry's
        // Save is armed without the row's current warning (see BudgetNavHost's onOpenReview).
        if (intent.action == ReceiptNotifier.ACTION_REVIEW) {
            lifecycleScope.launch {
                val destination = resolveDestination(intent.action)
                if (destination != Routes.CAPTURE && destination != Routes.ONBOARDING) {
                    navController?.navigate(destination) { launchSingleTop = true }
                }
            }
        }
    }

    /**
     * Shared by [onCreate] (as [BudgetNavHost]'s start destination) and [onNewIntent] (as an
     * explicit `navigate()` when the app is already running): no credentials -> ONBOARDING;
     * launched from the review notification -> the oldest AWAITING_REVIEW item, or CAPTURE if
     * none exist *or* the query fails; otherwise CAPTURE.
     *
     * Runs off the main thread: [CredentialStore.load] is synchronous but not free --
     * [dev.otherworld.budget.data.auth.EncryptedCredentialStore] does Keystore-backed AES-GCM
     * decryption, plus first-access [androidx.security.crypto.MasterKey] construction -- and
     * [ReceiptQueue.oldestAwaitingReview] is a suspend Room query. Neither belongs on the
     * first-frame critical path gating [setContent].
     */
    private suspend fun resolveDestination(intentAction: String?): String = withContext(Dispatchers.IO) {
        when {
            credentials.load() == null -> Routes.ONBOARDING
            intentAction == ReceiptNotifier.ACTION_REVIEW ->
                // A thrown query failure and "nothing awaiting review" (already reviewed, or
                // discarded) are treated the same: fall back to Capture rather than crashing
                // launch or showing an error the user can't act on.
                runCatching { queue.oldestAwaitingReview() }.getOrNull()
                    ?.let { Routes.review(it.id) }
                    ?: Routes.CAPTURE
            else -> Routes.CAPTURE
        }
    }

    /**
     * Copies incoming shared images into app storage and hands each one to
     * [CaptureViewModel.onPhotoCaptured]. The read grant on an incoming content:// URI is
     * transient -- it will be gone by the time a background worker gets to this photo,
     * possibly hours later -- so the bytes are copied here, before the queue is told about
     * them at all.
     */
    private fun handleSharedImages(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return

        // A shared image is the same kind of event as a camera capture -- both end with a
        // notification once extraction finishes -- so it is as good a "first capture" moment
        // as the shutter button to ask for POST_NOTIFICATIONS. Requested here rather than at
        // launch, where it has no context and is more likely to be denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Resolved here, on the main thread. `by viewModels()` goes through ViewModelProvider,
        // which is not thread-safe; touching the delegate for the first time from inside the
        // Dispatchers.IO block below would construct the ViewModel off the main thread.
        val captureViewModel = this.captureViewModel

        lifecycleScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val destination = photoStore.newPhotoFile()
                val copied = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use(input::copyTo)
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (copied) {
                    // A shared screenshot or download is frequently larger than a phone-camera
                    // photo, so this needs the same shrink the camera path gets -- see
                    // PhotoStore.downscale's KDoc. downscale writes to a temp file and renames,
                    // so a failure here leaves the original full-size copy intact (never a
                    // truncated JPEG) and must not stop the photo being queued.
                    runCatching { photoStore.downscale(destination) }
                    captureViewModel.onPhotoCaptured(destination)
                } else {
                    // A share whose bytes could not be copied used to disappear without trace.
                    // Reported with a Toast rather than through CaptureUiState: this Activity's
                    // CaptureViewModel is scoped to the Activity's ViewModelStore, while the one
                    // CaptureScreen renders comes from hiltViewModel() inside a composable{},
                    // i.e. the NavBackStackEntry's store -- a different instance, so a message
                    // set here would never be displayed. A share can also arrive while the app
                    // is showing Review or Onboarding, where a Capture banner would be unseen
                    // anyway.
                    destination.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Couldn't use that photo. Please try again.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
}
