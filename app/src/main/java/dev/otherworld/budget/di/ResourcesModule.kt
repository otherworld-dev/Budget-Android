package dev.otherworld.budget.di

import dev.otherworld.budget.core.AndroidStringResources
import dev.otherworld.budget.core.StringResources
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ResourcesModule {
    @Binds
    abstract fun bindStringResources(impl: AndroidStringResources): StringResources
}
