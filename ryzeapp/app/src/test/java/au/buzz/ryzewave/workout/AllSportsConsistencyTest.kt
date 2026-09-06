package au.buzz.ryzewave.workout

import androidx.health.connect.client.records.ExerciseSessionRecord
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.health.HealthConnectMapping
import au.buzz.ryzewave.protocol.SportTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of the watch's 70 sports, through every decision the app makes about a sport, checked for internal
 * consistency. This is the test behind "is detection implemented for all sports": it cannot prove a sport is
 * *right* (only a recorded session can), but it does prove none is missing, none falls through a default, and
 * the four places that classify a sport never contradict each other.
 */
class AllSportsConsistencyTest {

    private val ids = SportTypes.NAMES.keys.sorted()
    private val profile = UserProfile(heightCm = 175, weightKg = 75, age = 40, male = true)

    @Test
    fun theWatchHasSeventySportsAndEveryOneIsNamed() {
        assertEquals(70, ids.size)
        for (id in ids) assertTrue("sport $id has a blank name", SportTypes.name(id).isNotBlank())
    }

    @Test
    fun everySportHasAnActivitySignatureThatIsNotTheUnknownFallback() {
        for (id in ids) {
            assertNotEquals("sport ${SportTypes.name(id)} fell through to UNKNOWN", SportClass.UNKNOWN, SportSignature.classOf(id))
        }
    }

    @Test
    fun everySportHasAStrideGateAndTheGateAgreesWithTheSignature() {
        for (id in ids) {
            val name = SportTypes.name(id)
            val gate = StrideCalibration.sportGait(id)
            val cls = SportSignature.classOf(id)
            when (gate) {
                // Stride can only be measured from footfalls; the signature must expect steps for these.
                SportGait.ANY, SportGait.WALK_ONLY ->
                    assertTrue("$name calibrates a stride but its signature does not expect steps ($cls)", Indicator.STEPS in cls.expected)
                // A machine driven by the arms must never be expected to travel.
                SportGait.STROKES ->
                    assertTrue(
                        "$name counts strokes; its signature must not demand GPS unless the wearer really travels ($cls)",
                        Indicator.GPS !in cls.expected || cls == SportClass.RIDE || cls == SportClass.UNMONITORED,
                    )
                // Court sports shuffle and swing; their signature must still accept steps as a sign of life.
                SportGait.COURT ->
                    assertTrue("$name is a court sport; its signature should accept the step counter ($cls)", Indicator.STEPS in cls.expected)
                // Repetitions: the session must have a sign of life that is not travel. A gym never expects GPS;
                // an outdoor field sport with sparse running (baseball) may list it, but never as the only signal.
                SportGait.REPS ->
                    assertTrue("$name is rep work but only travel would prove it alive ($cls)", (cls.expected - Indicator.GPS).isNotEmpty())
                // Nothing usable on the wrist: the signature must not lean on the step counter as the only sign of life.
                SportGait.NONE ->
                    assertFalse("$name has a useless wrist count yet its signature requires only steps", cls.expected == setOf(Indicator.STEPS))
            }
        }
    }

    @Test
    fun stationaryMachinesNeverExpectTheWearerToTravel() {
        for (id in ids) {
            if (SportSignature.classOf(id) in setOf(SportClass.STATIONARY_MACHINE, SportClass.STATIONARY_CARDIO, SportClass.FLOOR_WORK, SportClass.STRENGTH)) {
                assertTrue("${SportTypes.name(id)} is indoor/stationary but expects GPS", Indicator.GPS !in SportSignature.expected(id))
            }
        }
    }

