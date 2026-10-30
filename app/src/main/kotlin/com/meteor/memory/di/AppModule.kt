package com.meteor.memory.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.time.SystemTimeModule
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Composition root. `:app` is the only module that knows about Hilt, which keeps
 * every `:core-*` / `:feature-*` module plain Kotlin and removable (archive R3).
 *
 * Implementations are filled in as each version lands:
 *   v0.1 -> FileCrypto, AppLock, StorageRouter, BackupModule
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTimeModule(): TimeModule = SystemTimeModule()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("meteor_settings") },
        )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        store: DataStore<Preferences>,
    ): SettingsRepository = SettingsRepository(store)
}
