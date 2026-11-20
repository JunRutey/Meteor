package com.meteor.memory.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun peopleTagsPersistAndRemoveIdempotently() = runBlocking {
        val store = FakePreferencesDataStore(mutablePreferencesOf())
        val repository = SettingsRepository(store)

        repository.addPeopleTag("colleague")
        repository.addPeopleTag("classmate")
        repository.addPeopleTag("colleague")
        assertEquals(
            setOf("colleague", "classmate"),
            repository.settings.first().peopleTags,
        )

        // Removing a tag that was never remembered is a no-op.
        repository.removePeopleTag("missing")
        assertEquals(
            setOf("colleague", "classmate"),
            repository.settings.first().peopleTags,
        )

        repository.removePeopleTag("colleague")
        repository.removePeopleTag("colleague")
        assertEquals(setOf("classmate"), repository.settings.first().peopleTags)
    }

    @Test
    fun notificationsMasterSwitchUsesLegacyReminderValue() = runBlocking {
        val store = FakePreferencesDataStore(
            mutablePreferencesOf(booleanPreferencesKey("diary_reminder_enabled") to false),
        )
        val repository = SettingsRepository(store)

        assertFalse(repository.settings.first().notificationsEnabled)

        repository.migrateNotificationsMasterSwitch()

        assertFalse(repository.settings.first().notificationsEnabled)
    }

    @Test
    fun notificationsMasterSwitchDefaultsOnWhenLegacyKeyWasNeverTouched() = runBlocking {
        val store = FakePreferencesDataStore(mutablePreferencesOf())
        val repository = SettingsRepository(store)

        assertTrue(repository.settings.first().notificationsEnabled)

        repository.migrateNotificationsMasterSwitch()

        assertTrue(repository.settings.first().notificationsEnabled)
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