    @Test
    fun everySportMapsToAHealthConnectTypeAndOnlyTheNamedFewAreOther() {
        // Sports with genuinely no Health Connect counterpart in client 1.1.0-alpha11.
        val allowedOther = setOf(
            0x1E, // Horse Riding
            0x28, // VO2 max Test
            0x2D, // Waist Training
            0x35, // Archery
            0x40, // Bowling
            0x48, // Fishing
            0x56, // Lacrosse
            0x62, // Shooting
            0x68, // Parkour
            0x71, // Bungee Jumping
            0x72, // Long Jump
        )
        for (id in ids) {
            val t = HealthConnectMapping.exerciseTypeFor(id)
            if (id in allowedOther) {
                assertEquals("${SportTypes.name(id)} should be OTHER", ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, t)
            } else {
                assertNotEquals("${SportTypes.name(id)} exports as OTHER_WORKOUT but Health Connect has a type for it", ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, t)
            }
        }
    }

    @Test
    fun healthConnectTypeIsConsistentWithTheGait() {
        val running = setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)
        val walking = setOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING, ExerciseSessionRecord.EXERCISE_TYPE_GOLF)
        for (id in ids) {
            val t = HealthConnectMapping.exerciseTypeFor(id)
            val gate = StrideCalibration.sportGait(id)
            if (t in running) assertEquals("${SportTypes.name(id)} exports as running but its stride gate is $gate", SportGait.ANY, gate)
            if (t in walking) assertEquals("${SportTypes.name(id)} exports as walking but its stride gate is $gate", SportGait.WALK_ONLY, gate)
            if (t == ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE || t == ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL) {
                assertEquals("${SportTypes.name(id)} is an arm-driven machine", SportGait.STROKES, gate)
                assertEquals(SportClass.STATIONARY_MACHINE, SportSignature.classOf(id))
            }
            if (t == ExerciseSessionRecord.EXERCISE_TYPE_BIKING || t == ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY) {
                assertEquals("${SportTypes.name(id)}: a wrist cannot see pedals", SportGait.NONE, gate)
            }
        }
    }

    @Test
    fun aChosenSportIsNeverOverruledByAverageSpeed() {
        // Every sport, recorded after the picker existed, at a slow average: the wearer's choice must survive.
        val slowStart = SportTypes.PICKER_EPOCH_MS + 3_600_000L
        for (id in ids) {
            val w = Workout(id = 1, start = slowStart, end = slowStart + 600_000, sportType = id, distanceMeters = 500.0, durationSeconds = 600, avgHr = null, maxHr = null, calories = 0)
            assertEquals("${SportTypes.name(id)} was relabelled by speed", id, HealthConnectMapping.effectiveSportType(w))
            assertEquals(HealthConnectMapping.exerciseTypeFor(id), HealthConnectMapping.exerciseType(w))
        }
    }

    @Test
    fun onlyALegacyTypeOneRowIsSplitBySpeed() {
        val before = SportTypes.PICKER_EPOCH_MS - 3_600_000L
        val slowLegacyRun = Workout(id = 1, start = before, end = before + 600_000, sportType = SportTypes.OUTDOOR_RUNNING, distanceMeters = 500.0, durationSeconds = 600, avgHr = null, maxHr = null, calories = 0)
        assertEquals("a pre-picker slow type-1 row is a walk", SportTypes.OUTDOOR_WALKING, HealthConnectMapping.effectiveSportType(slowLegacyRun))
        val slowLegacyWalk = slowLegacyRun.copy(sportType = SportTypes.OUTDOOR_WALKING)
        assertEquals("only type 1 is ever second-guessed", SportTypes.OUTDOOR_WALKING, HealthConnectMapping.effectiveSportType(slowLegacyWalk))
        val slowLegacyCycle = slowLegacyRun.copy(sportType = 0x02)
        assertEquals(0x02, HealthConnectMapping.effectiveSportType(slowLegacyCycle))
    }

    @Test
    fun strideCalibrationNeverThrowsForAnySportOnAnEmptyTrack() {
        for (id in ids) {
            val out = StrideCalibration.calibrate(emptyList(), profile, id)
            assertFalse("${SportTypes.name(id)} saved a stride from nothing", out.changedAnything)
            assertTrue("${SportTypes.name(id)} gave no message", out.message.isNotBlank())
        }
    }
}
