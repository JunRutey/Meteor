package com.meteor.memory.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the screen where a lock-screen PIN/pattern/password is configured.
 * `ACTION_SECURITY_SETTINGS` is not handled by every OEM skin, so plain
 * Settings is the fallback rather than a crash.
 */
internal fun openSecuritySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
