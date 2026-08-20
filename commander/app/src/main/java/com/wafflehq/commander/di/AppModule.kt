package com.wafflehq.commander.di

import android.content.Context
import androidx.room.Room
import com.wafflehq.commander.data.db.AppDatabase
import com.wafflehq.commander.data.db.CommandHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "app.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideCommandHistoryDao(database: AppDatabase): CommandHistoryDao =
        database.commandHistoryDao()
}
