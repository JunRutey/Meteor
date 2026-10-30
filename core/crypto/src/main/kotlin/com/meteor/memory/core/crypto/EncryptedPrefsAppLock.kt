package com.meteor.memory.core.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Biometric-gated app lock implementing the frozen [AppLock] contract.
 *
 * The enabled flag and any verification material live in an
 * EncryptedSharedPreferences namespace that is separate from the AI key
 * namespace (archive §6), so clearing AI credentials never drops the lock.
 *
 * [requireUnlocked] is an in-memory guard for a single process lifetime; the
 * OS keeps the process warm, so a "locked" session is re-armed on cold start.
 */
class EncryptedPrefsAppLock(
    private val context: Context,
) : AppLock {

    private val prefs: SharedPreferences by lazy { createPreferences(context) }

    @Volatile
    private var sessionUnlocked: Boolean = false

    override fun isEnabled(): Boolean =
        runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) sessionUnlocked = true
    }

    override fun requireUnlocked(): Boolean = !isEnabled() || sessionUnlocked

    override suspend fun unlock(activity: FragmentActivity): Boolean {
        if (!isEnabled()) {
            sessionUnlocked = true
            return true
        }
        if (sessionUnlocked) return true

        return if (canAuthenticate(activity)) {
            promptBiometric(activity).also { if (it) sessionUnlocked = true }
        } else {
            // No biometric hardware or nothing enrolled: do not silently unlock.
            false
        }
    }

    private fun canAuthenticate(context: Context): Boolean {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(context).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private suspend fun promptBiometric(activity: FragmentActivity): Boolean =
        suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // A single bad finger is not terminal; the prompt stays up.
                }
            }

            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.meteor_lock_title))
                .setSubtitle(activity.getString(R.string.meteor_lock_subtitle))
                .setNegativeButtonText(activity.getString(R.string.meteor_lock_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()

            continuation.invokeOnCancellation {
                if (settled.compareAndSet(false, true)) {
                    prompt.cancelAuthentication()
                }
            }

            prompt.authenticate(info)
        }

    private fun createPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        /** Archive §6: namespace `app_lock`. */
        const val PREFS_NAME = "meteor_app_lock"
        const val KEY_ENABLED = "app_lock_enabled"
    }
}
