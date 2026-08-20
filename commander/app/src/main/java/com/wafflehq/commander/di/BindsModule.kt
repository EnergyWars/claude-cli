package com.wafflehq.commander.di

import com.wafflehq.commander.data.connection.ConnectionRepository
import com.wafflehq.commander.data.connection.ConnectionSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {

    @Binds
    abstract fun bindConnectionSource(impl: ConnectionRepository): ConnectionSource
}
