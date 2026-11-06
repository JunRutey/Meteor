package com.meteor.memory.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meteor.memory.MainActivity
import com.meteor.memory.core.log.AppLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Entry point for the `*#*#314159#*#*` dial code (ADR-031).
 *
 * The dialer dispatches secret codes as a protected broadcast whose data URI is
 * `android_secret_code://<code>`; the manifest filters accept host `314159`
 * only. This receiver is the way back into the app while the launcher icon is
 * hidden, which is why it must keep working when the `LauncherEntry` alias is
 * disabled.
 *
 * The **action string changed in Android 10**: `android.provider.Telephony.
 * SECRET_CODE` is the legacy value, `android.telephony.action.SECRET_CODE` is
 * what current dialers send. Both are accepted here *and* declared as manifest
 * filters: a filter that does not match means the receiver is never called and
 * the hidden icon has no way back at all.
 *
 * The launch itself is not guaranteed. While the app sits in the background the
 * system may drop the activity start without throwing anything — MIUI / HyperOS
 * does exactly that unless the app is granted 「后台弹出界面」, and AOSP-style
 * builds have their own background launch restriction. That is why the attempt
 * is logged: `adb logcat` plus our own log file tell "broadcast never arrived"
 * apart from "broadcast arrived, launch was dropped".
 *
 * It must be exported: the broadcast comes from the dialer, not from our own
 * process.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SECRET_CODE_ACTIONS) return

        val log = runCatching { entryPoint(context).appLog() }.getOrNull()

        // A receiver has no task of its own; without NEW_TASK the launch is
        // refused. No CLEAR_TOP: dialling the code should just bring the
        // existing task forward instead of recreating the visible screen.
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(launch) }
            .onSuccess { log?.info(TAG, "dial code received ($action), launch requested") }
            .onFailure {
                // A dialer that cannot resolve the target must not crash the dialer.
                log?.warn(TAG, "dial code could not start MainActivity", it)
            }
    }

    /** Hilt does not inject manifest receivers; the log is resolved on demand. */
    private fun entryPoint(context: Context): LogEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LogEntryPoint::class.java,
        )

    private companion object {
        val SECRET_CODE_ACTIONS = setOf(
            "android.telephony.action.SECRET_CODE",
            // Legacy value, still dispatched by some OEM dialers.
            "android.provider.Telephony.SECRET_CODE",
        )
        const val TAG = "SecretCode"
    }
}

/** Hilt cannot inject a manifest receiver, so the log is resolved on demand. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LogEntryPoint {
    fun appLog(): AppLog
}
