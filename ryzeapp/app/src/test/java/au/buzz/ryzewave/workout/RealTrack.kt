package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout

/**
 * The first real outdoor walk (captures/pixel_outdoor_walk_20260905/README.md): workout #1 on the Pixel 9a,
 * 08:35:14–08:37:56, 155 m, 146 GPS fixes of which 86 were accepted. The rows are the `track_point` table of
 * that database, exported to `src/test/resources/pixel_outdoor_walk_20260905_track.csv`.
 */
object RealTrack {
    const val WORKOUT_ID = 1L
    const val START = 1_788_561_314_238L
    const val END = 1_788_561_476_045L

    val workout = Workout(
        id = WORKOUT_ID, start = START, end = END, sportType = 1, distanceMeters = 155.167481391673,
        durationSeconds = 158, avgHr = 99, maxHr = 105, calories = 14,
    )

    fun pixelOutdoorWalk(): List<TrackPoint> {
        val stream = RealTrack::class.java.getResourceAsStream("/pixel_outdoor_walk_20260905_track.csv")
            ?: error("fixture pixel_outdoor_walk_20260905_track.csv missing from test resources")
        return stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val c = line.split(',')
                TrackPoint(
                    workoutId = WORKOUT_ID,
                    time = c[0].toLong(),
                    lat = c[1].toDouble(),
                    lon = c[2].toDouble(),
                    accuracyM = c[3].toFloat(),
                    speedMps = c[4].toFloat(),
                    altitudeM = c[5].takeIf { it.isNotEmpty() }?.toDouble(),
                    accepted = c[6] == "1",
                    cumulativeM = c[7].takeIf { it.isNotEmpty() }?.toDouble(),
                )
            }.toList()
        }
    }
}
