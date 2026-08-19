package dev.otherworld.budget

import android.app.Application

/**
 * Stands in for the real, `@HiltAndroidApp` [BudgetApp] in every Robolectric-based unit test.
 *
 * `RobolectricTestRunner` instantiates and calls `onCreate()` on whatever Application class the
 * merged manifest declares (`android:name=".BudgetApp"`) before *every* test method, regardless
 * of what that test is actually exercising. `BudgetApp`'s `@Inject lateinit var` fields are
 * injected synchronously as part of Hilt's generated `onCreate()`, and `BudgetApp.onCreate()`
 * itself calls `scheduler.schedulePrune()` and launches `receiptQueue.reconcileInterrupted()` --
 * both of which resolve real production dependencies: a real, file-backed Room `AppDatabase`
 * (via `StorageModule`) and a real `WorkManager` instance (self-initialised through
 * `Configuration.Provider` the moment `WorkManager.getInstance()` is first called), which then
 * immediately tries to run `PruneWorker` for real on a background thread.
 *
 * None of the tests in this module inject anything through `BudgetApp`/Hilt -- every test
 * constructs its subject directly (`ReceiptRepository(api, dao, photos)`,
 * `TestWorkerFactory(repo, notifier)`, etc.) -- so this production graph construction is pure,
 * unwanted side effect: unnecessary disk I/O, a real `WorkManager` background thread racing every
 * test's own (usually in-memory) Room database, and a stray temp directory Robolectric can't
 * always clean up if that background work still holds the database open at JVM exit. It doesn't
 * currently fail an assertion, but it did produce a reproducible stray "Illegal connection
 * pointer" exception on a WorkManager thread during `ExtractWorkerTest`.
 *
 * `@Config(application = RobolectricTestApplication::class)` on a Robolectric test class swaps
 * this in instead, so `Application.onCreate()` is a no-op and nothing beyond the test's own
 * explicit setup ever touches Room, Hilt, or WorkManager.
 */
class RobolectricTestApplication : Application()
