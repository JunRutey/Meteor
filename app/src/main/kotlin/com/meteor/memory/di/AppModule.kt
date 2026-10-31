package com.meteor.memory.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.backup.BackupRetentionProvider
import com.meteor.memory.core.backup.ExternalBackupMirror
import com.meteor.memory.core.backup.NoExternalBackupMirror
import com.meteor.memory.core.backup.PlaintextBackupModule
import com.meteor.memory.core.crypto.AppLock
import com.meteor.memory.core.crypto.EncryptedPrefsAppLock
import com.meteor.memory.core.crypto.FileCrypto
import com.meteor.memory.core.crypto.SecretStore
import com.meteor.memory.core.crypto.androidKeystoreFileCrypto
import com.meteor.memory.core.crypto.androidKeystoreSecretStore
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.log.FileAppLogger
import com.meteor.memory.core.log.LogExporter
import com.meteor.memory.core.storage.AiChatStore
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.EncryptedAiChatStore
import com.meteor.memory.core.storage.EncryptedImageStore
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.PrivateDiaryQueries
import com.meteor.memory.core.storage.PrivateMoodQueries
import com.meteor.memory.core.storage.PrivatePersonQueries
import com.meteor.memory.core.storage.PrivateRecordScanner
import com.meteor.memory.core.storage.PrivateStorageRouter
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.core.time.SystemTimeModule
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.feature.ai.AiModule
import com.meteor.memory.feature.ai.FilePersonaStore
import com.meteor.memory.feature.ai.MeteorAiModule
import com.meteor.memory.feature.editor.EditorModule
import com.meteor.memory.feature.editor.MarkdownEditorModule
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.SettingsRepository
import com.meteor.memory.ui.ai.AiGate
import com.meteor.memory.ui.ai.AiSettingsReader
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

    @Provides
    @Singleton
    fun provideMoodQueries(        @ApplicationContext context: Context,
        storageRouter: StorageRouter,
    ): MoodQueries = PrivateMoodQueries(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        router = storageRouter,
    )

    @Provides
    @Singleton
    fun providePersonQueries(
        @ApplicationContext context: Context,
        storageRouter: StorageRouter,
    ): PersonQueries = PrivatePersonQueries(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        router = storageRouter,
    )

    @Provides
    @Singleton
    fun provideImageStore(
        @ApplicationContext context: Context,
        fileCrypto: FileCrypto,
    ): ImageStore = EncryptedImageStore(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        crypto = fileCrypto,
    )

    @Provides
    @Singleton
    fun provideEditorModule(
        storageRouter: StorageRouter,
        imageStore: ImageStore,
    ): EditorModule = MarkdownEditorModule(storageRouter, imageStore)

    /** AI credentials share the archive's Keystore AES-256-GCM key (ADR-027). */
    @Provides
    @Singleton
    fun provideSecretStore(
        @ApplicationContext context: Context,
        fileCrypto: FileCrypto,
    ): SecretStore = androidKeystoreSecretStore(context, fileCrypto)

    /** Chat history is encrypted with the same key and never scanned as records. */
    @Provides
    @Singleton
    fun provideAiChatStore(
        @ApplicationContext context: Context,
        fileCrypto: FileCrypto,
    ): AiChatStore = EncryptedAiChatStore(File(context.filesDir, StorageLayout.ROOT_NAME), fileCrypto)

    @Provides
    @Singleton
    fun provideAiSettingsReader(
        settingsRepository: SettingsRepository,
        secretStore: SecretStore,
    ): AiSettingsReader = AiSettingsReader(settingsRepository, secretStore)

    @Provides
    @Singleton
    fun provideAiGate(reader: AiSettingsReader): AiGate = AiGate(reader)

    /**
     * The AI domain (ADR-027): skills, a bounded read-only agent and the user's
     * own provider credentials. Records are read through [RecordScanner], the
     * persona snapshot is written through the frozen [StorageRouter], and the
     * skill assets are loaded from this APK.
     */
    @Provides
    @Singleton
    fun provideAiModule(
        @ApplicationContext context: Context,
        recordScanner: RecordScanner,
        personQueries: PersonQueries,
        moodQueries: MoodQueries,
        diaryQueries: DiaryQueries,
        storageRouter: StorageRouter,
    ): AiModule = MeteorAiModule(
        rootDir = File(context.filesDir, StorageLayout.ROOT_NAME),
        scanner = recordScanner,
        personQueries = personQueries,
        moodQueries = moodQueries,
        diaryQueries = diaryQueries,
        personaStore = FilePersonaStore(storageRouter),
        skillLoader = { path ->
            runCatching {
                context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
        },
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
        mirror: ExternalBackupMirror,
    ): BackupModule = PlaintextBackupModule(context, storageRouter, retentionProvider, mirror = mirror)

    @Provides
    @Singleton
    fun provideExternalBackupMirror(): ExternalBackupMirror = NoExternalBackupMirror

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
