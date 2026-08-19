package dev.otherworld.budget.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate

/**
 * [dev.otherworld.budget.ui.review.ReviewViewModel]'s `clock: () -> LocalDate` constructor
 * parameter carries a Kotlin default value (`{ LocalDate.now() }`) so tests can supply a
 * deterministic one without Hilt. That default is a Kotlin-compiler feature, not something
 * Dagger's generated factory understands -- it calls the constructor explicitly and still
 * requires a binding for every declared parameter type, default or not. This provides
 * exactly what the default already says, so production is unaffected.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    fun clock(): () -> LocalDate = { LocalDate.now() }
}
