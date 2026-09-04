package au.buzz.ryzewave.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The local day start that "today" refers to, kept current while the app runs. */
object DayClock {
    const val TICK_MS = 60_000L

    /**
     * Emits the current local day start immediately, then again each time it changes (checked every [tickMs],
     * so a running screen rolls over within a minute of midnight or a zone change).
     */
    fun dayStarts(tickMs: Long = TICK_MS, now: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
        var last = Fmt.dayStart(now())
        emit(last)
        while (true) {
            delay(tickMs)
            val current = Fmt.dayStart(now())
            if (current != last) {
                last = current
                emit(current)
            }
        }
    }
}
