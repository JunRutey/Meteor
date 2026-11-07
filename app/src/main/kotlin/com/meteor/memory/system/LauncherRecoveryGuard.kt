package com.meteor.memory.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.LauncherRecovery
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-checks the dial-code way back after an app update (ADR-031).
 *
 * MIUI / HyperOS resets its 「后台弹出界面」 grant on some updates. If the icon
 * is still hidden when that happens the user has no way into the app at all, so
 * an update must not leave the icon hidden unless the recovery path is provably
 * available: on `MY_PACKAGE_REPLACED` the guard either finds
 * [LauncherRecovery.READY] and does nothing, or restores the launcher entry and
 * clears [com.meteor.memory.feature.settings.AppSettings.hideLauncherIcon] so
 * the app stays reachable. `UNKNOWN` is treated as "not provable" on purpose:
 * for a one-time update the icon coming back is recoverable, being locked out is
 * not.
 */
class LauncherRecoveryGuard : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val guard = entryPoint(appContext)
                val repository = guard.settingsRepository()
                if (!repository.settings.first().hideLauncherIcon) return@launch

                val recovery = LauncherPermissionGuide(appContext).backgroundStartState()
                if (recovery == LauncherRecovery.READY) return@launch

                // Restore first, then persist: if the DataStore write failed the
                // icon is still visible for this session, which is the safe side.
                LauncherIconController(appContext).setHidden(false)
                repository.setHideLauncherIcon(false)
                guard.appLog().warn(
                    TAG,
                    "background-start gate $recovery after update; launcher icon restored",
                )
            } catch (failure: Exception) {
                runCatching {
                    entryPoint(appContext).appLog()
                        .warn(TAG, "could not re-check the launcher recovery path", failure)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Hilt does not inject manifest receivers; both objects are resolved on demand. */
    private fun entryPoint(context: Context): GuardEntryPoint =
        EntryPointAccessors.fromApplication(context, GuardEntryPoint::class.java)

    private companion object {
        const val TAG = "LauncherRecovery"
    }
}

/** Hilt cannot inject a manifest receiver, so the two objects are resolved on demand. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GuardEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun appLog(): AppLog
}
