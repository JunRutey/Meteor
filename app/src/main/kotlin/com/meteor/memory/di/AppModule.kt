package com.meteor.memory.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.backup.BackupRetentionProvider
import com.meteor.memory.core.backup.PlaintextBackupModule
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.crypto.EncryptedPrefsAppLock
import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.crypto.androidKeystoreFileCrypto
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.log.FileAppLogger
import com.meteor.memory.core.log.LogExporter
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.PrivateDiaryQueries
import com.meteor.memory.core.storage.PrivateRecordScanner
import com.meteor.memory.core.storage.PrivateStorageRouter
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.core.time.SystemTimeModule
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Singleton

/**
 * Composition root. `:app` is the only module that knows about Hilt, which keeps
 * every `:core-*` / `:feature-*` module plain Kotlin and removable (archive R3).
 *
 * Still unwired: [BackupModule] (lands with the backup implementation).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTimeModule(): TimeModule = SystemTimeModule()

    @Provides
    @Singleton
    fun provideFileCrypto(): FileCrypto = androidKeystoreFileCrypto()

    @Provides
    @Singleton
    fun provideAppLock(
        @ApplicationContext context: Context,
    ): AppLock = EncryptedPrefsAppLock(context)

    @Provides
    @Singleton
    fun provideStorageRouter(
        @ApplicationContext context: Context,
        fileCrypto: FileCrypto,
        timeModule: TimeModule,
    ): StorageRouter = PrivateStorageRouter(context, fileCrypto, timeModule)

    @Provides
    @Singleton
    fun provideDiaryQueries(
        @ApplicationContext context: Context,
        storageRouter: StorageRouter,
    ): DiaryQueries = PrivateDiaryQueries(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        router = storageRouter,
    )

    /**
     * `:core-backup` may not depend on `:feature-settings` (that edge runs the
     * other way), so the retention count is injected as a cached provider here.
     */
    @Provides
    @Singleton
    fun provideBackupRetentionProvider(
        settingsRepository: SettingsRepository,
    ): BackupRetentionProvider {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = settingsRepository.settings
            .map { it.backupRetention }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = AppSettings.DEFAULT_BACKUP_RETENTION,
            )
        return BackupRetentionProvider { cached.value }
    }

    @Provides
    @Singleton
    fun provideBackupModule(
        @ApplicationContext context: Context,
        storageRouter: StorageRouter,
        retentionProvider: BackupRetentionProvider,
    ): BackupModule = PlaintextBackupModule(context, storageRouter, retentionProvider)

    @Provides
    @Singleton
    fun provideAppLog(@ApplicationContext context: Context): AppLog = FileAppLogger(context)

    @Provides
    @Singleton
    fun provideLogExporter(@ApplicationContext context: Context): LogExporter = LogExporter(context)

    @Provides
    @Singleton
    fun provideRecordScanner(
        @ApplicationContext context: Context,
        storageRouter: StorageRouter,
    ): RecordScanner = PrivateRecordScanner(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        router = storageRouter,
    )

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
