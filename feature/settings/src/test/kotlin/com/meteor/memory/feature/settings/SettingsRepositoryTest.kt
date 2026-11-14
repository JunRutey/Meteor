package com.meteor.memory.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun screenshotProtectionFollowsAppLockUntilSetExplicitly() = runBlocking {
        val store = FakePreferencesDataStore(
            mutablePreferencesOf(booleanPreferencesKey("app_lock_enabled") to true),
        )
        val repository = SettingsRepository(store)

        // An upgrade from a build without the switch keeps the old protection.
        assertTrue(repository.settings.first().screenshotProtectionEnabled)

        repository.setScreenshotProtectionEnabled(false)

        // The user's explicit choice wins and no longer follows the lock.
        assertFalse(repository.settings.first().screenshotProtectionEnabled)
        repository.setAppLockEnabled(false)
        assertFalse(repository.settings.first().screenshotProtectionEnabled)
    }

    @Test
    fun screenshotProtectionCanBeEnabledWithoutAppLock() = runBlocking {
        val store = FakePreferencesDataStore(mutablePreferencesOf())
        val repository = SettingsRepository(store)

        assertFalse(repository.settings.first().screenshotProtectionEnabled)

        repository.setScreenshotProtectionEnabled(true)

        assertTrue(repository.settings.first().screenshotProtectionEnabled)
    }

    private class FakePreferencesDataStore(
        initial: Preferences,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
