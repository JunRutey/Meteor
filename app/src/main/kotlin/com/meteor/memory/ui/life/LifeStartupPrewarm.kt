package com.meteor.memory.ui.life

import android.os.SystemClock
import com.meteor.memory.core.log.AppLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the birth profile and its next birthday during the cold-start splash
 * (ADR-044), so the first visit to the life tab does not sit on a spinner.
 *
 * The expensive part is the lunar path: `ChineseLunarCalendar.nextBirthday`
 * enumerates several years day by day through ICU. Doing that once while the
 * system splash is still on screen hides the cost behind a frame the user
 * already expects to wait for. The result is **only** held in memory; nothing
 * birthday-related is rendered during the splash and the app lock is untouched.
 *
 * [isReady] also becomes true when there is no profile or when the computation
 * fails. The splash waits for readiness, so a failure must release it instead
 * of hanging it forever; the life tab then shows its own Missing/Failed state.
 */
@Singleton
class LifeStartupPrewarm @Inject constructor(
    private val profileStore: BirthdayProfileStore,
    private val appLog: AppLog,
) {

    /**
     * The birth profile plus the next birthday as of [capturedAt]. [profile] is
     * null when the store had no profile; [nextBirthday] is null only in that
     * case. [zone] and [capturedAt] let callers decide whether the snapshot is
     * still usable (timezone changed, or the cached birthday has passed).
     */
    data class Snapshot(
        val profile: BirthProfile?,
        val nextBirthday: LocalDate?,
        val zone: ZoneId,
        val capturedAt: Instant,
    )

    // App-scoped: the prewarm has to outlive the Activity that started it and
    // must not be cancelled when the splash goes away.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val calendar = ChineseLunarCalendar()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** One-shot readiness check for callers that do not collect [ready]. */
    val isReady: Boolean get() = _ready.value

    @Volatile
    private var cachedSnapshot: Snapshot? = null

    /** Last completed snapshot, or null while the prewarm is still running. */
    val snapshot: Snapshot? get() = cachedSnapshot

    private var started = false

    /** Starts the one-shot prewarm. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { compute(profileOverride = null) }
    }

    /**
     * Refreshes the snapshot after the user saved a new birth profile. The save
     * paths call this so a later visit to the life tab can reuse the result even
     * before the next refresh reads the store again.
     */
    fun updateAfterSave(profile: BirthProfile) {
        scope.launch { compute(profileOverride = profile) }
    }

    private suspend fun compute(profileOverride: BirthProfile?) {
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val zone = ZoneId.systemDefault()
            val capturedAt = Instant.now()
            var result = "failed"
            try {
                val profile = profileOverride ?: withContext(Dispatchers.IO) {
                    profileStore.read()
                }
                val nextBirthday = if (profile == null) {
                    result = "no-profile"
                    null
                } else {
                    result = when (profile) {
                        is BirthProfile.Solar -> "solar"
                        is BirthProfile.Lunar -> "lunar"
                    }
                    // ICU enumeration must stay off the main thread. The shared
                    // year cache in ChineseLunarCalendar makes this the only
                    // full price the process pays before the life tab opens.
                    withContext(Dispatchers.Default) {
                        BirthdayAgeCalculator.nextBirthday(
                            profile = profile,
                            now = capturedAt,
                            zone = zone,
                            lunarCalendar = calendar,
                        )
                    }
                }
                // Publish the data before the ready flag so anyone observing
                // readiness already sees the snapshot.
                cachedSnapshot = Snapshot(
                    profile = profile,
                    nextBirthday = nextBirthday,
                    zone = zone,
                    capturedAt = capturedAt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Never log the profile itself; the tag/message are enough to
                // find the failure in an exported log.
                appLog.error(TAG, "life prewarm failed", failure)
            } finally {
                _ready.value = true
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                appLog.info(TAG, "life prewarm finished in ${elapsedMs}ms result=$result")
            }
        }
    }

    private companion object {
        const val TAG = "LifePrewarm"
    }
}
