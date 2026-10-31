package com.meteor.memory.core.index

import com.meteor.memory.core.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the "rebuild the index on launch" fallback (archive §9) with exponential
 * backoff.
 *
 * The device log from v0.1.0 showed 15 consecutive rebuild attempts in about a
 * minute when the SQLite build lacked FTS5 — a failure that will never fix
 * itself in-session — so repeated attempts must be damped instead of retried
 * eagerly.
 */
@Singleton
class IndexBootstrap @Inject constructor(
    private val indexModule: IndexModule,
    private val appLog: AppLog,
) {

    private val mutex = Mutex()
    private var consecutiveFailures = 0
    private var blockedUntilMillis = 0L

    suspend fun rebuildIfDue() {
        if (System.currentTimeMillis() < blockedUntilMillis) {
            appLog.info(TAG, "index rebuild skipped (backoff active)")
            return
        }

        mutex.withLock {
            if (System.currentTimeMillis() < blockedUntilMillis) return

            runCatching { indexModule.rebuild() }.fold(
                onSuccess = {
                    consecutiveFailures = 0
                    blockedUntilMillis = 0
                    appLog.info(TAG, "index rebuild ok")
                },
                onFailure = { throwable ->
                    consecutiveFailures++
                    val backoff = backoffFor(consecutiveFailures)
                    blockedUntilMillis = System.currentTimeMillis() + backoff
                    appLog.error(TAG, "index rebuild failed (attempt=$consecutiveFailures backoff=${backoff}ms)", throwable)
                },
            )
        }
    }

    private fun backoffFor(failures: Int): Long {
        val exponent = (failures - 1).coerceIn(0, MAX_EXPONENT)
        return (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }

    private companion object {
        const val TAG = "Index"
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_EXPONENT = 6
    }
}
