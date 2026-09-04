package au.buzz.ryzewave.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideModel
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.protocol.SportTypes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.min

/**
 * Pure mapping from the app's models (docs/APP.md "Health Connect mapping") to Health Connect records.
 * No Android classes here so the mapping is unit-testable on the JVM; [HealthConnectExporter] does the I/O.
 *
 * Every record carries `Metadata(clientRecordId = …, clientRecordVersion = now)`; the ids are stable functions
 * of the data's own timestamps so a re-export updates the record instead of duplicating it. The version is the
 * planner's phone-clock `now` (epoch millis), which grows with every export: Health Connect only applies an
 * upsert under an existing client id when the new `clientRecordVersion` is *higher* than the stored one
 * (a re-write at an equal version is silently ignored), so a constant version would have frozen the current
 * hour's steps / today's distance at the values of the first export of that hour / day.
 *
 * | record | client id |
 * |---|---|
 * | [StepsRecord] per watch hour | `steps-<hourStart>` |
 * | [HeartRateRecord] per (epoch) hour of samples | `hr-<hourStart>` |
 * | [OxygenSaturationRecord] per sample | `spo2-<time>` |
 * | [DistanceRecord] per local day (stride model) | `dist-<dayStart>` |
 * | [DistanceRecord] per workout (GPS) | `wdist-<workoutId>` |
 * | [SleepSessionRecord] per night | `sleep-<morning date>` |
 * | [ExerciseSessionRecord] per workout | `workout-<workoutId>` |
 *
 * Times are epoch milliseconds; `now` cuts off anything the watch reports in the future (clock skew) and caps
 * the open-ended intervals of the current hour / day.
 */
object HealthConnectMapping {

    /** `HealthRepository.setLastSyncTime` kind used for the export cursor. */
    const val CURSOR_KIND = "hc-export"

    const val HOUR_MS = 60L * 60L * 1000L
    const val MINUTE_MS = 60L * 1000L

    /** Average speed (m/s) at or above which a workout counts as running; below it is walking. */
    const val RUNNING_SPEED_MPS = SportTypes.RUNNING_SPEED_MPS

    /** Health Connect's own limits, enforced by the record constructors. */
    private const val MAX_STEPS_PER_RECORD = 1_000_000L
    private const val MAX_DISTANCE_M = 1_000_000.0
    private const val MIN_BPM = 1
    private const val MAX_BPM = 300

    val WATCH: Device = Device(manufacturer = "Ryze", model = "Wave", type = Device.TYPE_WATCH)
    val PHONE: Device = Device(manufacturer = "Android", model = "phone", type = Device.TYPE_PHONE)

    // ---- client record ids

    fun stepsId(hourStart: Long): String = "steps-$hourStart"
    fun heartRateId(hourStart: Long): String = "hr-$hourStart"
    fun spo2Id(time: Long): String = "spo2-$time"
    fun dayDistanceId(dayStart: Long): String = "dist-$dayStart"
    fun workoutDistanceId(workoutId: Long): String = "wdist-$workoutId"
    fun sleepId(morning: LocalDate): String = "sleep-$morning"
    fun workoutId(workoutId: Long): String = "workout-$workoutId"

    /**
     * [version] must be strictly greater than the version of the record Health Connect already holds under
     * [clientRecordId] for the write to take effect; the record builders pass their `now`.
     */
    fun metadata(
        clientRecordId: String,
        version: Long,
        device: Device = WATCH,
        recordingMethod: Int = Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
    ): Metadata =
        Metadata(
            clientRecordId = clientRecordId,
            clientRecordVersion = version,
            device = device,
            recordingMethod = recordingMethod,
        )

    // ---- code mappings

    /**
     * Watch sleep stage code → Health Connect stage type. Best current guess (docs/APP.md):
     * 1 = deep, 2 = light, 3 = REM, 4 = awake; anything else is unknown. The single place this is decided.
     */
    fun sleepStageType(code: Int): Int = when (code) {
        1 -> SleepSessionRecord.STAGE_TYPE_DEEP
        2 -> SleepSessionRecord.STAGE_TYPE_LIGHT
        3 -> SleepSessionRecord.STAGE_TYPE_REM
        4 -> SleepSessionRecord.STAGE_TYPE_AWAKE
        else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
    }

