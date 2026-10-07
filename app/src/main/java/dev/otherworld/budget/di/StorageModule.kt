package dev.otherworld.budget.di

import android.content.Context
import androidx.room.Room
import dev.otherworld.budget.data.local.AppDatabase
import dev.otherworld.budget.data.local.PendingReceiptDao
import dev.otherworld.budget.data.local.PhotoStore
import dev.otherworld.budget.data.local.SnapshotDao
import dev.otherworld.budget.data.prefs.LastAccountStore
import dev.otherworld.budget.data.prefs.LastServerStore
import dev.otherworld.budget.data.prefs.SharedPreferencesLastAccountStore
import dev.otherworld.budget.data.prefs.SharedPreferencesLastServerStore
import dev.otherworld.budget.data.prefs.SharedPreferencesThemeColorStore
import dev.otherworld.budget.data.prefs.SharedPreferencesWelcomeStore
import dev.otherworld.budget.data.prefs.ThemeColorStore
import dev.otherworld.budget.data.prefs.WelcomeStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "budget_receipts.db")
            // Deliberately no fallbackToDestructiveMigration(): a dev install upgrading may hold
            // queued receipts, and dropping the table would lose them and orphan their photo files.
            // See AppDatabase.MIGRATION_1_2 / MIGRATION_2_3 / MIGRATION_3_4 / MIGRATION_4_5.
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides fun dao(db: AppDatabase): PendingReceiptDao = db.pendingReceipts()

    @Provides fun snapshotDao(db: AppDatabase): SnapshotDao = db.snapshots()

    @Provides @Singleton
    fun photoStore(@ApplicationContext context: Context) = PhotoStore(context)

    @Provides @Singleton
    fun lastAccountStore(@ApplicationContext context: Context): LastAccountStore =
        SharedPreferencesLastAccountStore(context)

    @Provides @Singleton
    fun lastServerStore(@ApplicationContext context: Context): LastServerStore =
        SharedPreferencesLastServerStore(context)

    @Provides @Singleton
    fun themeColorStore(@ApplicationContext context: Context): ThemeColorStore =
        SharedPreferencesThemeColorStore(context)

    @Provides @Singleton
    fun welcomeStore(@ApplicationContext context: Context): WelcomeStore =
        SharedPreferencesWelcomeStore(context)
}
