package com.meteor.memory.system

import android.os.SystemClock

/**
 * Remembers that the dial code actually brought the app back during this
 * process (ADR-031).
 *
 * A launch the system blocks never reaches `MainActivity`, so a record here is
 * the only positive proof that the background-start gate works; `startActivity`
 * returning normally proves nothing. The record is intentionally process-scoped:
 * a persisted success would outlive a permission reset and could authorise
 * hiding the icon after the way back had already broken.
 */
object DialCodeLaunchRecorder {

    /** A test older than this no longer counts; the user is asked to dial again. */
    private const val VALID_FOR_MS = 10 * 60 * 1000L

    @Volatile
    private var lastSuccessAt = 0L

    /** Called from `MainActivity` only when the launch carried the dial-code extra. */
    fun recordSuccess() {
        lastSuccessAt = SystemClock.elapsedRealtime()
    }

    /** True when the dial code returned to the app within the last ten minutes. */
    fun succeededRecently(): Boolean =
        lastSuccessAt != 0L && SystemClock.elapsedRealtime() - lastSuccessAt <= VALID_FOR_MS
}
