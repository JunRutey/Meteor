package com.meteor.memory.system

import android.content.Context
import com.meteor.memory.core.storage.StorageLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognises an install that predates the v0.9.0 first-launch gate.
 *
 * Only fresh installs should see the introduction (which asks for a birth
 * date). Anyone who already has an archive file has used the app before, so
 * the gate must not block their diary behind four new screens.
 */
@Singleton
class LegacyInstallDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasArchiveRecords(): Boolean {
        val root = File(context.filesDir, StorageLayout.ROOT_NAME)
        if (!root.isDirectory) return false
        return root.walkTopDown().any { it.isFile }
    }
}
