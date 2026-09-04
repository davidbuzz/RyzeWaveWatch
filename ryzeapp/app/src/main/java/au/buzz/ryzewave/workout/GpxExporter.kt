package au.buzz.ryzewave.workout

import android.content.Context
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a workout as GPX 1.1 into `<external files dir>/gpx/workout-<id>-<yyyyMMdd-HHmm>.gpx`
 * (falls back to the internal files dir when external storage is unavailable). Returns the file so the
 * UI can share it with a FileProvider or show the path.
 */
class GpxExporter(
    private val context: Context,
    private val repo: HealthRepository,
) {
    /** The directory exports go to; created on demand. */
    fun exportDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    /** Export one stored workout. Throws [IllegalArgumentException] when the id is unknown. */
    suspend fun export(workoutId: Long, includeRejected: Boolean = false): File = withContext(Dispatchers.IO) {
        val workout = repo.workout(workoutId).first()
            ?: throw IllegalArgumentException("no workout with id $workoutId")
        val points = repo.trackPoints(workoutId).first()
        val lastTime = maxOf(
            workout.end ?: Long.MIN_VALUE,
            points.maxOfOrNull { it.time } ?: Long.MIN_VALUE,
            workout.start + workout.durationSeconds * 1000L,
        )
        val hr: List<HrSample> = repo.hrBetween(workout.start - HR_MARGIN_MS, lastTime + HR_MARGIN_MS).first()
        val file = File(exportDir(), fileName(workout))
        file.bufferedWriter().use { GpxWriter.write(it, workout, points, hr, includeRejected) }
        file
    }

    /** Export every stored workout that has at least one track point; returns the files written. */
    suspend fun exportAll(): List<File> {
        val workouts = repo.workouts().first()
        val files = ArrayList<File>()
        for (w in workouts) {
            if (w.id == 0L) continue
            val points = repo.trackPoints(w.id).first()
            if (points.isEmpty()) continue
            files += export(w.id)
        }
        return files
    }

    companion object {
        const val DIR_NAME = "gpx"
        private const val HR_MARGIN_MS = 10_000L
        private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

        fun fileName(workout: Workout, zone: ZoneId = ZoneId.systemDefault()): String =
            "workout-${workout.id}-${fileStamp.format(Instant.ofEpochMilli(workout.start).atZone(zone))}.gpx"
    }
}
