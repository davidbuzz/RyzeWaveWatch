package au.buzz.ryzewave.protocol

/**
 * The watch's sport-mode ids and names (docs/PROTOCOL.md §6c; a copy of `ryzewave/protocol.py` `SPORT_TYPES`).
 *
 * The Ryze Wave numbers its 70 modes with the vendor's global ids (gaps are modes this model does not have); the
 * names are the watch's own menu, read off the wrist on 2026-09-05. The same id goes into `FD 11/22/00/44 <type>`
 * and comes back as byte 1 of the 14-byte realtime push ([Protocol.decSportRt]).
 */
object SportTypes {
    const val OUTDOOR_RUNNING = 0x01
    const val OUTDOOR_WALKING = 0x23

    /** The default for a new workout (what every workout recorded before the sport picker existed used). */
    const val DEFAULT = OUTDOOR_RUNNING

    /** id -> menu name, in id order. */
    val NAMES: Map<Int, String> = linkedMapOf(
        0x01 to "Outdoor Running", 0x02 to "Cycling", 0x04 to "Swimming", 0x05 to "Badminton", 0x07 to "Tennis",
        0x08 to "Hiking", 0x09 to "Walking", 0x0A to "Basketball", 0x0B to "Soccer", 0x0C to "Baseball",
        0x0D to "Volleyball", 0x0E to "Cricket", 0x0F to "Rugby", 0x10 to "Hockey", 0x12 to "Spinning",
        0x13 to "Yoga", 0x14 to "Sit-ups", 0x15 to "Treadmill", 0x17 to "Boating", 0x18 to "Jumping Jacks",
        0x19 to "Free Training", 0x1B to "Indoor Running", 0x1C to "Strength Training", 0x1E to "Horse Riding", 0x1F to "Elliptical",
        0x22 to "Boxing", 0x23 to "Outdoor Walking", 0x24 to "Trail Running", 0x25 to "Skiing", 0x27 to "Taekwondo",
        0x28 to "VO2 max Test", 0x29 to "Rower", 0x2C to "Athletics", 0x2D to "Waist Training", 0x2E to "Karate",
        0x34 to "Physical Training", 0x35 to "Archery", 0x37 to "Aerobic Combo", 0x39 to "Street Dancing", 0x3A to "Kick Boxing",
        0x3F to "Handball", 0x40 to "Bowling", 0x41 to "Racquetball", 0x44 to "Snowboarding", 0x46 to "American Football",
        0x48 to "Fishing", 0x4B to "Golf", 0x4D to "Downhill Skiing", 0x4E to "Snow Sports", 0x50 to "Core Training",
        0x51 to "Skating", 0x55 to "Kickboxing Aerobics", 0x56 to "Lacrosse", 0x58 to "Wrestling", 0x59 to "Fencing",
        0x5A to "Softball", 0x60 to "Pickleball", 0x61 to "HIIT", 0x62 to "Shooting", 0x63 to "Judo",
        0x65 to "Skateboarding", 0x68 to "Parkour", 0x6A to "Surfing", 0x6B to "Snorkeling", 0x6C to "Pull-up",
        0x6D to "Push-up", 0x6F to "Rock Climbing", 0x71 to "Bungee Jumping", 0x72 to "Long Jump", 0x73 to "Marathon",
    )

    /** The short picker on the Workout screen, in display order (`SPORT_TYPES_POPULAR` in protocol.py). */
    val POPULAR: List<Int> = listOf(0x01, 0x23, 0x09, 0x02, 0x08, 0x24, 0x15, 0x1B, 0x04, 0x19)

    /** Outdoor sports whose distance comes from the phone's GPS (the app tracks GPS for every sport regardless). */
    val GPS_SPORTS: Set<Int> = setOf(0x01, 0x02, 0x08, 0x09, 0x23, 0x24)

    /** All (id, name) pairs sorted by name, for the "More…" list. */
    val byName: List<Pair<Int, String>> = NAMES.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }

    /** Average speed from which a type-1 workout counts as running rather than walking (see [effectiveId]). */
    const val RUNNING_SPEED_MPS = 2.0

    /** The watch's menu name, or "Sport <id>" for an id the Ryze Wave does not list. */
    fun name(id: Int): String = NAMES[id] ?: "Sport $id"

    fun isGps(id: Int): Boolean = id in GPS_SPORTS

    /**
     * The sport a stored workout really was. Type 1 (Outdoor Running) was the only type the app could start before
     * the picker existed, walks included, so for type 1 alone the GPS average speed decides: below
     * [RUNNING_SPEED_MPS] it is treated as [OUTDOOR_WALKING]. Every other id is returned unchanged.
     */
    fun effectiveId(sportType: Int, avgSpeedMps: Double): Int =
        if (sportType == OUTDOOR_RUNNING && avgSpeedMps < RUNNING_SPEED_MPS) OUTDOOR_WALKING else sportType
}