    /** Average speed of a finished workout in m/s (0 when unknown). */
    fun averageSpeedMps(workout: Workout): Double =
        if (workout.durationSeconds > 0 && workout.distanceMeters.isFinite()) workout.distanceMeters / workout.durationSeconds else 0.0

    /** The sport a stored workout really was: the watch id, except that type 1 is split by speed ([SportTypes.effectiveId]). */
    fun effectiveSportType(workout: Workout): Int = SportTypes.effectiveId(workout.sportType, averageSpeedMps(workout))

    /**
     * Health Connect exercise type from the watch's sport id (docs/PROTOCOL.md §6c). Type 1 (Outdoor Running) was the
     * only type the app could start before the sport picker existed, walks included, so for type 1 alone the GPS
     * average speed still decides between running and walking (>= [RUNNING_SPEED_MPS] is running), the same rule as
     * the GPX writer. Every other id maps directly.
     */
    fun exerciseType(workout: Workout): Int = exerciseTypeFor(effectiveSportType(workout))

    /** Sport id -> Health Connect exercise type; ids without a close match become OTHER_WORKOUT. */
    fun exerciseTypeFor(sportType: Int): Int = when (sportType) {
        0x01, 0x24, 0x73 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING                // Outdoor Running, Trail Running, Marathon
        0x1B, 0x15 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL             // Indoor Running, Treadmill
        0x09, 0x23 -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING                       // Walking, Outdoor Walking
        0x02 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING                              // Cycling
        0x12 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY                   // Spinning
        0x08 -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        0x04 -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        0x13 -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        0x1C -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        0x61 -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING    // HIIT
        0x1F -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        0x29 -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE                      // Rower
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** Session title: the sport's name (for type 1, the one the speed tie-breaker resolved to). */
    fun exerciseTitle(workout: Workout): String = SportTypes.name(effectiveSportType(workout))

    fun exerciseNotes(workout: Workout): String = buildString {
        append(String.format(Locale.ROOT, "%.2f km", workout.distanceMeters / 1000.0))
        append(", ").append(formatElapsed(workout.durationSeconds))
        workout.avgHr?.let { append(", avg HR ").append(it) }
        workout.maxHr?.let { append(", max HR ").append(it) }
        append(", ").append(workout.calories).append(" kcal")
        append(", ").append(SportTypes.name(workout.sportType)).append(" (sport type ").append(workout.sportType).append(')')
    }

    // ---- calendar helpers (local wall clock in [zone])

    fun offsetAt(time: Long, zone: ZoneId): ZoneOffset = zone.rules.getOffset(Instant.ofEpochMilli(time))

    fun localDate(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun startOfDay(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Local midnight at or before [time]. */
    fun startOfDay(time: Long, zone: ZoneId): Long = startOfDay(localDate(time, zone), zone)

    /** The local midnight after the day containing [time]. */
    fun endOfDay(time: Long, zone: ZoneId): Long = startOfDay(localDate(time, zone).plusDays(1), zone)

    /** Local noon at or before [time]: the start of the noon-to-noon window a sleep stage at [time] belongs to. */
    fun previousNoon(time: Long, zone: ZoneId): Long {
        val zdt = Instant.ofEpochMilli(time).atZone(zone)
        val date = if (zdt.toLocalTime() < LocalTime.NOON) zdt.toLocalDate().minusDays(1) else zdt.toLocalDate()
        return date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * The morning a sleep stage belongs to: stages after noon belong to the next morning, stages before noon to
     * this one (docs/PROTOCOL.md §6a, the same noon-to-noon window as `data.Days.nightWindow`).
     */
    fun sleepMorning(time: Long, zone: ZoneId): LocalDate {
        val zdt = Instant.ofEpochMilli(time).atZone(zone)
        return if (zdt.toLocalTime() < LocalTime.NOON) zdt.toLocalDate() else zdt.toLocalDate().plusDays(1)
    }

    /** Start of the whole (epoch) hour containing [time]; used to group HR samples. */
    fun hourBin(time: Long): Long = Math.floorDiv(time, HOUR_MS) * HOUR_MS

    // ---- records

    /** One [StepsRecord] per watch hour; empty hours and future hours are skipped, the current hour ends at [now]. */
    fun stepsRecords(hours: List<StepsHour>, now: Long, zone: ZoneId): List<StepsRecord> =
        hours.asSequence()
            .filter { it.total > 0 && it.hourStart <= now }
            .sortedBy { it.hourStart }
            .distinctBy { it.hourStart }
            .mapNotNull { h ->
                val start = h.hourStart
                val end = min(start + HOUR_MS, now)
                if (end <= start) return@mapNotNull null
                StepsRecord(
                    startTime = Instant.ofEpochMilli(start),
                    startZoneOffset = offsetAt(start, zone),
                    endTime = Instant.ofEpochMilli(end),
                    endZoneOffset = offsetAt(end, zone),
                    count = h.total.toLong().coerceAtMost(MAX_STEPS_PER_RECORD),
                    metadata = metadata(stepsId(start), now),
                )
            }
            .toList()

    /**
     * HR samples grouped per hour into one [HeartRateRecord] each (start = first sample, end = last sample).
     * Samples at the same instant from different sources collapse to one; out-of-range and future values are dropped.
     */
    fun heartRateRecords(samples: List<HrSample>, now: Long, zone: ZoneId): List<HeartRateRecord> =
        samples.asSequence()
            .filter { it.bpm in MIN_BPM..MAX_BPM && it.time <= now }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .groupBy { hourBin(it.time) }
            .toSortedMap()
            .map { (hourStart, group) ->
                val start = group.first().time
                val end = group.last().time
                HeartRateRecord(
                    startTime = Instant.ofEpochMilli(start),
                    startZoneOffset = offsetAt(start, zone),
                    endTime = Instant.ofEpochMilli(end),
                    endZoneOffset = offsetAt(end, zone),
                    samples = group.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.time), it.bpm.toLong()) },
                    metadata = metadata(heartRateId(hourStart), now),
                )
            }

    /** One [OxygenSaturationRecord] per SpO2 sample; 0 and > 100 (the watch's "no result" values) are dropped. */
    fun spo2Records(samples: List<Spo2Sample>, now: Long, zone: ZoneId): List<OxygenSaturationRecord> =
        samples.asSequence()
            .filter { it.percent in 1..100 && it.time <= now }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .map { s ->
                OxygenSaturationRecord(
                    time = Instant.ofEpochMilli(s.time),
                    zoneOffset = offsetAt(s.time, zone),
                    percentage = Percentage(s.percent.toDouble()),
                    metadata = metadata(spo2Id(s.time), now),
                )
            }
            .toList()

    /**
     * One [DistanceRecord] per local day from the stride model: walk steps × walk stride + run steps × run stride,
     * exactly as `DailySummary.distanceMeters`. [hours] must contain every hour of the days to be written
     * (a partial day would shrink the day's distance). Today ends at [now].
     */
    fun dailyDistanceRecords(
        hours: List<StepsHour>,
        profile: UserProfile,
        strideSettings: StrideSettings,
        stride: StrideModel,
        now: Long,
        zone: ZoneId,
    ): List<DistanceRecord> =
        hours.asSequence()
            .filter { it.hourStart <= now }
            .distinctBy { it.hourStart }
            .groupBy { startOfDay(it.hourStart, zone) }
            .toSortedMap()
            .mapNotNull { (dayStart, dayHours) ->
                val walk = dayHours.sumOf { it.walk }
                val run = dayHours.sumOf { it.run }
                val meters = stride.stepsToMeters(walk, run, profile, strideSettings)
                if (!meters.isFinite() || meters <= 0.0) return@mapNotNull null
                val end = min(endOfDay(dayStart, zone), now)
                if (end <= dayStart) return@mapNotNull null
                DistanceRecord(
                    startTime = Instant.ofEpochMilli(dayStart),
                    startZoneOffset = offsetAt(dayStart, zone),
                    endTime = Instant.ofEpochMilli(end),
                    endZoneOffset = offsetAt(end, zone),
                    distance = Length.meters(meters.coerceAtMost(MAX_DISTANCE_M)),
                    metadata = metadata(dayDistanceId(dayStart), now),
                )
            }

    /** Finished workouts only: end set, end after start, started no later than [now]. */
    fun exportableWorkouts(workouts: List<Workout>, now: Long): List<Workout> =
        workouts.asSequence()
            .filter { w -> w.id > 0 && w.start <= now && (w.end ?: 0L) > w.start }
            .sortedBy { it.start }
            .distinctBy { it.id }
            .toList()

    /** One [DistanceRecord] per finished workout with a positive GPS distance. */
    fun workoutDistanceRecords(workouts: List<Workout>, now: Long, zone: ZoneId): List<DistanceRecord> =
        exportableWorkouts(workouts, now)
            .filter { it.distanceMeters.isFinite() && it.distanceMeters > 0.0 }
            .map { w ->
                val end = w.end ?: w.start
                DistanceRecord(
                    startTime = Instant.ofEpochMilli(w.start),
                    startZoneOffset = offsetAt(w.start, zone),
                    endTime = Instant.ofEpochMilli(end),
                    endZoneOffset = offsetAt(end, zone),
                    distance = Length.meters(w.distanceMeters.coerceAtMost(MAX_DISTANCE_M)),
                    metadata = metadata(workoutDistanceId(w.id), now, PHONE, Metadata.RECORDING_METHOD_ACTIVELY_RECORDED),
                )
            }

    /** One [ExerciseSessionRecord] per finished workout, walking or running by [exerciseType]. */
    fun exerciseSessionRecords(workouts: List<Workout>, now: Long, zone: ZoneId): List<ExerciseSessionRecord> =
        exportableWorkouts(workouts, now).map { w ->
            val end = w.end ?: w.start
            ExerciseSessionRecord(
                startTime = Instant.ofEpochMilli(w.start),
                startZoneOffset = offsetAt(w.start, zone),
                endTime = Instant.ofEpochMilli(end),
                endZoneOffset = offsetAt(end, zone),
                exerciseType = exerciseType(w),
                title = exerciseTitle(w),
                notes = exerciseNotes(w),
                metadata = metadata(workoutId(w.id), now, WATCH, Metadata.RECORDING_METHOD_ACTIVELY_RECORDED),
            )
        }

    /** A night's stages after clamping: [start, end) half-open epoch millis and the Health Connect stage type. */
    data class NightStage(val start: Long, val end: Long, val stageType: Int)

    /** A night ready to become a [SleepSessionRecord]. */
    data class Night(val morning: LocalDate, val start: Long, val end: Long, val stages: List<NightStage>)

    /**
     * Groups stages into nights by the morning they belong to (noon-to-noon windows) and makes each night's stages
     * contiguous and non-overlapping: a stage ends at `start + minutes`, cut short by the next stage's start and by
     * [now]. Stages of zero length are dropped; a night without any stage is dropped.
     */
    fun nights(stages: List<SleepStage>, now: Long, zone: ZoneId): List<Night> =
        stages.asSequence()
            .filter { it.minutes > 0 && it.start <= now }
            .sortedBy { it.start }
            .distinctBy { it.start }
            .groupBy { sleepMorning(it.start, zone) }
            .toSortedMap()
            .mapNotNull { (morning, group) ->
                val out = ArrayList<NightStage>(group.size)
                for ((i, s) in group.withIndex()) {
                    var end = s.start + s.minutes * MINUTE_MS
                    group.getOrNull(i + 1)?.let { next -> end = min(end, next.start) }
                    end = min(end, now)
                    if (end > s.start) out += NightStage(s.start, end, sleepStageType(s.stage))
                }
                if (out.isEmpty()) null else Night(morning, out.first().start, out.last().end, out)
            }

    /** One [SleepSessionRecord] per night (see [nights]) with the stage list. */
    fun sleepSessionRecords(stages: List<SleepStage>, now: Long, zone: ZoneId): List<SleepSessionRecord> =
        nights(stages, now, zone).map { night ->
            SleepSessionRecord(
                startTime = Instant.ofEpochMilli(night.start),
                startZoneOffset = offsetAt(night.start, zone),
                endTime = Instant.ofEpochMilli(night.end),
                endZoneOffset = offsetAt(night.end, zone),
                title = null,
                notes = null,
                stages = night.stages.map { s ->
                    SleepSessionRecord.Stage(Instant.ofEpochMilli(s.start), Instant.ofEpochMilli(s.end), s.stageType)
                },
                metadata = metadata(sleepId(night.morning), now),
            )
        }

    private fun formatElapsed(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }
}
