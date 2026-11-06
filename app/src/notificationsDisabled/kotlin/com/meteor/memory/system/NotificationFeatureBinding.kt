package com.meteor.memory.system

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableNotificationFeature @Inject constructor() : NotificationFeature {
    override val available: Boolean = false
    override fun initialize() = Unit
    override fun canPostNotifications(): Boolean = false
    override fun canScheduleExactAlarms(): Boolean = false
    override fun openNotificationSettings() = Unit
    override fun openExactAlarmSettings() = Unit
    override suspend fun syncReminder() = Unit
    override suspend fun handleReminderAlarm() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationFeatureBinding {
    @Binds
    @Singleton
    abstract fun bindNotificationFeature(feature: UnavailableNotificationFeature): NotificationFeature
}
