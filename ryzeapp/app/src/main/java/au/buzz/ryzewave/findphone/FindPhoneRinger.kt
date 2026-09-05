package au.buzz.ryzewave.findphone

import au.buzz.ryzewave.core.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the phone does while the watch is looking for it. Implemented on Android by
 * [AndroidFindPhoneAlerter] (alarm ringtone + vibration + a notification with a Stop action); the
 * [FindPhoneRinger] state machine itself is Android-free so it is unit tested.
 */
interface FindPhoneAlerter {
    /** Begin ringing, vibrating and showing the notification. Called once per ring. */
    fun startAlarm()

    /** Silence everything and remove the notification. Called once per ring, after [startAlarm]. */
    fun stopAlarm()
}

/**
 * Find-my-phone state machine, fed with [WatchEvent.FindPhone] from `WatchApi.events`:
 *
 *  - `D1 0A 01` (start) while idle → [FindPhoneAlerter.startAlarm] and a [timeoutMs] timer (30 s);
 *  - `D1 0A 00` (stop), the notification's Stop action ([stop] with reason `user`) or the timer → [FindPhoneAlerter.stopAlarm];
 *  - a second start while already ringing is ignored (the ringtone is not restarted and the original timer
 *    keeps running), a stop while idle is a no-op — the watch sends `D1 0A 00` on every connect.
 *
 * Every ring has a [generation] number; the timer belongs to the ring that started it and cannot stop a later
 * one (a timeout job that was already past its delay when a user Stop + watch restart raced it is ignored).
 *
 * Locking: the state (ringing flag, generation, timer) is changed under a lock that is held only for a few
 * field writes; the alerter calls run *outside* it, serialised by a second lock, so a Stop tap on the main
 * thread flips the state at once instead of waiting behind `MediaPlayer.prepare()` (~400 ms) in `startAlarm`.
 * The worst case for the main thread is the alerter lock while a start is still in progress.
 *
 * Alerter failures are logged, never propagated: a broken ringtone must not stop the vibration or the notification
 * (the Android alerter handles each part on its own), and the state must stay consistent whatever happens.
 */
class FindPhoneRinger(
    private val alerter: FindPhoneAlerter,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val log: (String, Throwable?) -> Unit = { m, t -> println("FindPhone: $m${t?.let { " ($it)" } ?: ""}") },
) {
    private val stateLock = Any()
    private val alerterLock = Any()
    private val _ringing = MutableStateFlow(false)
    /** True while the phone rings. */
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    private var timeout: Job? = null

    /** Number of rings started (tests / diagnostics). */
    @Volatile var startCount = 0
        private set

    /** The ring currently (or last) started; the timer of ring *n* only stops ring *n*. */
    @Volatile var generation = 0
        private set

    /** Why the last ring ended: `watch`, `user` or `timeout`; null while ringing or before the first ring. */
    @Volatile var lastStopReason: String? = null
        private set

    /** Route for `WatchApi.events`; everything but [WatchEvent.FindPhone] is ignored. */
    fun onEvent(event: WatchEvent) {
        if (event !is WatchEvent.FindPhone) return
        if (event.start) start(SOURCE_WATCH) else stop(SOURCE_WATCH)
    }

    /** Starts ringing unless already ringing. Returns true when a new ring started. */
    fun start(source: String): Boolean {
        val gen: Int
        synchronized(stateLock) {
            if (_ringing.value) {
                log("find phone ($source): already ringing, ignored", null)
                return false
            }
            _ringing.value = true
            startCount++
            lastStopReason = null
            gen = ++generation
            timeout = scope.launch {
                delay(timeoutMs)
                stop(SOURCE_TIMEOUT, gen)
            }
        }
        synchronized(alerterLock) {
            try {
                alerter.startAlarm()
            } catch (e: Exception) {
                log("alerter start failed", e)
            }
        }
        log("find phone ($source): ringing for up to ${timeoutMs / 1000} s (ring $gen)", null)
        return true
    }

    /** Stops ringing; [reason] is `watch`, `user` or `timeout`. Returns true when a ring was actually stopped. */
    fun stop(reason: String): Boolean = stop(reason, null)

    /**
     * Stops the current ring, or only ring [ringGeneration] when given (the timer's call): a stale timer is a
     * no-op. Returns true when a ring was actually stopped.
     */
    fun stop(reason: String, ringGeneration: Int?): Boolean {
        synchronized(stateLock) {
            if (!_ringing.value) return false
            if (ringGeneration != null && ringGeneration != generation) {
                log("find phone: stale $reason for ring $ringGeneration ignored (ring $generation is active)", null)
                return false
            }
            _ringing.value = false
            lastStopReason = reason
            timeout?.cancel()
            timeout = null
        }
        synchronized(alerterLock) {
            try {
                alerter.stopAlarm()
            } catch (e: Exception) {
                log("alerter stop failed", e)
            }
        }
        log("find phone: stopped ($reason)", null)
        return true
    }

    companion object {
        /** The phone stops on its own after this long if neither the watch nor the user stops it. */
        const val TIMEOUT_MS = 30_000L
        const val SOURCE_WATCH = "watch"
        const val SOURCE_USER = "user"
        const val SOURCE_TIMEOUT = "timeout"
        const val SOURCE_DEBUG = "debug"
    }
}
