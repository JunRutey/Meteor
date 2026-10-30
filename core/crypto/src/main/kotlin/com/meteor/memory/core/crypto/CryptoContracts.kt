package com.meteor.memory.core.crypto

import androidx.fragment.app.FragmentActivity

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * AES-256-GCM over whole-file bytes. Ciphertext layout is
 * `nonce (12B) || ciphertext || tag (16B)`; the caller stores it verbatim.
 */
interface FileCrypto {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * Biometric gate. [requireUnlocked] is a cheap guard callers use before touching
 * plaintext or exposing AI entry points.
 */
interface AppLock {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    suspend fun unlock(activity: FragmentActivity): Boolean
    fun requireUnlocked(): Boolean

    /**
     * Re-arms the lock for this process lifetime, discarding the "already
     * unlocked in this session" shortcut.
     *
     * Added with a default body under archive R2 (additive only): callers that
     * never re-lock keep compiling unchanged.
     */
    fun lock() {
        // Default: implementations without session state need no action.
    }
}
