package au.buzz.ryzewave.workout

import au.buzz.ryzewave.protocol.SportTypes

/**
 * Which activity [Indicator]s a live workout of each kind is expected to show (docs/PLAN.md, "Stuck-in-exercise-
 * mode detector"). The workout is "genuinely live" when ANY expected indicator is active, so the sets err on the
 * lenient side: an extra expected indicator can only make a false "stuck" verdict less likely, while a missing
 * one could flag a real session (a rowing machine has no GPS, yoga no steps and no elevated HR).
 */
enum class SportClass(val expected: Set<Indicator>) {
    /** On foot, outdoors: running, walking, hiking, field sports, golf. */
    MOVEMENT(setOf(Indicator.STEPS, Indicator.GPS, Indicator.HR, Indicator.MOTION)),

    /** On foot, indoors: treadmill, indoor running, court sports, dance, aerobics. GPS is not expected. */
    INDOOR_STEPS(setOf(Indicator.STEPS, Indicator.HR, Indicator.MOTION)),

    /** Wheels, water, snow, hooves: no steps, but the phone travels. */
    RIDE(setOf(Indicator.GPS, Indicator.HR, Indicator.MOTION)),

    /** Spinning, elliptical: the phone goes nowhere; HR and cadence motion tell. */
    STATIONARY_CARDIO(setOf(Indicator.HR, Indicator.MOTION)),

    /** Yoga / stretching: low HR, no steps; the only sign of life is the phone moving with the body. */
    FLOOR_WORK(setOf(Indicator.MOTION)),

    /** Strength, HIIT, combat sports, calisthenics: HR and motion; steps are a lenient extra ("~" in the plan). */
    STRENGTH(setOf(Indicator.STEPS, Indicator.HR, Indicator.MOTION)),

    /**
     * Never judged: the phone is not on the body (swimming, surfing, snorkeling) or standing still for many minutes
     * is the sport itself (fishing, archery, shooting). Nothing is expected, so nothing is ever measurable and the
     * detector cannot fire.
     */
    UNMONITORED(emptySet()),

    /** An id the watch's menu does not have: assume everything, i.e. the most lenient signature. */
    UNKNOWN(setOf(Indicator.STEPS, Indicator.GPS, Indicator.HR, Indicator.MOTION)),
}

object SportSignature {
    /** Every one of the 70 Ryze Wave sport ids ([SportTypes.NAMES]) → its class. */
    val CLASSES: Map<Int, SportClass> = buildMap {
        listOf(0x01, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0E, 0x0F, 0x10, 0x23, 0x24, 0x28, 0x2C, 0x46, 0x4B, 0x56, 0x5A, 0x68, 0x72, 0x73)
            .forEach { put(it, SportClass.MOVEMENT) }
        listOf(0x05, 0x0A, 0x0D, 0x15, 0x18, 0x1B, 0x37, 0x39, 0x3F, 0x40, 0x41, 0x55, 0x60)
            .forEach { put(it, SportClass.INDOOR_STEPS) }
        listOf(0x02, 0x17, 0x1E, 0x25, 0x29, 0x44, 0x4D, 0x4E, 0x51, 0x65)
            .forEach { put(it, SportClass.RIDE) }
        listOf(0x12, 0x1F).forEach { put(it, SportClass.STATIONARY_CARDIO) }
        put(0x13, SportClass.FLOOR_WORK)
        listOf(0x14, 0x19, 0x1C, 0x22, 0x27, 0x2D, 0x2E, 0x34, 0x3A, 0x50, 0x58, 0x59, 0x61, 0x63, 0x6C, 0x6D, 0x6F, 0x71)
            .forEach { put(it, SportClass.STRENGTH) }
        listOf(0x04, 0x6A, 0x6B, 0x35, 0x48, 0x62).forEach { put(it, SportClass.UNMONITORED) }
    }

    fun classOf(sportId: Int?): SportClass = if (sportId == null) SportClass.UNKNOWN else CLASSES[sportId] ?: SportClass.UNKNOWN

    /** The indicators a live workout of [sportId] is expected to show; lenient for an unknown / null id. */
    fun expected(sportId: Int?): Set<Indicator> = classOf(sportId).expected

    /** "Outdoor Running (MOVEMENT: steps, gps, hr, motion)" for the log. */
    fun describe(sportId: Int?): String {
        val cls = classOf(sportId)
        val name = if (sportId == null) "unknown sport" else SportTypes.name(sportId)
        val expected = if (cls.expected.isEmpty()) "unmonitored" else cls.expected.joinToString(", ") { it.name.lowercase() }
        return "$name (${cls.name}: $expected)"
    }
}
