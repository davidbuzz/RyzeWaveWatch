package au.buzz.ryzewave.health

import au.buzz.ryzewave.core.SleepStage

/**
 * Honest reconstruction of a sleep window the watch under-staged. Pure Kotlin, no Android imports, unit-tested.
 *
 * When an accidental exercise mode (or any glitch) stops the watch detecting sleep, the watch reports only a few
 * fragmentary stages for a night the wearer clearly slept through. We must be able to record the *real* sleep
 * window (e.g. from the heart-rate trace) without inventing deep/light/REM detail we do not have. [fillWindow]
 * therefore keeps every real stage the watch did record inside the window and fills only the *gaps* — and the
 * two ends — with [SleepStage.GENERIC_ASLEEP] (code 5, "asleep, stage unknown"). Awake stages stay awake;
 * generic-asleep counts as asleep but is never split into a real stage.
 */
object SleepReconstruction {

    private const val MINUTE_MS = 60_000L

    /** The reconstructed night plus a small tally for logging / assertions. */
    data class Result(
        /** Real + generic stages that tile [windowStart, windowEnd), sorted by start. */
        val stages: List<SleepStage>,
        /** Minutes asleep = every stage but awake (deep + light + REM + generic). */
        val totalAsleepMin: Int,
        /** Count of app-generated generic-asleep blocks written. */
        val genericBlocks: Int,
        /** Count of the watch's real stages kept inside the window. */
        val stagedBlocks: Int,
    )

    /**
     * Reconstruct the sleep of [windowStart, windowEnd) (half-open, epoch ms).
     *
     * With [preserveStaged] (the default) every stage in [existing] whose interval intersects the window is kept
     * unchanged, and the parts of the window not covered by any of them — the gaps between real stages and the
     * stretches at each end — are filled with [SleepStage.GENERIC_ASLEEP]. With [preserveStaged] false the whole
     * window becomes a single generic-asleep block (the caller's `existing` is ignored).
     *
     * Minutes are computed from the millisecond boundaries; a fill shorter than one minute is dropped, so the
     * result may leave a sub-minute sliver un-tiled — harmless for whole-minute watch data.
     */
    fun fillWindow(
        windowStart: Long,
        windowEnd: Long,
        existing: List<SleepStage>,
        preserveStaged: Boolean = true,
    ): Result {
        if (windowEnd <= windowStart) return Result(emptyList(), 0, 0, 0)

        val kept = if (preserveStaged) {
            existing.filter { it.minutes > 0 && it.start < windowEnd && it.start + it.minutes * MINUTE_MS > windowStart }
                .sortedBy { it.start }
        } else {
            emptyList()
        }

        val fills = ArrayList<SleepStage>()
        var cursor = windowStart
        for (s in kept) {
            val sStart = s.start.coerceAtLeast(windowStart)
            val sEnd = (s.start + s.minutes * MINUTE_MS).coerceAtMost(windowEnd)
            if (sStart > cursor) addFill(fills, cursor, sStart)
            if (sEnd > cursor) cursor = sEnd
        }
        if (cursor < windowEnd) addFill(fills, cursor, windowEnd)

        val stages = (kept + fills).sortedBy { it.start }
        val totalAsleep = stages.filter { it.stage != SleepStage.AWAKE }.sumOf { it.minutes }
        return Result(stages, totalAsleep, fills.size, kept.size)
    }

    private fun addFill(out: MutableList<SleepStage>, start: Long, end: Long) {
        val mins = ((end - start) / MINUTE_MS).toInt()
        if (mins > 0) out += SleepStage(start, SleepStage.GENERIC_ASLEEP, mins)
    }
}
