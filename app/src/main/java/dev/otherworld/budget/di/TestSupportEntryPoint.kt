package dev.otherworld.budget.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.otherworld.budget.data.auth.AuthExpiry
import dev.otherworld.budget.data.local.PendingReceiptDao

/**
 * Lets an androidTest seed the app's real, singleton Room database directly, bypassing
 * extraction (which needs a real server). This project has no Hilt test runner/test
 * application (see `NavigationTest`'s KDoc), so [EntryPointAccessors.fromApplication] only
 * resolves against an interface the app-under-test's own generated Hilt component -- built
 * from this module, at this module's compile time -- actually implements; an `@EntryPoint`
 * declared inside the androidTest source set is invisible to it and fails with a
 * `ClassCastException` at runtime. Kept to the narrowest surface those tests actually need;
 * unused by any production code path.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestSupportEntryPoint {
    fun pendingReceiptDao(): PendingReceiptDao

    /**
     * Lets an androidTest raise the app's real auth-expiry signal, which otherwise only a 401
     * from a live server can produce -- the singleton [dev.otherworld.budget.MainActivity]
     * observes, so the navigation it drives is the production one.
     */
    fun authExpiry(): AuthExpiry
}
