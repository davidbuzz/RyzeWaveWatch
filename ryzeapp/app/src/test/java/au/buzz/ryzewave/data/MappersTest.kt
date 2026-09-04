package au.buzz.ryzewave.data

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    private val now = 1_700_000_000_000L

    @Test
    fun stepsRoundTrip() {
        val m = StepsHour(hourStart = 1_000L, total = 520, walk = 500, run = 20)
        assertEquals(m, m.toEntity(now).toModel())
        assertEquals(StepsHourEntity(1_000L, 520, 500, 20, now), m.toEntity(now))
    }

    @Test
    fun hrRoundTripKeepsSource() {
        val live = HrSample(2_000L, 88, SampleSource.LIVE)
        assertEquals(live, live.toEntity(now).toModel())
        assertEquals("LIVE", live.toEntity(now).source)
        assertEquals(now, live.toEntity(now).updatedAt)
        val history = HrSample(3_000L, 70)
        assertEquals(SampleSource.HISTORY, history.toEntity(now).toModel().source)
    }

    @Test
    fun unknownSourceFallsBackToHistory() {
        assertEquals(SampleSource.HISTORY, HrSampleEntity(1L, "bogus", 60, now).toModel().source)
        assertEquals(SampleSource.WORKOUT, sourceOf("WORKOUT"))
        assertEquals(SampleSource.AUTO, sourceOf("AUTO"))
    }

    @Test
    fun spo2SleepWorkoutTrackRoundTrip() {
        val s = Spo2Sample(4_000L, 97, SampleSource.AUTO)
        assertEquals(s, s.toEntity(now).toModel())

        val st = SleepStage(5_000L, 2, 45)
        assertEquals(st, st.toEntity(now).toModel())

        val w = Workout(id = 7, start = 10L, end = 20L, sportType = 1, distanceMeters = 1234.5,
            durationSeconds = 600, avgHr = 120, maxHr = 150, calories = 80)
        assertEquals(w, w.toEntity(now).toModel())
        assertEquals(20L, w.toEntity(now).endTime)
        assertEquals(now, w.toEntity(now).updatedAt)
        val open = w.copy(end = null)
        assertEquals(open, open.toEntity(now).toModel())

        val tp = TrackPoint(7, 11L, -27.47, 153.02, 5f, 1.4f, 30.0, true)
        assertEquals(tp, tp.toEntity().toModel())
        val noAlt = tp.copy(altitudeM = null, accepted = false)
        assertEquals(noAlt, noAlt.toEntity().toModel())
    }
}
