package com.meteor.memory.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meteor.memory.MainActivity

/**
 * Entry point for the `*#*#314159#*#*` dial code (ADR-031).
 *
 * The system dialer dispatches secret codes as a protected broadcast with the
 * action `android.provider.Telephony.SECRET_CODE` and the data URI
 * `android_secret_code://<code>`; the manifest filter accepts host `314159`
 * only. This receiver is the way back into the app while the launcher icon is
 * hidden, which is why it must keep working when the `LauncherEntry` alias is
 * disabled.
 *
 * It must be exported: the broadcast comes from the dialer, not from our own
 * process. AOSP Settings does the same thing for `*#*#4636#*#*`.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return

        // A receiver has no task of its own; without NEW_TASK the launch is
        // refused. No CLEAR_TOP: dialling the code should just bring the
        // existing task forward instead of recreating the visible screen.
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A dialer that cannot resolve the target must not crash the dialer.
        runCatching { context.startActivity(launch) }
    }

    private companion object {
        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    }
}
