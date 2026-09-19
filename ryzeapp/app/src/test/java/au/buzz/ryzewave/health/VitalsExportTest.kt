package au.buzz.ryzewave.health

import au.buzz.ryzewave.core.RestingHr
import au.buzz.ryzewave.core.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The two daily vitals on their way to Health Connect: the resting rate as its own record, recovery in the notes. */
class VitalsExportTest {
    private val zone = ZoneId.of("Australia/Brisbane")
    private val now = 1_789_820_000_000L

    private fun workout(hrr1: Int?, hrr2: Int?, peak: Int?) = Workout(
        id = 40, start = now - 3_600_000L, end = now - 1_500_000L, sportType = 1, distanceMeters = 3177.0,
        durationSeconds = 2146, avgHr = 140, maxHr = 173, calories = 387, hrrPeak = peak, hrr1 = hrr1, hrr2 = hrr2,
    )

    @Test
    fun recoveryRidesInTheSessionNotesWhenPresent() {
        val notes = HealthConnectMapping.exerciseNotes(workout(38, 52, 173))
        assertTrue(notes, notes.contains("HRR 38/52 bpm from peak 173"))
        // and changes the session's route-less fingerprint, so an existing session is re-sent once
        val without = HealthConnectMapping.exerciseNotes(workout(null, null, null))
        assertFalse(without, without.contains("HRR"))
    }

    @Test
    fun oneMinuteOnlyRecoveryIsStillReported() {
        val notes = HealthConnectMapping.exerciseNotes(workout(31, null, 170))
        assertTrue(notes, notes.contains("HRR 31 bpm from peak 170"))
    }

    @Test
    fun oneRestingRecordPerDayStampedWhenItWasComputed() {
        val day = now - (now % 86_400_000L)
        val values = listOf(
            RestingHr(dayStart = day, bpm = 61, computedAt = now - 7_200_000L),
            RestingHr(dayStart = day, bpm = 63, computedAt = now - 60_000L),          // same day twice: keep first by order
            RestingHr(dayStart = day - 86_400_000L, bpm = 66, computedAt = now - 90_000_000L),
            RestingHr(dayStart = day + 86_400_000L, bpm = 60, computedAt = now + 60_000L),  // future: skipped
        )
        val records = HealthConnectMapping.restingHrRecords(values, now, zone)
        assertEquals(2, records.size)
        val today = records.first { it.metadata.clientRecordId == HealthConnectMapping.restingHrId(day) }
        assertEquals(61L, today.beatsPerMinute)
        assertEquals(now - 7_200_000L, today.time.toEpochMilli())
        // the fingerprint covers time and value, so a refreshed figure re-exports and an unchanged one does not
        val fp1 = HealthConnectMapping.fingerprint(today, now)
        val fp2 = HealthConnectMapping.fingerprint(HealthConnectMapping.restingHrRecords(listOf(values[1]), now, zone).single(), now)
        assertTrue(fp1 != fp2)
    }
}
